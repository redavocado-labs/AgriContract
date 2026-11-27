# Report 5.5 (acceptance test scripts) on the school template: one UAT script per business scenario of SRS I.2
# (BF-01..BF-07), written in business language for the business user who runs it on the demo stack. Result stays
# "Not Executed" until that user runs it; Feedback names the system test evidence of the same flow, read from
# Report 5.3 (Round 2 and Round 3 per flow sheet), so the reviewer sees what was already proven end to end.
# The Exploratory sheet keeps only its header: no exploratory session has been held yet. Pure openpyxl.
#
#   python fill_55_uat.py ../../docs/reports/Report-5.3_System_Test_FRs.xlsx \
#       "../../docs/reports/Report-5.5_Acceptance Test Scripts.xlsx"
import sys
from copy import copy

import openpyxl

ST_BOOK, BOOK = sys.argv[1:3]

SCRIPTS = [  # id, BF, title, actor, steps, outcome, priority, 5.3 sheet
    ("SC-01", "BF-01", "Người mua đăng ký và được duyệt KYC", "Guest → Buyer, Operator",
     "1. Khách mở trang Đăng ký, chọn vai trò Người mua, nhập email và thông tin doanh nghiệp.\n"
     "2. Mở email xác minh và bấm liên kết.\n"
     "3. Nộp hồ sơ KYC (giấy phép kinh doanh, người đại diện).\n"
     "4. Operator mở hàng chờ KYC, xem hồ sơ, bấm Xác minh.\n"
     "5. Người mua đăng nhập lại và xem trạng thái tài khoản.",
     "Tài khoản ở trạng thái Đã xác minh, người mua nhận email kết quả; trước khi được duyệt thì không tạo được "
     "offer và màn hình nói rõ lý do.", "Critical", "BF01_Onboarding"),
    ("SC-02", "BF-01", "Người bán bị từ chối KYC", "Guest → Seller, Operator",
     "1. Khách đăng ký vai trò Người bán và xác minh email.\n"
     "2. Nộp hồ sơ KYC thiếu giấy tờ.\n"
     "3. Operator mở hồ sơ, bấm Từ chối và ghi lý do.\n"
     "4. Người bán đăng nhập, thử đăng listing.",
     "Người bán nhận email từ chối có lý do; không đăng được listing; thông tin liên hệ không hiện ở trang công "
     "khai.", "High", "BF01_Onboarding"),
    ("SC-03", "BF-02", "Người bán đăng lô đất và listing cà phê", "Seller (đã xác minh), Guest",
     "1. Người bán thêm lô đất bằng ghim GPS (hoặc file KML).\n"
     "2. Chờ hệ thống kiểm tra rủi ro phá rừng của lô.\n"
     "3. Tạo sản phẩm Robusta và listing: số lượng, giá sàn, lô đất nguồn.\n"
     "4. Sửa số lượng và giá.\n"
     "5. Khách (chưa đăng nhập) mở chợ và xem listing.",
     "Listing ở trạng thái Đang bán, khách xem được giá và số lượng còn lại; lô đất gắn với listing có kết quả "
     "kiểm tra rừng 2020.", "Critical", "BF02_PlotListing"),
    ("SC-04", "BF-03", "Đàm phán, ký và kích hoạt hợp đồng", "Buyer, Seller",
     "1. Người mua chọn listing, tạo offer: số lượng, giá, lịch giao từng đợt, tỷ lệ cọc.\n"
     "2. Người bán xem offer, sửa đơn giá.\n"
     "3. Người mua đăng nhập lại, nhận mã OTP qua email, ký.\n"
     "4. Người bán đăng nhập lại, nhận OTP, ký.\n"
     "5. Hai bên mở chi tiết hợp đồng chờ khoá cọc.",
     "Hợp đồng chỉ Hiệu lực sau khi cọc hai bên được khoá (ký ≠ kích hoạt); số lượng của listing bị giữ đúng phần "
     "đã offer; đợt đầu được yêu cầu cấp tiền.", "Critical", "BF03_Signing"),
    ("SC-05", "BF-03", "Người mua rút offer trước chữ ký thứ hai", "Buyer",
     "1. Người mua tạo offer trên listing.\n"
     "2. Trước khi người bán ký, người mua bấm Rút offer và xác nhận.\n"
     "3. Kiểm tra lại listing và hợp đồng.",
     "Hợp đồng Đã rút; số lượng giữ được trả lại listing; không có khoản tiền nào bị khoá hay phạt.", "High",
     "BF03_Signing"),
    ("SC-06", "BF-04", "Giao nhận đợt hàng sạch và quyết toán", "Seller, Buyer",
     "1. Đợt hàng được cấp tiền (tiền người mua bị khoá).\n"
     "2. Người bán cân hàng, tải ảnh phiếu cân, ghi khối lượng.\n"
     "3. Người mua nhận hàng, tải bằng chứng, ghi khối lượng nhận.\n"
     "4. Người mua xác nhận hàng đạt.\n"
     "5. Hai bên xem sao kê ký quỹ.",
     "Đợt hàng Đã quyết toán, tiền trả người bán theo khối lượng thực nhận; khi mọi đợt xong, hợp đồng Đã quyết "
     "toán và tổng tiền còn khoá bằng 0.", "Critical", "BF04_Milestone"),
    ("SC-07", "BF-04", "Tranh chấp chất lượng và giám định Level 1.5", "Buyer, Seller, Inspector, Operator",
     "1. Người mua báo hàng không đạt (độ ẩm vượt mức).\n"
     "2. Người bán phản hồi không đồng ý.\n"
     "3. Operator phân công giám định viên.\n"
     "4. Giám định viên đăng nhập lại và nộp kết quả.\n"
     "5. Hai bên xem quyết định và chờ hết 2 ngày làm việc khiếu nại.",
     "Đợt hàng chuyển Đang tranh chấp rồi quyết toán theo kết quả giám định; khoản bồi thường chỉ thực hiện sau "
     "khi hết hạn khiếu nại.", "High", "BF04_Milestone"),
    ("SC-08", "BF-05", "Báo vi phạm và phán quyết", "Buyer, Arbitrator",
     "1. Người mua báo người bán vi phạm hợp đồng, kèm bằng chứng.\n"
     "2. Arbitrator mở hồ sơ vi phạm, yêu cầu giám định nếu cần.\n"
     "3. Arbitrator ra quyết định quy lỗi.\n"
     "4. Hết hạn khiếu nại, hai bên xem tiền phạt và điểm uy tín.",
     "Chỉ quyết định quy lỗi mới gây phạt tiền và khoá uy tín; một lời cáo buộc chưa được xử không ảnh hưởng gì.",
     "High", "BF05_Breach"),
    ("SC-09", "BF-05", "Xin miễn trách nhiệm do bất khả kháng", "Seller, Arbitrator",
     "1. Người bán báo bất khả kháng cho một đợt (lũ lụt), trong 7 ngày.\n"
     "2. Arbitrator xem bằng chứng và chấp nhận hoặc từ chối.",
     "Chấp nhận: đợt được xử lý không phạt; từ chối: đợt tiếp tục như cũ.", "Medium", "BF05_Breach"),
    ("SC-10", "BF-06", "Chấm dứt theo thoả thuận hoặc thay hợp đồng", "Buyer, Seller",
     "1. Một bên đề xuất chấm dứt theo thoả thuận (hoặc đề xuất hợp đồng thay thế).\n"
     "2. Bên kia chấp nhận.\n"
     "3. Hai bên xem trạng thái hợp đồng và tiền còn khoá.",
     "Hợp đồng Đã chấm dứt (hoặc Đã thay thế); mọi khoản còn khoá được trả về chủ, không bên nào bị phạt.",
     "Medium", "BF06_Termination"),
    ("SC-11", "BF-07", "Khoá khẩn cấp mọi giao dịch tiền", "External Verifier, Buyer, Admin",
     "1. External Verifier gửi yêu cầu khoá có chữ ký.\n"
     "2. Người mua thử cấp tiền cho một đợt.\n"
     "3. Admin thử mở khoá.\n"
     "4. External Verifier gửi yêu cầu mở khoá.",
     "Trong lúc khoá mọi chuyển động tiền bị từ chối và màn hình nói rõ lý do; Admin không mở được; sau khi mở "
     "khoá giao dịch chạy lại bình thường.", "High", "BF07_SecurityLock"),
]


