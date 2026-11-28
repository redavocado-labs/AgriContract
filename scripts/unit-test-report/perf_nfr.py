# Performance measurements of SRS IV.2.2 for Report 5.4, against the running Docker Compose stack (one machine).
#
#   1. Form submission: response time of every write request (POST/PUT/PATCH/DELETE, file uploads excluded) of the
#      system test and integration test rounds, read from their Bruno JSON reports (results/st-round<N>/,
#      results/round<N>/). Target: within 2 s.
#   2. Listing browse/search, single user: first page (20 rows) anonymously and with the reputation filter
#      (minSellerScore, signed-in buyer) over the listings in product_db. Target: within 3 s.
#   3. Concurrent users: N virtual users (threads), each looping over browse, filtered search, reference prices,
#      own profile and own contracts for D seconds. Target: no errors, and the browse/search targets exceeded by no
#      more than 50%.
#
#   python perf_nfr.py results/perf-20261128 --st results/st-round3 --it results/round3 --users 100 --seconds 60
import argparse
import concurrent.futures
import json
import os
import random
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

p = argparse.ArgumentParser()
p.add_argument("out")
p.add_argument("--st", required=True)
p.add_argument("--it", required=True)
p.add_argument("--users", type=int, default=100)
p.add_argument("--seconds", type=int, default=60)
p.add_argument("--gateway", default="http://localhost:8080")
p.add_argument("--keycloak", default="http://localhost:8180/realms/agricontract/protocol/openid-connect/token")
args = p.parse_args()
os.makedirs(args.out, exist_ok=True)


def pct(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(q / 100 * (len(values) - 1))))] if values else None


def summary(ms):
    return dict(count=len(ms), mean=round(statistics.mean(ms), 1) if ms else None, p50=pct(ms, 50), p95=pct(ms, 95),
                p99=pct(ms, 99), max=max(ms) if ms else None)


# ── 1. form submission from the Bruno reports ──────────────────────────────────────────────────────────────────
writes = []
for folder in (args.st, args.it):
    for name in sorted(os.listdir(folder)):
        if not (name.endswith(".json") and name not in ("run.json", "defects.json")) or name.startswith("queues-"):
            continue
        if name in ("bruno-bf03.json", "bruno-bf04.json"):  # merged copies of the happy/unhappy, clean/... reports
            continue
        report = json.load(open(os.path.join(folder, name), encoding="utf-8"))
        for it in report if isinstance(report, list) else [report]:
            for r in it.get("results", []):
                req, resp = r.get("request") or {}, r.get("response") or {}
                method, url = (req.get("method") or "").upper(), req.get("url") or ""
                rt = resp.get("responseTime")
                if method in ("POST", "PUT", "PATCH", "DELETE") and isinstance(rt, (int, float)) \
                        and isinstance(resp.get("status"), int) and "/files" not in url and "evidence" not in url \
                        and "keycloak" not in url and ":8180" not in url:
                    writes.append(dict(file=name, name=r.get("name") or (r.get("test") or {}).get("filename"),
                                       method=method, status=resp["status"], ms=rt))
form = summary([w["ms"] for w in writes])
form["slowest"] = sorted(writes, key=lambda w: -w["ms"])[:5]


# ── helpers for live requests ──────────────────────────────────────────────────────────────────────────────────
def token(user):
    body = urllib.parse.urlencode(dict(grant_type="password", client_id="agricontract-frontend", username=user,
                                       password="pass123")).encode()
    with urllib.request.urlopen(urllib.request.Request(args.keycloak, data=body), timeout=30) as r:
        return json.load(r)["access_token"]


BUYER = token("buyer1")


def call(path, auth=False):
    req = urllib.request.Request(args.gateway + path)
    if auth:
        req.add_header("Authorization", "Bearer " + BUYER)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        status = e.code
    except Exception as e:  # connection reset, timeout
        status = type(e).__name__
    return status, round((time.perf_counter() - start) * 1000, 1)


total = json.load(urllib.request.urlopen(args.gateway + "/api/v1/listings?page=0&size=1"))
listings = total.get("totalElements") or total.get("page", {}).get("totalElements")
pages = max(1, listings // 20)

# ── 2. single user ─────────────────────────────────────────────────────────────────────────────────────────────
single = {}
for label, make, auth in (
        ("browse", lambda: f"/api/v1/listings?page={random.randrange(pages)}&size=20", False),
        ("filtered", lambda: f"/api/v1/listings?page={random.randrange(pages)}&size=20&minSellerScore=0", True)):
    ms, bad = [], []
    for _ in range(30):
        status, t = call(make(), auth)
        ms.append(t)
        if status != 200:
            bad.append(status)
    single[label] = dict(summary(ms), errors=len(bad))

# ── 3. concurrent users ────────────────────────────────────────────────────────────────────────────────────────
SCENARIO = [
    ("browse", lambda: f"/api/v1/listings?page={random.randrange(pages)}&size=20", False),
    ("filtered", lambda: f"/api/v1/listings?page={random.randrange(pages)}&size=20&minSellerScore=0", True),
    ("prices", lambda: "/api/v1/prices/COFFEE/dak-lak", False),
    ("profile", lambda: "/api/v1/users/me", True),
    ("contracts", lambda: "/api/v1/contracts?page=0&size=20", True),
]
lock = threading.Lock()
samples = {name: [] for name, _, _ in SCENARIO}
errors = {}
stop_at = time.time() + args.seconds


def user(_):
    while time.time() < stop_at:
        for name, make, auth in SCENARIO:
            status, t = call(make(), auth)
            with lock:
                samples[name].append(t)
                if status != 200:
                    errors[f"{name} {status}"] = errors.get(f"{name} {status}", 0) + 1


started = time.time()
with concurrent.futures.ThreadPoolExecutor(args.users) as pool:
    list(pool.map(user, range(args.users)))
elapsed = time.time() - started
requests = sum(len(v) for v in samples.values())
load = dict(users=args.users, seconds=round(elapsed, 1), requests=requests, throughput=round(requests / elapsed, 1),
            errors=errors, error_rate=round(100 * sum(errors.values()) / requests, 3),
            per_request={k: summary(v) for k, v in samples.items()})

result = dict(date=date.today().strftime("%d/%m/%Y"), listings=listings, form_submission=form, single_user=single,
              concurrent=load, sources=dict(st=os.path.basename(args.st), it=os.path.basename(args.it)))
json.dump(result, open(os.path.join(args.out, "perf.json"), "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print(json.dumps({k: v for k, v in result.items() if k != "form_submission"}, indent=1)[:3000])
print("form submission", {k: v for k, v in form.items() if k != "slowest"})
