# Rebuild Report 5.3 as system tests through the web client (Playwright on a real stack, tests/st/), after
# move_bruno_st_to_52.py moved the Bruno e2e sheets to Report 5.2. LibreOffice UNO keeps the charts and validation.
# Round 1 = the run of results/st-ui/results-chromium.json.
#
#   soffice --headless --invisible --norestore --accept="pipe,name=agc52;urp;" &
#   python3 fill_53_ui.py ../../docs/reports/Report-5.3_System_Test_FRs.xlsx /tmp/Report-5.3.xlsx
import json
import os
import sys

import uno
from com.sun.star.beans import PropertyValue
from com.sun.star.table import CellAddress, CellRangeAddress

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data_st_ui import CASES, PRE_STACK, SHEETS  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROUND_DATE = "28/11/2026"
TESTER = "Nguyen Minh Cuong"
OLD_SHEETS = ["BF01_Onboarding", "BF02_PlotListing", "BF03_Signing", "BF04_Milestone", "BF05_Breach",
              "BF06_Termination", "BF07_SecurityLock", "Security"]
WEEKEND = ("Not run: 28/11/2026 is a Saturday, so the milestone funding date (delivery minus 7 business days) cannot "
           "be today: the second signature is refused (D67) and the milestone is not funded at activation (D63); the "
           "test skips itself and runs again on a business day (30/11/2026)")


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


src, out = (uno.systemPathToFileUrl(os.path.abspath(p)) for p in sys.argv[1:3])
ctx = uno.getComponentContext()
resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
remote = resolver.resolve("uno:pipe,name=agc52;urp;StarOffice.ComponentContext")
desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
doc = desktop.loadComponentFromURL(src, "_blank", 0, (prop("Hidden", True),))
sheets = doc.Sheets


def copy_row(src_sheet, src_row, dst_sheet, dst_row, last_col):
    s, d = src_sheet.RangeAddress.Sheet, dst_sheet.RangeAddress.Sheet
    dst_sheet.copyRange(CellAddress(d, 0, dst_row), CellRangeAddress(s, 0, src_row, last_col, src_row))


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


# results of the run, by test-title prefix
results = {}
report = json.load(open(os.path.join(HERE, "results/st-ui/results-chromium.json")))


def walk(suite):
    for spec in suite.get("specs", []):
        for t in spec["tests"]:
            r = t["results"][-1]
            results[spec["title"].split(" ")[0]] = (r["status"], r["duration"], f'{spec["file"]}:{spec["line"]}')
    for child in suite.get("suites", []):
        walk(child)


for s in report["suites"]:
    walk(s)
started = report["stats"]["startTime"]

# 1. new module sheets from a copy of BF01_Onboarding (header, round formulas, validation); old sheets go at the end
template = sheets.getByName("BF01_Onboarding")
for name, *_ in SHEETS:
    sheets.copyByName("BF01_Onboarding", "new_" + name, sheets.Count)
    sh = sheets.getByName("new_" + name)
    sh.getCellRangeByName("A12:Q400").clearContents(1023)
    put(sh, "B2", name)

counts = {}
for name, *_ in SHEETS:
    sh = sheets.getByName("new_" + name)
    row, n = 12, 0
    for key, sheet, group, pre, steps, expected, kind, observed in CASES:
        if sheet != name:
            continue
        if group:
            copy_row(template, 11, sh, row - 1, 16)
            sh.getCellRangeByName(f"A{row}:Q{row}").clearContents(31)  # values, text, comments, formulas; keep the format
            put(sh, f"A{row}", group)
            row += 1
        copy_row(template, 12, sh, row - 1, 16)
        n += 1
        status, duration, where = results[key]
        status = {"passed": "Passed", "failed": "Failed", "skipped": "Pending"}[status]
        actual = (f"Round 1 (Chromium): {observed} ({duration / 1000:.1f} s)" if status == "Passed"
                  else f"Round 1: {WEEKEND}" if status == "Pending" else "Round 1: see the Playwright trace")
        values = {
            "A": f"STC{n:03d}", "B": f"{PRE_STACK}\n{pre}", "C": f"{key}: {steps}",
            "D": f"Return: {expected}\nException: N/A\nLog message: N/A", "E": kind,
            "F": status, "G": ROUND_DATE, "H": TESTER,
            "I": "", "J": "", "K": "", "L": "", "M": "", "N": "", "O": "",
            "P": f"Playwright: tests/st/{where}", "Q": actual,
        }
        for col, value in values.items():
            put(sh, f"{col}{row}", value)
        sh.Rows.getByIndex(row - 1).OptimalHeight = True
        row += 1
    counts[name] = n

for old in OLD_SHEETS:
    sheets.removeByName(old)
for name, *_ in SHEETS:
    sheets.getByName("new_" + name).Name = name

# 2. Test Cases: 5 modules in rows 9..13
tc = sheets.getByName("Test Cases")
tc.Rows.removeByIndex(8 + len(SHEETS), len(OLD_SHEETS) - len(SHEETS))
for i, (name, module, description, precondition) in enumerate(SHEETS):
    r = 9 + i
    put(tc, f"B{r}", i + 1)
    put(tc, f"C{r}", module)
    link(tc, f"D{r}", name)
    put(tc, f"E{r}", description)
    put(tc, f"F{r}", precondition)
    tc.Rows.getByIndex(r - 1).OptimalHeight = True
