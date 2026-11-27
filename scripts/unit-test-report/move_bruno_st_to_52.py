# Move the 8 Bruno e2e sheets (BF-01..BF-07, Security) from Report 5.3 into Report 5.2 as integration tests: they call
# the API through the gateway, which D95 counts as IT; Report 5.3 keeps only system tests through the web client.
# LibreOffice UNO keeps the charts, comments and validation that openpyxl would drop.
#
#   soffice --headless --invisible --norestore --accept="pipe,name=agc52;urp;" &
#   python3 move_bruno_st_to_52.py ../../docs/reports/Report-5.2_Test_Report.xlsx \
#       ../../docs/reports/Report-5.3_System_Test_FRs.xlsx /tmp/Report-5.2.xlsx
import os
import sys

import uno
from com.sun.star.beans import PropertyValue
from com.sun.star.table import CellAddress, CellRangeAddress


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


src52, src53, out = (uno.systemPathToFileUrl(os.path.abspath(p)) for p in sys.argv[1:4])
ctx = uno.getComponentContext()
resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
remote = resolver.resolve("uno:pipe,name=agc52;urp;StarOffice.ComponentContext")
desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
doc = desktop.loadComponentFromURL(src52, "_blank", 0, (prop("Hidden", True),))
doc53 = desktop.loadComponentFromURL(src53, "_blank", 0, (prop("Hidden", True),))
sheets = doc.Sheets


def copy_row(sheet, src_row, dst_row, last_col):
    idx = sheet.RangeAddress.Sheet
    sheet.copyRange(CellAddress(idx, 0, dst_row), CellRangeAddress(idx, 0, src_row, last_col, src_row))


def put(sheet, ref, value):
    cell = sheet.getCellRangeByName(ref)
    if isinstance(value, str) and value.startswith("="):
        cell.Formula = value
    elif isinstance(value, (int, float)):
        cell.Value = value
    else:
        cell.String = value


def link(sheet, ref, target_sheet):
    cell = sheet.getCellRangeByName(ref)
    cell.String = ""
    field = doc.createInstance("com.sun.star.text.TextField.URL")
    field.URL = "#" + target_sheet + ".A1"
    field.Representation = target_sheet
    text = cell.Text
    text.insertTextContent(text.createTextCursor(), field, False)


def replace(sheet, pattern, replacement):
    rd = sheet.createReplaceDescriptor()
    rd.SearchString, rd.ReplaceString = pattern, replacement
    rd.SearchRegularExpression = True
    return sheet.replaceAll(rd)


# sheet in 5.3 -> sheet in 5.2 (5.2 names modules ...Api), module column of Test Cases
MOVED = [
    ("BF01_Onboarding", "BF01_OnboardingApi", "e2e BF-01"),
    ("BF02_PlotListing", "BF02_PlotListingApi", "e2e BF-02"),
    ("BF03_Signing", "BF03_SigningApi", "e2e BF-03"),
    ("BF04_Milestone", "BF04_MilestoneApi", "e2e BF-04"),
    ("BF05_Breach", "BF05_BreachApi", "e2e BF-05"),
    ("BF06_Termination", "BF06_TerminationApi", "e2e BF-06"),
    ("BF07_SecurityLock", "BF07_SecurityLockApi", "e2e BF-07"),
    ("Security", "SecurityApi", "e2e Security"),
]

# descriptions from 5.3 Test Cases rows 9..16, before those rows go away
tc53 = doc53.Sheets.getByName("Test Cases")
desc = {name: (tc53.getCellRangeByName(f"E{9 + i}").String, tc53.getCellRangeByName(f"F{9 + i}").String)
        for i, (name, _, _) in enumerate(MOVED)}

# 1. the sheets, after GoldenFlow
for name, new, _ in MOVED:
    sheets.importSheet(doc53, name, sheets.Count)
    sheets.getByName(name).Name = new
    sh = sheets.getByName(new)
    put(sh, "B2", new)
    put(sh, "B3", '=COUNTIF(A13:A1005;"ITC*")')
    replace(sh, "^STC([0-9]+)$", "ITC$1")
    replace(sh, "^ST qua gateway", "IT qua gateway")
