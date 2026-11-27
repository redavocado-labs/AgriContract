# Read the result of each Playwright test (frontend/tests/qa) for one Report 5.5 round.
# A round folder under results/ holds run.json (date, tester, commit, source) and either
#   playwright.json     the JSON reporter of a local run (--reporter=list,json), or
#   playwright-ci.log   the Playwright part of a GitHub Actions job log (list reporter: ✓ / ✘ / - per test, then one
#                       numbered block per failed test with its error)
# Tests are matched by title (the text after "›"), so line numbers that moved between commits do not matter.
# Besides the status, the error Playwright printed for a failed test is kept for the Actual Results column.
import json
import os
import re

LIST_LINE = re.compile(r"^\s+(✓|✘|-|ok|x)\s+\d+\s+\S+\.pw\.ts:\d+:\d+ › (.+?)(?: \(\d+(?:\.\d+)?m?s\))?$")
LIST_STATUS = {"✓": "Passed", "ok": "Passed", "✘": "Failed", "x": "Failed", "-": "Pending"}
JSON_STATUS = {"expected": "Passed", "unexpected": "Failed", "flaky": "Failed", "skipped": "Pending"}
# "  1) tests/qa/accessibility.pw.ts:126:1 › FE-016 the public home page reflows at 320 CSS pixels ────"
FAILURE_HEAD = re.compile(r"^\s+\d+\) \S+\.pw\.ts:\d+:\d+ › (.+?)\s*─*\s*$")
# where the error text ends: the code frame ("  > 15 |", "  13 |"), the stack ("at ...") or the context file
ERROR_END = re.compile(r"^\s*(?:>?\s*\d+ \||at |Error Context:)")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
MAX_ERROR = 300


def _error_text(lines):
    """the error message of one failure, on one line: "Error: ...; Expected: <= 320; Received: 343" """
    kept = []
    for line in lines:
        if ERROR_END.match(line):
            break
        if line.strip():
            kept.append(" ".join(ANSI.sub("", line).split()))
    text = "; ".join(kept)
    return text if len(text) <= MAX_ERROR else text[:MAX_ERROR - 3] + "..."


def _from_log(path):
    results, errors, failure, block = {}, {}, None, []
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for line in lines + [""]:
        m = LIST_LINE.match(line)
        if m:
            results[m.group(2)] = LIST_STATUS[m.group(1)]
            continue
        head = FAILURE_HEAD.match(line)
        if head or re.match(r"^\s+\d+ (failed|passed)", line):
            if failure:
                errors[failure] = _error_text(block)
            failure, block = (head.group(1) if head else None), []
        elif failure:
            block.append(line)
    if failure:
        errors[failure] = _error_text(block)
    return results, errors


def _specs(suite):
    yield from suite.get("specs", [])
    for child in suite.get("suites", []):
        yield from _specs(child)


def _from_json(path):
    with open(path, encoding="utf-8") as f:
        report = json.load(f)
    results, errors = {}, {}
    for suite in report["suites"]:
        for spec in _specs(suite):
            for test in spec["tests"]:
                results[spec["title"]] = JSON_STATUS[test["status"]]
                messages = [e.get("message", "") for r in test.get("results", []) for e in r.get("errors", [])]
                if messages:
                    errors[spec["title"]] = _error_text(messages[-1].splitlines())
    return results, errors


def load_round(folder):
    """{"date", "tester", "commit", ..., "results": {title: Passed|Failed|Pending}, "errors": {title: text}}"""
    meta_path = os.path.join(folder, "run.json")
    assert os.path.exists(meta_path), f"no run.json in {folder}: every round needs its date, tester and commit"
    with open(meta_path, encoding="utf-8") as f:
        meta = json.load(f)
    json_report, ci_log = os.path.join(folder, "playwright.json"), os.path.join(folder, "playwright-ci.log")
    meta["results"], meta["errors"] = _from_json(json_report) if os.path.exists(json_report) else _from_log(ci_log)
    assert meta["results"], f"no Playwright result in {folder}"
    failed = {t for t, s in meta["results"].items() if s == "Failed"}
    assert failed <= set(meta["errors"]), f"{folder}: no error text for {sorted(failed - set(meta['errors']))}"
    return meta
