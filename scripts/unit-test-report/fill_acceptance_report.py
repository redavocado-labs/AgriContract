# Fill the Report 5.1 template (template/Report-5.1_Unit_Test_template.xlsx) with the UI and acceptance test cases
# of data_acceptance.py and write Report 5.4 (#677). Same steps as fill_bruno_report.py (one copy of ModuleName1 per
# role sheet, Test Cases list, Test Statistics), driven through Excel (COM) so charts, comments and validation
# survive; it needs Windows with Excel and pywin32. Every Excel call goes through busy(), as in
# fill_system_test_report.py, so a busy Excel ("call was rejected by callee") is waited for, not fatal.
#
# Rounds (results/at-round<N>/, read by playwright_results.py; nothing is typed by hand):
#   Round 1  CI job frontend of run 45484721149 (PR #643, 0088280c): FE-016 reflow and FE-003 reload failed (DEF-09)
#   Round 2  CI job frontend of run 45488633271 (main f233904e): DEF-09 confirmed fixed, FE-015 retry failed (DEF-10)
#   Round 3  local run of the whole suite on 94d46500 after both fixes (confirmation + regression), and the
#            acceptance round of the manual rows (Pending until a tester runs them on the demo stack)
#
# Actual Results (column Q, mentor review 27/11): per round, the Playwright error of a failed test, or the expected
# outcome for a passed one (Playwright passes only when every assertion of that outcome held); a manual row says it
# has not been checked yet.
#
#   python fill_acceptance_report.py template/Report-5.1_Unit_Test_template.xlsx \
#       ../../docs/reports/Report-5.4_Acceptance_Test.xlsx
import atexit
import os
import shutil
import sys
import tempfile
import time

import pythoncom
import win32com.client

from data_acceptance import AUTO, DEFECTS, KIND_LABEL, MANUAL, MODULES
from playwright_results import load_round

CREATOR = "Nguyen Hai Long"
ISSUE_DATE = "27/11/2026"
PROJECT_CODE = "AGC"
SRC, OUT = (os.path.abspath(p) for p in sys.argv[1:3])
RESULTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
ROUNDS = [load_round(os.path.join(RESULTS_DIR, f"at-round{i}")) for i in (1, 2, 3)]
COUNTS = [{} for _ in ROUNDS]  # per round: result -> number of cases

titles = [c[1] for m in MODULES for _, cases in m["groups"] for c in cases if c[0] == AUTO]
assert len(titles) == len(set(titles)), "a Playwright test is listed twice"
for r in ROUNDS:
    assert set(titles) == set(r["results"]), f"{r['round']}: Playwright tests and data_acceptance.py differ: " \
        f"{sorted(set(titles) ^ set(r['results']))}"
assert len({m["sheet"] for m in MODULES}) == len(MODULES), "duplicate sheet name"

RPC_E_CALL_REJECTED = -2147418111


def busy(fn):
    """Run one Excel call; while Excel answers "call was rejected by callee" (still busy), wait and retry."""
    for _ in range(300):
        try:
            return fn()
        except pythoncom.com_error as e:
            if e.hresult != RPC_E_CALL_REJECTED:
                raise
            time.sleep(0.2)
    return fn()


xl = win32com.client.DispatchEx("Excel.Application")
busy(lambda: setattr(xl, "Visible", False))
busy(lambda: setattr(xl, "DisplayAlerts", False))
# open a private copy: an Excel left over from a crashed run may still hold the template, and a second Excel then
# waits on a hidden "file in use" dialog
WORK = os.path.join(tempfile.mkdtemp(prefix="report54-"), os.path.basename(SRC))
shutil.copyfile(SRC, WORK)
wb = busy(lambda: xl.Workbooks.Open(WORK))


@atexit.register
def close_excel():
    # also on an exception, so no hidden Excel stays behind
    for fn in (lambda: wb.Close(False), xl.Quit):
        try:
            fn()
        except Exception:
            pass


busy(lambda: setattr(xl, "ScreenUpdating", False))
busy(lambda: setattr(xl, "Calculation", -4135))  # xlCalculationManual; CalculateFull runs once before saving


def same_font(rng):
    busy(lambda: setattr(rng.Font, "Name", "Tahoma"))
    busy(lambda: setattr(rng.Font, "Size", 10))
    busy(lambda: setattr(rng.Font, "Italic", False))


def copy_row(sh, src_row, dst_row):
    busy(lambda: sh.Rows(src_row).Copy(sh.Rows(dst_row)))


def put(rng, value):
    busy(lambda: setattr(rng, "Value", value))


def formula(rng, text):
    busy(lambda: setattr(rng, "Formula", text))


