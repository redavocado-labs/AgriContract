# Report 5.1 (#521): say what each round is, in place through Excel (COM).
#   Round 1  first run: 31/10/2026 for the modules merged by then, the merge date for later ones; the history the
#            10/11 report recorded, with no run log kept (except AnalyticsTopology UTC004, whose CI runs are DEF-01
#            of Report 5.6)
#   Round 2  confirmation: the 31/10 modules run again on 07/11/2026; history, no run log kept
#   Round 3  regression: every unit test of each service run again on today's code, each row read per test from
#            results/unit-round3*/ (update_51_traced.py and, for api-gateway/product/user, update_51_nhl.py)
# For Round 3 it lists what changed since Round 2, per service: commits on main between the last main commit of
# 07/11/2026 and the commit the service's Round 3 ran on, and how many test / other files they touched (git, not
# typed by hand). The text goes into Test Statistics B7 (replacing an earlier copy of it) and one row of the Cover's
# record of change.
#
#   python update_51_rounds.py ../../docs/reports/Report-5.1_Unit_Test.xlsx
import atexit
import glob
import json
import os
import re
import subprocess
import sys
import time

import pythoncom
import win32com.client

BOOK = os.path.abspath(sys.argv[1])
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ROUND2_END = "2026-11-07T23:59:59+07:00"
CHANGE_DATE = "27/11/2026"
MARKER = " Phân loại round:"
TEST_FILE = re.compile(r"(src/test/|\.spec\.ts$|/test/)")


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, check=True).stdout.strip()


base = git("rev-list", "-1", "--first-parent", f"--before={ROUND2_END}", "origin/main")
base_short = git("rev-parse", "--short=8", base)

# Round 3 run of every service: results/unit-round3*/run.json names its services and commit
runs = {}
for path in sorted(glob.glob(os.path.join(HERE, "results", "unit-round3*", "run.json"))):
    with open(path, encoding="utf-8") as f:
        run = json.load(f)
    for svc in run["services"]:
        svc = "shared" if svc.startswith("shared/") else svc
        runs[svc] = (run["commit"].split()[0], run["date"], os.path.basename(os.path.dirname(path)))

changes = []
for svc in sorted(runs):
    commit, day, folder = runs[svc]
    paths = [f"backend/{svc}"]
    count = int(git("rev-list", "--count", "--first-parent", f"{base}..{commit}", "--", *paths))
    files = [f for f in git("diff", "--name-only", base, commit, "--", *paths).splitlines() if f]
    tests = sum(1 for f in files if TEST_FILE.search(f))
    changes.append(f"{svc} (Round 3 ngày {day} trên {commit}, {folder}): {count} commit, {tests} file test, "
                   f"{len(files) - tests} file khác")

text = (MARKER + " Round 1 = lần chạy đầu (31/10/2026 với module cũ, ngày merge với module mới), lịch sử của báo cáo "
        "10/11, không lưu log chạy, trừ AnalyticsTopology UTC004 có CI run trong Report 5.6 DEF-01. Round 2 = "
        "confirmation: chạy lại ngày 07/11/2026 các module của 31/10, cũng là lịch sử, không lưu log. Round 3 = "
        "regression: chạy lại toàn bộ unit test của từng service trên code hiện tại, kết quả từng test lưu trong "
        "scripts/unit-test-report/results/unit-round3*/; dòng nào test đã bị xoá thì N/A. Thay đổi từ Round 2 (main "
        f"{base_short}, commit cuối ngày 07/11) tới Round 3, đếm bằng git trên backend/<service>: "
        + "; ".join(changes) + ".")


def busy(fn):
    for _ in range(300):
        try:
            return fn()
        except pythoncom.com_error as e:
            if e.hresult != -2147418111:  # RPC_E_CALL_REJECTED: Excel still busy
                raise
            time.sleep(0.2)
    return fn()


xl = win32com.client.DispatchEx("Excel.Application")
busy(lambda: setattr(xl, "Visible", False))
busy(lambda: setattr(xl, "DisplayAlerts", False))
wb = busy(lambda: xl.Workbooks.Open(BOOK))


@atexit.register
def close_excel():
    for fn in (lambda: wb.Close(False), xl.Quit):
        try:
            fn()
        except Exception:
            pass


st = busy(lambda: wb.Worksheets("Test Statistics"))
note = busy(lambda: st.Range("B7").Value) or ""
note = note.split(MARKER)[0]  # an earlier copy of this text is replaced, not repeated
busy(lambda: setattr(st.Range("B7"), "Value", note + text))

# Cover: one row in the record of change, styled like the first one, replaced on a re-run
cover = busy(lambda: wb.Worksheets("Cover"))
row = 11
while busy(lambda: cover.Cells(row, 1).Value):
    if "Phân loại round" in str(busy(lambda: cover.Cells(row, 5).Value) or ""):
        break
    row += 1
busy(lambda: cover.Rows(11).Copy(cover.Rows(row)))
version = busy(lambda: cover.Cells(row - 1, 2).Value) if row > 11 else "v1.0"
for col, value in enumerate(["'" + CHANGE_DATE, version, "Test Statistics, Round 3 các service", "M",
                             "Phân loại round: Round 1 lần chạy đầu, Round 2 confirmation, Round 3 regression; mỗi "
                             "dòng ghi test và đọc Round 3 từ lần chạy thật (không còn Passed mặc định); UTC004 "
                             "Failed → Passed theo DEF-01; thay đổi giữa Round 2 và Round 3 đếm bằng git",
                             "#521, Report 5.6"], start=1):
    busy(lambda: setattr(cover.Cells(row, col), "Value", value))

busy(lambda: xl.CalculateFull())
busy(lambda: wb.Save())
close_excel()
print(f"base {base_short}; record of change row {row}")
for c in changes:
    print(" ", c)
