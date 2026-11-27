# Copy the evidence of one tests/scripts/run-e2e-full.sh round into results/<round>/ for Report 5.3 (#676):
# the Bruno JSON reports, the RabbitMQ queue snapshots and the runner's TESTING-ROUNDS.md / FINDINGS.md.
# Service logs stay in the run directory (named in run.json). Every JWT is masked, as redact_reports.py does for
# Report 5.2; the local dev secrets of the runner are its documented defaults, not real credentials.
#
#   python import_e2e_round.py ~/agricontract-e2e/run-20261127-1603 st-round1 "Nguyen Hai Long" 94d46500
#   python import_e2e_round.py ~/agricontract-e2e/run-20261127-1630 st-round2 "Nguyen Hai Long" \
#       "43c48666 (merged as 4d0eff24, PR #686)"
import json
import os
import re
import sys

JWT = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
run_dir, round_name, tester, commit = sys.argv[1:5]
run_dir = os.path.abspath(os.path.expanduser(run_dir))
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", round_name)
os.makedirs(out, exist_ok=True)

copied = []
for name in sorted(os.listdir(run_dir)):
    if not (name.endswith(".json") and name.startswith(("bruno-", "queues-")) or name in ("TESTING-ROUNDS.md",
                                                                                           "FINDINGS.md")):
        continue
    with open(os.path.join(run_dir, name), encoding="utf-8") as f:
        text = JWT.sub("<redacted:jwt>", f.read())
    # links in FINDINGS.md point at the tester's checkout
    text = re.sub(r"file:///[^)\s]*?/backend/", "<repo>/backend/", text)
    text = re.sub(r"[A-Za-z]:[\\/]+(?:[^\"\\/\s]+[\\/]+)*?(?=backend[\\/])", "<repo>/", text)
    # stack traces of the Bruno CLI carry the tester's home directory: C:\Users\<name>, /c/Users/<name> (Git Bash),
    # /Users/<name> (macOS), /home/<name> (Linux)
    text = re.sub(r"(?:[A-Za-z]:|/[a-z])?[\\/]+Users[\\/]+[^\"\\/\s]+", "<home>", text)
    text = re.sub(r"(?<![\w.])/home/[^\"\\/\s]+", "<home>", text)
    with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    copied.append(name)

stamp = re.search(r"run-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})", run_dir)
meta = {
    "round": round_name,
    "date": f"{stamp[3]}/{stamp[2]}/{stamp[1]}",
    "tester": tester,
    "commit": commit,
    "run": os.path.basename(run_dir),
    "source": "tests/scripts/run-e2e-full.sh (Bruno CLI 3.4.2, Docker Compose, Windows 11 + Docker Desktop); "
              "service logs: ~/agricontract-e2e/" + os.path.basename(run_dir) + "/logs/",
}
with open(os.path.join(out, "run.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(meta, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"{round_name}: {len(copied)} files from {run_dir}")