def cell(sh, ref):
    return busy(lambda: sh.Range(ref))


def link_to_sheet(sh, c, sheet_name):
    busy(lambda: c.Hyperlinks.Delete())
    put(c, sheet_name)
    busy(lambda: sh.Hyperlinks.Add(Anchor=c, Address="", SubAddress=f"'{sheet_name}'!A1", TextToDisplay=sheet_name))
    same_font(c)


def set_widths(sh, widths_mm):
    for col, w in widths_mm.items():
        busy(lambda: setattr(sh.Columns(col), "ColumnWidth", round(w / 1.9, 1)))


def fit_rows(sh, first_row, last_row, last_col, skip=()):
    for r in range(first_row, last_row + 1):
        if r in skip:
            continue
        busy(lambda: setattr(sh.Range(sh.Cells(r, 1), sh.Cells(r, last_col)), "WrapText", True))
        busy(lambda: sh.Rows(r).AutoFit())
        height = busy(lambda: sh.Rows(r).RowHeight)
        busy(lambda: setattr(sh.Rows(r), "RowHeight", min(409, height * 1.08 + 2)))


def fit_page_width(sh):
    busy(lambda: setattr(sh.PageSetup, "Zoom", False))
    busy(lambda: setattr(sh.PageSetup, "FitToPagesWide", 1))
    busy(lambda: setattr(sh.PageSetup, "FitToPagesTall", False))


def round_cells(kind, title, i):
    """(result, date, tester) of one row in round i."""
    rnd = ROUNDS[i]
    if kind == AUTO:
        result = rnd["results"][title]
    else:
        # manual checks are not part of the automated CI rounds; Round 3 is also the acceptance round
        result = "Pending" if i == len(ROUNDS) - 1 else "N/A"
    COUNTS[i][result] = COUNTS[i].get(result, 0) + 1
    tested = result in ("Passed", "Failed")
    return [result, "'" + rnd["date"] if tested else "", rnd["tester"] if tested else ""]


def actual(kind, title, expected):
    """Actual Results of one row: one line per round, or one line when every round saw the same"""
    seen = []
    for i, rnd in enumerate(ROUNDS):
        if kind == MANUAL:
            seen.append("chưa kiểm, chờ tester trên demo stack" if i == len(ROUNDS) - 1
                        else "N/A, không thuộc lần chạy CI tự động")
        elif rnd["results"][title] == "Failed":
            seen.append("Failed: " + rnd["errors"][title])
        elif rnd["results"][title] == "Passed":
            seen.append("đúng như mong đợi: " + expected)
        else:
            seen.append("không chạy")
    if len(set(seen)) == 1:
        return f"Round 1-{len(ROUNDS)}: {seen[0]}"
    # rounds that saw the same are written together, e.g. "Round 2-3: ..."
    lines, start = [], 0
    for i in range(1, len(seen) + 1):
        if i == len(seen) or seen[i] != seen[start]:
            label = f"Round {start + 1}" if i - start == 1 else f"Round {start + 1}-{i}"
            lines.append(f"{label}: {seen[start]}")
            start = i
    return "\n".join(lines)


