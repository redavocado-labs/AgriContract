# Report 5.1 (#521): trace the rows of one more service to the tests that ran, in place, through Excel (COM).
# For every sheet of the service in trace_<name>.py it writes, row by row:
#   Round 3 (L:N)  the result read per test method from the surefire / Jest run in results/<round>/ (cases.json,
#                  run.json from surefire_cases.py collect); a row passes only if all its methods passed, fails if
#                  one failed; a row whose test no longer exists (REMOVED) gets N/A, never a default Passed
#   Note (P)       "Test: method, ..." or why the row has no test any more
# Rounds 1 and 2 stay as the 10/11 report recorded them. The sheet's line in Test Statistics switches to Round 3,
# and Test Statistics B7 gets (or replaces) one sentence for this service with the run, its counts and the test
# methods no row covers. Every other sheet stays as it is.
#
#   python update_51_traced.py trace_contract results/unit-round3-contract-service ../../docs/reports/Report-5.1_Unit_Test.xlsx
#
# Needs Windows with Excel and pywin32.
import atexit
import importlib
import os
import re
import sys
import time

import pythoncom
import win32com.client

import surefire_cases

trace = importlib.import_module(sys.argv[1])
ROUND_DIR, BOOK = (os.path.abspath(p) for p in sys.argv[2:4])
RUN, CASES = surefire_cases.load(ROUND_DIR)
assert RUN["date"], f"no run.json in {ROUND_DIR}: run surefire_cases.py collect first"
SVC = trace.SERVICE
TESTER = RUN["tester"]
COL = dict(round1=6, date1=7, tester1=8, round2=9, date2=10, tester2=11, round3=12, date3=13, tester3=14,
           defect=15, note=16)


def classes_of(sheet_names):
    """test class of each sheet, read from the data files the workbook was built from"""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import all_modules, data_r2_changes, data_r2_contract, data_r2_core, data_r2_team, data_r2_node
    modules = (data_r2_changes.apply(all_modules.MODULES) + data_r2_changes.NEW_MODULES + data_r2_contract.MODULES
               + data_r2_core.MODULES + data_r2_team.MODULES + data_r2_node.MODULES)
    found = {m["sheet"]: m.get("test_class") or m.get("test_file") for m in modules if m["module"] == SVC}
    missing = set(sheet_names) - set(found)
    assert not missing, f"sheets not in the data files: {missing}"
    return {s: found[s] for s in sheet_names}


SHEETS = list(trace.TESTS) + list(getattr(trace, "REMOVED", {}))
CLASS = classes_of(SHEETS)


def where(sheet, test):
    """(test class or spec file, test name) of one test of a row; "spec.ts::name" points into a folded spec file"""
    if "::" in test:
        source, name = test.split("::", 1)
        return source, name
    return CLASS[sheet], test


def expand(sheet, test):
    """the (source, name) pairs one trace entry stands for; "prefix*" matches every test whose name starts with
    prefix (a whole describe block or it.each table), anything else exactly one test"""
    source, name = where(sheet, test)
    ran = CASES.get(f"{SVC}/{source}", {})
    if name.endswith("*"):
        return [(source, n) for n in ran if n.startswith(name[:-1])]
    return [(source, name)] if name in ran else []


def row_result(sheet, tests):
    pairs = [p for t in tests for p in expand(sheet, t)]
    statuses = [CASES[f"{SVC}/{source}"][name] for source, name in pairs]
    if "failed" in statuses:
        return "Failed"
    return "Passed" if pairs and all(s == "passed" for s in statuses) else ""


def tests_of(row):
    """the tests of one trace row: a list, {"tests": [...], "note": ...}, or a string (row has no test: none)"""
    return [] if isinstance(row, str) else row["tests"] if isinstance(row, dict) else row


# every test named in the trace must have run (a pattern must match at least one); a typo would silently become an
# empty Round 3
unknown = [(s, t) for s, rows in trace.TESTS.items() for row in rows for t in tests_of(row) if not expand(s, t)]
assert not unknown, f"tests not in the run: {unknown}"


def busy(fn):
    """Run one Excel call; while Excel answers "call was rejected by callee" (still busy), wait and retry."""
    for _ in range(300):
        try:
            return fn()
        except pythoncom.com_error as e:
            if e.hresult != -2147418111:
                raise
            time.sleep(0.2)
    return fn()


xl = win32com.client.DispatchEx("Excel.Application")
xl.Visible = False
xl.DisplayAlerts = False
wb = busy(lambda: xl.Workbooks.Open(BOOK))
busy(lambda: setattr(xl, "ScreenUpdating", False))


@atexit.register
def close_excel():
    for fn in (lambda: wb.Close(False), xl.Quit):
        try:
            fn()
        except Exception:
            pass


def put(sh, row, col, value):
    c = busy(lambda: sh.Cells(row, col))
    busy(lambda: setattr(c, "Value", value))
    busy(lambda: setattr(c.Font, "Name", "Tahoma"))
    busy(lambda: setattr(c.Font, "Size", 10))
    busy(lambda: setattr(c.Font, "Italic", False))


