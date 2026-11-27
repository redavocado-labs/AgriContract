# Report 5.4 (system test of the non-functional requirements, SRS IV.2) on the school template: Security and
# Performance sheets as the template has them, plus Reliability and Usability sheets copied from Security. Pure
# openpyxl (the template has no charts or images).
#
# Each row names its evidence and its Status is decided from it, nothing typed:
#   ut(...)    unit / integration tests of the regression run (results/<ut round>/cases.json): Pass when every
#              named test ran and passed
#   st(...)    a Bruno e2e case (results/st-round<N>/, Report 5.2 since 28/11: API through the gateway is IT, D95): its
#              result as report.py decides it, or st_code(...) its HTTP status only (the access decision, apart from
#              the error body of DEF-15)
#   pw(...)    a Playwright test of frontend/tests/qa (results/pw-<date>/playwright.json)
#   ui(...)    a system test through the web client on a real stack (frontend/tests/st, Report 5.3;
#              results/st-ui/results-<browser>.json)
#   perf       results/perf-<date>/perf.json and pageload.json (perf_nfr.py)
# A row without evidence is Not Run, with the reason in Notes.
#
#   python fill_54_nfr.py results/ut-round3-20261128 results/st-round3 results/pw-20261128 results/perf-20261128 \
#       ../../docs/reports/Report-5.4_System_Test_NFRs.xlsx results/st-ui
import importlib.util
import json
import os
import sys
from copy import copy
from pathlib import Path

import openpyxl

HERE = Path(__file__).resolve().parent
UT_DIR, ST_DIR, PW_DIR, PERF_DIR, BOOK, UI_DIR = (Path(p).resolve() for p in sys.argv[1:7])
spec = importlib.util.spec_from_file_location("e2e_report", HERE.parents[1] / "backend/scripts/e2e/report.py")
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)

CASES = json.loads((UT_DIR / "cases.json").read_text(encoding="utf-8"))
UT_RUN = json.loads((UT_DIR / "run.json").read_text(encoding="utf-8"))
ST_RUN = json.loads((ST_DIR / "run.json").read_text(encoding="utf-8"))
PW_RUN = json.loads((PW_DIR / "run.json").read_text(encoding="utf-8"))
PERF = json.loads((PERF_DIR / "perf.json").read_text(encoding="utf-8"))
PAGES = json.loads((PERF_DIR / "pageload.json").read_text(encoding="utf-8"))
ST = {}
for name in os.listdir(ST_DIR):
    if name.startswith("bruno-") and name.endswith(".json") and name not in ("bruno-bf03.json", "bruno-bf04.json"):
        ST.update(e2e.load_results(ST_DIR / name))
PW = {}


def _walk(suite):
    for s in suite.get("specs", []):
        for t in s["tests"]:
            PW[s["title"]] = t["results"][-1]["status"]
    for c in suite.get("suites", []):
        _walk(c)


for s in json.loads((PW_DIR / "playwright.json").read_text(encoding="utf-8"))["suites"]:
    _walk(s)

UI = {}  # (browser, test-id) -> status
UI_DATE = {}
for browser in ("chromium", "firefox"):
    report = json.loads((UI_DIR / f"results-{browser}.json").read_text(encoding="utf-8"))
    UI_DATE[browser] = report["stats"]["startTime"]

    def _walk_ui(suite, browser=browser):
        for s in suite.get("specs", []):
            for t in s["tests"]:
                UI[(browser, s["title"].split(" ")[0])] = t["results"][-1]["status"]
        for c in suite.get("suites", []):
            _walk_ui(c)

    for s in report["suites"]:
        _walk_ui(s)