doc53.close(True)

# 2. Test Cases: modules 7..14 after GoldenFlow (row 14)
tc = sheets.getByName("Test Cases")
tc.Rows.insertByIndex(14, len(MOVED))
for i, (name, new, module) in enumerate(MOVED):
    row = 15 + i
    copy_row(tc, 13, row - 1, 6)
    put(tc, f"B{row}", 7 + i)
    put(tc, f"C{row}", module)
    link(tc, f"D{row}", new)
    put(tc, f"E{row}", desc[name][0])
    put(tc, f"F{row}", desc[name][1])
    tc.Rows.getByIndex(row - 1).OptimalHeight = True
d5 = tc.getCellRangeByName("D5")
d5.String = d5.String + (
    "\n9. Bộ Bruno e2e BF-01..BF-07 và Security (chuyển từ Report 5.3 ngày 28/11/2026, D95: gọi API qua gateway là "
    "integration test): cd backend && ./scripts/run-e2e-full.sh --fresh --keep, Bruno CLI 3.4.2 --env local; Round 1..3 "
    "giữ nguyên như khi còn ở 5.3 (run-20261127-1603, run-20261127-1630, run-20261128-0350); log ở "
    "scripts/unit-test-report/results/st-round<N>/")

# 3. Test Statistics: rows 18..25 before Sub total, totals and the gateway kind row include them
st = sheets.getByName("Test Statistics")
st.Rows.insertByIndex(17, len(MOVED))  # Sub total 18 -> 26, kind rows 27/28 -> 35/36; the charts follow
for i, (_, new, _) in enumerate(MOVED):
    row = 18 + i
    copy_row(st, 16, row - 1, 8)
    put(st, f"A{row}", 7 + i)
    link(st, f"B{row}", new)
    for col, ref in zip("CDEFGHI", ("B7", "C7", "D7", "B9", "C9", "D9", "B3")):
        put(st, f"{col}{row}", f"=${new}.{ref}")
for col in "CDEFGHI":
    put(st, f"{col}26", f"=SUM({col}10:{col}25)")
    put(st, f"{col}36", f"={col}12+{col}13+SUM({col}17:{col}25)")
b7 = st.getCellRangeByName("B7")
b7.String = b7.String + (
    " Ngày 28/11/2026 thêm 8 module Bruno e2e (BF-01..BF-07, Security; 128 case) chuyển từ Report 5.3: gọi API qua "
    "gateway nên là integration test (D95), Report 5.3 chỉ còn system test qua giao diện web. Các module này đếm theo "
    "Round 3 (28/11/2026, run-20261128-0350, chạy thứ Bảy nên chuỗi BF-03 ký 2 → BF-04 Pending, DEF-19).")
st.Rows.getByIndex(6).OptimalHeight = True

# 4. Cover: one change-log row
cv = sheets.getByName("Cover")
copy_row(cv, 13, 14, 5)
for ref, value in {
    "A15": "28/11/2026", "B15": "v1.1", "C15": "8 module Bruno e2e, Test Cases, Test Statistics", "D15": "A",
    "E15": "Chuyển 8 sheet Bruno e2e (BF-01..BF-07, Security; 128 case, Round 1..3) từ Report 5.3 sang, đổi STC → ITC "
           "và tên sheet thêm Api: gọi API qua gateway là integration test (D95); 5.3 chỉ còn system test qua UI",
    "F15": "D95, Report 5.3",
}.items():
    put(cv, ref, value)
cv.Rows.getByIndex(14).OptimalHeight = True

doc.calculateAll()
doc.storeToURL(out, (prop("FilterName", "Calc MS Excel 2007 XML"),))
doc.close(True)
print("wrote", sys.argv[3])
