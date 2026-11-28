# Report 5.4 (system test of the non-functional requirements, SRS IV.2) on the school template: Security and
# Performance sheets as the template has them, plus Reliability and Usability sheets copied from Security. Pure
# openpyxl (the template has no charts or images).
#
# Each row names its evidence and its Status is decided from it, nothing typed:
#   ut(...)    unit / integration tests of the regression run (results/<ut round>/cases.json): Pass when every
#              named test ran and passed
#   st(...)    a Bruno e2e case (results/st-round<N>/, Report 5.2 since 28/11: API through the gateway is IT, D95): its
#              result as report.py decides it, or st_code(...) its HTTP status only (the access decision, apart from
#              the error body of DEF-15)
#   pw(...)    a Playwright test of frontend/tests/qa (results/pw-<date>/playwright.json)
#   ui(...)    a system test through the web client on a real stack (tests/st, Report 5.3;
#              results/st-ui/results-<browser>.json)
#   perf       results/perf-<date>/perf.json and pageload.json (perf_nfr.py)
# A row without evidence is Not Run, with the reason in Notes.
#
#   python fill_54_nfr.py results/ut-round3-20261128 results/st-round3 results/pw-20261128 results/perf-20261128-retest \
#       ../../docs/reports/Report-5.4_System_Test_NFRs.xlsx results/st-ui
import importlib.util
import json
import os
import sys
from copy import copy
from pathlib import Path

import openpyxl