# ── evidence: each returns (verdict, text); verdict Pass | Fail | Blocked | None (not run) ────────────────────
def ut(*keys):
    """"service/Class" (every test of it) or "service/Class#name" (tests whose name starts with name)"""
    found, bad = 0, []
    for key in keys:
        source, _, prefix = key.partition("#")
        tests = {n: s for n, s in CASES.get(source, {}).items() if n.startswith(prefix)}
        assert tests, f"no test {key} in {UT_DIR.name}"
        found += len(tests)
        bad += [f"{source.split('/')[-1]}.{n}" for n, s in tests.items() if s != "passed"]
    names = ", ".join(k.split("/", 1)[1].replace("#", ".") for k in keys)
    return ("Fail" if bad else "Pass"), f"UT {UT_RUN['date']}: {names} ({found} test, {len(bad)} không pass)"


def st(*case_ids, defect=None):
    results = [(c, e2e.outcome(ST.get(c))) for c in case_ids]
    text = f"IT Bruno {ST_RUN['run']}: " + ", ".join(f"{c} {r}" for c, r in results)
    if any(r == "Failed" for _, r in results):
        return ("Blocked" if defect == "DEF-19" else "Fail"), text
    if all(r == "Passed" for _, r in results):
        return "Pass", text
    return None, text


def dlq(*flows):
    """every .dlq queue empty in the snapshot run-e2e-full.sh took after each flow (queues-<flow>.json)"""
    parts, ok = [], True
    for flow in flows:
        queues = [q for q in json.loads((ST_DIR / f"queues-{flow}.json").read_text(encoding="utf-8"))
                  if q["name"].endswith(".dlq")]
        dirty = [q["name"] for q in queues if q.get("messages") != 0]
        ok = ok and bool(queues) and not dirty
        parts.append(f"{flow} {len(queues)} .dlq" + (f", có message: {', '.join(dirty)}" if dirty else " rỗng"))
    return ("Pass" if ok else "Fail"), f"IT Bruno {ST_RUN['run']}: " + "; ".join(parts)


def st_code(expected):
    """{case: HTTP status}: the access decision only"""
    got = {c: (ST.get(c) or {}).get("response", {}).get("status") for c in expected}
    ok = all(got[c] == s for c, s in expected.items())
    return ("Pass" if ok else "Fail"), f"IT Bruno {ST_RUN['run']}: " + ", ".join(f"{c} HTTP {got[c]}" for c in expected)


def pw(*prefixes):
    tests = [(t, s) for t, s in PW.items() if t.startswith(prefixes)]
    assert tests, prefixes
    bad = [t for t, s in tests if s != "passed"]
    return ("Fail" if bad else "Pass"), (f"Playwright {PW_RUN['date']}: {len(tests)} test "
                                         + "/".join(prefixes) + f", {len(bad)} không pass")


def ui(*ids, browsers=("chromium",)):
    """system tests through the web client: every named test passed on every named browser"""
    got = [(b, i, UI.get((b, i))) for b in browsers for i in ids]
    missing = [f"{b}/{i}" for b, i, st_ in got if st_ is None]
    assert not missing, missing
    bad = [f"{i} ({b})" for b, i, st_ in got if st_ != "passed"]
    names = ", ".join(ids) if len(ids) <= 3 else f"{ids[0]}..{ids[-1]} ({len(ids)} test)"
    return ("Fail" if bad else "Pass"), (f"ST qua UI 28/11/2026 ({', '.join(b.capitalize() for b in browsers)}): {names}"
                                         + (f"; không pass: {', '.join(bad)}" if bad else ", pass"))


def combine(*evidence):
    verdicts = [v for v, _ in evidence]
    verdict = ("Fail" if "Fail" in verdicts else "Blocked" if "Blocked" in verdicts
               else None if None in verdicts else "Pass")
    return verdict, "\n".join(t for _, t in evidence)


NOT_RUN = (None, "")

