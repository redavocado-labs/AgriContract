# Fill the Report 5.1 template (template/Report-5.1_Unit_Test_template.xlsx) with the system test cases (STC) of
# the business flows BF-01..BF-07 and the security suite, and write Report 5.3 (#676). Same steps as
# fill_bruno_report.py (one copy of ModuleName1 per sheet, Test Cases list, Test Statistics), driven through Excel
# (COM) so the Cover logo, the statistics charts, comments and validation survive; it needs Windows with Excel and
# pywin32.
#
# Nothing is typed by hand:
#   cases     one row per Bruno request of backend/bruno/e2e and backend/bruno/security (name, docs, request and
#             expected status read from the .bru file), in the order run-e2e-full.sh runs them; plus the
#             not-yet-runnable steps of backend/scripts/e2e/status.tsv and one dead-letter-queue check per flow
#   results   each round is a run of backend/scripts/run-e2e-full.sh imported by import_e2e_round.py into
#             results/st-round<N>/; Passed/Failed/Pending are decided by backend/scripts/e2e/report.py, the same code
#             that wrote the runner's TESTING-ROUNDS.md
#   defects   a Failed row must map to a defect of Report 5.5 (DEFECTS below), else the script stops
#
#   python fill_system_test_report.py template/Report-5.1_Unit_Test_template.xlsx \
#       ../../docs/reports/Report-5.3_System_Test.xlsx
import atexit
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
import time
from pathlib import Path