def st_counts(book):
    wb = openpyxl.load_workbook(book, read_only=True, data_only=True)
    out = {}
    for ws in wb.worksheets[3:]:
        rounds = [{}, {}]
        for row in ws.iter_rows(min_row=12, max_col=12, values_only=True):
            if isinstance(row[0], str) and row[0].startswith("STC"):
                for i, col in enumerate((8, 11)):
                    if row[col]:
                        rounds[i][row[col]] = rounds[i].get(row[col], 0) + 1
        out[ws.title] = rounds
    return out


ST = st_counts(ST_BOOK)


def feedback(sheet):
    r2, r3 = ST[sheet]
    fmt = lambda c: ", ".join(f"{c[k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if c.get(k))
    return (f"Chưa chạy với người dùng nghiệp vụ. Bằng chứng system test cùng luồng (Report 5.3, sheet {sheet}): "
            f"Round 2 {fmt(r2)}; Round 3 {fmt(r3)}.")


wb = openpyxl.load_workbook(BOOK)
uat = wb["UAT"]
style = [copy(uat.cell(2, c)._style) for c in range(1, 10)]
for row in uat.iter_rows(min_row=2, max_row=max(uat.max_row, 300)):
    for cell in row:
        cell.value = None
for i, (sid, bf, title, actor, steps, outcome, priority, sheet) in enumerate(SCRIPTS, start=2):
    for c, v in enumerate([sid, bf, title, actor, steps, outcome, priority, "Not Executed", feedback(sheet)], 1):
        uat.cell(i, c, v)._style = copy(style[c - 1])
uat.column_dimensions["E"].width = 60
uat.column_dimensions["F"].width = 44
uat.column_dimensions["I"].width = 44
ex = wb["Exploratory"]
for row in ex.iter_rows(min_row=2, max_row=max(ex.max_row, 300)):
    for cell in row:
        cell.value = None
wb.save(BOOK)
print(f"{len(SCRIPTS)} UAT scripts")
