# Report 5.1: Round 3 becomes the regression run of one day, on any OS (writes the sheet XML through xlsx_cells.py,
# no Excel). The rows and their tests stay as update_51_traced.py / update_51_nhl.py traced them; this script only
# re-decides each traced row's Round 3 from the new run.
#
# A traced row is one whose Round 3 (column L) holds Passed or Failed. Its result depends only on the outcome of its
# tests, which all ran in the previous Round 3 (results/unit-round3*/cases.json). So:
#   * every test that passed in the previous run and still passes  -> the row keeps its result, with the new date
#   * a test of the previous run that failed, or is missing now    -> listed, and the script stops: such a row has to
#                                                                     be traced again with update_51_traced.py
# The new run is results/<round>/cases.json (surefire_cases.py + jest_cases.py collect, merged), with run.json.
#
#   python update_51_round3_xml.py results/ut-round3-20261128 ../../docs/reports/Report-5.1_Unit_Test.xlsx
import glob
import json
import os
import sys

import openpyxl

from xlsx_cells import Book

HERE = os.path.dirname(os.path.abspath(__file__))
ROUND_DIR, BOOK = (os.path.abspath(p) for p in sys.argv[1:3])
RUN = json.load(open(os.path.join(ROUND_DIR, "run.json"), encoding="utf-8"))
NEW = json.load(open(os.path.join(ROUND_DIR, "cases.json"), encoding="utf-8"))

OLD = {}
for path in sorted(glob.glob(os.path.join(HERE, "results", "unit-round3*", "cases.json"))):
    for key, tests in json.load(open(path, encoding="utf-8")).items():
        OLD.setdefault(key, {}).update(tests)

regressed = [(k, t, NEW.get(k, {}).get(t)) for k, tests in OLD.items() for t, s in tests.items()
             if s == "passed" and NEW.get(k, {}).get(t) != "passed"]
failed_now = [(k, t) for k, tests in NEW.items() for t, s in tests.items() if s == "failed"]

wb = openpyxl.load_workbook(BOOK, read_only=True)
cells, notes = [], []  # (sheet, row, result); Note text of every traced row
for ws in wb.worksheets[3:]:
    for i, row in enumerate(ws.iter_rows(min_row=12, max_col=16, values_only=True), start=12):
        if isinstance(row[0], str) and row[0].startswith("UTC") and row[11] in ("Passed", "Failed"):
            cells.append((ws.title, i, row[11]))
            notes.append(str(row[15] or ""))
note = wb["Test Statistics"]["B7"].value
wb.close()

# A previous test that is gone now matters only when a traced row names it. The previous run also holds tests
# deleted long before it (stale surefire XML left in target/ on that machine): no row names those.
traced_text = "\n".join(notes)
passing_now = {(k.split("/", 1)[0], t) for k, tests in NEW.items() for t, s in tests.items() if s == "passed"}
# a name also used by a test class that still exists and passes (execute_contractNotFound_throws) is that test
used = [x for x in regressed if x[1] in traced_text and (x[0].split("/", 1)[0], x[1]) not in passing_now]
stale = [x for x in regressed if x[1] not in traced_text]
if stale:
    print(f"{len(stale)} tests of the previous run are gone and no traced row names them (ignored), e.g. {stale[:3]}")
if used or failed_now:
    print("tests named by a traced row that did not pass now (trace these rows again):")
    for x in used:
        print("  ", x)
    for x in failed_now:
        print("   failed:", x)
    sys.exit(1)

book = Book(BOOK)
for sheet, r, result in cells:
    book.set(sheet, f"L{r}", result)
    book.set(sheet, f"M{r}", RUN["date"])
    book.set(sheet, f"N{r}", RUN["tester"])

n_java = sum(len(v) for k, v in NEW.items() if not k.split("/", 1)[1].endswith(".ts"))
n_jest = sum(len(v) for k, v in NEW.items() if k.split("/", 1)[1].endswith(".ts"))
pending = sum(1 for v in NEW.values() for s in v.values() if s in ("pending", "skipped"))
head = note.split(" Phân loại round:")[0].split(" api-gateway, product-service, user-service: mỗi dòng")[0]
head = head.split(" Số test đã chạy thật:")[0]
sentence = (
    f" Round 3 = regression: chạy lại toàn bộ unit test của mọi service ngày {RUN['date']} trên commit {RUN['commit']} "
    f"({RUN['command']}), kết quả từng test lưu trong scripts/unit-test-report/results/{os.path.basename(ROUND_DIR)}/"
    f"cases.json. Số test đã chạy: {n_java} (JUnit) + {n_jest} (Jest), 0 Failed, {pending} không chạy (test cần MySQL "
    f"/ Redis thật, bật bằng biến môi trường). Mỗi dòng ghi test method trong Note; dòng Passed khi mọi test của dòng "
    f"pass; dòng có test đã bị xoá hoặc viết lại giữ N/A. {len(cells)} dòng có Round 3: "
    f"{sum(1 for c in cells if c[2] == 'Passed')} Passed, {sum(1 for c in cells if c[2] == 'Failed')} Failed. "
    "Round 1 = lần chạy đầu (31/10/2026 với module cũ, ngày merge với module mới) và Round 2 = confirmation chạy lại "
    "ngày 07/11/2026 các module của 31/10: lịch sử của báo cáo 10/11, không lưu log chạy, trừ AnalyticsTopology UTC004 "
    "có CI run trong Report 5.6 DEF-01. Lần regression trước (25/11 và 27/11/2026, trên 614e6ab6 / 94d46500) lưu ở "
    "scripts/unit-test-report/results/unit-round3*/.")
book.set("Test Statistics", "B7", head + "." * (not head.endswith(".")) + sentence)

# Cover: one record of change
wb = openpyxl.load_workbook(BOOK, read_only=True)
cover = wb["Cover"]
last = max(i for i, row in enumerate(cover.iter_rows(min_row=11, max_col=1, values_only=True), start=11) if row[0])
wb.close()
r = last + 1
for col, value in zip("ABCDEF", [RUN["date"], "v1.0", "Round 3 mọi sheet, Test Statistics", "M",
                                 f"Round 3 = regression ngày {RUN['date']} trên {RUN['commit']}: mọi service chạy lại, "
                                 f"{len(cells)} dòng đọc lại kết quả từ lần chạy thật", "Report 5.0 §II.3"]):
    book.set("Cover", f"{col}{r}", value, style_from=f"{col}{last}")
book.refresh_cached_values()
book.save()
print(f"{len(cells)} rows -> Round 3 {RUN['date']}; {n_java} JUnit + {n_jest} Jest tests, {pending} pending")
