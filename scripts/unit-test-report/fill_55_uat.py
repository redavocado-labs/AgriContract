# Report 5.5 (acceptance test scripts) on the school template: one UAT script per business scenario of SRS I.2
# (BF-01..BF-07), written in business language. Each script is executed by the Playwright system tests through the
# web client on a real stack (frontend/tests/st, Report 5.3): Result comes from their run, nothing typed.
#   Pass         every step of the script ran through the screens and passed
#   Partial      what ran passed, but some steps have no screen yet (done through the API) or did not run today
#   Fail         a test of the script failed
#   Not Executed the flow has no screen yet; Feedback names the API evidence of Report 5.2
# The Exploratory sheet keeps only its header: no exploratory session with business users has been held. Pure openpyxl.
#
#   python fill_55_uat.py results/st-ui/results-chromium.json ../../docs/reports/Report-5.2_Test_Report.xlsx \
#       "../../docs/reports/Report-5.5_Acceptance Test Scripts.xlsx"
import json
import sys
from copy import copy

import openpyxl

UI_RESULTS, IT_BOOK, BOOK = sys.argv[1:4]

SCRIPTS = [  # id, BF, title, actor, steps, outcome, priority, UI tests, steps without a screen, 5.2 sheet
    ("SC-01", "BF-01", "Người mua đăng ký và được duyệt KYC", "Guest → Buyer, Operator",
     "1. Khách mở trang Đăng ký, chọn vai trò Người mua, nhập email và mật khẩu.\n"
     "2. Điền hồ sơ doanh nghiệp (tên, số điện thoại, địa chỉ) và gửi.\n"
     "3. Nhập mã 6 số trong email xác minh.\n"
     "4. Trước khi được duyệt, thử tạo offer.\n"
     "5. Operator xác minh hồ sơ.\n"
     "6. Người mua tải lại trang tài khoản và xem email kết quả.",
     "Tài khoản ở trạng thái Đã xác minh, người mua nhận email kết quả; trước khi được duyệt thì không tạo được "
     "offer và màn hình nói rõ lý do.", "Critical",
     ["ST-BF01-010", "ST-BF01-020", "ST-BF01-030", "ST-USA-040", "ST-BF01-040", "ST-BF01-050"],
     "bước 5 Operator xác minh gọi API (chưa có màn hình duyệt KYC)", "BF01_OnboardingApi"),
    ("SC-02", "BF-01", "Người bán bị từ chối KYC", "Guest → Seller, Operator",
     "1. Khách đăng ký vai trò Người bán, gửi hồ sơ doanh nghiệp và xác minh email.\n"
     "2. Operator từ chối hồ sơ và ghi lý do.\n"
     "3. Người bán tải lại trang tài khoản và đọc email.",
     "Màn hình báo hồ sơ chưa được chấp thuận; email từ chối ghi đúng lý do.", "High",
     ["ST-BF01-060", "ST-BF01-070"], "bước 2 Operator từ chối gọi API (chưa có màn hình duyệt KYC)",
     "BF01_OnboardingApi"),
    ("SC-03", "BF-02", "Người bán đăng listing cà phê, khách xem trên sàn", "Seller (đã xác minh), Guest",
     "1. Người bán thêm lô đất, chờ hệ thống kiểm tra rủi ro phá rừng.\n"
     "2. Tạo sản phẩm Robusta và listing 5.000 kg, giá 96.000 ₫/kg.\n"
     "3. Khách (chưa đăng nhập) mở sàn nguồn hàng, mở listing.\n"
     "4. Khách bấm Gửi đề nghị.",
     "Listing Đang mở bán, khách xem được giá, số lượng, chất lượng khai báo, không thấy thông tin liên hệ của "
     "người bán; muốn gửi đề nghị thì phải đăng nhập.", "Critical",
     ["ST-BF02-010", "ST-BF02-020", "ST-BF02-030", "ST-BF02-040"],
     "bước 1-2 của người bán gọi API (chưa có màn hình người bán)", "BF02_PlotListingApi"),
    ("SC-04", "BF-03", "Đàm phán, ký và kích hoạt hợp đồng", "Buyer, Seller",
     "1. Người mua mở listing, gửi đề nghị 1.000 kg với ngày giao.\n"
     "2. Kiểm tra listing giữ đúng phần đã đề nghị.\n"
     "3. Người bán sửa đơn giá.\n"
     "4. Người mua nhận mã OTP qua email và ký.\n"
     "5. Người bán nhận OTP và ký.\n"
     "6. Hai bên mở hợp đồng chờ khoá cọc.",
     "Hợp đồng chỉ Đang hiệu lực sau khi cọc hai bên được khoá (ký ≠ kích hoạt); số lượng của listing bị giữ đúng "
     "phần đã đề nghị.", "Critical",
     ["ST-BF03-010", "ST-BF03-020", "ST-BF03-030", "ST-BF03-040", "ST-BF03-050", "ST-BF03-060"], "",
     "BF03_SigningApi"),
    ("SC-05", "BF-03", "Người mua rút đề nghị trước chữ ký thứ hai", "Buyer",
     "1. Người mua gửi đề nghị trên listing.\n"
     "2. Trước khi người bán ký, người mua bấm Rút đề nghị và xác nhận.\n"
     "3. Kiểm tra lại listing và hợp đồng.",
     "Hợp đồng Đã rút; số lượng giữ được trả lại listing; không có khoản tiền nào bị khoá hay phạt.", "High",
     ["ST-BF03-110", "ST-BF03-120", "ST-BF03-130"], "", "BF03_SigningApi"),
    ("SC-06", "BF-04", "Giao nhận đợt hàng sạch và quyết toán", "Seller, Buyer",
     "1. Đợt hàng được cấp tiền khi hợp đồng kích hoạt.\n"
     "2. Người bán cân hàng, tải ảnh phiếu cân, ghi khối lượng.\n"
     "3. Người mua nhận hàng, tải ảnh, ghi khối lượng nhận.\n"
     "4. Người mua xác nhận hàng đạt.\n"
     "5. Hai bên xem trạng thái đợt và hợp đồng.",
     "Đợt hàng Đã tất toán; khi mọi đợt xong, hợp đồng Đã tất toán và tổng tiền còn khoá bằng 0.", "Critical",
     ["ST-BF04-010", "ST-BF04-020", "ST-BF04-030", "ST-BF04-040", "ST-BF04-050"], "", "BF04_MilestoneApi"),
    ("SC-07", "BF-04", "Tranh chấp chất lượng và giám định Level 1.5", "Buyer, Seller, Inspector, Operator",
     "1. Người mua báo hàng không đạt (độ ẩm vượt mức).\n"
     "2. Người bán phản hồi không đồng ý.\n"
     "3. Operator phân công giám định viên.\n"
     "4. Giám định viên đăng nhập lại và nộp kết quả.\n"
     "5. Hai bên xem quyết định và chờ hết 2 ngày làm việc khiếu nại.",
     "Đợt hàng chuyển Đang tranh chấp rồi quyết toán theo kết quả giám định; khoản bồi thường chỉ thực hiện sau "
     "khi hết hạn khiếu nại.", "High", [], "chưa có test qua giao diện cho nhánh tranh chấp", "BF04_MilestoneApi"),
    ("SC-08", "BF-05", "Báo vi phạm và phán quyết", "Buyer, Arbitrator",
     "1. Người mua báo người bán vi phạm hợp đồng, kèm bằng chứng.\n"
     "2. Arbitrator mở hồ sơ vi phạm, yêu cầu giám định nếu cần.\n"
     "3. Arbitrator ra quyết định quy lỗi.\n"
     "4. Hết hạn khiếu nại, hai bên xem tiền phạt và điểm uy tín.",
     "Chỉ quyết định quy lỗi mới gây phạt tiền và khoá uy tín; một lời cáo buộc chưa được xử không ảnh hưởng gì.",
     "High", [], "chưa có màn hình báo vi phạm; hàng đợi của Arbitrator mở được (ST-ARB-010)", "BF05_BreachApi"),
    ("SC-09", "BF-05", "Xin miễn trách nhiệm do bất khả kháng", "Seller, Arbitrator",
     "1. Người bán báo bất khả kháng cho một đợt (lũ lụt), trong 7 ngày.\n"
     "2. Arbitrator xem bằng chứng và chấp nhận hoặc từ chối.",
     "Chấp nhận: đợt được xử lý không phạt; từ chối: đợt tiếp tục như cũ.", "Medium", [],
     "chưa có màn hình bất khả kháng", "BF05_BreachApi"),
    ("SC-10", "BF-06", "Chấm dứt theo thoả thuận hoặc thay hợp đồng", "Buyer, Seller",
     "1. Một bên đề xuất chấm dứt theo thoả thuận (hoặc đề xuất hợp đồng thay thế).\n"
     "2. Bên kia chấp nhận.\n"
     "3. Hai bên xem trạng thái hợp đồng và tiền còn khoá.",
     "Hợp đồng Đã chấm dứt (hoặc Đã thay thế); mọi khoản còn khoá được trả về chủ, không bên nào bị phạt.",
     "Medium", [], "chưa có controller và màn hình (#242, #243)", "BF06_TerminationApi"),
    ("SC-11", "BF-07", "Khoá khẩn cấp mọi giao dịch tiền", "External Verifier, Buyer, Admin",
     "1. External Verifier gửi yêu cầu khoá có chữ ký.\n"
     "2. Người mua thử cấp tiền cho một đợt.\n"
     "3. Admin thử mở khoá.\n"
     "4. External Verifier gửi yêu cầu mở khoá.",
     "Trong lúc khoá mọi chuyển động tiền bị từ chối và màn hình nói rõ lý do; Admin không mở được; sau khi mở "
     "khoá giao dịch chạy lại bình thường.", "High", [], "chưa có màn hình khoá khẩn cấp", "BF07_SecurityLockApi"),
]