put(tc, "D5", "\n".join([
    "1. Stack: Docker Compose project agricontract-bruno of backend/ (docker-compose.yml + docker-compose.override.yml "
    "+ an overlay that shortens the geo check), gateway 8080, Keycloak 8180, MailHog 8025; nothing mocked",
    "2. Web client: a production build of frontend/ (VITE_API_BASE_URL=http://localhost:8080 VITE_USE_MOCKS=false "
    "vite build) served by vite preview on port 5173 (the dev server reloads the page when Playwright writes files)",
    "3. Playwright: cd tests/st && npm ci && npx playwright test --project=chromium "
    "(Chromium 1366x900, vi-VN, Asia/Ho_Chi_Minh, 1 worker); the cases of one flow run in order and share its state",
    "4. Keycloak accounts (Report 5.0 §II.1.1): buyer1, seller1, operator1, arbitrator1, admin1; BF-01 registers new "
    "accounts on every run; the e-mail verification code and the signing OTP are read from MailHog",
    "5. Steps without a screen yet (Operator KYC decision, seller category/product/plot/listing) call the API and say "
    "so in Input parameters; every other step goes through the screens as a user does",
    "6. Results: Playwright's JSON reporter in scripts/unit-test-report/results/st-ui/results-chromium.json; "
    "Note gives the test's file:line; Actual Results gives what was observed and the run time",
    "7. The Bruno e2e suite (API through the gateway) that this report held before moved to Report 5.2 (D95: it is "
    "integration testing)",
]))
tc.Rows.getByIndex(4).OptimalHeight = True

# 3. Test Statistics: 5 module rows (12..16), counted on Round 1
st = sheets.getByName("Test Statistics")
st.Rows.removeByIndex(11 + len(SHEETS), len(OLD_SHEETS) - len(SHEETS))
for i, (name, *_) in enumerate(SHEETS):
    r = 12 + i
    put(st, f"A{r}", i + 1)
    link(st, f"B{r}", name)
    for col, ref in zip("CDEFGHI", ("B5", "C5", "D5", "B9", "C9", "D9", "B3")):
        put(st, f"{col}{r}", f"=${name}.{ref}")
total = sum(counts.values())
passed = sum(1 for k, *_ in CASES if results[k][0] == "passed")
pending = sum(1 for k, *_ in CASES if results[k][0] == "skipped")
failed = total - passed - pending
put(st, "B7", (
    f"System tests through the web client (STC) in 5 sheets: BF-01..BF-04 and the role screens, {total} cases, each "
    f"one Playwright test in tests/st/ on a real stack (nothing mocked). Round 1 on {ROUND_DATE}, tester {TESTER}, "
    f"Chromium: {passed} Passed, {failed} Failed, {pending} Pending. The {pending} Pending cases are the second "
    "signature → ACTIVE → BF-04 part of the main chain: 28/11/2026 is a Saturday, so no milestone funding date is "
    "valid (D63, D67) and the tests skip themselves; the run of 30/11/2026 becomes Round 2. BF-05..BF-07 have no "
    "screens yet, so no system test through the web client; their API side is in Report 5.2. Browser (Firefox) and "
    "usability checks are in Report 5.4. Log: scripts/unit-test-report/results/st-ui/."))
st.Rows.getByIndex(6).OptimalHeight = True
put(st, "F6", ROUND_DATE)

# 4. Cover: change log (the rows of the Bruno version translated, then this version)
cv = sheets.getByName("Cover")
put(cv, "C11", "Whole document")
put(cv, "E11", "First version: 128 system test cases (STC) of BF-01..BF-07 and the defence suite, one row per step of "
                "the flow (Bruno requests in backend/bruno/e2e, backend/bruno/security), plus the steps of status.tsv "
                "that cannot run yet and a DLQ check after each flow; results from run-e2e-full.sh, Round 1 "
                "run-20261127-1603, Round 2 run-20261127-1630")
put(cv, "C12", "Round 3 of every sheet")
put(cv, "E12", "Round 3: run run-20261128-0350 of run-e2e-full.sh on 42a48dd5, 128 rows: 46 Passed, 21 Failed, "
                "53 Pending, 8 N/A")
last = 12  # row 12 is the latest entry
copy_row(cv, last - 1, cv, last, 5)
for ref, value in {
    f"A{last + 1}": ROUND_DATE, f"B{last + 1}": "v1.0",
    f"C{last + 1}": "Every module sheet, Test Cases, Test Statistics", f"D{last + 1}": "M",
    f"E{last + 1}": (f"System test redone through the web client: {total} Playwright cases on a real stack (BF-01..BF-04, "
                     f"role screens), Round 1 {passed} Passed, {pending} Pending (Saturday). The 8 Bruno e2e sheets "
                     "(API calls) moved to Report 5.2 as integration tests (D95)"),
    f"F{last + 1}": "D95, Report 5.2, tests/st/",
}.items():
    put(cv, ref, value)
cv.Rows.getByIndex(last).OptimalHeight = True

doc.calculateAll()
doc.storeToURL(out, (prop("FilterName", "Calc MS Excel 2007 XML"),))
doc.close(True)
print("wrote", sys.argv[2], counts, f"passed={passed} pending={pending} failed={failed}", "run", started)
