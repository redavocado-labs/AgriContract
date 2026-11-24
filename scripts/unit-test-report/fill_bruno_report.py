# Fill the same template as Report 5.1 (template/Report-5.1_Unit_Test_template.xlsx) with the Bruno test cases of
# data_bruno.py and write Report-5.2. Same steps as fill_report.py (one copy of ModuleName1 per sheet, Test Cases
# list, Test Statistics rows on Round 1), driven through Excel (COM) so charts, comments and validation survive;
# it needs Windows with Excel and pywin32. The cases are not run yet, so Round 1 is Pending.
# Every case is an integration test: IDs are ITC, Pre-conditions starts with the kind (service called directly or
# through the gateway), and Test Statistics splits the totals by those two kinds.
#
#   python fill_bruno_report.py template/Report-5.1_Unit_Test_template.xlsx \
#       ../../docs/reports/Report-5.2_Test_Report.xlsx
import os
import sys

import win32com.client

from data_bruno import DIRECT, GATEWAY, KIND_LABEL, MODULES

CREATOR = "Nguyen Hai Long"
ISSUE_DATE = "24/11/2026"
PROJECT_CODE = "AGC"
SRC, OUT = (os.path.abspath(p) for p in sys.argv[1:3])

assert len({m["sheet"] for m in MODULES}) == len(MODULES), "duplicate sheet name"
assert all(len(m["sheet"]) <= 31 for m in MODULES)
assert all(m["kind"] in KIND_LABEL for m in MODULES)

xl = win32com.client.DispatchEx("Excel.Application")
xl.Visible = False
xl.DisplayAlerts = False
wb = xl.Workbooks.Open(SRC)


def same_font(rng):
    # every value is Tahoma 10 upright, as in Report 5.1
    rng.Font.Name = "Tahoma"
    rng.Font.Size = 10
    rng.Font.Italic = False


def copy_row(sh, src_row, dst_row):
    sh.Rows(src_row).Copy(sh.Rows(dst_row))


def link_to_sheet(sh, c, sheet_name):
    # the template's link cells already hold a link to ModuleName1, which keeps its text over TextToDisplay
    c.Hyperlinks.Delete()
    c.Value = sheet_name
    sh.Hyperlinks.Add(Anchor=c, Address="", SubAddress=f"'{sheet_name}'!A1", TextToDisplay=sheet_name)
    same_font(c)


def set_widths(sh, widths_mm):
    # widths_mm: {column number (1-based): width in mm}; one Excel width unit is about 1.9 mm in Tahoma 10
    for col, w in widths_mm.items():
        sh.Columns(col).ColumnWidth = round(w / 1.9, 1)


def fit_rows(sh, first_row, last_row, last_col, skip=()):
    for r in range(first_row, last_row + 1):
        if r in skip:
            continue
        sh.Range(sh.Cells(r, 1), sh.Cells(r, last_col)).WrapText = True
        sh.Rows(r).AutoFit()
        sh.Rows(r).RowHeight = min(409, sh.Rows(r).RowHeight * 1.08 + 2)


def fit_page_width(sh):
    sh.PageSetup.Zoom = False
    sh.PageSetup.FitToPagesWide = 1
    sh.PageSetup.FitToPagesTall = False


# ── Cover ────────────────────────────────────────────────────────────────────
cover = wb.Worksheets("Cover")
cover.Range("B2").Value = "API TEST DOCUMENT"
cover.Range("B4").Value = "AgriContract"
cover.Range("B5").Value = PROJECT_CODE
cover.Range("B6").Formula = '=B5&"_TestReport_v1.0"'
cover.Range("F4").Value = CREATOR
cover.Range("F5").Value = "'" + ISSUE_DATE
cover.Range("F6").Value = "v1.0"
for ref, text in zip(["A11", "B11", "C11", "D11", "E11", "F11"],
                     ["'" + ISSUE_DATE, "v1.0", "Toàn bộ tài liệu", "A",
                      "Bản đầu: test case tích hợp (ITC) từ Bruno collection (backend/bruno) của api-gateway, "
                      "user-service, product-service; Pre-conditions ghi gọi thẳng service hay qua gateway; "
                      "1 dòng = 1 kịch bản; Note ghi request Bruno, Note trống = chưa có request",
                      "SRS v1.7, Report 5.0"]):
    cover.Range(ref).Value = text

