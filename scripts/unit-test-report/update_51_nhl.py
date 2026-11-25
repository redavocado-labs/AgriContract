# Replaces the api-gateway, product-service and user-service sheets of Report-5.1 in place, through Excel (COM),
# from data_nhl.py + data_nhl_new.py and the surefire run in results/unit-round3/. Every other sheet, and the rows of
# the other services in Test Cases and Test Statistics, stay as they are. fill_report.py builds the same sheets
# from the same data when the whole workbook is regenerated on LibreOffice.
#
#   python update_51_nhl.py template/Report-5.1_Unit_Test_template.xlsx ../../docs/reports/Report-5.1_Unit_Test.xlsx
#
# Needs Windows with Excel and pywin32.
import os
import sys

import win32com.client

import data_nhl
import data_nhl_new
import surefire_cases

TEMPLATE, BOOK = (os.path.abspath(p) for p in sys.argv[1:3])
HERE = os.path.dirname(os.path.abspath(__file__))
TESTER = "Nguyen Hai Long"
TRACED = data_nhl.MODULES + data_nhl_new.MODULES
OWNED = {m["module"] for m in TRACED}
STALE = {"ProductUserEligibility", "ProductUserServiceClient"}  # their test classes were removed in #431
ROUND3, CASES = surefire_cases.load(os.path.join(HERE, "results", "unit-round3"))
assert ROUND3["date"], "run surefire_cases.py collect first"

xl = win32com.client.DispatchEx("Excel.Application")
xl.Visible = False
xl.DisplayAlerts = False
wb = xl.Workbooks.Open(BOOK)
tpl_wb = xl.Workbooks.Open(TEMPLATE, ReadOnly=True)


def same_font(rng):
    rng.Font.Name = "Tahoma"
    rng.Font.Size = 10
    rng.Font.Italic = False


def link_to_sheet(sh, c, sheet_name):
    c.Hyperlinks.Delete()
    c.Value = sheet_name
    sh.Hyperlinks.Add(Anchor=c, Address="", SubAddress=f"'{sheet_name}'!A1", TextToDisplay=sheet_name)
    same_font(c)


def fit_rows(sh, first_row, last_row, last_col, skip=()):
    for r in range(first_row, last_row + 1):
        if r in skip:
            continue
        sh.Range(sh.Cells(r, 1), sh.Cells(r, last_col)).WrapText = True
        sh.Rows(r).AutoFit()
        sh.Rows(r).RowHeight = min(409, sh.Rows(r).RowHeight * 1.08 + 2)


# ── which rows of Test Cases belong to whom ─────────────────────────────────
tc = wb.Worksheets("Test Cases")
listed = []  # (module, sheet, desc, pre) in workbook order
r = 9
while tc.Cells(r, 3).Value:
    listed.append((tc.Cells(r, 3).Value, tc.Cells(r, 4).Value, tc.Cells(r, 5).Value, tc.Cells(r, 6).Value))
    r += 1
first_list_row, old_count = 9, len(listed)
others = [x for x in listed if x[0] not in OWNED]
mine = [(m["module"], m["sheet"], m["desc"], m["pre"]) for m in TRACED]
# fill_report order: services sorted by name, a service's sheets in their data order
order = sorted(others + mine, key=lambda x: x[0])
assert len({x[1] for x in order}) == len(order), "duplicate sheet name"

# ── drop the old sheets of the three services, build the new ones ───────────
for name in [x[1] for x in listed if x[0] in OWNED] + sorted(STALE):
    try:
        wb.Worksheets(name).Delete()
    except Exception:  # noqa: BLE001 - a stale sheet may already be gone
        pass

STYLE_HEADER, STYLE_CASE = 1100, 1101
by_sheet = {m["sheet"]: m for m in TRACED}
results = {"Passed": 0, "Failed": 0, "": 0}
prev = wb.Worksheets("Test Statistics")
for module, sheet, _, _ in order:
    if sheet not in by_sheet:
        prev = wb.Worksheets(sheet)
        continue
    m = by_sheet[sheet]
    tpl_wb.Worksheets("ModuleName1").Copy(None, prev)
    sh = wb.Worksheets(prev.Index + 1)
    sh.Name = sheet
    prev = sh
    sh.Rows(12).Copy(sh.Rows(STYLE_HEADER))
    sh.Rows(13).Copy(sh.Rows(STYLE_CASE))
    sh.Range("A12:P1003").ClearContents()
    sh.Range("B2").Value = sheet
    sh.Range("D9").Formula = "=COUNTIF($E12:$E1003,D8)"
    sh.Range("T5").Value = "Failed"
    row, n, header_rows = 12, 0, set()
    for group, cases in m["groups"]:
        sh.Rows(STYLE_HEADER).Copy(sh.Rows(row))
        sh.Range(f"A{row}").Value = group
        header_rows.add(row)
        row += 1
        for case in cases:
            n += 1
            pre, inp, ret, exc, typ = case[:5]
            sh.Rows(STYLE_CASE).Copy(sh.Rows(row))
            rounds = surefire_cases.traced_rounds(m, case, TESTER, ROUND3, CASES)
            results[rounds[6]] += 1
            values = [f"UTC{n:03d}", pre, inp, f"Return: {ret}\nException: {exc}\nLog message: N/A", typ] + [
                ("'" + v if i in (1, 4, 7) and v else v) for i, v in enumerate(rounds)]
            rng = sh.Range(sh.Cells(row, 1), sh.Cells(row, 16))
            rng.Value = [values]
            same_font(rng)
            for col in (6, 9, 12):
                c = sh.Cells(row, col)
                c.Validation.Delete()
                c.Validation.Add(3, 1, 1, "=$T$2:$T$5")
            row += 1
    sh.Rows(f"{STYLE_HEADER}:{STYLE_CASE}").Delete()
    for col, w in {1: 32, 2: 45, 3: 55, 4: 90, 5: 20, 6: 18, 7: 22, 8: 34, 9: 18, 10: 22, 11: 34, 12: 18, 13: 22,
                   14: 34, 16: 80}.items():
        sh.Columns(col).ColumnWidth = round(w / 1.9, 1)
    fit_rows(sh, 13, row - 1, 16, skip=header_rows)