UI = {}
report = json.load(open(UI_RESULTS, encoding="utf-8"))


def _walk(suite):
    for spec in suite.get("specs", []):
        for t in spec["tests"]:
            UI[spec["title"].split(" ")[0]] = t["results"][-1]["status"]
    for child in suite.get("suites", []):
        _walk(child)


for s in report["suites"]:
    _walk(s)


def it_round3(book):
    """Round 3 counts of each Bruno e2e sheet of Report 5.2"""
    wb = openpyxl.load_workbook(book, read_only=True, data_only=True)
    out = {}
    for ws in wb.worksheets:
        if not ws.title.startswith(("BF0", "SecurityApi")):
            continue
        counts = {}
        for row in ws.iter_rows(min_row=12, max_col=12, values_only=True):
            if isinstance(row[0], str) and row[0].startswith("ITC") and row[11]:
                counts[row[11]] = counts.get(row[11], 0) + 1
        out[ws.title] = ", ".join(f"{counts[k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if counts.get(k))
    return out


IT = it_round3(IT_BOOK)
WEEKEND = ("28/11/2026 là thứ Bảy: không có ngày khoá tiền đợt hợp lệ (D63, D67) nên chữ ký thứ 2 → kích hoạt → "
           "giao nhận tự skip, chạy lại ngày làm việc")


def result(tests, gap, it_sheet):
    it = f"API cùng luồng (Report 5.2, {it_sheet}, Round 3): {IT[it_sheet]}."
    if not tests:
        return "Not Executed", f"Chưa chạy được qua giao diện: {gap}. {it}"
    got = {t: UI[t] for t in tests}
    passed = [t for t, s in got.items() if s == "passed"]
    failed = [t for t, s in got.items() if s in ("failed", "timedOut")]
    skipped = [t for t, s in got.items() if s == "skipped"]
    text = (f"Chạy tự động bằng Playwright qua giao diện trên stack thật, 28/11/2026 (Chromium; Report 5.3): "
            f"{len(passed)}/{len(tests)} bước pass ({', '.join(passed) or 'không có'}).")
    if failed:
        text += f" Fail: {', '.join(failed)}."
    if skipped:
        text += f" Chưa chạy: {', '.join(skipped)} ({WEEKEND})."
    if gap:
        text += f" Lưu ý: {gap}."
    verdict = "Fail" if failed else "Partial" if (skipped or gap) else "Pass"
    if not passed and not failed:
        verdict = "Not Executed"
    return verdict, text + (f" {it}" if verdict != "Pass" else "")


wb = openpyxl.load_workbook(BOOK)
uat = wb["UAT"]
style = [copy(uat.cell(2, c)._style) for c in range(1, 10)]
for row in uat.iter_rows(min_row=2, max_row=max(uat.max_row, 300)):
    for cell in row:
        cell.value = None
verdicts = {}
for i, (sid, bf, title, actor, steps, outcome, priority, tests, gap, it_sheet) in enumerate(SCRIPTS, start=2):
    verdict, text = result(tests, gap, it_sheet)
    verdicts[verdict] = verdicts.get(verdict, 0) + 1
    for c, v in enumerate([sid, bf, title, actor, steps, outcome, priority, verdict, text], 1):
        uat.cell(i, c, v)._style = copy(style[c - 1])
uat.column_dimensions["E"].width = 60
uat.column_dimensions["F"].width = 44
uat.column_dimensions["I"].width = 44
ex = wb["Exploratory"]
for row in ex.iter_rows(min_row=2, max_row=max(ex.max_row, 300)):
    for cell in row:
        cell.value = None
wb.save(BOOK)
print(f"{len(SCRIPTS)} UAT scripts", verdicts)
