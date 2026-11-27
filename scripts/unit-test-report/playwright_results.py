# Read the result of each Playwright test (frontend/tests/qa) for one Report 5.4 round.
# A round folder under results/ holds run.json (date, tester, commit, source) and either
#   playwright.json     the JSON reporter of a local run (--reporter=list,json), or
#   playwright-ci.log   the Playwright part of a GitHub Actions job log (list reporter: ✓ / ✘ / - per test).
# Tests are matched by title (the text after "›"), so line numbers that moved between commits do not matter.
import json
import os
import re

LIST_LINE = re.compile(r"^\s+(✓|✘|-|ok|x)\s+\d+\s+\S+\.pw\.ts:\d+:\d+ › (.+?)(?: \(\d+(?:\.\d+)?m?s\))?$")
LIST_STATUS = {"✓": "Passed", "ok": "Passed", "✘": "Failed", "x": "Failed", "-": "Pending"}
JSON_STATUS = {"expected": "Passed", "unexpected": "Failed", "flaky": "Failed", "skipped": "Pending"}


def _from_log(path):
    results = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = LIST_LINE.match(line.rstrip("\n"))
            if m:
                results[m.group(2)] = LIST_STATUS[m.group(1)]
    return results


def _specs(suite):
    yield from suite.get("specs", [])
    for child in suite.get("suites", []):
        yield from _specs(child)


def _from_json(path):
    with open(path, encoding="utf-8") as f:
        report = json.load(f)
    results = {}
    for suite in report["suites"]:
        for spec in _specs(suite):
            for test in spec["tests"]:
                results[spec["title"]] = JSON_STATUS[test["status"]]
    return results


def load_round(folder):
    """{"date", "tester", "commit", ..., "results": {title: Passed|Failed|Pending}}; empty results = not run."""
    meta_path = os.path.join(folder, "run.json")
    if not os.path.exists(meta_path):
        return {"results": {}}
    with open(meta_path, encoding="utf-8") as f:
        meta = json.load(f)
    json_report, ci_log = os.path.join(folder, "playwright.json"), os.path.join(folder, "playwright-ci.log")
    meta["results"] = _from_json(json_report) if os.path.exists(json_report) else _from_log(ci_log)
    assert meta["results"], f"no Playwright result in {folder}"
    return meta