import pythoncom
import win32com.client

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BRUNO = REPO / "backend" / "bruno"
spec = importlib.util.spec_from_file_location("e2e_report", REPO / "backend" / "scripts" / "e2e" / "report.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)

CREATOR = "Nguyen Hai Long"
ISSUE_DATE = "27/11/2026"
PROJECT_CODE = "AGC"
SRC, OUT = (os.path.abspath(p) for p in sys.argv[1:3])

# flow, sheet, folder (as run-e2e-full.sh runs it), description, pre-condition of the whole sheet
FLOWS = [
    ("BF01", "BF01_Onboarding", "e2e/bf01-onboarding-kyc", "BF-01 User Onboarding & KYC: đăng ký, xác minh email "
     "qua MailHog, Operator duyệt hoặc từ chối KYC", "Stack vừa dựng (--fresh), seed 01/02; tài khoản Keycloak mới mỗi lần chạy"),
    ("BF02", "BF02_PlotListing", "e2e/bf02-plot-listing", "BF-02 Plot & Listing Publication: category cà phê, "
     "product, lô đất, listing", "seller1 VERIFIED (seed); product-service rút ngắn kiểm tra geo"),
    ("BF03", "BF03_Signing", "e2e/bf03-signing-activation", "BF-03 Contract Signing & Activation: offer, giữ hàng, "
     "ký OTP 2 bên, khoá cọc, ACTIVE; rút offer", "buyer1/seller1 có tài khoản ngân hàng mock; listing của BF02-070"),
    ("BF04", "BF04_Milestone", "e2e/bf04-milestone-delivery-settlement", "BF-04 Milestone Delivery & Settlement: "
     "cấp tiền, cân, nhận, xác nhận hoặc tranh chấp, giám định Level 1.5, quyết toán, audit",
     "Hợp đồng ACTIVE của BF03-080; inspector1, arbitrator1"),
    ("BF05", "BF05_Breach", "e2e/bf05-breach-force-majeure", "BF-05 Breach & Force Majeure: phần đã có controller",
     "arbitrator1"),
    ("BF06", "BF06_Termination", "e2e/bf06-mutual-termination-replacement", "BF-06 Mutual Termination & "
     "Replacement: chưa có controller (#242, #243)", "N/A"),
    ("BF07", "BF07_SecurityLock", "e2e/bf07-emergency-security-lock", "BF-07 Emergency Security Lock: trạng thái "
     "khoá qua reputation", "Tài khoản fixture"),
    ("Security", "Security", "security", "Phòng thủ: xác thực, ma trận quyền đọc, retry idempotent, audit đọc, "
     "kiểm tra đầu vào, lộ thông tin (data-governance §5.4, hash-chain §4.6)", "buyer1, seller1, operator1, "
     "inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"),
]
SUBFOLDER_ORDER = {"happy": 0, "unhappy": 1, "clean": 0, "completion": 1, "dispute": 2}
KIND_LABEL = {"IT-GW": "ST qua gateway (token Keycloak)",
              "IT-DIRECT": "ST đọc thẳng service / broker / DB để kiểm kết quả"}
BOUNDARY = {  # the input sits on a limit (Report 5.0 §I.2.1)
    "BF04-250": "auto-confirm đúng 14 ngày sau khi nhận (D78)",
    "SEC-120": "khoá tạm sau đúng 5 lần sai mật khẩu",
    "SEC-180": "page = -1 (min - 1)",
}
# Failed row -> defect of Report 5.5 (data_defects.py)
DEFECTS = {"gateway-empty-denial": "DEF-15 (#663)",
           # Round 1 on Windows: the time-travel helper could not start (spawn EFTYPE), fixed in PR #686
           "BF04-250": "DEF-18 (#685)"}

ROUND_DIRS = sorted((p for p in (HERE / "results").glob("st-round*") if (p / "run.json").exists()),
                    key=lambda p: int(re.sub(r"\D", "", p.name)))
assert ROUND_DIRS, "no results/st-round<N>/run.json: import a run with import_e2e_round.py first"
assert len(ROUND_DIRS) <= 3, "the template has three rounds"
ROUNDS = [dict(json.loads((p / "run.json").read_text(encoding="utf-8")), dir=p) for p in ROUND_DIRS]
COUNTS = [{} for _ in ROUNDS]


def parse_bru(path):
    text = path.read_text(encoding="utf-8")
    method, url = re.search(r"^(get|post|put|patch|delete) \{\n  url: (\S+)", text, re.M).groups()
    token = re.search(r"^  token: \{\{(\w+?)_token\}\}", text, re.M)
    statuses = re.findall(r"getStatus\(\)\)\.to\.equal\((\d+)\)", text)
    tests = re.findall(r'^  test\("([^"]+)"', text, re.M)
    # title and spec reference again here: report.py reads the file in the platform encoding (cp1252 on Windows)
    title = re.search(r"^  name: \S+ (.+)$", text, re.M).group(1)
    reference = re.search(r"^docs \{\n  (?:IT-GW|IT-DIRECT)\n\n  (.+)$", text, re.M).group(1)
    return dict(method=method.upper(), url=url, actor=token.group(1) if token else None,
                status=statuses[0] if statuses else None, tests=tests, title=title, reference=reference)


def case_rows(flow, folder):
    """Bruno cases of one suite, grouped by sub-folder in run order: [(group, [case, ...])]."""
    cases = e2e.parse_cases(BRUNO, folder)
    groups = {}
    for case_id, (title, kind, reference, location) in cases.items():
        rel = location.relative_to(BRUNO / folder)
        group = rel.parts[0] if len(rel.parts) > 1 else ""
        seq = int(re.search(r"^  seq: (\d+)", location.read_text(encoding="utf-8"), re.M).group(1))
        groups.setdefault(group, []).append((seq, case_id, title, kind, reference, location))
    order = sorted(groups, key=lambda g: (SUBFOLDER_ORDER.get(g, 9), g))
    return [(g, [c[1:] for c in sorted(groups[g])]) for g in order]


def bruno_result(rnd, flow, case_id):
    """(result, defect, note) of one Bruno case in one round, decided as report.py decides it."""
    results = e2e.load_results(rnd["dir"] / f"bruno-{flow.lower()}.json")
    result = results.get(case_id)
    status = e2e.outcome(result)
    if case_id == "BF04-390" and status == "Passed" and result["response"]["status"] == 404:
        return "Pending", "", "Gateway chưa có route audit cho các bên (#630): 404"
    if status == "Failed":
        if e2e.empty_auth_denial(result):
            return status, DEFECTS["gateway-empty-denial"], "401/403 body rỗng thay vì ErrorEnvelope"
        checks = (result or {}).get("testResults") or []
        error = (result or {}).get("error") or next((c.get("error") for c in checks if c["status"] == "fail"), "")
        return status, DEFECTS.get(case_id), str(error)[:300]
    if status == "Pending":
        return status, "", "Không có kết quả: request trước đó dừng flow (--bail)"
    return status, "", ""


def body_summary(data):
    """short text of a response body: the ErrorEnvelope code and message, else the start of the JSON"""
    if data in (None, ""):
        return "body rỗng"
    if isinstance(data, dict) and "code" in data:
        return f"{data['code']}: {data.get('message', '')}".strip()
    if isinstance(data, list):
        return f"mảng {len(data)} phần tử"
    text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    return text if len(text) <= 160 else text[:157] + "..."


def observed(rnd, flow, case_id):
    """what the system returned for one Bruno case in one round (Actual Results column)"""
    result = e2e.load_results(rnd["dir"] / f"bruno-{flow.lower()}.json").get(case_id)
    if not result or result.get("skipped"):
        return "không chạy (request trước đó dừng flow)"
    response = result.get("response") or {}
    checks = result.get("testResults") or []
    error = result.get("error") or next((c.get("error") for c in checks if c["status"] == "fail"), "")
    status = response.get("status")
    text = (f"HTTP {status}; " if isinstance(status, int) else "") + (
        body_summary(response.get("data")) if isinstance(status, int) else "không có response")
    return text + (f"; lỗi: {str(error)[:200]}" if error else "")


def actual_text(per_round):
    """one line per round, or one line when every round saw the same"""
    if len(set(per_round)) == 1:
        label = "Round 1" if len(per_round) == 1 else f"Round 1-{len(per_round)}"
        return f"{label}: {per_round[0]}"
    return "\n".join(f"Round {i + 1}: {text}" for i, text in enumerate(per_round))


def dlq_observed(rnd, flow):
    snapshot = rnd["dir"] / f"queues-{flow.lower()}.json"
    if not snapshot.exists():
        return "không có snapshot queue"
    dlqs = [q for q in json.loads(snapshot.read_text(encoding="utf-8")) if q["name"].endswith(".dlq")]
    dirty = [f"{q['name']} = {q.get('messages')}" for q in dlqs if q.get("messages") != 0]
    return f"{len(dlqs)} queue .dlq, " + ("có message: " + ", ".join(dirty) if dirty else "tất cả 0 message")


def dlq_result(rnd, flow):
    snapshot = rnd["dir"] / f"queues-{flow.lower()}.json"
    if not snapshot.exists():
        return "Failed", None, "Không chụp được queue"
    queues = json.loads(snapshot.read_text(encoding="utf-8"))
    dlqs = [q for q in queues if q["name"].endswith(".dlq")]
    dirty = [q["name"] for q in dlqs if q.get("messages") != 0]
    if dlqs and not dirty:
        return "Passed", "", ""
    return "Failed", None, "DLQ có message: " + ", ".join(dirty)


def busy(fn):
    """Run one Excel call; while Excel answers "call was rejected by callee" (still busy), wait and retry."""
    for _ in range(300):
        try:
            return fn()
        except pythoncom.com_error as e:
            if e.hresult != -2147418111:  # RPC_E_CALL_REJECTED
                raise
            time.sleep(0.2)
    return fn()


# ── build the rows first, so a missing defect stops the script before Excel starts ─────────────────────────
MODULES = []
STATUS_ROWS = [line.split("\t") for line in (REPO / "backend/scripts/e2e/status.tsv").read_text(
    encoding="utf-8").splitlines() if line]
unmapped = []
for flow, sheet, folder, desc, pre in FLOWS:
    groups = []
    for group, cases in case_rows(flow, folder):
        rows, previous = [], None
        for case_id, title, kind, reference, location in cases:
            bru = parse_bru(location)
            title, reference = bru["title"], bru["reference"]
            rounds, defects, notes = [], set(), []
            for i, rnd in enumerate(ROUNDS):
                result, defect, why = bruno_result(rnd, flow, case_id)
                if result == "Failed" and defect is None:
                    unmapped.append(f"{rnd['round']} {case_id}: {why}")
                if defect:
                    defects.add(defect)
                if why:
                    notes.append(f"Round {i + 1}: {why}")
                rounds.append(result)
            status = int(bru["status"]) if bru["status"] else None
            typ = "Boundary" if case_id in BOUNDARY else (
                "Abnormal" if (status and status >= 400) or group == "unhappy" else "Normal")
            actor = f" ({bru['actor']})" if bru["actor"] else ""
            rows.append(dict(
                pre=KIND_LABEL[kind] + "\n" + (f"Chạy sau {previous}" if previous else
                                               f"Đầu nhóm{' ' + group if group else ''}: {pre}"),
                inp=f"{case_id}: {bru['method']} {bru['url']}{actor}" + (
                    f"\nGiới hạn: {BOUNDARY[case_id]}" if case_id in BOUNDARY else ""),
                ret=(f"HTTP {status}; " if status else "") + title,
                typ=typ, rounds=rounds, defect=", ".join(sorted(defects)),
                actual=actual_text([observed(rnd, flow, case_id) for rnd in ROUNDS]),
                note=f"Bruno: {location.relative_to(BRUNO).as_posix()}\nSpec: {reference}"
                     + ("".join("\n" + n for n in notes))))
            previous = case_id
        label = {"": "Luồng chính", "happy": "Happy path", "unhappy": "Unhappy path: rút offer",
                 "clean": "Nhận hàng sạch", "completion": "Hoàn tất hợp đồng", "dispute": "Tranh chấp chất lượng"}
        groups.append((f"{folder}/{group}".rstrip("/") + f" ({label.get(group, group)})", rows))
    pending = [r for r in STATUS_ROWS if r[0] == flow]
    if pending:
        groups.append(("Chưa chạy được (backend/scripts/e2e/status.tsv)", [dict(
            pre=KIND_LABEL[kind] + "\nN/A", inp=f"{case_id}: {title}", ret=title, typ="Normal",
            rounds=[status] * len(ROUNDS), defect="",
            actual=actual_text(["không chạy: " + reason] * len(ROUNDS)),
            note=f"{status}: {reason}" + (f" Issue {issue}." if issue else ""))
            for _, case_id, title, kind, status, issue, reason in pending]))
    # run-e2e-full.sh snapshots the queues after every flow, BF06 included
    dlq = [dlq_result(rnd, flow) for rnd in ROUNDS]
    unmapped += [f"{rnd['round']} {flow}-DLQ: {why}" for rnd, (res, d, why) in zip(ROUNDS, dlq) if d is None]
    groups.append(("RabbitMQ sau flow", [dict(
        pre=KIND_LABEL["IT-DIRECT"] + f"\nSau mọi case của {flow}",
        inp=f"{flow}-DLQ: GET http://localhost:15672/api/queues", ret="Mọi queue .dlq có mặt và rỗng",
        typ="Normal", rounds=[r for r, _, _ in dlq], defect="",
        actual=actual_text([dlq_observed(rnd, flow) for rnd in ROUNDS]),
        note=f"queues-{flow.lower()}.json" + "".join(
            f"\nRound {i + 1}: {why}" for i, (_, _, why) in enumerate(dlq) if why))]))
    MODULES.append(dict(flow=flow, sheet=sheet, desc=desc, pre=pre, groups=groups))
assert not unmapped, "Failed rows without a Report 5.5 defect:\n  " + "\n  ".join(unmapped)

# ── Excel ────────────────────────────────────────────────────────────────────────────────────────────────────
xl = win32com.client.DispatchEx("Excel.Application")
xl.Visible = False
xl.DisplayAlerts = False
# a private copy: an Excel left over from a crashed run may still hold the template open
WORK = os.path.join(tempfile.mkdtemp(prefix="report53-"), os.path.basename(SRC))
shutil.copyfile(SRC, WORK)
wb = busy(lambda: xl.Workbooks.Open(WORK))
busy(lambda: setattr(xl, "ScreenUpdating", False))
busy(lambda: setattr(xl, "Calculation", -4135))  # manual; CalculateFull runs once before saving


@atexit.register
def close_excel():
    try:
        wb.Close(False)
    except Exception:
        pass
    try:
        xl.Quit()
    except Exception:
        pass


def put(rng, value):
    busy(lambda: setattr(rng, "Value", value))


def same_font(rng):
    busy(lambda: setattr(rng.Font, "Name", "Tahoma"))
    busy(lambda: setattr(rng.Font, "Size", 10))
    busy(lambda: setattr(rng.Font, "Italic", False))


def copy_row(sh, src_row, dst_row):
    busy(lambda: sh.Rows(src_row).Copy(sh.Rows(dst_row)))


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


STYLE_HEADER, STYLE_CASE = 1100, 1101
tpl = wb.Worksheets("ModuleName1")
total = 0
for m in MODULES:
    last = wb.Worksheets(wb.Worksheets.Count)
    busy(lambda: tpl.Copy(None, last))
    sh = busy(lambda: wb.Worksheets(wb.Worksheets.Count))
    assert sh.Name != "ModuleName1" and sh.Name != last.Name, "sheet copy went to the wrong place"
    busy(lambda: setattr(sh, "Name", m["sheet"]))
    # Actual Results (mentor review 27/11): the template has no such column, so Q takes the look of Note (P)
    # after it; A..P keep their place so the template's COUNTIF ranges and dropdowns stay valid
    for r in (11, 12, 13):
        busy(lambda: sh.Range(f"P{r}").Copy(sh.Range(f"Q{r}")))
    copy_row(sh, 12, STYLE_HEADER)
    copy_row(sh, 13, STYLE_CASE)
    busy(lambda: sh.Range("A12:Q1003").ClearContents())
    put(sh.Range("Q11"), "Actual Results")
    put(sh.Range("B2"), m["sheet"])
    busy(lambda: setattr(sh.Range("B3"), "Formula", '=COUNTIF(A13:A1005,"STC*")'))
    busy(lambda: setattr(sh.Range("D9"), "Formula", "=COUNTIF($E12:$E1003,D8)"))
    put(sh.Range("T5"), "Failed")
    row, n, header_rows = 12, 0, set()
    for group, rows in m["groups"]:
        copy_row(sh, STYLE_HEADER, row)
        put(sh.Range(f"A{row}"), group)
        header_rows.add(row)
        row += 1
        for c in rows:
            n += 1
            copy_row(sh, STYLE_CASE, row)
            cells = []
            for i, (rnd, result) in enumerate(zip(ROUNDS, c["rounds"])):
                COUNTS[i][result] = COUNTS[i].get(result, 0) + 1
                tested = result in ("Passed", "Failed")
                cells += [result, "'" + rnd["date"] if tested else "", rnd["tester"] if tested else ""]
            cells += [""] * (9 - len(cells))
            values = [f"STC{n:03d}", c["pre"], c["inp"], f"Return: {c['ret']}\nException: N/A\nLog message: N/A",
                      c["typ"], *cells, c["defect"], c["note"], c["actual"]]
            rng = busy(lambda: sh.Range(sh.Cells(row, 1), sh.Cells(row, 17)))
            put(rng, [values])
            same_font(rng)
            for col in (6, 9, 12):
                cell = busy(lambda: sh.Cells(row, col))
                busy(lambda: cell.Validation.Delete())
                busy(lambda: cell.Validation.Add(3, 1, 1, "=$T$2:$T$5"))
            row += 1
    total += n
    busy(lambda: sh.Rows(f"{STYLE_HEADER}:{STYLE_CASE}").Delete())
    set_widths(sh, {1: 32, 2: 55, 3: 75, 4: 75, 5: 20, 6: 18, 7: 22, 8: 34, 9: 18, 10: 22, 11: 34,
                    12: 18, 13: 22, 14: 34, 15: 24, 16: 80, 17: 90})
    fit_rows(sh, 13, row - 1, 17, skip=header_rows)
busy(lambda: tpl.Delete())


def summary(i):
    return ", ".join(f"{COUNTS[i][k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if COUNTS[i].get(k))


def round_line(i):
    r = ROUNDS[i]
    return (f"Round {i + 1} ngày {r['date']} trên commit {r['commit']}, tester {r['tester']}, lần chạy {r['run']}: "
            f"{summary(i)}. Log: scripts/unit-test-report/results/{r['round']}/ (Bruno JSON, queue, "
            f"TESTING-ROUNDS.md, FINDINGS.md); log service ở ~/agricontract-e2e/{r['run']}/logs/.")


# ── Cover ────────────────────────────────────────────────────────────────────
cover = wb.Worksheets("Cover")
put(cover.Range("B2"), "SYSTEM TEST DOCUMENT")
put(cover.Range("B4"), "AgriContract")
put(cover.Range("B5"), PROJECT_CODE)
busy(lambda: setattr(cover.Range("B6"), "Formula", '=B5&"_SystemTest_v1.0"'))
put(cover.Range("F4"), CREATOR)
put(cover.Range("F5"), "'" + ISSUE_DATE)
put(cover.Range("F6"), "v1.0")
for ref, text in zip(["A11", "B11", "C11", "D11", "E11", "F11"],
                     ["'" + ISSUE_DATE, "v1.0", "Toàn bộ tài liệu", "A",
                      f"Bản đầu: {total} system test case (STC) của BF-01..BF-07 và bộ phòng thủ, 1 dòng = 1 bước "
                      "của luồng (request Bruno trong backend/bruno/e2e, backend/bruno/security), cộng các bước chưa "
                      "chạy được của status.tsv và kiểm tra DLQ sau mỗi flow; kết quả từ run-e2e-full.sh, "
                      + ", ".join(f"Round {i + 1} {r['run']}" for i, r in enumerate(ROUNDS)),
                      "SRS v1.7 §I.2, Report 5.0, Report 5.5"]):
    put(cover.Range(ref), text)

# ── Test Cases ───────────────────────────────────────────────────────────────
tc = wb.Worksheets("Test Cases")
busy(lambda: setattr(tc.Range("D3"), "Formula", "=Cover!B4"))
busy(lambda: setattr(tc.Range("D4"), "Formula", "=Cover!B5"))
put(tc.Range("D5"), (
    "1. Stack: cd backend && ./scripts/run-e2e-full.sh --fresh --keep (Docker Compose project agricontract-bruno, "
    "xoá volume của project test, build mọi image từ commit, chờ service sẵn sàng, nạp seed 01-product-db.sql và "
    "02-user-db.sql; product-service rút ngắn kiểm tra geo; bank 8086 và audit 8092 mở trên loopback)\n"
    "2. Bruno CLI 3.4.2 --env local --sandbox developer --bail (Security: --no-bail); BF-02 truyền listing cho "
    "BF-03/BF-04, BF-03 truyền hợp đồng ACTIVE cho BF-04\n"
    "3. Tài khoản Keycloak (Report 5.0 §II.1.1): buyer1, seller1, operator1, inspector1, arbitrator1, admin1; BF-01 "
    "tạo tài khoản mới mỗi lần chạy; OTP đọc từ MailHog\n"
    "4. Kết quả: report.py của runner quyết định Passed/Failed/Pending từ JSON của Bruno; nhập vào "
    "scripts/unit-test-report/results/st-round<N>/ bằng import_e2e_round.py\n"
    "5. 1 dòng = 1 bước của luồng; Pre-conditions ghi case phải chạy trước; Note ghi request Bruno và nguồn đặc tả; "
    "Defect IDs ghi defect của Report 5.5; dòng Pending/N/A ghi lý do theo status.tsv\n"
    "6. Actual Results (cột Q, thêm theo góp ý mentor 27/11): HTTP status và body (code, message của ErrorEnvelope) "
    "hoặc lỗi assertion mà hệ thống trả ở từng round, đọc từ JSON của Bruno; dòng DLQ ghi số message của queue .dlq"))
for i, m in enumerate(MODULES):
    r = 9 + i
    if r > 10:
        copy_row(tc, 10, r)
    put(tc.Cells(r, 2), i + 1)
    for col, text in [(3, m["flow"]), (5, m["desc"]), (6, m["pre"])]:
        put(tc.Cells(r, col), text)
        same_font(tc.Cells(r, col))
    link_to_sheet(tc, tc.Cells(r, 4), m["sheet"])
set_widths(tc, {2: 12, 3: 24, 4: 40, 5: 95, 6: 80})
fit_rows(tc, 9, 8 + len(MODULES), 6)
busy(lambda: setattr(tc.Range("D5"), "WrapText", True))
busy(lambda: setattr(tc.Rows(5), "RowHeight", 12 * 14.5))
fit_page_width(tc)

# ── Test Statistics ──────────────────────────────────────────────────────────
st = wb.Worksheets("Test Statistics")
put(st.Range("A2"), "SYSTEM TEST STATISTICS")
for _ in range(max(0, len(MODULES) - 4)):  # template has data rows 12..15 inside SUM(…10:…15)
    busy(lambda: st.Rows(15).Insert())
busy(lambda: setattr(st.Range("B6"), "Formula", "=Cover!B6"))
put(st.Range("F4"), CREATOR)
put(st.Range("F6"), "'" + ISSUE_DATE)
latest = len(ROUNDS)
put(st.Range("B7"), (
    f"System test (STC) của {len(MODULES)} sheet: BF-01..BF-07 và Security, {total} dòng. "
    + " ".join(round_line(i) for i in range(len(ROUNDS)))
    + f" Bảng dưới đếm theo Round {latest}."))
for i, m in enumerate(MODULES):
    r = 12 + i
    if r > 12:
        copy_row(st, 12, r)
    put(st.Cells(r, 1), i + 1)
    link_to_sheet(st, st.Cells(r, 2), m["sheet"])
    for col, ref in zip(range(3, 10), [f"B{4 + latest}", f"C{4 + latest}", f"D{4 + latest}", "B9", "C9", "D9", "B3"]):
        busy(lambda: setattr(st.Cells(r, col), "Formula", f"='{m['sheet']}'!{ref}"))
busy(lambda: setattr(st.Range("B7"), "WrapText", True))
busy(lambda: setattr(st.Rows(7), "RowHeight", 8 * 14.5))
busy(lambda: setattr(st.Columns(2), "ColumnWidth", round(60 / 1.9, 1)))
fit_page_width(st)

busy(lambda: wb.Worksheets("Cover").Activate())
busy(lambda: xl.CalculateFull())
if os.path.exists(OUT):
    os.remove(OUT)
busy(lambda: wb.SaveAs(OUT, 51))
close_excel()
print("wrote", OUT, f"({total} rows)")
for i in range(len(ROUNDS)):
    print(f"  Round {i + 1} ({ROUNDS[i]['run']}): {summary(i)}")
