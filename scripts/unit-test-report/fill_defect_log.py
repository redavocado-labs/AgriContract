# Write Report 5.5 (defect log, #670) from data_defects.py, on the Report 5.1 template so Cover and the statistics
# sheet look like 5.1/5.2. Pure openpyxl (Linux): the template's ModuleName1 sheet is reused for the defect list,
# its header and body cell styles are copied to the new columns; Test Cases is dropped.
#
#   python fill_defect_log.py template/Report-5.1_Unit_Test_template.xlsx \
#       ../../docs/reports/Report-5.5_Defect_Log.xlsx
import sys
from copy import copy

import openpyxl

from data_defects import DEFECTS

SRC, OUT = sys.argv[1:3]
CREATOR = "Nguyen Minh Cuong"
ISSUE_DATE = "27/11/2026"
TESTERS = ("Nguyen Minh Cuong", "Nguyen Hai Long")
LEVELS = ("UT", "IT", "ST")
SEVERITIES = ("High", "Medium", "Low")
STATUSES = ("Open", "Closed")
COLUMNS = [  # header, width, value(defect)
    ("Defect ID", 10, lambda d: d["id"]),
    ("Test Case ID", 34, lambda d: d["test"]),
    ("Level", 7, lambda d: d["level"]),
    ("Module", 18, lambda d: d["module"]),
    ("Description", 36, lambda d: d["title"]),
    ("Expected Results", 36, lambda d: d["expected"]),
    ("Actual Results", 40, lambda d: d["actual"]),
    ("Severity", 9, lambda d: d["severity"]),
    ("Found date", 11, lambda d: d["found"][0]),
    ("Found commit", 11, lambda d: d["found"][1] or "N/A"),
    ("Tester", 18, lambda d: d["found"][2]),
    ("Found evidence", 34, lambda d: d["found"][3]),
    ("Cause", 36, lambda d: d["cause"]),
    ("Fix (issue / PR)", 16, lambda d: d["fix"][0]),
    ("Fix commit", 12, lambda d: d["fix"][1] or "N/A"),
    ("Fixed by", 18, lambda d: d["fix"][2]),
    ("Status", 8, lambda d: d["status"]),
    ("Re-test date", 11, lambda d: d["retest"][0] if d["retest"] else ""),
    ("Re-test commit", 12, lambda d: (d["retest"][1] or "N/A") if d["retest"] else ""),
    ("Re-test result", 10, lambda d: d["retest"][2] if d["retest"] else "Pending"),
    ("Re-test evidence", 34, lambda d: d["retest"][3] if d["retest"] else ""),
]
HEADER_ROW, FIRST = 4, 5

ids = [d["id"] for d in DEFECTS]
assert len(set(ids)) == len(ids), "duplicate defect id"
for d in DEFECTS:
    assert d["found"][2] in TESTERS, d["id"]
    assert d["severity"] in SEVERITIES and d["status"] in STATUSES, d["id"]
    assert all(l in LEVELS for l in d["level"].split("/")), d["id"]
    assert (d["status"] == "Open") == (d["retest"] is None), d["id"]


def style_from(dst, src):
    dst.font, dst.fill, dst.border = copy(src.font), copy(src.fill), copy(src.border)
    dst.alignment, dst.number_format = copy(src.alignment), src.number_format


wb = openpyxl.load_workbook(SRC)
del wb["Test Cases"]

# Cover
cv = wb["Cover"]
cv["B2"] = "DEFECT LOG"
cv["B4"], cv["B5"] = "AgriContract", "AGC"
cv["F4"], cv["F5"], cv["F6"] = CREATOR, ISSUE_DATE, "v1.0"
cv["B6"] = '=B5&"_DefectLog_v1.0"'
cv["A11"], cv["B11"], cv["C11"], cv["D11"] = ISSUE_DATE, "v1.0", "Toàn bộ tài liệu", "A"
cv["E11"] = ("Bản đầu: 16 defect thật từ CI (GitHub Actions), Report 5.2 Round 1 và các vòng E2E; Tester là người "
             "kích hoạt lần chạy; mỗi defect có commit, log, bản sửa và lần chạy lại")
cv["F11"] = "Report 5.0, 5.1, 5.2; #670"
# Later changes: one row each under the first, same version (date, item, A/D/M, description, reference)
CHANGES = [
    ("27/11/2026", "Defect List", "A", "Thêm DEF-17: image Java thiếu module luật kiến trúc, stack không build từ main",
     "#288, PR #678"),
]
for row, (date, item, kind, text, ref) in enumerate(CHANGES, start=12):
    for col, value in zip("ABCDEF", (date, "v1.0", item, kind, text, ref)):
        cell = cv[f"{col}{row}"]
        cell.value = value
        cell._style = copy(cv[f"{col}11"]._style)

# Defect List (from ModuleName1)
ws = wb["ModuleName1"]
ws.title = "Defect List"
head_cell, body_cell, label_cell, value_cell = ws["A11"], ws["B13"], ws["A2"], ws["B2"]
styles = {k: (c.font, c.fill, c.border, c.alignment) for k, c in
          (("head", head_cell), ("body", body_cell), ("label", label_cell), ("value", value_cell))}