def summary(i):
    return ", ".join(f"{COUNTS[i][k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if COUNTS[i].get(k))


# ── Module sheets: one copy of ModuleName1 per role ─────────────────────────
STYLE_HEADER, STYLE_CASE = 1100, 1101
tpl = busy(lambda: wb.Worksheets("ModuleName1"))
n_auto = n_manual = 0
for m in MODULES:
    last = busy(lambda: wb.Worksheets(wb.Worksheets.Count))
    busy(lambda: tpl.Copy(None, last))
    sh = busy(lambda: wb.Worksheets(wb.Worksheets.Count))
    assert sh.Name != "ModuleName1" and sh.Name != last.Name, "sheet copy went to the wrong place"
    busy(lambda: setattr(sh, "Name", m["sheet"]))
    # Actual Results: the template has no such column, so Q takes the look of Note (P) after it; A..P keep their
    # place so the template's COUNTIF ranges and dropdowns stay valid
    for r in (11, 12, 13):
        busy(lambda: sh.Range(f"P{r}").Copy(sh.Range(f"Q{r}")))
    copy_row(sh, 12, STYLE_HEADER)
    copy_row(sh, 13, STYLE_CASE)
    busy(lambda: sh.Range("A12:Q1003").ClearContents())
    put(cell(sh, "Q11"), "Actual Results")
    put(cell(sh, "B2"), m["sheet"])
    formula(cell(sh, "B3"), '=COUNTIF(A13:A1005,"ATC*")')
    formula(cell(sh, "D9"), "=COUNTIF($E12:$E1003,D8)")
    put(cell(sh, "T5"), "Failed")

    row, n, header_rows = 12, 0, set()
    for group, cases in m["groups"]:
        copy_row(sh, STYLE_HEADER, row)
        put(cell(sh, f"A{row}"), group)
        header_rows.add(row)
        row += 1
        for kind, title, inp, ret, typ in cases:
            n += 1
            n_auto += kind == AUTO
            n_manual += kind == MANUAL
            copy_row(sh, STYLE_CASE, row)
            rounds = [c for i in range(len(ROUNDS)) for c in round_cells(kind, title, i)]
            failed = any(r["results"].get(title) == "Failed" for r in ROUNDS) if kind == AUTO else False
            defect = DEFECTS.get(title, "") if failed else ""
            assert not failed or defect, f"failed row without a Report 5.5 defect: {title}"
            if kind == AUTO:
                history = " → ".join(r["results"][title] for r in ROUNDS)
                note = f"Playwright: {title}" + (f"\nRound 1 → 3: {history}" if failed else "")
            else:
                note = "Kiểm tay; Round 1-2 là lần chạy CI tự động nên N/A; chờ tester chạy trên demo stack"
            values = [f"ATC{n:03d}", KIND_LABEL[kind] + "\n" + m["pre"], inp,
                      f"Return: {ret}\nException: N/A\nLog message: N/A", typ, *rounds, defect, note,
                      actual(kind, title, ret)]
            rng = busy(lambda: sh.Range(sh.Cells(row, 1), sh.Cells(row, 17)))
            put(rng, [values])
            same_font(rng)
            for col in (6, 9, 12):
                c = busy(lambda: sh.Cells(row, col))
                busy(lambda: c.Validation.Delete())
                busy(lambda: c.Validation.Add(3, 1, 1, "=$T$2:$T$5"))
            row += 1
    busy(lambda: sh.Rows(f"{STYLE_HEADER}:{STYLE_CASE}").Delete())
    set_widths(sh, {1: 32, 2: 50, 3: 55, 4: 90, 5: 20, 6: 18, 7: 22, 8: 34, 9: 18, 10: 22, 11: 34,
                    12: 18, 13: 22, 14: 34, 15: 20, 16: 70, 17: 90})
    fit_rows(sh, 13, row - 1, 17, skip=header_rows)

busy(lambda: tpl.Delete())
cases = n_auto + n_manual


def round_line(i, label):
    r = ROUNDS[i]
    return (f"{label} ngày {r['date']} trên commit {r['commit']}, tester {r['tester']}: {summary(i)}. "
            f"Nguồn: {r['source']}. Log: scripts/unit-test-report/results/{r['round']}/{r['log']}.")


# ── Cover ────────────────────────────────────────────────────────────────────
cover = busy(lambda: wb.Worksheets("Cover"))
put(cell(cover, "B2"), "ACCEPTANCE TEST DOCUMENT")
put(cell(cover, "B4"), "AgriContract")
put(cell(cover, "B5"), PROJECT_CODE)
formula(cell(cover, "B6"), '=B5&"_AcceptanceTest_v1.0"')
put(cell(cover, "F4"), CREATOR)
put(cell(cover, "F5"), "'" + ISSUE_DATE)
put(cell(cover, "F6"), "v1.0")
for ref, text in zip(["A11", "B11", "C11", "D11", "E11", "F11"],
                     ["'" + ISSUE_DATE, "v1.0", "Toàn bộ tài liệu", "A",
                      f"Bản đầu: {cases} test case UI và nghiệm thu (ATC) theo vai trò, 1 dòng = 1 kiểm tra nhìn thấy "
                      f"trên 1 màn hình; {n_auto} dòng tự động lấy kết quả từ Playwright (frontend/tests/qa), "
                      f"{n_manual} dòng kiểm tay trên demo stack. Round 1-2 là 2 lần chạy CI (DEF-09, DEF-10 của "
                      "Report 5.5), Round 3 chạy lại toàn bộ sau khi sửa; cột Actual Results (Q) ghi kết quả "
                      "thực tế từng round",
                      "SRS v1.7 §I.5.1, Report 5.0, Report 5.5"]):
    put(cell(cover, ref), text)

# ── Test Cases ───────────────────────────────────────────────────────────────
tc = busy(lambda: wb.Worksheets("Test Cases"))
formula(cell(tc, "D3"), "=Cover!B4")
formula(cell(tc, "D4"), "=Cover!B5")
put(cell(tc, "D5"), (
    "1. Tự động: cd frontend && npm ci && npx playwright install chromium --only-shell && npm run test:e2e "
    "(Playwright 1.64, Chromium; Vite chạy loopback; API giả lập trong browser, không cần backend); CI chạy cùng lệnh "
    "ở job frontend của .github/workflows/ci.yml\n"
    "2. Round 3 local thêm --reporter=list,json, file JSON ở scripts/unit-test-report/results/at-round3/; Round 1-2 "
    "lấy từ log job frontend của GitHub Actions (results/at-round1, at-round2)\n"
    "3. Kiểm tay: demo stack docker compose (backend/docker-compose.yml), gateway 8080, Keycloak 8180, MailHog 8025 "
    "cho OTP; trình duyệt Chrome/Firefox bản mới\n"
    "4. Tài khoản Keycloak (Report 5.0 §II.1.1): buyer1, seller1, operator1, arbitrator1, admin1 (pass123); "
    "seed backend/scripts/seed/\n"
    "5. 1 dòng = 1 kiểm tra nhìn thấy trên 1 màn hình (số màn hình theo SRS §I.5.1 b); chỉ liệt kê màn hình đã có "
    "route trong frontend/src/App.tsx; Inspector chưa có màn hình nên chưa có sheet\n"
    "6. Defect IDs ghi defect của Report 5.5 gây ra case Failed; Note ghi tên test Playwright\n"
    "7. Actual Results (cột Q, thêm theo góp ý mentor 27/11): lỗi Playwright in ra khi test Failed, kết quả mong đợi "
    "khi test Passed (mọi assertion đều đạt), dòng kiểm tay ghi chưa kiểm"))
for i, m in enumerate(MODULES):
    r = 9 + i
    if r > 10:
        copy_row(tc, 10, r)
    put(busy(lambda: tc.Cells(r, 2)), i + 1)
    for col, text in [(3, m["module"]), (5, m["desc"]), (6, m["pre"])]:
        c = busy(lambda: tc.Cells(r, col))
        put(c, text)
        same_font(c)
    link_to_sheet(tc, busy(lambda: tc.Cells(r, 4)), m["sheet"])
set_widths(tc, {2: 12, 3: 30, 4: 40, 5: 95, 6: 80})
fit_rows(tc, 9, 8 + len(MODULES), 6)
busy(lambda: setattr(cell(tc, "D5"), "WrapText", True))
busy(lambda: setattr(tc.Rows(5), "RowHeight", 11 * 14.5))
fit_page_width(tc)

# ── Test Statistics ──────────────────────────────────────────────────────────
st = busy(lambda: wb.Worksheets("Test Statistics"))
put(cell(st, "A2"), "ACCEPTANCE TEST STATISTICS")
for _ in range(max(0, len(MODULES) - 4)):  # template has data rows 12..15 inside SUM(…10:…15)
    busy(lambda: st.Rows(15).Insert())
formula(cell(st, "B6"), "=Cover!B6")
put(cell(st, "F4"), CREATOR)
put(cell(st, "F6"), "'" + ISSUE_DATE)
put(cell(st, "B7"), (
    f"Test UI và nghiệm thu (ATC) của web client theo {len(MODULES)} vai trò. {cases} kiểm tra: {n_auto} tự động "
    f"(Playwright, frontend/tests/qa), {n_manual} kiểm tay trên demo stack. "
    + round_line(0, "Round 1") + " " + round_line(1, "Round 2") + " "
    + round_line(2, "Round 3 (sau khi sửa DEF-09, DEF-10; chạy lại toàn bộ)")
    + " Bảng dưới đếm theo Round 3."))
for i, m in enumerate(MODULES):
    r = 12 + i
    if r > 12:
        copy_row(st, 12, r)
    put(busy(lambda: st.Cells(r, 1)), i + 1)
    link_to_sheet(st, busy(lambda: st.Cells(r, 2)), m["sheet"])
    # Round 3 row (7) as in the template: the latest whole round
    for col, ref in zip(range(3, 10), ["B7", "C7", "D7", "B9", "C9", "D9", "B3"]):
        formula(busy(lambda: st.Cells(r, col)), f"='{m['sheet']}'!{ref}")
busy(lambda: setattr(cell(st, "B7"), "WrapText", True))
busy(lambda: setattr(st.Rows(7), "RowHeight", 8 * 14.5))
busy(lambda: setattr(st.Columns(2), "ColumnWidth", round(60 / 1.9, 1)))
fit_page_width(st)

busy(lambda: wb.Worksheets("Cover").Activate())
busy(lambda: xl.CalculateFull())
if os.path.exists(OUT):
    os.remove(OUT)
busy(lambda: wb.SaveAs(OUT, 51))
close_excel()
print("wrote", OUT, f"({cases} cases: {n_auto} Playwright, {n_manual} manual)")
for i in range(len(ROUNDS)):
    print(f"  Round {i + 1}: {summary(i)}")