# ── Module sheets: one copy of ModuleName1 per sheet ────────────────────────
STYLE_HEADER, STYLE_CASE = 1100, 1101
tpl = wb.Worksheets("ModuleName1")
for m in MODULES:
    last = wb.Worksheets(wb.Worksheets.Count)
    tpl.Copy(None, last)  # (Before, After): a keyword After is dropped and the copy lands in a new workbook
    sh = wb.Worksheets(wb.Worksheets.Count)
    assert sh.Name != "ModuleName1" and sh.Name != last.Name, "sheet copy went to the wrong place"
    sh.Name = m["sheet"]
    copy_row(sh, 12, STYLE_HEADER)
    copy_row(sh, 13, STYLE_CASE)
    sh.Range("A12:P1003").ClearContents()
    sh.Range("B2").Value = m["sheet"]
    sh.Range("B3").Formula = '=COUNTIF(A13:A1005,"ITC*")'
    # template counts Boundary from E10 while Normal/Abnormal start at E12
    sh.Range("D9").Formula = "=COUNTIF($E12:$E1003,D8)"
    # status dropdown list (T2:T4) has no "Failed"
    sh.Range("T5").Value = "Failed"

    row, n, header_rows = 12, 0, set()
    for group, cases in m["groups"]:
        copy_row(sh, STYLE_HEADER, row)
        sh.Range(f"A{row}").Value = group
        header_rows.add(row)
        row += 1
        for pre, inp, ret, exc, typ, note in cases:
            n += 1
            copy_row(sh, STYLE_CASE, row)
            pre = KIND_LABEL[m["kind"]] + ("" if pre == "N/A" else "\n" + pre)
            values = [f"ITC{n:03d}", pre, inp, f"Return: {ret}\nException: {exc}\nLog message: N/A", typ,
                      "Pending", "", "", "", "", "", "", "", "", "", f"Bruno: {note}" if note else ""]
            rng = sh.Range(sh.Cells(row, 1), sh.Cells(row, 16))
            rng.Value = [values]
            same_font(rng)
            for col in (6, 9, 12):
                c = sh.Cells(row, col)
                c.Validation.Delete()
                c.Validation.Add(3, 1, 1, "=$T$2:$T$5")
            row += 1
    sh.Rows(f"{STYLE_HEADER}:{STYLE_CASE}").Delete()
    # ID, Pre-conditions, Input, Expected, Type, Round 1, Test date, Tester, Round 2, Test date, Tester, Note
    set_widths(sh, {1: 32, 2: 45, 3: 55, 4: 90, 5: 20, 6: 18, 7: 22, 8: 34, 9: 18, 10: 22, 11: 34, 16: 60})
    fit_rows(sh, 13, row - 1, 16, skip=header_rows)

tpl.Delete()

# ── Test Cases ───────────────────────────────────────────────────────────────
tc = wb.Worksheets("Test Cases")
tc.Range("D3").Formula = "=Cover!B4"
tc.Range("D4").Formula = "=Cover!B5"
tc.Range("D5").Value = (
    "1. Toàn bộ stack: docker compose up trong backend/ (gateway 8080, user 8081, product 8082, contract 8083, "
    "Keycloak 8180, RabbitMQ, MySQL 8.0)\n"
    "2. Bruno CLI: cd backend/bruno && bru run <thư mục> --env local\n"
    "3. Tài khoản Keycloak: buyer1, seller1, seller2, operator1, admin1 (pass123)\n"
    "4. Seed: backend/scripts/seed/01-product-db.sql, 02-user-db.sql\n"
    "5. product-service: PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0, PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000\n"
    "6. Mọi case là integration test (ITC), 2 kiểu: gọi thẳng service với X-Gateway-Secret (product-service) "
    "hoặc qua gateway {{base_url}} với token Keycloak (api-gateway, user-service); system test theo luồng FE "
    "BF-01..BF-07 không nằm ở đây\n"
    "7. Note ghi request Bruno; Note trống = case chưa có request")