# ── Security (SRS IV.2.3) ─────────────────────────────────────────────────────────────────────────────────────
SECURITY = [
    ("Màn hình được bảo vệ cần phiên đăng nhập", "Guest (không token)", "Stack vừa dựng, không đăng nhập",
     "GET /api/v1/users/me qua gateway", "HTTP 401, không có dữ liệu hồ sơ", "Critical",
     combine(st_code({"SEC-020": 401}), ut("api-gateway/GatewayBoundaryTest")), "",
     "Chặn đúng. Body của 401 rỗng, không theo ErrorEnvelope: xem TC-SEC-005 (DEF-15)"),
    ("Route công khai đọc được không cần đăng nhập", "Guest", "Listing AVAILABLE của BF-02",
     "GET listing công khai, giá tham chiếu qua gateway", "HTTP 200 với dữ liệu công khai, không gắn danh tính",
     "High", combine(st("BF02-070"), ut("api-gateway/GatewayBoundaryTest#public")), "",
     "Giá tham chiếu trả 500 trên stack Compose vì thiếu pricing-service (DEF-20, xem Performance)"),
    ("Hàng chờ KYC chỉ Operator đọc được", "BUYER, SELLER, INSPECTOR, OPERATOR, ARBITRATOR, ADMIN",
     "6 tài khoản seed, mỗi vai trò 1 token", "GET /api/v1/admin/users?verificationStatus=PENDING bằng từng token",
     "OPERATOR 200; 5 vai trò còn lại 403 (không fallback, ADMIN cũng 403)", "Critical",
     st_code({"SEC-040": 403, "SEC-041": 403, "SEC-042": 403, "SEC-043": 200, "SEC-044": 403, "SEC-045": 403}),
     "", "Ma trận quyền data-governance §5.4; quyết định truy cập đúng, body 403 xem TC-SEC-005"),
    ("Hàng chờ phán xử chỉ Arbitrator đọc được", "6 vai trò", "6 tài khoản seed",
     "GET /api/v1/arbitration/breach-cases bằng từng token", "ARBITRATOR 200; 5 vai trò còn lại 403", "Critical",
     combine(st_code({"SEC-060": 403, "SEC-061": 403, "SEC-062": 403, "SEC-063": 403, "SEC-064": 200,
                      "SEC-065": 403}), ui("ST-SEC-010")),
     "", "OPERATOR và ADMIN không làm thay ARBITRATOR; trên giao diện ADMIN và BUYER mở route sai vai trò đều bị chặn"),
    ("Từ chối 401/403 trả ErrorEnvelope", "Guest, vai trò không đủ quyền", "Như TC-SEC-001, 003, 004",
     "Đọc body của các phản hồi 401/403 từ gateway", "Body JSON có code và traceId theo OpenAPI ErrorEnvelope",
     "Medium",
     st("SEC-020", "SEC-040", "SEC-041", "SEC-042", "SEC-044", "SEC-045", "SEC-060", "SEC-061", "SEC-062",
        "SEC-063", "SEC-065", "SEC-110"),
     "DEF-15", "Gateway trả body rỗng; issue #663 còn mở"),
    ("Header danh tính giả bị gateway thay/xoá", "Client tự gửi header", "Request có X-User-Id, X-User-Role, "
     "X-Gateway-Secret giả", "Gọi qua gateway có và không có JWT", "Header danh tính lấy từ JWT; header giả và "
     "X-Internal-Secret bị xoá", "Critical", ut("api-gateway/UserContextInjectionFilterTest"), "", ""),
    ("Ký hợp đồng cần đăng nhập lại trong 300 giây", "Buyer / Seller", "Phiên đăng nhập cũ, thiếu hoặc auth_time "
     "ở tương lai", "Xác nhận OTP để ký", "Từ chối, yêu cầu nhập lại mật khẩu", "High",
     ut("contract-service/SignerEligibilityTest"), "", "MSG05"),
    ("Luật OTP ký: 6 số, hết hạn 300 giây, tối đa 5 lần sai, cooldown 60 giây", "Buyer / Seller",
     "Hợp đồng chờ ký", "Nhập sai, gửi lại trong cooldown, gửi song song", "Hết lượt sau 5 lần sai; gửi lại trong "
     "cooldown bị từ chối; mã chỉ dùng cho đúng điều khoản đã cấp", "High",
     ut("contract-service/OtpPolicyTest", "contract-service/OtpChallengeTest",
        "contract-service/OtpChallengeIntegrationTest"), "", ""),
    ("Khoá tạm tài khoản khi sai mật khẩu nhiều lần", "Tài khoản mới", "Tài khoản Keycloak vừa tạo",
     "Đăng nhập sai mật khẩu 5 lần liên tiếp", "Lần tiếp theo bị từ chối (khoá tạm)", "High", st("SEC-120"), "",
     "Brute-force detection của realm agricontract"),
    ("Tài khoản chưa VERIFIED không đăng được listing", "Seller chưa xác minh KYC", "Seller PENDING",
     "POST /api/v1/listings", "HTTP 403; hệ thống chặn, không chỉ ẩn nút", "High",
     ut("product-service/CreateListingUseCaseImplTest#execute_sellerNotVerified",
        "product-service/CreateListingControllerTest#ineligibleSeller"), "", ""),
    ("Hồ sơ công khai không lộ thông tin liên hệ", "Người dùng đã đăng nhập", "Hồ sơ của đối tác",
     "GET /api/v1/users/{id}", "Chỉ tên tổ chức, vai trò, trạng thái xác minh, uy tín; không email, điện thoại, "
     "địa chỉ", "High", ut("user-service/UserControllerTest#getPublicUser"), "", ""),
    ("Người không phải bên của đợt không xem được dữ liệu đợt", "Buyer đóng vai Seller; người dùng khác",
     "Milestone của hợp đồng khác", "Mở chi tiết milestone, đổi danh tính trong phiên",
     "403 ẩn dữ liệu; không hiện dữ liệu cache của người trước", "High", pw("FE-011"), "", "UI (frontend/tests/qa)"),
    ("File chỉ dùng được sau khi quét malware", "Seller", "File bằng chứng vừa upload",
     "Upload file sạch và file bị từ chối khi quét", "File bị từ chối không gắn vào hồ sơ, có nút thay thế; file "
     "sạch READY mới dùng được", "Critical",
     combine(ut("file-service/src/infrastructure/scan/clamd-scanner.spec.ts"),
             pw("FE-015 a rejected scan", "FE-015 blocks evidence")), "", ""),
    ("Chuỗi audit chống sửa, kiểm tra toàn cục", "Admin", "Audit chain sau các flow",
     "GET /api/v1/admin/audit/verification", "HTTP 200, status INTACT; màn Nhật ký bằng chứng báo toàn chuỗi nguyên vẹn", "High",
     combine(st("SEC-100"), ui("ST-ADM-010")), "", ""),
    ("Ghi vết khi người vận hành đọc dữ liệu hạn chế", "Admin", "Hợp đồng đã quyết toán của BF-04",
     "Admin đọc trail của hợp đồng, rồi đọc bản ghi SYSTEM", "Mỗi lần đọc thêm đúng 1 bản ghi audit.viewed",
     "Medium", st("SEC-140", "SEC-150", "SEC-160", "SEC-170", defect="DEF-19"), "DEF-19",
     "Round 3 chạy thứ Bảy: không có hợp đồng quyết toán (DEF-19) nên chưa kiểm được; Report 5.2 (Bruno e2e) Round 1-2 Passed; trên giao diện ST-ADM-020 (trail hợp đồng đã rút) có audit.viewed (Report 5.3)"),
    ("Lỗi validation không lộ chi tiết hệ thống", "Buyer", "N/A", "GET /api/v1/contracts?page=-1",
     "HTTP 400 VALIDATION_FAILED, không stack trace, không tên class", "Medium", st("SEC-180", "SEC-190"), "", ""),
    ("Khoá khẩn cấp chỉ nhận yêu cầu ký ES256, timestamp ±300 giây, nonce chưa dùng", "External Verifier",
     "Khoá Verifier đã đăng ký", "POST lock/unlock có chữ ký hợp lệ, sai, nonce lặp", "Chỉ yêu cầu hợp lệ được "
     "nhận; không Admin nào thay được", "Critical", NOT_RUN, "",
     "Chưa có controller cho đường lock/unlock (Report 5.2 BF07_SecurityLockApi BF07-020 N/A)"),
    ("Xoá / ẩn danh dữ liệu cá nhân hết hạn lưu trữ", "Hệ thống (JOB-19)", "Dữ liệu quá hạn, có và không có "
     "legal hold", "Chạy job lưu trữ", "Dữ liệu hết hạn bị xoá hoặc ẩn danh; dữ liệu legal hold giữ nguyên",
     "Medium", NOT_RUN, "", "Chưa có test tự động cho JOB-19"),
]