counts = {"Passed": 0, "Failed": 0, "N/A": 0}
for sheet in SHEETS:
    sh = busy(lambda: wb.Worksheets(sheet))
    rows = []  # worksheet rows of the test cases, in order
    r = 13
    while True:
        value = busy(lambda: sh.Cells(r, 1).Value)
        if value is None and busy(lambda: sh.Cells(r + 1, 1).Value) is None:
            break
        if isinstance(value, str) and value.startswith("UTC"):
            rows.append(r)
        r += 1
    if sheet in trace.TESTS:
        tests = trace.TESTS[sheet]
        assert len(tests) == len(rows), f"{sheet}: {len(rows)} rows in the workbook, {len(tests)} in the trace"
        for r, methods in zip(rows, tests):
            if isinstance(methods, str):  # this row's test is gone: N/A with the reason, like a REMOVED sheet
                counts["N/A"] += 1
                put(sh, r, COL["round3"], "N/A")
                put(sh, r, COL["date3"], "")
                put(sh, r, COL["tester3"], "")
                put(sh, r, COL["note"], methods)
                continue
            # {"tests": [...], "note": "..."}: the row still has its tests, but something about it needs saying
            # (e.g. its expected status is older than the test's)
            # it may also correct the history of Rounds 1-2 and the defect from the defect log (Report 5.6), e.g. a
            # row whose failure, fix and re-run are recorded there: {"round1": (result, date, tester), ...}
            extra = ""
            if isinstance(methods, dict):
                for n in (1, 2):
                    if f"round{n}" in methods:
                        res, day, who = methods[f"round{n}"]
                        put(sh, r, COL[f"round{n}"], res)
                        put(sh, r, COL[f"date{n}"], "'" + day)
                        put(sh, r, COL[f"tester{n}"], who)
                if "defect" in methods:
                    put(sh, r, COL["defect"], methods["defect"])
                methods, extra = methods["tests"], "\n" + methods["note"]
            result = row_result(sheet, methods)
            assert result in ("Passed", "Failed"), f"{sheet} row {r}: {methods} did not run"
            counts[result] += 1
            put(sh, r, COL["round3"], result)
            put(sh, r, COL["date3"], "'" + RUN["date"])
            put(sh, r, COL["tester3"], TESTER)
            put(sh, r, COL["note"], "Test: " + ", ".join(methods) + extra)
    else:
        for r in rows:
            counts["N/A"] += 1
            put(sh, r, COL["round3"], "N/A")
            put(sh, r, COL["date3"], "")
            put(sh, r, COL["tester3"], "")
            put(sh, r, COL["note"], trace.REMOVED[sheet])
    for r in rows:
        busy(lambda: sh.Rows(r).AutoFit())

# Test Statistics: this service's sheets count Round 3 (row 7 of each sheet), as the traced services already do
st = busy(lambda: wb.Worksheets("Test Statistics"))
r = 12
while busy(lambda: st.Cells(r, 2).Value) not in (None, "Sub total"):
    name = busy(lambda: st.Cells(r, 2).Value)
    if name in SHEETS:
        for col, ref in zip(range(3, 6), ("B7", "C7", "D7")):
            busy(lambda: setattr(st.Cells(r, col), "Formula", f"='{name}'!{ref}"))
    r += 1

covered = {p for s, rows in trace.TESTS.items() for row in rows for t in tests_of(row) for p in expand(s, t)}
sources = {CLASS[s] for s in SHEETS} | {source for source, _ in covered}
uncovered = {}
for source in sources:
    for t in CASES.get(f"{SVC}/{source}", {}):
        if (source, t) not in covered:
            label = os.path.basename(source)
            uncovered[label] = uncovered.get(label, 0) + 1
sentence = (f" {SVC}: mỗi dòng ghi test method trong Note; Round 3 chạy ngày {RUN['date']} trên commit "
            f"{RUN['commit']} ({RUN.get('command', '')}), kết quả từng test đọc từ "
            f"scripts/unit-test-report/results/{os.path.basename(ROUND_DIR)}/cases.json; bảng này lấy Round 3 cho "
            f"service này. {sum(counts.values())} kịch bản: {counts['Passed']} Passed, {counts['Failed']} Failed, "
            f"{counts['N/A']} N/A (test của dòng đã bị xoá hoặc viết lại)."
            + (f" Test chưa có dòng: {sum(uncovered.values())} ("
               + ", ".join(f"{c} {n}" for c, n in sorted(uncovered.items())) + ")." if uncovered else ""))
note = busy(lambda: st.Range("B7").Value) or ""
note = re.sub(rf" {re.escape(SVC)}: mỗi dòng ghi test method trong Note;.*?(?= [a-z-]+-service: mỗi dòng|$)", "",
              note, flags=re.S)
busy(lambda: setattr(st.Range("B7"), "Value", note + sentence))

busy(lambda: xl.CalculateFull())
busy(lambda: wb.Save())
close_excel()
print(f"{SVC}: {counts}; uncovered test methods: {uncovered}")