for i, m in enumerate(MODULES):
    r = 9 + i
    if r > 10:
        copy_row(tc, 10, r)
    tc.Cells(r, 2).Value = i + 1
    for col, text in [(3, m["module"]), (5, m["desc"]), (6, m["pre"])]:
        tc.Cells(r, col).Value = text
        same_font(tc.Cells(r, col))
    link_to_sheet(tc, tc.Cells(r, 4), m["sheet"])
set_widths(tc, {2: 12, 3: 38, 4: 52, 5: 95, 6: 80})
fit_rows(tc, 9, 8 + len(MODULES), 6)
tc.Range("D5").WrapText = True
tc.Rows(5).RowHeight = 9 * 14.5
fit_page_width(tc)

# ── Test Statistics ──────────────────────────────────────────────────────────
st = wb.Worksheets("Test Statistics")
st.Range("A2").Value = "API TEST STATISTICS"
extra = len(MODULES) - 4  # template has data rows 12..15 inside SUM(…10:…15)
for _ in range(max(0, extra)):
    st.Rows(15).Insert()
st.Range("B6").Formula = "=Cover!B6"
st.Range("F4").Value = CREATOR
st.Range("F6").Value = "'" + ISSUE_DATE
cases = sum(len(c) for m in MODULES for _, c in m["groups"])
no_bruno = sum(1 for m in MODULES for _, c in m["groups"] for row in c if not row[5])
by_kind = {k: sum(len(c) for m in MODULES if m["kind"] == k for _, c in m["groups"]) for k in KIND_LABEL}
st.Range("B7").Value = (
    f"Integration test (ITC) của 3 module: api-gateway, user-service, product-service, lấy từ Bruno collection "
    f"backend/bruno. {cases} kịch bản: {by_kind[DIRECT]} gọi thẳng service, {by_kind[GATEWAY]} qua gateway; "
    f"{cases - no_bruno} có request Bruno và {no_bruno} chưa có (Note trống). Round 1 chưa chạy (Pending).")
for i, m in enumerate(MODULES):
    r = 12 + i
    if r > 12:
        copy_row(st, 12, r)
    st.Cells(r, 1).Value = i + 1
    link_to_sheet(st, st.Cells(r, 2), m["sheet"])
    # Round 1 row (5) — template pointed at Round 3 (row 7)
    for col, ref in zip(range(3, 10), ["B5", "C5", "D5", "B9", "C9", "D9", "B3"]):
        st.Cells(r, col).Formula = f"='{m['sheet']}'!{ref}"
# IT split by kind, below the coverage block: same header and row styles as the module table
first_mod, sub_total = 12, 12 + len(MODULES)
kind_head = sub_total + 8
copy_row(st, 11, kind_head)
st.Cells(kind_head, 1).Value = "No"
st.Cells(kind_head, 2).Value = "Kiểu integration test"
for i, k in enumerate((DIRECT, GATEWAY)):
    r = kind_head + 1 + i
    copy_row(st, first_mod, r)
    st.Cells(r, 1).Value = i + 1
    st.Cells(r, 2).Hyperlinks.Delete()
    st.Cells(r, 2).Value = KIND_LABEL[k]
    same_font(st.Cells(r, 2))
    rows = [first_mod + j for j, m in enumerate(MODULES) if m["kind"] == k]
    for col in range(3, 10):
        col_l = chr(ord("A") + col - 1)
        st.Cells(r, col).Formula = "=" + "+".join(f"{col_l}{x}" for x in rows) if rows else "=0"
copy_row(st, sub_total, kind_head + 3)
for col in range(3, 10):
    col_l = chr(ord("A") + col - 1)
    st.Cells(kind_head + 3, col).Formula = f"=SUM({col_l}{kind_head + 1}:{col_l}{kind_head + 2})"
st.Range("B7").WrapText = True
st.Rows(7).RowHeight = 4 * 14.5
st.Columns(2).ColumnWidth = round(60 / 1.9, 1)
fit_page_width(st)

wb.Worksheets("Cover").Activate()
xl.CalculateFull()
if os.path.exists(OUT):
    os.remove(OUT)
wb.SaveAs(OUT, 51)
wb.Close(False)
xl.Quit()
print("wrote", OUT, f"({cases} cases, {no_bruno} without a Bruno request)")