# ── Reliability (SRS IV.2.4) ──────────────────────────────────────────────────────────────────────────────────
RELIABILITY = [
    ("Lặp lại offer cùng Idempotency-Key không giữ hàng 2 lần", "Buyer", "Listing AVAILABLE",
     "POST /api/v1/contracts 2 lần cùng key, đọc lại listing", "Lần 2 trả kết quả cũ; số lượng giữ chỉ trừ 1 lần",
     "Critical", st("SEC-080", "SEC-090"), "", ""),
    ("Lệnh ngân hàng thực hiện tối đa 1 lần", "Hệ thống (bank-service)", "Lệnh lock/release lặp cùng "
     "sourceEventId", "Gửi lại lệnh và kết quả", "Ledger chỉ ghi 1 lần, lệnh lặp bị bỏ qua", "Critical",
     ut("bank-service/LedgerCommandConsumerTest", "bank-service/ProcessLedgerCommandUseCaseImplTest"), "", ""),
    ("Khoá cọc thất bại được thử lại theo lịch", "Hệ thống (JOB-04)", "Kết quả khoá cọc FAILED",
     "Nhận kết quả thất bại, chạy job retry", "Thử lại theo lịch 5 phút / 30 phút / 2 giờ, hết lượt thì "
     "ACTIVATION_FAILED, không treo", "High",
     ut("escrow-service/DepositLockResultFlowIntegrationTest", "contract-service/ActivationRecoveryIntegrationTest"),
     "", ""),
    ("Payload hỏng vào DLQ, không retry", "Hệ thống (consumer)", "Message sai schema",
     "Đưa message hỏng vào queue", "Từ chối ngay (1 lần), message vào .dlq", "High",
     ut("product-service/ListenerRetryTest", "user-service/ReputationLockFlowIntegrationTest#schemaViolation"), "",
     ""),
    ("Không còn message trong DLQ sau mỗi flow", "Hệ thống", "Stack sau từng flow BF-01..BF-07 và Security",
     "GET /api/queues của RabbitMQ", "Mọi queue .dlq có mặt và rỗng", "High",
     dlq("bf01", "bf02", "bf03", "bf04", "bf05", "bf06", "bf07", "security"), "",
     "Sau BF-04 chỉ kiểm được phần đã chạy (DEF-19)"),
    ("Web client không gửi lại lệnh rút offer khi mất phản hồi", "Buyer", "Rút offer, mất response, reload / 2 tab",
     "Reload, mở tab thứ 2, hết thời gian khôi phục", "Tối đa 1 lần gửi; key cũ được dùng lại sau khi đọc trạng "
     "thái thật; không gửi với key mới", "High", pw("FE-003"), "", "UI (frontend/tests/qa)"),
    ("Web client chờ kích hoạt có giới hạn", "Buyer", "Hợp đồng SIGNED chờ khoá cọc",
     "Mở chi tiết hợp đồng, lỗi đọc tạm thời, đổi hợp đồng", "Poll 5 giây trong ngân sách 120 giây, dừng khi ACTIVE, "
     "có nút làm mới thủ công", "Medium", pw("FE-004"), "", "UI (frontend/tests/qa)"),
    ("Upload lỗi 503 được thử lại an toàn", "Seller", "Upload bằng chứng trả 503", "Bấm thử lại",
     "Giữ nguyên key và bytes; file đang quét dừng tự đọc sau 60 giây", "Medium",
     pw("FE-015 an explicit retry", "FE-015 a projected"), "", "UI (frontend/tests/qa)"),
    ("Thông báo lỗi được gửi lại, không gửi trùng", "Hệ thống (notification-service)", "Gửi email thất bại",
     "Retry và nhận lại cùng event", "Thử lại có giới hạn; cùng event + người nhận + loại không gửi 2 email",
     "Medium", ut("notification-service/src/application/notification-sender.spec.ts"), "", ""),
]

