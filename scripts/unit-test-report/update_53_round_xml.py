# Since 28/11/2026 these Bruno e2e sheets live in Report 5.2 (move_bruno_st_to_52.py, D95) as <Sheet>Api with ITC IDs,
# and Report 5.3 holds the system tests through the web client (fill_53_ui.py). The text below describes the old place.
# Report 5.3: write the newest run-e2e-full.sh round (results/st-round<N>/, imported by import_e2e_round.py) into
# the existing workbook, on any OS (sheet XML through xlsx_cells.py, no Excel). The rows, their results and their
# Actual Results text are decided by the same code as fill_system_test_report.py: its row-building part (everything
# before the Excel section) is run here as it is, so Passed/Failed/Pending still come from backend/scripts/e2e/report.py
# and a Failed row without a Report 5.6 defect still stops the script.
#
# Per row it writes Round <N> (result, date, tester), Defect IDs, Note and Actual Results; rows are found by the case
# id at the start of Input parameters, so the STC numbering and the layout stay. A case of the run that has no row
# stops the script (rebuild the workbook with fill_system_test_report.py on Windows).
#
#   python update_53_round_xml.py ../../docs/reports/Report-5.3_System_Test_FRs.xlsx
import os
import re
import sys
import types
from pathlib import Path

import openpyxl

from xlsx_cells import Book

HERE = Path(__file__).resolve().parent
BOOK = os.path.abspath(sys.argv[1])
src = (HERE / "fill_system_test_report.py").read_text(encoding="utf-8")
prefix = src.split("# ── Excel ──")[0]
for name in ("pythoncom", "win32com", "win32com.client"):
    sys.modules.setdefault(name, types.ModuleType(name))
sys.modules["win32com"].client = sys.modules["win32com.client"]
sys.argv = ["fill_system_test_report.py", BOOK, BOOK]
ns = {"__file__": str(HERE / "fill_system_test_report.py"), "__name__": "fill_53"}
exec(compile(prefix, "fill_system_test_report.py", "exec"), ns)
ROUNDS, MODULES = ns["ROUNDS"], ns["MODULES"]
latest = len(ROUNDS)
RND = ROUNDS[-1]
COLS = {1: "FGH", 2: "IJK", 3: "LMN"}[latest]

rows_by_sheet = {}
wb = openpyxl.load_workbook(BOOK, read_only=True)
for m in MODULES:
    ws = wb[m["sheet"]]
    found = {}
    for i, row in enumerate(ws.iter_rows(min_row=12, max_col=3, values_only=True), start=12):
        if isinstance(row[0], str) and row[0].startswith("STC") and row[2]:
            found[str(row[2]).split(":", 1)[0]] = i
    rows_by_sheet[m["sheet"]] = found
cover_last = max(i for i, row in enumerate(wb["Cover"].iter_rows(min_row=11, max_col=1, values_only=True), start=11)
                 if row[0])
wb.close()

book = Book(BOOK)
counts, missing = {}, []
for m in MODULES:
    found = rows_by_sheet[m["sheet"]]
    for _, rows in m["groups"]:
        for c in rows:
            case_id = c["inp"].split(":", 1)[0]
            r = found.get(case_id)
            if r is None:
                missing.append(f"{m['sheet']} {case_id}")
                continue
            result = c["rounds"][-1]
            counts[result] = counts.get(result, 0) + 1
            tested = result in ("Passed", "Failed")
            for col, value in zip(COLS, (result, RND["date"] if tested else "", RND["tester"] if tested else "")):
                book.set(m["sheet"], f"{col}{r}", value)
            book.set(m["sheet"], f"O{r}", c["defect"])
            book.set(m["sheet"], f"P{r}", c["note"])
            book.set(m["sheet"], f"Q{r}", c["actual"])
assert not missing, "cases of the run with no row in the workbook: " + ", ".join(missing)

# Test Statistics counts the newest round; B7 gets one line per round
for i, m in enumerate(MODULES):
    for col, ref in zip("CDE", (f"B{4 + latest}", f"C{4 + latest}", f"D{4 + latest}")):
        book.set("Test Statistics", f"{col}{12 + i}", f"='{m['sheet']}'!{ref}")
total = sum(len(rows) for m in MODULES for _, rows in m["groups"])
summ = ", ".join(f"{counts[k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if counts.get(k))
old_note = openpyxl.load_workbook(BOOK, read_only=True)["Test Statistics"]["B7"].value
old_note = re.sub(r" Bảng dưới đếm theo Round \d+\.$", "", old_note)
book.set("Test Statistics", "B7", (
    f"{old_note} Round {latest} ngày {RND['date']} trên commit {RND['commit']}, tester {RND['tester']}, lần chạy "
    f"{RND['run']}: {summ}. Log: scripts/unit-test-report/results/{RND['round']}/ (Bruno JSON, queue, "
    f"TESTING-ROUNDS.md, FINDINGS.md). Bảng dưới đếm theo Round {latest}."))
r = cover_last + 1
for col, value in zip("ABCDEF", [RND["date"], "v1.0", f"Round {latest} mọi sheet", "A",
                                 f"Round {latest}: lần chạy {RND['run']} của run-e2e-full.sh trên {RND['commit']}, "
                                 f"{total} dòng: {summ}", "Report 5.0 §II.3, Report 5.6"]):
    book.set("Cover", f"{col}{r}", value, style_from=f"{col}{cover_last}")
book.refresh_cached_values()
book.save()
print(f"Round {latest} ({RND['run']}): {summ}")