styles = {k: tuple(copy(x) for x in v) for k, v in styles.items()}
for rng in list(ws.merged_cells.ranges):
    ws.unmerge_cells(str(rng))
ws.delete_rows(1, ws.max_row)
ws.conditional_formatting = type(ws.conditional_formatting)()
ws.data_validations.dataValidation = []


def put(cell, value, kind, wrap=True):
    cell.value = value
    f, fi, b, a = styles[kind]
    cell.font, cell.fill, cell.border = copy(f), copy(fi), copy(b)
    cell.alignment = copy(a).__class__(horizontal=a.horizontal, vertical="top", wrap_text=wrap)


put(ws["A1"], "Code Module", "label")
put(ws["B1"], "Defect Log", "value")
put(ws["A2"], "Number of defects", "label")
put(ws["B2"], '=COUNTIF(A%d:A1000,"DEF-*")' % FIRST, "value")
for col in list(ws.column_dimensions):  # the template hides its list-value columns
    del ws.column_dimensions[col]
for i, (name, width, _) in enumerate(COLUMNS, start=1):
    ws.column_dimensions[openpyxl.utils.get_column_letter(i)].width = width
    put(ws.cell(HEADER_ROW, i), name, "head")
ws.column_dimensions["A"].width = 18
for r, d in enumerate(DEFECTS, start=FIRST):
    for i, (_, _, value) in enumerate(COLUMNS, start=1):
        put(ws.cell(r, i), value(d), "body")
    lines = max(-(-len(str(value(d))) // max(int(width * 1.1), 1)) for _, width, value in COLUMNS)
    ws.row_dimensions[r].height = 13 * max(lines, 1) + 2
ws.freeze_panes = ws.cell(FIRST, 2)
last_col = openpyxl.utils.get_column_letter(len(COLUMNS))
ws.print_area = "A1:%s%d" % (last_col, FIRST + len(DEFECTS) - 1)
ws.page_setup.orientation = "landscape"
ws.sheet_properties.pageSetUpPr.fitToPage = True
ws.page_setup.fitToWidth, ws.page_setup.fitToHeight = 1, 0
ws.auto_filter.ref = "A%d:%s%d" % (HEADER_ROW, openpyxl.utils.get_column_letter(len(COLUMNS)),
                                   FIRST + len(DEFECTS) - 1)

# Defect Statistics (from Test Statistics)
st = wb["Test Statistics"]
st.title = "Defect Statistics"
st["A2"] = "DEFECT STATISTICS"
st["F4"], st["F6"] = CREATOR, ISSUE_DATE
st["B6"] = '=Cover!B6'
st["B7"] = ("Defect tìm thấy khi chạy test từ 15/11 đến 26/11/2026: CI GitHub Actions (UT, IT, Playwright, golden flow), "
            "Report 5.2 Round 1 (Bruno) và các vòng E2E ~/agricontract-e2e/. Chỉ tính lỗi do code, config, contract "
            "hoặc dữ liệu seed; không tính lần CI không chạy (khoá billing), lỗi tải Maven và lỗi đỏ lúc đang code. "
            "Tester = người kích hoạt lần chạy. Re-test = lần CI/vòng test xanh đầu tiên sau commit sửa.")
col_of = {name: openpyxl.utils.get_column_letter(i) for i, (name, _, _) in enumerate(COLUMNS, start=1)}
tbl_head, tbl_body = copy(st["B11"]._style), copy(st["C12"]._style)
st._charts, st._images = [], []  # template charts count test rounds, not defects
blank = copy(st["A1"]._style)
for row in st.iter_rows(min_row=11, max_row=st.max_row):
    for c in row:
        c.value = None
        c._style = copy(blank)
st.column_dimensions["C"].width = 22


def count(column, criterion):
    col = col_of[column]
    return "COUNTIFS('Defect List'!$%s$%d:$%s$1000,\"%s\")" % (col, FIRST, col, criterion)


rows = [("Level", l, count("Level", "*%s*" % l)) for l in LEVELS]
rows += [("Severity", s, count("Severity", s)) for s in SEVERITIES]
rows += [("Status", s, count("Status", s)) for s in STATUSES]
rows += [("Tester", t, count("Tester", t)) for t in TESTERS]
rows += [("Re-test", "Passed", count("Re-test result", "Passed"))]
for i, h in enumerate(("No", "Group", "Value", "Defects"), start=1):
    st.cell(11, i).value = h
    st.cell(11, i)._style = copy(tbl_head)
for n, (group, value, formula) in enumerate(rows, start=1):
    for i, v in enumerate((n, group, value, "=" + formula), start=1):
        st.cell(11 + n, i).value = v
        st.cell(11 + n, i)._style = copy(tbl_body)
total = 12 + len(rows)
for i, v in enumerate(("", "Total", "", "='Defect List'!B2"), start=1):
    st.cell(total, i).value = v
    st.cell(total, i)._style = copy(tbl_head)

wb._sheets = [wb["Cover"], st, ws]
wb.active = 0
wb.save(OUT)
print("wrote %s: %d defects" % (OUT, len(DEFECTS)))