# ── Usability (SRS IV.2.1) ────────────────────────────────────────────────────────────────────────────────────
USABILITY = [
    ("Trang chủ reflow ở 320 CSS px", "Guest", "Viewport 320 px", "Mở trang chủ", "Không cuộn ngang",
     "Medium", pw("FE-016 the public home page reflows"), "", "WCAG 2.2 AA 1.4.10"),
    ("Dialog dùng được bằng bàn phím, không lỗi axe nghiêm trọng", "Buyer, Guest", "Chi tiết hợp đồng, bảng giá",
     "Mở / duyệt / đóng dialog rút offer bằng bàn phím; quét axe", "Focus đúng, không lỗi serious/critical",
     "Medium", pw("FE-016 an offer withdrawal", "FE-016 contract detail", "FE-016 public prices"), "", ""),
    ("Hạn sửa cân của seller đếm theo giờ server", "Seller", "Milestone SELLER_WEIGHED", "Chờ quá hạn sửa",
     "Đếm ngược theo hạn server trả, quá hạn thì ẩn nút sửa", "Medium", pw("FE-010"), "", ""),
    ("Validation hiện cạnh field và giữ giá trị đã nhập", "Buyer", "Form tạo offer",
     "Nhập độ ẩm 11.5, đơn giá 95000 (thiếu 2 số lẻ) → Gửi đề nghị",
     "Thông báo cạnh field đơn giá, độ ẩm giữ 11.5, vẫn ở form", "Medium", ui("ST-USA-010"), "", ""),
    ("Tiền hiển thị VND, phân cách nghìn, 2 số lẻ", "Buyer", "Đề nghị 1.000 kg giá 96000.00",
     "Xem trang hợp đồng và trang listing", "96.000,00 ₫/kg và tổng 96.000.000,00 ₫ (SRS IV.2.1)", "Low",
     ui("ST-USA-020"), "DEF-23",
     "Màn hình hiện 96.000 ₫: formatDecimalVnd (frontend/src/shared/lib/decimal-display.ts) bỏ phần lẻ ,00"),
    ("Ngày dd/MM/yyyy, giờ Asia/Ho_Chi_Minh", "Buyer", "Hợp đồng vừa tạo",
     "Xem ngày giao; gửi mã OTP ký", "Ngày giao dạng dd/MM/yyyy; hạn mã OTP ghi giờ Việt Nam", "Low",
     ui("ST-USA-030"), "", ""),
    ("Hành động bị chặn nói rõ lý do", "Buyer chưa VERIFIED", "KYC PENDING",
     "Mở form tạo offer", "Màn hình ghi lý do (chỉ bên mua đã xác minh mới gửi được), nút gửi bị khoá",
     "Medium", ui("ST-USA-040"), "", "Khoá uy tín / khoá khẩn cấp chưa có màn hình (BF-07)"),
    ("Chạy được trên Chrome, Edge, Firefox (2 bản mới nhất), ≥ 1366 px", "Guest", "Chromium và Firefox của "
     "Playwright, 1366x900", "Mở 7 màn công khai (trang chủ, sàn, bảng giá, cách hoạt động, ký quỹ, đăng nhập, "
     "chi tiết listing)", "Hiện đủ nội dung chính, không lỗi JavaScript, không cuộn ngang", "Medium",
     ui(*(f"ST-BRW-0{i}0" for i in range(1, 8)), browsers=("chromium", "firefox")), "",
     "Edge dùng cùng engine Chromium; màn sau đăng nhập mới chạy trên Chromium (Report 5.3)"),
    ("Seller VERIFIED đăng listing đầu tiên trong 15 phút không cần hướng dẫn", "Seller mới", "Seller VERIFIED "
     "chưa dùng hệ thống", "Tự đăng lô đất, sản phẩm, listing", "Xong trong 15 phút", "Low", NOT_RUN, "",
     "Cần người dùng thật: thuộc Report 5.5 (UAT)"),
]

