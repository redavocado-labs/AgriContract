# Per-test results of the NestJS services (Jest) for Report 5.1 (#521), in the same shape as surefire_cases.py:
# cases.json maps "<service>/<spec path inside the service>" to {full test name: passed | failed | pending}, where the
# full name is Jest's "describe › ... › test" title path joined with " › "; run.json holds date, tester, commit,
# command. A row's test list in trace_<svc>.py names these full names; a name may be prefixed with
# "<spec path>::" when the sheet folds several spec files (data_r2_node.FOLDED).
#
#   cd backend/<service> && npx jest --json --outputFile=<dir>/jest-<service>.json
#   SUREFIRE_COMMIT=<sha> python jest_cases.py collect results/<round> "<tester>" <service>=<dir>/jest-<service>.json ...
import json
import os
import subprocess
import sys
from datetime import date

STATUS = {"passed": "passed", "failed": "failed", "pending": "pending", "skipped": "pending", "todo": "pending"}


def collect(reports):
    cases = {}
    for svc, path in reports:
        with open(path, encoding="utf-8") as f:
            run = json.load(f)
        for suite in run["testResults"]:
            spec = suite["name"].replace("\\", "/").split(f"/{svc}/", 1)[1]
            for a in suite["assertionResults"]:
                name = " › ".join(a["ancestorTitles"] + [a["title"]])
                key = f"{svc}/{spec}"
                prev = cases.setdefault(key, {}).get(name)
                # the same title twice in one file (it.each without $values): one failure fails it
                cases[key][name] = "failed" if "failed" in (prev, STATUS[a["status"]]) else STATUS[a["status"]]
    return cases


if __name__ == "__main__" and sys.argv[1] == "collect":
    out, tester, *pairs = sys.argv[2:]
    reports = [p.split("=", 1) for p in pairs]
    os.makedirs(out, exist_ok=True)
    cases = collect(reports)
    commit = os.environ.get("SUREFIRE_COMMIT") or subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    with open(os.path.join(out, "cases.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(cases, f, indent=1, sort_keys=True, ensure_ascii=False)
    with open(os.path.join(out, "run.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"date": date.today().strftime("%d/%m/%Y"), "tester": tester, "commit": commit,
                   "command": "; ".join(f"cd backend/{svc} && npx jest --json" for svc, _ in reports),
                   "services": [svc for svc, _ in reports]}, f, indent=2)
    n = sum(len(v) for v in cases.values())
    bad = sum(1 for v in cases.values() for s in v.values() if s == "failed")
    print(f"{len(cases)} spec files, {n} tests, {bad} failed -> {out}")
