# System test cases through the web client (Report 5.3 from 28/11/2026): one entry per Playwright test of
# tests/st/*.st.ts. The key is the test-title prefix; results come from results/st-ui/results-chromium.json.
# Steps the web client has no screen for yet (Operator KYC decision, seller catalogue) go through the API and say so.

SHEETS = [
    # sheet, module, description (Test Cases), pre-condition (Test Cases)
    ("BF01_Onboarding", "BF01",
     "BF-01 User Onboarding & KYC qua giao diện: đăng ký trên trang Keycloak (chọn vai trò), hoàn tất hồ sơ doanh "
     "nghiệp, xác minh email bằng mã trong thư, xem kết quả KYC; quyết định KYC của Operator gọi API (chưa có màn hình)",
     "Stack chạy đủ; email mới mỗi lần chạy (st-buyer-<ms>@test.com, st-seller-<ms>@test.com); MailHog 8025"),
    ("BF02_Marketplace", "BF02",
     "BF-02 Plot & Listing Publication nhìn từ sàn: seller đăng listing cà phê (API, chưa có màn hình seller), Guest "
     "tìm và đọc listing không cần đăng nhập, bấm Gửi đề nghị thì được đưa đi đăng nhập",
     "seller1 VERIFIED (seed); product-service kiểm tra geo ngay (overlay PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0)"),
    ("BF03_Signing", "BF03",
     "BF-03 Contract Signing & Activation qua giao diện: buyer gửi đề nghị từ trang listing, seller sửa đơn giá, 2 bên "
     "ký bằng OTP trong thư, escrow khoá cọc thì hợp đồng ACTIVE; nhánh rút đề nghị trước chữ ký thứ 2",
     "buyer1, seller1 VERIFIED có tài khoản ngân hàng mock; listing 5.000 kg giá 96.000 ₫/kg tạo mới cho mỗi luồng"),
    ("BF04_Milestone", "BF04",
     "BF-04 Milestone Delivery & Settlement qua giao diện, nối tiếp chuỗi BF-03 (cùng hợp đồng, cùng phiên): seller cân "
     "kèm ảnh, buyer cân nhận hàng và xác nhận đạt, đợt và hợp đồng tất toán",
     "Hợp đồng ACTIVE của ST-BF03-060 với 1 đợt 1.000 kg đã khoá tiền lúc kích hoạt (chỉ được vào ngày làm việc)"),
    ("RolePortals", "Roles",
     "Màn hình của Admin, Operator, Arbitrator và cách ly vai trò không fallback (data-governance §5.4): nhật ký bằng "
     "chứng + kiểm tra chuỗi hash, gửi lại lệnh tiền, hàng đợi tranh chấp, chặn route sai vai trò",
     "admin1, operator1, arbitrator1, buyer1 (Report 5.0 §II.1.1); ADM-020 tạo và rút 1 đề nghị qua API để có trail"),
]

PRE_STACK = "ST qua giao diện web (Playwright Chromium 1366x900, đăng nhập trên trang Keycloak thật)"