# ── Performance (SRS IV.2.2) ──────────────────────────────────────────────────────────────────────────────────
c, form, single = PERF["concurrent"], PERF["form_submission"], PERF["single_user"]


def page_row(path, label):
    times = PAGES[path]
    load, idle = max(t["load"] for t in times), max(t["idle"] for t in times)
    # network idle is reported 500 ms after the last request ends
    rendered = idle - 500
    return (f"Tải trang {label} khi có {c['users']} user đồng thời", "Playwright (Chromium) + perf_nfr.py",
            "≤ 3 giây (toàn màn hình hiển thị xong)",
            f"{len(times)} lần: load event ≤ {load} ms, hết request ≤ {rendered} ms",
            "Pass" if rendered <= 3000 else "Fail")


PERFORMANCE = [
    (*page_row("/", "trang chủ"), "", "vite build trỏ gateway localhost:8080, vite preview; đo trong lúc load test"),
    (*page_row("/listing", "chợ listing"), "", f"{PERF['listings']} listing trong product_db"),
    (*page_row("/prices", "giá tham chiếu")[:4], "Fail", "DEF-20",
     "API giá trả 500 sau ~3,9 s: stack Compose không có pricing-service"),
    ("Gửi form (không tính upload, không tính bước chờ dịch vụ ngoài)", "Bruno CLI 3.4.2 (responseTime)", "≤ 2 giây",
     f"{form['count']} request ghi của ST Round 3 + IT Round 3: p95 {form['p95']} ms, max {form['max']} ms",
     "Pass" if form["max"] <= 2000 else "Fail", "", "results/st-round3, results/round3"),
    ("Duyệt / lọc listing, 1 user, trang đầu 20 dòng", "perf_nfr.py", "≤ 3 giây với tới 10.000 listing",
     f"{PERF['listings']} listing; duyệt p95 {single['browse']['p95']} ms, lọc theo uy tín p95 "
     f"{single['filtered']['p95']} ms (30 lần mỗi loại)",
     "Pass" if max(single["browse"]["max"], single["filtered"]["max"]) <= 3000 else "Fail", "",
     "Lọc = minSellerScore, người xem đã đăng nhập"),
    (f"Duyệt / lọc listing khi {c['users']} user đồng thời", "perf_nfr.py", "≤ 4,5 giây (3 giây + 50%)",
     f"duyệt p95 {c['per_request']['browse']['p95']} ms, max {c['per_request']['browse']['max']} ms; lọc p95 "
     f"{c['per_request']['filtered']['p95']} ms, max {c['per_request']['filtered']['max']} ms",
     "Pass" if max(c["per_request"]["browse"]["max"], c["per_request"]["filtered"]["max"]) <= 4500 else "Fail", "",
     ""),
    (f"{c['users']} user đồng thời không lỗi", "perf_nfr.py (thread, urllib)",
     "0 lỗi; không vượt mục tiêu quá 50%",
     f"{c['requests']} request trong {c['seconds']} s ({c['throughput']} req/s); lỗi: "
     + (", ".join(f"{k}: {v}" for k, v in c["errors"].items()) or "0") + f" ({c['error_rate']}%)",
     "Pass" if not c["errors"] else "Fail", "DEF-20" if c["errors"] else "",
     "Mỗi user lặp: duyệt, lọc, giá tham chiếu, hồ sơ của mình, hợp đồng của mình. Lỗi chỉ ở giá tham chiếu; "
     "4 loại còn lại 0 lỗi"),
    ("Email OTP ký tới nhà cung cấp mail", "MailHog", "≤ 10 giây sau yêu cầu; mã sống 300 giây", "", "Not Run", "",
     "Chưa đo riêng; ST Round 3 (thứ Bảy) dừng ở chữ ký thứ 2, DEF-19"),
    ("Kết quả lệnh ngân hàng cập nhật hợp đồng / đợt", "Bruno BF-04", "≤ 10 giây", "", "Not Run", "DEF-19",
     "BF-04 không chạy ở Round 3 (DEF-19); chạy lại vào ngày làm việc"),
    ("Outbox publish event", "UT outbox poller", "≤ 5 giây sau khi ghi", "", "Not Run", "",
     "Chưa có phép đo end-to-end"),
    ("File ≤ 10 MB dùng được sau upload", "Bruno BF-04 (upload bằng chứng)", "≤ 60 giây", "", "Not Run", "DEF-19",
     "BF-04 không chạy ở Round 3 (DEF-19)"),
]

