# Report 5.2: write one more Bruno round (results/<round>/ from run_bruno_report52.sh) into Round 3 of the existing
# workbook, on any OS (sheet XML through xlsx_cells.py, no Excel). Each row's result is decided by bruno_results.py
# from the requests named in its Note, as fill_bruno_report.py decides Rounds 1-2. The GoldenFlow row is not a Bruno
# row: its Round 3 comes from the command line (result and the note of the golden-flow run).
#
#   python update_52_round_xml.py results/round3 ../../docs/reports/Report-5.2_Test_Report.xlsx \
#       Pending "golden-flow.test.mjs skipped itself: 28/11/2026 is not a Vietnam business day (D63, D67)"
import os
import re
import sys

import openpyxl

from bruno_results import load_round, row_defects, row_result
from xlsx_cells import Book

ROUND_DIR, BOOK, GOLDEN_RESULT, GOLDEN_NOTE = sys.argv[1:5]
RND = load_round(os.path.abspath(ROUND_DIR))
assert RND["runs"], f"no Bruno report in {ROUND_DIR}"

wb = openpyxl.load_workbook(BOOK, read_only=True)
sheets = [ws.title for ws in wb.worksheets[3:]]
rows = []  # (sheet, row, result, defects, old note)
for ws in wb.worksheets[3:]:
    for i, row in enumerate(ws.iter_rows(min_row=12, max_col=16, values_only=True), start=12):
        if not (isinstance(row[0], str) and row[0].startswith("ITC")):
            continue
        note = row[15] or ""
        if ws.title == "GoldenFlow":
            rows.append((ws.title, i, GOLDEN_RESULT, "", note))
            continue
        bruno = note[len("Bruno: "):] if note.startswith("Bruno: ") else ""
        result = row_result(RND, bruno, row[3] or "")
        rows.append((ws.title, i, result, row_defects(RND, bruno) if result == "Failed" else "", note))
old_note = wb["Test Statistics"]["B7"].value
cover_last = max(i for i, row in enumerate(wb["Cover"].iter_rows(min_row=11, max_col=1, values_only=True), start=11)
                 if row[0])
wb.close()

book = Book(BOOK)
counts = {}
for sheet, r, result, defects, note in rows:
    counts[result] = counts.get(result, 0) + 1
    tested = result in ("Passed", "Failed")
    for col, value in zip("LMN", (result, RND["date"] if tested else "", RND["tester"] if tested else "")):
        book.set(sheet, f"{col}{r}", value)
    if defects:
        book.set(sheet, f"O{r}", defects)
    if sheet == "GoldenFlow":
        book.set(sheet, f"P{r}", re.sub(r"\nRound 3:.*", "", note, flags=re.S) + "\nRound 3: " + GOLDEN_NOTE)
# Test Statistics counts Round 3 (row 7 of each sheet)
for i, sheet in enumerate(sheets):
    for col, ref in zip("CDE", ("B7", "C7", "D7")):
        book.set("Test Statistics", f"{col}{12 + i}", f"='{sheet}'!{ref}")
summ = ", ".join(f"{counts[k]} {k}" for k in ("Passed", "Failed", "Pending") if counts.get(k))
bruno = {k: v for k, v in counts.items()}
golden = next(r for r in rows if r[0] == "GoldenFlow")[2]
bruno[golden] -= 1
book.set("Test Statistics", "B7", (
    f"{old_note} Round 3 (regression) ngày {RND['date']} trên commit {RND['commit']}, tester {RND['tester']}, Bruno CLI "
    f"{RND['bruno_cli']}, stack Docker Compose dựng mới: Bruno "
    + ", ".join(f"{bruno[k]} {k}" for k in ("Passed", "Failed", "Pending") if bruno.get(k))
    + f"; GoldenFlow {golden} ({GOLDEN_NOTE}). Log: scripts/unit-test-report/results/{os.path.basename(ROUND_DIR)}/. "
    "Bảng dưới đếm theo Round 3."))
r = cover_last + 1
for col, value in zip("ABCDEF", [RND["date"], "v1.1", "Round 3 mọi module, Test Statistics", "M",
                                 f"Round 3 = regression ngày {RND['date']} trên {RND['commit']}: {summ}; Test "
                                 "Statistics đếm theo Round 3", "Report 5.0 §II.3"]):
    book.set("Cover", f"{col}{r}", value, style_from=f"{col}{cover_last}")
book.refresh_cached_values()
book.save()
print(f"Round 3: {summ}")
