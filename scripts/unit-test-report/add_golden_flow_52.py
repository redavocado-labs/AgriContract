# Add the GoldenFlow module (DEF-17, #288) to Report 5.2 through LibreOffice UNO on Linux, keeping charts, comments
# and validation (openpyxl drops the charts). Run it after fill_bruno_report.py, which does not know this module.
#
#   soffice --headless --invisible --norestore --accept="pipe,name=agc52;urp;" &
#   python3 add_golden_flow_52.py ../../docs/reports/Report-5.2_Test_Report.xlsx /tmp/Report-5.2.xlsx
import sys
import uno
from com.sun.star.beans import PropertyValue
from com.sun.star.table import CellAddress, CellRangeAddress


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


src, out = (uno.systemPathToFileUrl(p) for p in sys.argv[1:3])
ctx = uno.getComponentContext()
resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
remote = resolver.resolve("uno:pipe,name=agc52;urp;StarOffice.ComponentContext")
desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
doc = desktop.loadComponentFromURL(src, "_blank", 0, (prop("Hidden", True),))
sheets = doc.Sheets


def copy_row(sheet, src_row, dst_row, last_col):
    """Copy content and format of one row (0-based) onto another, then the caller overwrites the values."""
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
    """Cell text as an internal hyperlink to target_sheet!A1, like the other module names (kept on xlsx export)."""
    cell = sheet.getCellRangeByName(ref)
    cell.String = ""
    field = doc.createInstance("com.sun.star.text.TextField.URL")
    field.URL = "#" + target_sheet + ".A1"
    field.Representation = target_sheet
    text = cell.Text
    text.insertTextContent(text.createTextCursor(), field, False)


ROUND1_DATE = ROUND2_DATE = "27/11/2026"
TESTER = "Nguyen Minh Cuong"

# 1. GoldenFlow sheet: a copy of GatewayRouting (same header, formulas, comments), one scenario
sheets.copyByName("GatewayRouting", "GoldenFlow", sheets.Count)
gf = sheets.getByName("GoldenFlow")
# keep row 12 (request group) and row 13 (first case) as the format; empty the rows below without deleting them,
# so the sheet's COUNTIF ranges stay as long as in the other module sheets
gf.getCellRangeByName("A14:P80").clearContents(1023)
gf.getCellRangeByName("A14:P80").Rows.OptimalHeight = True
put(gf, "B2", "GoldenFlow")
put(gf, "A12", "Dựng cả stack compose từ commit + chạy golden flow (.github/workflows/e2e.yml)")
for ref, value in {
    "A13": "ITC001",
    "B13": "IT qua gateway (token Keycloak)",
    "C13": "Commit cần kiểm (Round 1: main 44202a49, Round 2: PR #678 e1d33257); docker compose -f docker-compose.yml "
           "-f e2e/compose.e2e.yml up -d --build; npm test trong backend/e2e",
    "D13": "Return: mọi image build được, stack healthy, golden flow pass (offer → ký 2 bên → ACTIVE → giao nhận → SETTLED)\n"
           "Exception: N/A\nLog message: N/A",
    "E13": "Normal",
    "F13": "Failed", "G13": ROUND1_DATE, "H13": TESTER,
    "I13": "Passed", "J13": ROUND2_DATE, "K13": TESTER,
    "L13": "", "M13": "", "N13": "",
    "O13": "#288",
    "P13": "e2e: backend/e2e/golden-flow.test.mjs; Round 1 run 45887466927 (Child module shared/java-arch-rules "
           "does not exist, DEF-17), Round 2 run 45888671217",
}.items():
    put(gf, ref, value)

gf.Rows.getByIndex(12).OptimalHeight = True  # fit the longer scenario text

# 2. Test Cases: row 14 = module 6
tc = sheets.getByName("Test Cases")
tc.Rows.insertByIndex(13, 1)
copy_row(tc, 12, 13, 6)
for ref, value in {
    "B14": 6, "C14": "e2e (cả stack)",
    "E14": "Cả stack: build mọi image từ commit, dựng compose, chạy golden flow qua gateway (BF-01 → BF-04 rút gọn)",
    "F14": "Stack build từ chính commit cần kiểm (.github/workflows/e2e.yml), secret sinh mới mỗi lần chạy; "
           "e2e/compose.e2e.yml rút ngắn thời gian chờ",
}.items():
    put(tc, ref, value)
link(tc, "D14", "GoldenFlow")
d5 = tc.getCellRangeByName("D5")
d5.String = d5.String + ("\n8. GoldenFlow: .github/workflows/e2e.yml build cả stack từ commit rồi chạy npm test trong "
                         "backend/e2e; Round 1/2 của module này là 2 lần chạy E2E của #288 (trước và sau sửa Dockerfile)")

# 3. Test Statistics: GoldenFlow row before Sub total, totals and the gateway kind row include it
st = sheets.getByName("Test Statistics")
st.Rows.insertByIndex(16, 1)  # new row 17, Sub total moves to 18, kind rows to 27/28; charts follow
copy_row(st, 15, 16, 8)
put(st, "A17", 6)
link(st, "B17", "GoldenFlow")
for col, ref in zip("CDEFGHI", ("B5", "C5", "D5", "B9", "C9", "D9", "B3")):
    put(st, f"{col}17", f"=$GoldenFlow.{ref}")  # UNO Formula takes LibreOffice syntax
    put(st, f"{col}18", f"=SUM({col}10:{col}17)")
    put(st, f"{col}28", f"={col}12+{col}13+{col}17")
b7 = st.getCellRangeByName("B7")
b7.String = b7.String + (" Thêm module GoldenFlow (#288): 1 kịch bản qua gateway, cả stack build từ commit; Round 1 "
                         "ngày 27/11/2026 trên main 44202a49 Failed (DEF-17, image Java thiếu module "
                         "shared/java-arch-rules), Round 2 cùng ngày trên PR #678 e1d33257 Passed.")

st.Rows.getByIndex(6).Height = 3100  # Notes grew by one sentence (1/100 mm)

# 4. Cover: one change-log row, version unchanged
cv = sheets.getByName("Cover")
copy_row(cv, 11, 12, 5)
for ref, value in {
    "A13": "27/11/2026", "B13": "v1.1", "C13": "GoldenFlow, Test Cases, Test Statistics", "D13": "A",
    "E13": "Thêm module GoldenFlow: build cả stack từ commit + golden flow qua gateway; Round 1 Failed (DEF-17: image "
           "Java thiếu module shared/java-arch-rules), Round 2 Passed sau khi sửa Dockerfile",
    "F13": "#288, PR #678",
}.items():
    put(cv, ref, value)

doc.calculateAll()
doc.storeToURL(out, (prop("FilterName", "Calc MS Excel 2007 XML"),))
doc.close(True)
print("wrote", sys.argv[2])
