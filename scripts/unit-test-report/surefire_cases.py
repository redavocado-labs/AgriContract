# Per-test results for the traced Report-5.1 rows (data_nhl.py, data_nhl_new.py).
#
#   python surefire_cases.py collect ../../backend results/unit-round3 "<tester>" api-gateway product-service ...
#
# collect reads target/surefire-reports/TEST-*.xml of the given services after `mvn test` and keeps, per test class,
# each test method's outcome (a parameterized test is one method: it passes only if every invocation passed) in
# results/<round>/cases.json, with run.json (date, tester, commit). row_result() turns a row's test methods into
# Passed / Failed, or "" when one of them did not run.
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import date


def collect(backend, services):
    cases = {}
    for svc in services:
        for path in glob.glob(os.path.join(backend, svc, "target", "surefire-reports", "TEST-*.xml")):
            suite = ET.parse(path).getroot()
            for tc in suite.iter("testcase"):
                cls = tc.get("classname", "").split(".")[-1]
                # "method(String)[2]", "method()", "[1] x" all reduce to the method name
                name = re.split(r"[(\[]", tc.get("name", ""), maxsplit=1)[0].strip()
                failed = tc.find("failure") is not None or tc.find("error") is not None
                skipped = tc.find("skipped") is not None
                status = "failed" if failed else ("skipped" if skipped else "passed")
                key = f"{svc}/{cls}"
                prev = cases.setdefault(key, {}).get(name)
                if prev != "failed":  # one failed invocation fails the method
                    cases[key][name] = status if prev in (None, "passed") else prev
    return cases


def load(folder):
    meta = {"date": "", "tester": "", "commit": ""}
    if not os.path.exists(os.path.join(folder, "run.json")):
        return meta, {}
    with open(os.path.join(folder, "run.json"), encoding="utf-8") as f:
        meta.update(json.load(f))
    with open(os.path.join(folder, "cases.json"), encoding="utf-8") as f:
        return meta, json.load(f)


def row_result(cases, module, test_class, tests):
    ran = cases.get(f"{module}/{test_class}", {})
    statuses = [ran.get(t) for t in tests]
    if any(s == "failed" for s in statuses):
        return "Failed"
    if not tests or any(s != "passed" for s in statuses):
        return ""
    return "Passed"


def traced_rounds(m, case, tester, round3, cases):
    # columns F..P of a traced row: round 1, round 2 (history), round 3 (this run), Defect IDs, Note
    pre, inp, ret, exc, typ, tests, r1_date, r2_date = case
    r1 = ["Passed", r1_date, tester] if r1_date else ["", "", ""]
    r2 = ["Passed", r2_date, tester] if r2_date else ["", "", ""]
    res = row_result(cases, m["module"], m["test_class"], tests)
    r3 = [res, round3["date"], tester] if res else ["", "", ""]
    return r1 + r2 + r3 + ["", "Test: " + ", ".join(tests)]


if __name__ == "__main__" and sys.argv[1] == "collect":
    backend, out, tester, *services = sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    cases = collect(backend, services)
    # SUREFIRE_COMMIT: the commit whose backend/ was tested, when the reports come from another checkout
    commit = os.environ.get("SUREFIRE_COMMIT") or subprocess.run(
        ["git", "-C", backend, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    with open(os.path.join(out, "cases.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(cases, f, indent=1, sort_keys=True)
    with open(os.path.join(out, "run.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"date": date.today().strftime("%d/%m/%Y"), "tester": tester, "commit": commit,
                   "command": "mvn -pl " + ",".join(services) + " -am test", "services": services}, f, indent=2)
    n = sum(len(v) for v in cases.values())
    bad = sum(1 for v in cases.values() for s in v.values() if s == "failed")
    print(f"{len(cases)} classes, {n} test methods, {bad} failed -> {out}")