HERE = Path(__file__).resolve().parent
UT_DIR, ST_DIR, PW_DIR, PERF_DIR, BOOK, UI_DIR = (Path(p).resolve() for p in sys.argv[1:7])
spec = importlib.util.spec_from_file_location("e2e_report", HERE.parents[1] / "tests/scripts/report.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)

CASES = json.loads((UT_DIR / "cases.json").read_text(encoding="utf-8"))
UT_RUN = json.loads((UT_DIR / "run.json").read_text(encoding="utf-8"))
ST_RUN = json.loads((ST_DIR / "run.json").read_text(encoding="utf-8"))
PW_RUN = json.loads((PW_DIR / "run.json").read_text(encoding="utf-8"))
PERF = json.loads((PERF_DIR / "perf.json").read_text(encoding="utf-8"))
PAGES = json.loads((PERF_DIR / "pageload.json").read_text(encoding="utf-8"))
ST = {}
for name in os.listdir(ST_DIR):
    if name.startswith("bruno-") and name.endswith(".json") and name not in ("bruno-bf03.json", "bruno-bf04.json"):
        ST.update(e2e.load_results(ST_DIR / name))
PW = {}


def _walk(suite):
    for s in suite.get("specs", []):
        for t in s["tests"]:
            PW[s["title"]] = t["results"][-1]["status"]
    for c in suite.get("suites", []):
        _walk(c)


for s in json.loads((PW_DIR / "playwright.json").read_text(encoding="utf-8"))["suites"]:
    _walk(s)

UI = {}  # (browser, test-id) -> status
UI_DATE = {}
for browser in ("chromium", "firefox"):
    report = json.loads((UI_DIR / f"results-{browser}.json").read_text(encoding="utf-8"))
    UI_DATE[browser] = report["stats"]["startTime"]

    def _walk_ui(suite, browser=browser):
        for s in suite.get("specs", []):
            for t in s["tests"]:
                UI[(browser, s["title"].split(" ")[0])] = t["results"][-1]["status"]
        for c in suite.get("suites", []):
            _walk_ui(c)

    for s in report["suites"]:
        _walk_ui(s)


# ── evidence: each returns (verdict, text); verdict Pass | Fail | Blocked | None (not run) ────────────────────
def ut(*keys):
    """"service/Class" (every test of it) or "service/Class#name" (tests whose name starts with name)"""
    found, bad = 0, []
    for key in keys:
        source, _, prefix = key.partition("#")
        tests = {n: s for n, s in CASES.get(source, {}).items() if n.startswith(prefix)}
        assert tests, f"no test {key} in {UT_DIR.name}"
        found += len(tests)
        bad += [f"{source.split('/')[-1]}.{n}" for n, s in tests.items() if s != "passed"]
    names = ", ".join(k.split("/", 1)[1].replace("#", ".") for k in keys)
    return ("Fail" if bad else "Pass"), f"UT {UT_RUN['date']}: {names} ({found} tests, {len(bad)} not passed)"


def st(*case_ids, defect=None):
    results = [(c, e2e.outcome(ST.get(c))) for c in case_ids]
    text = f"IT Bruno {ST_RUN['run']}: " + ", ".join(f"{c} {r}" for c, r in results)
    if any(r == "Failed" for _, r in results):
        return ("Blocked" if defect == "DEF-19" else "Fail"), text
    if all(r == "Passed" for _, r in results):
        return "Pass", text
    return None, text


def dlq(*flows):
    """every .dlq queue empty in the snapshot run-e2e-full.sh took after each flow (queues-<flow>.json)"""
    parts, ok = [], True
    for flow in flows:
        queues = [q for q in json.loads((ST_DIR / f"queues-{flow}.json").read_text(encoding="utf-8"))
                  if q["name"].endswith(".dlq")]
        dirty = [q["name"] for q in queues if q.get("messages") != 0]
        ok = ok and bool(queues) and not dirty
        parts.append(f"{flow} {len(queues)} .dlq" + (f", with messages: {', '.join(dirty)}" if dirty else " empty"))
    return ("Pass" if ok else "Fail"), f"IT Bruno {ST_RUN['run']}: " + "; ".join(parts)


def st_code(expected):
    """{case: HTTP status}: the access decision only"""
    got = {c: (ST.get(c) or {}).get("response", {}).get("status") for c in expected}
    ok = all(got[c] == s for c, s in expected.items())
    return ("Pass" if ok else "Fail"), f"IT Bruno {ST_RUN['run']}: " + ", ".join(f"{c} HTTP {got[c]}" for c in expected)


def pw(*prefixes):
    tests = [(t, s) for t, s in PW.items() if t.startswith(prefixes)]
    assert tests, prefixes
    bad = [t for t, s in tests if s != "passed"]
    return ("Fail" if bad else "Pass"), (f"Playwright {PW_RUN['date']}: {len(tests)} tests "
                                         + "/".join(prefixes) + f", {len(bad)} not passed")


def ui(*ids, browsers=("chromium",)):
    """system tests through the web client: every named test passed on every named browser"""
    got = [(b, i, UI.get((b, i))) for b in browsers for i in ids]
    missing = [f"{b}/{i}" for b, i, st_ in got if st_ is None]
    assert not missing, missing
    bad = [f"{i} ({b})" for b, i, st_ in got if st_ != "passed"]
    names = ", ".join(ids) if len(ids) <= 3 else f"{ids[0]}..{ids[-1]} ({len(ids)} tests)"
    return ("Fail" if bad else "Pass"), (f"ST through the web client 28/11/2026 "
                                         f"({', '.join(b.capitalize() for b in browsers)}): {names}"
                                         + (f"; not passed: {', '.join(bad)}" if bad else ", passed"))


def combine(*evidence):
    verdicts = [v for v, _ in evidence]
    verdict = ("Fail" if "Fail" in verdicts else "Blocked" if "Blocked" in verdicts
               else None if None in verdicts else "Pass")
    return verdict, "\n".join(t for _, t in evidence)


NOT_RUN = (None, "")

# ── Security (SRS IV.2.3) ─────────────────────────────────────────────────────────────────────────────────────
SECURITY = [
    ("A protected screen needs a signed-in session", "Guest (no token)", "Fresh stack, not signed in",
     "GET /api/v1/users/me through the gateway", "HTTP 401, no profile data", "Critical",
     combine(st_code({"SEC-020": 401}), ut("api-gateway/GatewayBoundaryTest")), "",
     "Blocked correctly. The 401 body is empty, not an ErrorEnvelope: see TC-SEC-005 (DEF-15)"),
    ("Public routes are readable without signing in", "Guest", "AVAILABLE listing of BF-02",
     "GET a public listing and the reference price through the gateway", "HTTP 200 with public data, no identity",
     "High", combine(st("BF02-070"), ut("api-gateway/GatewayBoundaryTest#public")), "",
     "The reference price returned 500 on the Compose stack without pricing-service (DEF-20, fixed by #715, "
     "see Performance)"),
    ("Only the Operator reads the KYC queue", "BUYER, SELLER, INSPECTOR, OPERATOR, ARBITRATOR, ADMIN",
     "6 seeded accounts, one token per role", "GET /api/v1/admin/users?verificationStatus=PENDING with each token",
     "OPERATOR 200; the other 5 roles 403 (no fallback, ADMIN 403 too)", "Critical",
     st_code({"SEC-040": 403, "SEC-041": 403, "SEC-042": 403, "SEC-043": 200, "SEC-044": 403, "SEC-045": 403}),
     "", "Permission matrix of data-governance §5.4; access decisions right, for the 403 body see TC-SEC-005"),
    ("Only the Arbitrator reads the arbitration queue", "6 roles", "6 seeded accounts",
     "GET /api/v1/arbitration/breach-cases with each token", "ARBITRATOR 200; the other 5 roles 403", "Critical",
     combine(st_code({"SEC-060": 403, "SEC-061": 403, "SEC-062": 403, "SEC-063": 403, "SEC-064": 200,
                      "SEC-065": 403}), ui("ST-SEC-010")),
     "", "OPERATOR and ADMIN do not stand in for ARBITRATOR; on the screens ADMIN and BUYER are refused every route "
         "of another role"),
    ("401/403 refusals return an ErrorEnvelope", "Guest, roles without permission", "As TC-SEC-001, 003, 004",
     "Read the body of the gateway's 401/403 responses", "JSON body with code and traceId as the OpenAPI ErrorEnvelope",
     "Medium",
     st("SEC-020", "SEC-040", "SEC-041", "SEC-042", "SEC-044", "SEC-045", "SEC-060", "SEC-061", "SEC-062",
        "SEC-063", "SEC-065", "SEC-110"),
     "DEF-15", "The gateway returns an empty body; issue #663 still open"),
    ("Forged identity headers are replaced or removed by the gateway", "A client sending its own headers",
     "Request with forged X-User-Id, X-User-Role, X-Gateway-Secret", "Call through the gateway with and without a JWT",
     "Identity headers come from the JWT; forged headers and X-Internal-Secret are removed", "Critical",
     ut("api-gateway/UserContextInjectionFilterTest"), "", ""),
    ("Signing a contract needs a sign-in within 300 seconds", "Buyer / Seller", "Old session, missing or future "
     "auth_time", "Confirm the OTP to sign", "Refused, asked to enter the password again", "High",
     ut("contract-service/SignerEligibilityTest"), "", "MSG05"),
    ("Signing OTP rules: 6 digits, expires in 300 seconds, at most 5 wrong tries, 60-second cooldown",
     "Buyer / Seller", "Contract waiting for signatures", "Wrong code, resend within the cooldown, parallel requests",
     "No tries left after 5 wrong codes; a resend within the cooldown is refused; a code only works for the terms it "
     "was issued for", "High",
     ut("contract-service/OtpPolicyTest", "contract-service/OtpChallengeTest",
        "contract-service/OtpChallengeIntegrationTest"), "", ""),
    ("Temporary lock after repeated wrong passwords", "New account", "Keycloak account just created",
     "Sign in with a wrong password 5 times in a row", "The next attempt is refused (temporary lock)", "High",
     st("SEC-120"), "", "Brute-force detection of the agricontract realm"),
    ("An account that is not VERIFIED cannot publish a listing", "Seller without verified KYC", "Seller PENDING",
     "POST /api/v1/listings", "HTTP 403; the system refuses, not only a hidden button", "High",
     ut("product-service/CreateListingUseCaseImplTest#execute_sellerNotVerified",
        "product-service/CreateListingControllerTest#ineligibleSeller"), "", ""),
    ("A public profile shows no contact data", "Signed-in user", "A counterparty's profile",
     "GET /api/v1/users/{id}", "Only organization name, role, verification status, reputation; no e-mail, phone or "
     "address", "High", ut("user-service/UserControllerTest#getPublicUser"), "", ""),
    ("Someone who is not a party of the milestone cannot see its data", "Buyer acting as Seller; another user",
     "Milestone of another contract", "Open the milestone details, switch identity in the session",
     "403 hides the data; nothing cached from the previous user is shown", "High", pw("FE-011"), "",
     "UI (frontend/tests/qa)"),
    ("A file can only be used after the malware scan", "Seller", "Evidence file just uploaded",
     "Upload a clean file and one rejected by the scan", "The rejected file is not attached and can be replaced; "
     "only a clean READY file can be used", "Critical",
     combine(ut("file-service/src/infrastructure/scan/clamd-scanner.spec.ts"),
             pw("FE-015 a rejected scan", "FE-015 blocks evidence")), "", ""),
    ("Tamper-evident audit chain, global check", "Admin", "Audit chain after the flows",
     "GET /api/v1/admin/audit/verification", "HTTP 200, status INTACT; the evidence log screen reports the whole "
     "chain intact", "High",
     combine(st("SEC-100"), ui("ST-ADM-010")), "", ""),
    ("Operators reading restricted data leave a trace", "Admin", "A contract settled by BF-04",
     "Admin reads the contract trail, then the SYSTEM records", "Each read adds exactly one audit.viewed record",
     "Medium", st("SEC-140", "SEC-150", "SEC-160", "SEC-170", defect="DEF-19"), "DEF-19",
     "Round 3 ran on a Saturday: no settled contract (DEF-19), so not checked; Report 5.2 (Bruno e2e) Rounds 1-2 "
     "Passed; through the web client ST-ADM-020 (trail of a withdrawn contract) records audit.viewed (Report 5.3)"),
    ("Validation errors reveal no system details", "Buyer", "N/A", "GET /api/v1/contracts?page=-1",
     "HTTP 400 VALIDATION_FAILED, no stack trace, no class names", "Medium", st("SEC-180", "SEC-190"), "", ""),
    ("The emergency lock only accepts ES256-signed requests, timestamp ±300 seconds, unused nonce",
     "External Verifier", "Verifier key registered", "POST lock/unlock with a valid signature, a wrong one, a reused "
     "nonce", "Only valid requests are accepted; no Admin can stand in", "Critical", NOT_RUN, "",
     "No controller for the lock/unlock path yet (Report 5.2 BF07_SecurityLockApi BF07-020 N/A)"),
    ("Personal data past its retention period is deleted or anonymized", "System (JOB-19)", "Expired data with and "
     "without a legal hold", "Run the retention job", "Expired data deleted or anonymized; data under legal hold kept",
     "Medium", NOT_RUN, "", "No automated test for JOB-19 yet"),
]

# ── Reliability (SRS IV.2.4) ──────────────────────────────────────────────────────────────────────────────────
RELIABILITY = [
    ("Repeating an offer with the same Idempotency-Key does not reserve twice", "Buyer", "AVAILABLE listing",
     "POST /api/v1/contracts twice with the same key, read the listing again",
     "The second call returns the first result; the reserved quantity is taken once", "Critical",
     st("SEC-080", "SEC-090"), "", ""),
    ("A bank command is carried out at most once", "System (bank-service)", "Lock/release command repeated with the "
     "same sourceEventId", "Send the command and its result again", "The ledger records it once, the repeat is "
     "ignored", "Critical",
     ut("bank-service/LedgerCommandConsumerTest", "bank-service/ProcessLedgerCommandUseCaseImplTest"), "", ""),
    ("A failed deposit lock is retried on schedule", "System (JOB-04)", "Deposit lock result FAILED",
     "Receive the failure, run the retry job", "Retried after 5 minutes / 30 minutes / 2 hours; when the tries run "
     "out the contract is ACTIVATION_FAILED, never stuck", "High",
     ut("escrow-service/DepositLockResultFlowIntegrationTest", "contract-service/ActivationRecoveryIntegrationTest"),
     "", ""),
    ("A broken payload goes to the DLQ without retries", "System (consumer)", "Message with a wrong schema",
     "Put the broken message on the queue", "Refused at once (one try), the message goes to .dlq", "High",
     ut("product-service/ListenerRetryTest", "user-service/ReputationLockFlowIntegrationTest#schemaViolation"), "",
     ""),
    ("No message left in any DLQ after each flow", "System", "Stack after each flow BF-01..BF-07 and Security",
     "GET /api/queues of RabbitMQ", "Every .dlq queue exists and is empty", "High",
     dlq("bf01", "bf02", "bf03", "bf04", "bf05", "bf06", "bf07", "security"), "",
     "After BF-04 only the part that ran could be checked (DEF-19)"),
    ("The web client does not resend an offer withdrawal when the response is lost", "Buyer",
     "Withdraw an offer, lose the response, reload / 2 tabs", "Reload, open a second tab, let the recovery time pass",
     "Sent at most once; the old key is reused after reading the real state; never sent with a new key", "High",
     pw("FE-003"), "", "UI (frontend/tests/qa)"),
    ("The web client waits for activation within a limit", "Buyer", "SIGNED contract waiting for the deposit lock",
     "Open the contract details, a temporary read error, switch contracts", "Polls every 5 seconds within a "
     "120-second budget, stops when ACTIVE, offers a manual refresh", "Medium", pw("FE-004"), "",
     "UI (frontend/tests/qa)"),
    ("A 503 upload error is retried safely", "Seller", "Evidence upload returns 503", "Click retry",
     "Same key and bytes; a file still being scanned stops polling after 60 seconds", "Medium",
     pw("FE-015 an explicit retry", "FE-015 a projected"), "", "UI (frontend/tests/qa)"),
    ("A failed notification is sent again, never twice", "System (notification-service)", "E-mail sending fails",
     "Retry and receive the same event again", "Limited retries; the same event + recipient + type never sends two "
     "e-mails", "Medium", ut("notification-service/src/application/notification-sender.spec.ts"), "", ""),
]

# ── Usability (SRS IV.2.1) ────────────────────────────────────────────────────────────────────────────────────
USABILITY = [
    ("The home page reflows at 320 CSS px", "Guest", "Viewport 320 px", "Open the home page", "No horizontal scroll",
     "Medium", pw("FE-016 the public home page reflows"), "", "WCAG 2.2 AA 1.4.10"),
    ("Dialogs work with the keyboard, no serious axe findings", "Buyer, Guest", "Contract details, price board",
     "Open / confirm / close the offer withdrawal dialog with the keyboard; axe scan", "Focus right, no "
     "serious/critical findings",
     "Medium", pw("FE-016 an offer withdrawal", "FE-016 contract detail", "FE-016 public prices"), "", ""),
    ("The seller's weight-correction window counts down on server time", "Seller", "Milestone SELLER_WEIGHED",
     "Wait past the correction deadline", "Countdown to the deadline the server returns; past it the edit button is "
     "hidden", "Medium", pw("FE-010"), "", ""),
    ("Validation shows beside the field and keeps the values entered", "Buyer", "Offer form",
     "Enter moisture 11.5, unit price 95000 (two decimals missing) → 'Gửi đề nghị' (Make an offer)",
     "Message beside the unit price, moisture still 11.5, still on the form", "Medium", ui("ST-USA-010"), "", ""),
    ("Money in VND with thousands separators and two decimals", "Buyer", "Offer of 1,000 kg at 96000.00",
     "Look at the contract page and the listing page", "96.000,00 ₫/kg and a total of 96.000.000,00 ₫ (SRS IV.2.1)",
     "Low", ui("ST-USA-020"), "DEF-23",
     "The screens show 96.000 ₫: formatDecimalVnd (frontend/src/shared/lib/decimal-display.ts) drops the ,00"),
    ("Dates as dd/MM/yyyy, times in Asia/Ho_Chi_Minh", "Buyer", "Contract just created",
     "Look at the delivery date; request a signing OTP", "Delivery date as dd/MM/yyyy; the OTP expiry is given in "
     "Vietnam time", "Low", ui("ST-USA-030"), "", ""),
    ("A blocked action states its reason", "Buyer not VERIFIED", "KYC PENDING",
     "Open the offer form", "The screen gives the reason (only a verified buyer can make an offer), the submit button "
     "is disabled", "Medium", ui("ST-USA-040"), "", "Reputation lock / emergency lock have no screen yet (BF-07)"),
    ("Works on Chrome, Edge, Firefox (two latest versions), ≥ 1366 px", "Guest", "Playwright Chromium and Firefox, "
     "1366x900", "Open 7 public screens (home, marketplace, price board, how it works, escrow, sign-in, listing "
     "details)", "Main content shown, no JavaScript error, no horizontal scroll", "Medium",
     ui(*(f"ST-BRW-0{i}0" for i in range(1, 8)), browsers=("chromium", "firefox")), "",
     "Edge uses the Chromium engine; the signed-in screens ran on Chromium only (Report 5.3)"),
    ("A VERIFIED seller publishes a first listing within 15 minutes without help", "New seller", "VERIFIED seller "
     "who has not used the system", "Add a plot, a product and a listing alone", "Done within 15 minutes", "Low",
     NOT_RUN, "", "Needs real users: part of Report 5.5 (UAT)"),
]

# ── Performance (SRS IV.2.2) ──────────────────────────────────────────────────────────────────────────────────
c, form, single = PERF["concurrent"], PERF["form_submission"], PERF["single_user"]


def page_row(path, label):
    times = PAGES[path]
    load, idle = max(t["load"] for t in times), max(t["idle"] for t in times)
    # network idle is reported 500 ms after the last request ends
    rendered = idle - 500
    return (f"Loading the {label} with {c['users']} concurrent users", "Playwright (Chromium) + perf_nfr.py",
            "≤ 3 seconds (whole screen rendered)",
            f"{len(times)} loads: load event ≤ {load} ms, last request done ≤ {rendered} ms",
            "Pass" if rendered <= 3000 else "Fail")


PERFORMANCE = [
    (*page_row("/", "home page"), "", "vite build pointing at the gateway localhost:8080, vite preview; measured "
                                      "during the load test"),
    (*page_row("/listing", "listing marketplace"), "", f"{PERF['listings']} listings in product_db"),
    (*page_row("/prices", "reference prices"), "DEF-20",
     "Re-test after #715 (pricing-service added to the Compose stack, one Operator price for COFFEE / Dak Lak). "
     "First run on 28/11 failed: the price API returned 500 after ~3.9 s"),
    ("Form submission (without uploads and waits on external services)", "Bruno CLI 3.4.2 (responseTime)",
     "≤ 2 seconds",
     f"{form['count']} write requests of Bruno Round 3 (e2e + IT): p95 {form['p95']} ms, max {form['max']} ms",
     "Pass" if form["max"] <= 2000 else "Fail", "", "results/st-round3, results/round3"),
    ("Browse / filter listings, 1 user, first page of 20", "perf_nfr.py", "≤ 3 seconds with up to 10,000 listings",
     f"{PERF['listings']} listings; browse p95 {single['browse']['p95']} ms, filter by reputation p95 "
     f"{single['filtered']['p95']} ms (30 times each)",
     "Pass" if max(single["browse"]["max"], single["filtered"]["max"]) <= 3000 else "Fail", "",
     "Filter = minSellerScore, viewer signed in"),
    (f"Browse / filter listings with {c['users']} concurrent users", "perf_nfr.py", "≤ 4.5 seconds (3 seconds + 50%)",
     f"browse p95 {c['per_request']['browse']['p95']} ms, max {c['per_request']['browse']['max']} ms; filter p95 "
     f"{c['per_request']['filtered']['p95']} ms, max {c['per_request']['filtered']['max']} ms",
     "Pass" if max(c["per_request"]["browse"]["max"], c["per_request"]["filtered"]["max"]) <= 4500 else "Fail", "",
     ""),
    (f"{c['users']} concurrent users without errors", "perf_nfr.py (threads, urllib)",
     "0 errors; no target exceeded by more than 50%",
     f"{c['requests']} requests in {c['seconds']} s ({c['throughput']} req/s); errors: "
     + (", ".join(f"{k}: {v}" for k, v in c["errors"].items()) or "0") + f" ({c['error_rate']}%)",
     "Pass" if not c["errors"] else "Fail", "DEF-20",
     "Each user loops: browse, filter, reference price, own profile, own contracts. Re-test after #715; the first "
     "run on 28/11 had 1389 errors (20%), all 500 on the reference price (DEF-20)"),
    ("Signing OTP e-mail reaches the mail provider", "MailHog", "≤ 10 seconds after the request; the code lives 300 "
     "seconds", "", "Not Run", "",
     "Not measured on its own; Bruno Round 3 (Saturday) stopped at the second signature, DEF-19"),
    ("Bank command results update the contract / milestone", "Bruno BF-04", "≤ 10 seconds", "", "Not Run", "DEF-19",
     "BF-04 did not run in Round 3 (DEF-19); runs again on a business day"),
    ("Outbox publishes the event", "UT outbox poller", "≤ 5 seconds after the write", "", "Not Run", "",
     "No end-to-end measurement yet"),
    ("A file ≤ 10 MB is usable after upload", "Bruno BF-04 (evidence upload)", "≤ 60 seconds", "", "Not Run",
     "DEF-19", "BF-04 did not run in Round 3 (DEF-19)"),
]

# ── write ─────────────────────────────────────────────────────────────────────────────────────────────────────
STATUS = {"Pass": "Pass", "Fail": "Fail", "Blocked": "Blocked", None: "Not Run"}
wb = openpyxl.load_workbook(BOOK)
sec = wb["Security"]
head_style = [copy(sec.cell(1, i)._style) for i in range(1, 11)]
body_style = [copy(sec.cell(2, i)._style) for i in range(1, 11)]


def clear(ws, first=2):
    for row in ws.iter_rows(min_row=first, max_row=max(ws.max_row, 300)):
        for cell in row:
            cell.value = None


def fill_cases(ws, prefix, rows):
    clear(ws)
    for i, (scenario, role, given, when, then, priority, (verdict, evidence), defect, note) in enumerate(rows):
        r = i + 2
        values = [f"TC-{prefix}-{i + 1:03d}", scenario, role, given, when, then, priority, STATUS[verdict],
                  defect, "\n".join(x for x in (evidence, note) if x)]
        for col, v in enumerate(values, start=1):
            cell = ws.cell(r, col, v)
            cell._style = copy(body_style[col - 1])


for title, prefix, rows in (("Reliability", "REL", RELIABILITY), ("Usability", "USA", USABILITY)):
    if title in wb.sheetnames:
        del wb[title]
    ws = wb.copy_worksheet(sec)
    ws.title = title
    for dv in sec.data_validations.dataValidation:
        ws.data_validations.append(copy(dv))
    ws.freeze_panes = "A2"
    fill_cases(ws, prefix, rows)
fill_cases(sec, "SEC", SECURITY)

perf = wb["Performance"]
pstyle = [copy(perf.cell(2, i)._style) for i in range(1, 9)]
clear(perf)
for i, row in enumerate(PERFORMANCE):
    for col, v in enumerate([f"TC-PERF-{i + 1:03d}", *row], start=1):
        perf.cell(i + 2, col, v)._style = copy(pstyle[col - 1])
perf.column_dimensions["E"].width = 40

wb._sheets = [wb["Security"], wb["Reliability"], wb["Usability"], wb["Performance"]]
wb.save(BOOK)
for name, rows in (("Security", SECURITY), ("Reliability", RELIABILITY), ("Usability", USABILITY)):
    counts = {}
    for r in rows:
        counts[STATUS[r[6][0]]] = counts.get(STATUS[r[6][0]], 0) + 1
    print(name, counts)
counts = {}
for r in PERFORMANCE:
    counts[r[4]] = counts.get(r[4], 0) + 1
print("Performance", counts)