# ── write ─────────────────────────────────────────────────────────────────────────────────────────────────────
STATUS = {"Pass": "Pass", "Fail": "Fail", "Blocked": "Blocked", None: "Not Run"}
wb = openpyxl.load_workbook(BOOK)
sec = wb["Security"]
head_style = [copy(sec.cell(1, i)._style) for i in range(1, 11)]
body_style = [copy(sec.cell(2, i)._style) for i in range(1, 11)]


def clear(ws, first=2):
    for row in ws.iter_rows(min_row=first, max_row=max(ws.max_row, 300)):
        for cell in row:
            cell.value = None


def fill_cases(ws, prefix, rows):
    clear(ws)
    for i, (scenario, role, given, when, then, priority, (verdict, evidence), defect, note) in enumerate(rows):
        r = i + 2
        values = [f"TC-{prefix}-{i + 1:03d}", scenario, role, given, when, then, priority, STATUS[verdict],
                  defect, "\n".join(x for x in (evidence, note) if x)]
        for col, v in enumerate(values, start=1):
            cell = ws.cell(r, col, v)
            cell._style = copy(body_style[col - 1])


for title, prefix, rows in (("Reliability", "REL", RELIABILITY), ("Usability", "USA", USABILITY)):
    if title in wb.sheetnames:
        del wb[title]
    ws = wb.copy_worksheet(sec)
    ws.title = title
    for dv in sec.data_validations.dataValidation:
        ws.data_validations.append(copy(dv))
    ws.freeze_panes = "A2"
    fill_cases(ws, prefix, rows)
fill_cases(sec, "SEC", SECURITY)

perf = wb["Performance"]
pstyle = [copy(perf.cell(2, i)._style) for i in range(1, 9)]
clear(perf)
for i, row in enumerate(PERFORMANCE):
    for col, v in enumerate([f"TC-PERF-{i + 1:03d}", *row], start=1):
        perf.cell(i + 2, col, v)._style = copy(pstyle[col - 1])
perf.column_dimensions["E"].width = 40

wb._sheets = [wb["Security"], wb["Reliability"], wb["Usability"], wb["Performance"]]
wb.save(BOOK)
for name, rows in (("Security", SECURITY), ("Reliability", RELIABILITY), ("Usability", USABILITY)):
    counts = {}
    for r in rows:
        counts[STATUS[r[6][0]]] = counts.get(STATUS[r[6][0]], 0) + 1
    print(name, counts)
counts = {}
for r in PERFORMANCE:
    counts[r[4]] = counts.get(r[4], 0) + 1
print("Performance", counts)