# test-title prefix -> sheet, group, pre-condition, steps, expected, type, observed when passed
CASES = [
    # ── BF-01 ──
    ("ST-BF01-010", "BF01_Onboarding", "Buyer đăng ký và được duyệt", "Guest chưa có tài khoản",
     "/register → Đăng ký → trang Keycloak: Email st-buyer-<ms>@test.com, vai trò Người mua, mật khẩu đúng chính sách "
     "realm → Tạo tài khoản",
     "Về /onboarding, tiêu đề 'Hoàn tất hồ sơ doanh nghiệp', vai trò Bên mua đã chọn sẵn", "Normal",
     "Về /onboarding, form hồ sơ hiện với Bên mua đã chọn"),
    ("ST-BF01-020", "BF01_Onboarding", None, "Chạy sau ST-BF01-010",
     "Tên doanh nghiệp, Số điện thoại 0901000111, Địa chỉ → Gửi hồ sơ doanh nghiệp",
     "Về /account, banner 'Hồ sơ đang chờ Operator xác minh', trạng thái Chờ xác minh", "Normal",
     "/account hiện banner chờ xác minh, trạng thái Chờ xác minh"),
    ("ST-BF01-030", "BF01_Onboarding", None, "Chạy sau ST-BF01-020",
     "Đọc mã 6 số trong thư 'email verification code' ở MailHog, gõ vào ô Mã xác minh email → Xác minh email",
     "Mục Email nhận OTP chuyển Đã xác minh", "Normal", "Email nhận OTP: Đã xác minh"),
    ("ST-BF01-040", "BF01_Onboarding", None, "Chạy sau ST-BF01-030",
     "Operator xác minh KYC qua API (POST /api/v1/admin/users/{id}/verify, chưa có màn hình duyệt); buyer tải lại /account",
     "Banner chờ xác minh biến mất, trạng thái hồ sơ Đã xác minh", "Normal",
     "Trong 60 s banner biến mất, hồ sơ Đã xác minh"),
    ("ST-BF01-050", "BF01_Onboarding", None, "Chạy sau ST-BF01-040",
     "Tìm thư gửi tới email của buyer trên MailHog",
     "Có thư báo kết quả KYC ngoài thư mã xác minh email", "Normal", "Có thư kết quả KYC trong MailHog"),
    ("ST-BF01-060", "BF01_Onboarding", "Seller đăng ký và bị từ chối", "Guest chưa có tài khoản",
     "Đăng ký vai trò Người bán, gửi hồ sơ, xác minh email bằng mã trong thư",
     "Vai trò Bên bán đã chọn; sau khi gửi hồ sơ trạng thái Chờ xác minh", "Normal",
     "Bên bán được chọn, trạng thái Chờ xác minh"),
    ("ST-BF01-070", "BF01_Onboarding", None, "Chạy sau ST-BF01-060",
     "Operator từ chối KYC qua API với lý do 'Thiếu giấy phép kinh doanh'; seller tải lại /account",
     "Màn hình báo 'Hồ sơ chưa được chấp thuận', trạng thái Chưa được chấp thuận; lý do nằm trong thư gửi seller "
     "(SRS BF-01: kết quả gửi bằng email)", "Abnormal",
     "Hiện Hồ sơ chưa được chấp thuận; thư từ chối chứa đúng lý do"),
    # ── BF-02 ──
    ("ST-BF02-010", "BF02_Marketplace", "Listing đăng lên sàn", "seller1 VERIFIED",
     "API: category COFFEE (Operator duyệt nếu chưa có), product Robusta ST <id>, lô đất Đắk Lắk (chờ kiểm tra geo), "
     "listing 5.000 kg, tối thiểu 100 kg, 96000.00 VND/kg",
     "Listing được tạo, có listingId", "Normal", "listingId trả về đúng dạng UUID"),
    ("ST-BF02-020", "BF02_Marketplace", None, "Chạy sau ST-BF02-010; trình duyệt chưa đăng nhập",
     "Mở /listing", "Tiêu đề 'Sàn nguồn hàng', có ít nhất 1 thẻ listing", "Normal", "Sàn hiện danh sách listing"),
    ("ST-BF02-030", "BF02_Marketplace", None, "Chạy sau ST-BF02-020",
     "Mở /listings/{listingId}",
     "Tên sản phẩm, Đang mở bán, 96.000 ₫/kg, 5.000 kg, Robusta; không có email, tên đăng nhập hay số điện thoại của "
     "seller (SRS IV.2.3)", "Normal", "Đủ thông tin listing, không lộ thông tin liên hệ"),
    ("ST-BF02-040", "BF02_Marketplace", None, "Chạy sau ST-BF02-030",
     "Guest bấm Gửi đề nghị", "Chuyển sang đăng nhập (trang Keycloak hoặc /login)", "Abnormal",
     "Chuyển sang trang đăng nhập"),
    # ── BF-03 + BF-04 (one serial chain) ──
    ("ST-BF03-010", "BF03_Signing", "Đề nghị → thương lượng → ký → ACTIVE", "buyer1, seller1 đã đăng nhập; listing mới",
     "buyer1: /listings/{id} → Gửi đề nghị → Khối lượng 1000, Ngày giao = hôm nay + 7 ngày làm việc (ngày khoá tiền "
     "đợt = hôm nay) → Gửi đề nghị",
     "Sang /contracts/{id}, Chi tiết hợp đồng, trạng thái Đã gửi đề nghị", "Normal",
     "Hợp đồng tạo, trạng thái Đã gửi đề nghị"),
    ("ST-BF03-020", "BF03_Signing", None, "Chạy sau ST-BF03-010",
     "Mở lại trang listing", "Khối lượng khả dụng còn 4.000 kg (giữ hàng theo event)", "Normal",
     "Khả dụng 4.000 kg"),
    ("ST-BF03-030", "BF03_Signing", None, "Chạy sau ST-BF03-020",
     "seller1 mở hợp đồng → Đơn giá đề xuất 95000.00 → Xem lại thay đổi → Lưu điều khoản",
     "'Đã lưu điều khoản mới', đơn giá 95.000 ₫, dấu vân tay điều khoản đổi", "Normal",
     "Lưu được, giá 95.000 ₫, vân tay điều khoản đổi"),
    ("ST-BF03-040", "BF03_Signing", None, "Chạy sau ST-BF03-030",
     "buyer1: đánh dấu đồng ý → Gửi mã OTP → đọc mã trong thư 'contract signing code' (thư chứa contractId) → nhập → "
     "Xác nhận chữ ký",
     "'Máy chủ đã xác nhận chữ ký của bạn'", "Normal", "Chữ ký buyer được xác nhận"),
    ("ST-BF03-050", "BF03_Signing", None, "Chạy sau ST-BF03-040; hôm nay là ngày làm việc",
     "seller1 ký bằng OTP như ST-BF03-040", "Chữ ký seller được xác nhận, hợp đồng SIGNED", "Normal", None),
    ("ST-BF03-060", "BF03_Signing", None, "Chạy sau ST-BF03-050",
     "Chờ escrow khoá cọc, tải lại trang hợp đồng của 2 bên", "Cả 2 bên thấy Đang hiệu lực", "Normal", None),
    ("ST-BF04-010", "BF04_Milestone", "Đợt sạch: cân → nhận → xác nhận → tất toán", "Hợp đồng ACTIVE của ST-BF03-060",
     "Mở hợp đồng", "Có Đợt 1 (1 đợt duy nhất), trạng thái Đã khoá tiền đợt hoặc Đang chuẩn bị hàng", "Normal", None),
    ("ST-BF04-020", "BF04_Milestone", None, "Chạy sau ST-BF04-010",
     "seller1: trang đợt → Cân hàng trước khi xe chạy: 1000 kg, ảnh cân (chờ quét virus Sẵn sàng) → Xem lại trước khi "
     "gửi → checkbox đồng ý → Xác nhận đã cân",
     "Đợt chuyển Đã cân, đang vận chuyển", "Normal", None),
    ("ST-BF04-030", "BF04_Milestone", None, "Chạy sau ST-BF04-020",
     "buyer1: trang đợt thấy số cân 1.000 của seller → cân nhận 998 kg + ảnh → Ghi nhận cân nhận hàng",
     "Đợt chuyển Đã nhận, chờ xác nhận", "Normal", None),
    ("ST-BF04-040", "BF04_Milestone", None, "Chạy sau ST-BF04-030 (lệch 0,2% < dung sai 5%)",
     "buyer1: Hàng đạt, xác nhận đúng → checkbox → Xác nhận đúng và tất toán", "Đợt Đã tất toán", "Normal", None),
    ("ST-BF04-050", "BF04_Milestone", None, "Chạy sau ST-BF04-040",
     "Tải lại trang hợp đồng của 2 bên",
     "Hợp đồng Đã tất toán sau khi escrow trả cọc và báo contract_lock_cleared (D94)", "Normal", None),
    ("ST-BF03-110", "BF03_Signing", "Rút đề nghị trước chữ ký thứ 2", "buyer1 đã đăng nhập; listing mới",
     "Gửi đề nghị 1000 kg, ngày giao hôm nay + 12 ngày làm việc", "Khả dụng còn 4.000 kg", "Normal",
     "Hợp đồng tạo, khả dụng 4.000 kg"),
    ("ST-BF03-120", "BF03_Signing", None, "Chạy sau ST-BF03-110",
     "Rút đề nghị → hộp thoại 'Rút đề nghị hợp đồng?' → Rút đề nghị", "Trạng thái Đã rút đề nghị", "Normal",
     "Trạng thái Đã rút đề nghị"),
    ("ST-BF03-130", "BF03_Signing", None, "Chạy sau ST-BF03-120",
     "Mở lại trang listing; đọc hợp đồng qua API", "Khả dụng về 5.000 kg; hợp đồng WITHDRAWN", "Normal",
     "Khả dụng 5.000 kg, status WITHDRAWN"),
    # ── role portals ──
    ("ST-ADM-010", "RolePortals", "Admin: nhật ký bằng chứng", "admin1 đăng nhập",
     "/admin/audit → Kiểm tra toàn chuỗi",
     "Tiêu đề Nhật ký bằng chứng, câu báo mỗi lần mở được ghi lại; kết quả 'Toàn chuỗi nguyên vẹn'", "Normal",
     "Toàn chuỗi nguyên vẹn"),
    ("ST-ADM-020", "RolePortals", None, "Một đề nghị vừa tạo và rút qua API (có trail contract.terminated)",
     "Tab Theo hợp đồng → Mã hợp đồng (UUID) → Tra cứu",
     "Hiện Mã bằng chứng hiện tại và Nguyên vẹn; nhật ký SYSTEM có bản ghi audit.viewed cho đúng hợp đồng", "Normal",
     "Trail nguyên vẹn, có audit.viewed"),
    ("ST-OPR-010", "RolePortals", "Operator: gửi lại lệnh tiền", "operator1 đăng nhập",
     "/operations/remedy → Mã hợp đồng 'not-a-uuid', rồi 1 UUID ngẫu nhiên → Gửi lại lệnh tiền lỗi",
     "Mã sai: báo 'Nhập mã hợp đồng đầy đủ.' và nút bị khoá; UUID không có lệnh lỗi: hiện thông báo kết quả",
     "Abnormal", "Chặn mã sai; UUID lạ trả thông báo"),
    ("ST-ARB-010", "RolePortals", "Arbitrator: hàng đợi tranh chấp", "arbitrator1 đăng nhập",
     "/disputes", "Tiêu đề Tranh chấp chờ duyệt; danh sách vụ hoặc trạng thái rỗng", "Normal",
     "Hàng đợi hiện từ backend thật"),
    ("ST-SEC-010", "RolePortals", "Cách ly vai trò", "admin1, buyer1 đăng nhập",
     "admin1 mở /disputes, /operations/remedy; buyer1 mở /admin/audit, /disputes, /operations/remedy",
     "Mọi route sai vai trò hiện 'Tài khoản chưa có vai trò phù hợp để truy cập trang này.' (ADMIN không thay "
     "OPERATOR/ARBITRATOR)", "Abnormal", "5 route đều bị chặn với đúng thông báo"),
]