# ── Test Cases list ──────────────────────────────────────────────────────────
delta = len(order) - old_count
if delta > 0:
    for _ in range(delta):
        tc.Rows(first_list_row + 1).Insert()
        tc.Rows(first_list_row + 2).Copy(tc.Rows(first_list_row + 1))
elif delta < 0:
    tc.Rows(f"{first_list_row + 1}:{first_list_row - delta}").Delete()
for i, (module, sheet, desc, pre) in enumerate(order):
    r = first_list_row + i
    tc.Cells(r, 2).Value = i + 1
    for col, text in [(3, module), (5, desc), (6, pre)]:
        tc.Cells(r, col).Value = text
        same_font(tc.Cells(r, col))
    link_to_sheet(tc, tc.Cells(r, 4), sheet)
    if sheet in by_sheet:
        fit_rows(tc, r, r, 6)

# ── Test Statistics: one row per sheet, the three services on Round 3 ───────
st = wb.Worksheets("Test Statistics")
first_stat = 12
if delta > 0:
    for _ in range(delta):
        st.Rows(first_stat + 1).Insert()
        st.Rows(first_stat + 2).Copy(st.Rows(first_stat + 1))
elif delta < 0:
    st.Rows(f"{first_stat + 1}:{first_stat - delta}").Delete()
for i, (module, sheet, _, _) in enumerate(order):
    r = first_stat + i
    st.Cells(r, 1).Value = i + 1
    link_to_sheet(st, st.Cells(r, 2), sheet)
    rnd = "7" if sheet in by_sheet else "5"  # Round 3 row of the module sheet for the traced services
    for col, ref in zip(range(3, 10), [f"B{rnd}", f"C{rnd}", f"D{rnd}", "B9", "C9", "D9", "B3"]):
        st.Cells(r, col).Formula = f"='{sheet}'!{ref}"
sub = first_stat + len(order)
for col in range(3, 10):
    letter = chr(ord("A") + col - 1)
    st.Cells(sub, col).Formula = f"=SUM({letter}{first_stat}:{letter}{sub - 1})"
cases_total = sum(len(c) for m in TRACED for _, c in m["groups"])
note = st.Range("B7").Value or ""
marker = " api-gateway, product-service, user-service:"
note = note.split(marker)[0].rstrip()
st.Range("B7").Value = (
    note + marker + f" mỗi dòng ghi test method trong Note; Round 1/2 là lịch sử của báo cáo 10/11, Round 3 chạy "
    f"ngày {ROUND3['date']} trên commit {ROUND3['commit']} ({ROUND3['command']}), kết quả từng test đọc từ surefire "
    f"(scripts/unit-test-report/results/unit-round3/cases.json); bảng này lấy Round 3 cho 3 service đó. "
    f"{cases_total} kịch bản: {results['Passed']} Passed, {results['Failed']} Failed"
    + (f", {results['']} chưa chạy" if results[""] else "") + ".")

# ── Cover change log ─────────────────────────────────────────────────────────
cover = wb.Worksheets("Cover")
r = 11
while cover.Cells(r, 1).Value and not str(cover.Cells(r, 5).Value or "").startswith("api-gateway, product"):
    r += 1
cover.Rows(r - 1).Copy(cover.Rows(r))  # same style as the row above
for col, text in enumerate([f"'{ROUND3['date']}", "v1.0", "Sheet của api-gateway, product-service, user-service",
                            "M", "api-gateway, product-service, user-service: mỗi dòng gắn test method; thêm sheet cho "
                            "UT chưa có (use case, consumer, client, @WebMvcTest); bỏ 2 sheet của test đã xoá (#431); "
                            "Boundary chỉ cho min-1/min/max/max+1; Round 3 = lần chạy surefire có commit và kết quả "
                            "từng test", "Report 5.0 §I.2.1, #521"], start=1):
    cover.Cells(r, col).Value = text

wb.Worksheets("Cover").Activate()
tpl_wb.Close(False)
xl.CalculateFull()
wb.Save()
wb.Close(False)
xl.Quit()
print(f"{len(TRACED)} sheets, {cases_total} cases: {results}")
