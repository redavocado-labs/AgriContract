# Acceptance test cases (ATC) of Report 5.5 (#677): one sheet per role, one row = one user-visible check on a screen.
# Screen numbers and names are those of SRS §I.5.1 b (Screen List); only screens that have a route in
# frontend/src/App.tsx are listed.
#
# Two kinds of row:
#   AUTO    a Playwright test of frontend/tests/qa; "test" is its exact title, the round results are read from the
#           Playwright run of each round (playwright_results.py). The suite stubs the API in the browser.
#   MANUAL  a check by hand on the demo stack (docker compose, seeded accounts of Report 5.0 §II.1.1); it is not in
#           the automated rounds (N/A there) and stays Pending in the acceptance round until a tester runs it.
#
# Case: (kind, test title or None, input / steps, expected return, type)
AUTO, MANUAL = "AUTO", "MANUAL"
KIND_LABEL = {
    AUTO: "Tự động: Playwright (frontend/tests/qa), API giả lập trong browser",
    MANUAL: "Kiểm tay trên demo stack (docker compose, seed Report 5.0 §II.1.1)",
}

GUEST = [
    ("#26 Market Price Board (/prices)", [
        (AUTO, "FE-016 public prices expose a keyboard-accessible route without serious axe findings",
         "Khách chưa đăng nhập; mở /prices; chọn tỉnh chỉ bằng bàn phím; quét axe",
         "Bảng giá COFFEE hiện theo tỉnh đã chọn, thao tác được hoàn toàn bằng bàn phím; axe không có lỗi serious/critical",
         "Normal"),
        (MANUAL, None, "Khách chưa đăng nhập; mở /prices",
         "Thấy giá tham khảo theo commodity và tỉnh kèm ngày lấy giá; không cần đăng nhập", "Normal"),
    ]),
    ("Trang chủ (/)", [
        (AUTO, "FE-016 the public home page reflows at 320 CSS pixels",
         "Khách chưa đăng nhập; viewport rộng 320 CSS px; mở /",
         "Trang reflow trong 320 px: chiều rộng nội dung <= 320, không có thanh cuộn ngang (WCAG 2.2 1.4.10)",
         "Boundary"),
    ]),
    ("#23 Browse Listings (/listing), #24 Listing Detail (/listings/:id)", [
        (MANUAL, None, "Khách chưa đăng nhập; mở /listing; lọc theo commodity COFFEE; mở 1 listing",
         "Danh sách chỉ có listing đang mở bán; chi tiết listing hiện thông tin người bán và giá tham khảo; "
         "nút tạo offer yêu cầu đăng nhập", "Normal"),
    ]),
    ("#1 Register Account (/register), #2 Login (/login)", [
        (MANUAL, None, "Khách bấm Đăng ký, chọn vai trò Buyer, điền form của Keycloak",
         "Chuyển sang trang đăng ký của Keycloak; đăng ký xong quay về web với vai trò Buyer và được dẫn tới "
         "onboarding", "Normal"),
        (MANUAL, None, "Đăng nhập buyer1 / sai mật khẩu", "Keycloak báo sai thông tin đăng nhập, không vào được web",
         "Abnormal"),
    ]),
]

BUYER = [
    ("#5 Organization Onboarding (/onboarding), #6 My Profile (/account)", [
        (MANUAL, None, "Tài khoản Buyer mới đăng ký; điền thông tin tổ chức; nhập OTP gửi qua email (MailHog)",
         "Hồ sơ tạo ở trạng thái chờ KYC; email được xác minh; /account hiện trạng thái KYC PENDING", "Normal"),
    ]),
    ("#28 Create Offer (/contracts/new)", [
        (AUTO, "FE-007 an eligible buyer can select a reservable source and receives that listing's offer form",
         "BUYER đã VERIFIED; mở /contracts/new; danh sách listing có AVAILABLE, PARTIALLY_COMMITTED, CLOSED, "
         "dưới mức tối thiểu và listing cũ không có tồn kho",
         "Chỉ hiện listing AVAILABLE và PARTIALLY_COMMITTED (kể cả tồn kho đúng bằng mức tối thiểu); chọn 1 listing "
         "thì listingId lên URL và form offer hiện giá mới nhất của server", "Normal"),
        (MANUAL, None, "buyer1; từ chi tiết listing cà phê bấm Tạo offer; nhập số lượng, giá, lịch milestone; gửi",
         "Hợp đồng mới ở trạng thái chờ ký, hiện trong /contracts; listing giảm lượng còn lại", "Normal"),
    ]),
    ("#29 My Contracts (/contracts), #30 Contract Detail, #32-33 Sign Contract / OTP", [
        (MANUAL, None, "buyer1; mở hợp đồng vừa tạo; bấm Ký; đăng nhập lại; nhập OTP từ MailHog",
         "Chữ ký của Buyer được ghi; hợp đồng chờ Seller ký", "Normal"),
        (MANUAL, None, "buyer1; nhập sai OTP", "Báo OTP sai, chưa ký; được nhập lại hoặc xin mã mới", "Abnormal"),
    ]),
    ("#36 Milestone Detail, #38 Record Receipt (/contracts/:id/milestones/:milestoneId)", [
        (AUTO, "FE-011 a Buyer cannot weigh as Seller, and an HTTP 403 hides milestone data",
         "BUYER mở milestone; API trả 403 khi đọc lại milestone",
         "Buyer không có thao tác cân của Seller; sau 403 dữ liệu milestone bị ẩn, không hiện dữ liệu cũ", "Abnormal"),
        (MANUAL, None, "buyer1; milestone Seller đã cân; tải ảnh bằng chứng; nhập lượng nhận; xác nhận",
         "Ghi nhận lượng nhận; milestone chuyển sang xác nhận sạch rồi quyết toán", "Normal"),
    ]),
    ("#7 Public User Profile (/reputation/:userId)", [
        (MANUAL, None, "buyer1; từ hợp đồng mở hồ sơ công khai của seller1",
         "Thấy trạng thái xác minh, điểm uy tín, lịch sử khoá; không thấy email/số điện thoại", "Normal"),
    ]),
]

SELLER = [
    ("#30 Contract Detail (/contracts/:id): rút offer", [
        (AUTO, "FE-016 an offer withdrawal dialog can be opened, traversed and dismissed by keyboard",
         "Hợp đồng chờ ký; mở hộp thoại Rút offer chỉ bằng bàn phím; Tab qua các nút; Esc",
         "Focus vào hộp thoại khi mở, Tab đi vòng trong hộp thoại, Esc đóng và trả focus về nút mở", "Normal"),
        (AUTO, "FE-016 contract detail with the withdrawal dialog open has no serious axe findings",
         "Hợp đồng chờ ký; mở hộp thoại Rút offer; quét axe", "axe không có lỗi serious/critical", "Normal"),
        (AUTO, "FE-003 a lost withdraw response reconciles across a second tab and reload without replay",
         "Bấm Rút offer; server ghi nhận nhưng mất response; mở tab thứ 2 và reload",
         "Web đọc lại hợp đồng, hiện WITHDRAWN; không gửi lại lệnh rút", "Abnormal"),
        (AUTO, "FE-003 an uncertain withdrawal survives reload and reuses its key after the authoritative read",
         "Lệnh rút chưa rõ kết quả; reload; đọc lại hợp đồng; bấm thử lại",
         "Sau reload vẫn nhớ lệnh rút đang dở; chỉ gửi lại sau khi đọc trạng thái, dùng lại đúng Idempotency-Key cũ",
         "Abnormal"),
        (AUTO, "FE-003 concurrent tabs submit at most one withdrawal while the first response is pending",
         "2 tab cùng bấm Rút offer khi lệnh đầu chưa có response", "Chỉ 1 lệnh rút được gửi", "Abnormal"),
        (AUTO, "FE-003 an expired unresolved withdrawal is never sent under a fresh key",
         "Lệnh rút chưa rõ kết quả đã quá thời hạn khôi phục",
         "Không gửi lệnh rút với key mới; người dùng được báo đọc lại trạng thái", "Abnormal"),
        (AUTO, "FE-003 a failed reconciliation read leaves an uncertain withdrawal unresolved",
         "Lệnh rút chưa rõ kết quả; API đọc lại hợp đồng lỗi",
         "Lệnh rút vẫn ở trạng thái chưa rõ, không gửi thêm lệnh ghi", "Abnormal"),
    ]),
    ("#30 Contract Detail (/contracts/:id): chờ kích hoạt", [
        (AUTO, "FE-004 begins polling when SIGNED follows a late manual read",
         "Hợp đồng chuyển sang SIGNED sau khi người dùng bấm tải lại",
         "Web bắt đầu tự đọc lại mỗi 5 s cho đến ACTIVE", "Normal"),
        (AUTO, "FE-004 uses a bounded budget for initially SIGNED and permits manual recovery",
         "Hợp đồng SIGNED, không lên ACTIVE trong 120 s",
         "Ngừng tự đọc sau 120 s, hiện nút tải lại tay; bấm thì đọc lại được", "Boundary"),
        (AUTO, "FE-004 recovers from a failed activation read without declaring activation",
         "Một lần đọc hợp đồng bị lỗi trong lúc chờ ACTIVE", "Không báo đã kích hoạt; lần đọc sau tiếp tục bình thường",
         "Abnormal"),
        (AUTO, "FE-004 switching contract IDs gets a fresh activation budget and stops polling the old resource",
         "Đang chờ ACTIVE thì chuyển sang hợp đồng khác",
         "Ngừng đọc hợp đồng cũ; hợp đồng mới có thời hạn chờ 120 s riêng", "Normal"),
    ]),
    ("#36 Milestone Detail, #37 Record Weighing: bằng chứng và cân", [
        (AUTO, "FE-015 blocks evidence while the read model omits it, then enables review only after READY",
         "Tải ảnh bằng chứng; read model chưa có file; sau đó file READY",
         "Nút ghi cân bị khoá khi chưa thấy file; chỉ bật khi file READY", "Normal"),
        (AUTO, "FE-015 a rejected scan offers replacement and leaves an independent file usable",
         "1 file quét virus FAILED, 1 file khác READY",
         "File FAILED có nút thay file, không có nút thử lại vô ích; file READY vẫn dùng được", "Abnormal"),
        (AUTO, "FE-015 a projected PROCESSING file stops automatic reads after its 60-second budget",
         "File ở PROCESSING quá 60 s", "Ngừng tự đọc sau 60 s; có nút kiểm tra lại tay, bấm thì thấy READY",
         "Boundary"),
        (AUTO, "FE-015 an explicit retry after evidence upload 503 keeps the original key and bytes",
         "Upload bằng chứng bị 503; người dùng bấm thử lại",
         "Lần thử lại gửi đúng Idempotency-Key và nội dung file cũ", "Abnormal"),
        (AUTO, "FE-010 counts down the server's seller correction deadline and removes the edit action",
         "Seller đã cân; thời hạn sửa cân của server sắp hết",
         "Đếm ngược theo thời hạn của server; hết hạn thì nút sửa cân biến mất", "Boundary"),
        (AUTO, "FE-011 switching user identity does not display the previous actor's cached milestone",
         "Seller xem milestone rồi đổi danh tính sang Buyer khác trong app",
         "Không hiện dữ liệu milestone đã cache của người trước", "Abnormal"),
        (MANUAL, None, "seller1; hợp đồng ACTIVE đã cấp tiền milestone; tải phiếu cân; nhập lượng cân; đồng ý",
         "Ghi nhận lượng cân của Seller; Buyer nhận email báo đã cân", "Normal"),
    ]),
    ("#32-33 Sign Contract / OTP Verification", [
        (MANUAL, None, "seller1; hợp đồng Buyer đã ký; ký bằng đăng nhập lại + OTP",
         "Hợp đồng SIGNED, cọc 2 bên được khoá rồi lên ACTIVE", "Normal"),
    ]),
]

OPERATOR = [
    ("#51-52 Activation / Funding Failures (/operations/remedy)", [
        (MANUAL, None, "operator1; mở /operations/remedy", "Thấy danh sách khoá cọc hoặc cấp tiền milestone bị lỗi, có nút thử lại",
         "Normal"),
        (MANUAL, None, "buyer1 mở /operations/remedy", "Bị chặn vì không có vai trò OPERATOR", "Abnormal"),
    ]),
]

ARBITRATOR = [
    ("#44 Breach Case Queue (/disputes), #45 Breach Case Detail (/disputes/:breachCaseId)", [
        (MANUAL, None, "arbitrator1; mở /disputes; mở vụ tranh chấp chất lượng của BF-04",
         "Thấy vụ chưa xử lý, bằng chứng và kết quả giám định Level 1.5", "Normal"),
        (MANUAL, None, "seller1 mở /disputes", "Bị chặn vì không có vai trò ARBITRATOR", "Abnormal"),
    ]),
]

ADMIN = [
    ("#59 Audit Chain Verification Status, #60 Contract Evidence Export (/admin/audit)", [
        (MANUAL, None, "admin1; mở /admin/audit; kiểm chuỗi audit của 1 hợp đồng đã quyết toán",
         "Chuỗi audit hợp lệ; xem được bản ghi của hợp đồng", "Normal"),
        (MANUAL, None, "operator1 mở /admin/audit", "Bị chặn vì không có vai trò ADMIN", "Abnormal"),
    ]),
]

MODULES = [
    dict(module="Guest", sheet="GuestUI", groups=GUEST,
         desc="Khách chưa đăng nhập: trang chủ, bảng giá, danh sách listing, đăng ký và đăng nhập (BF-01, BF-02)",
         pre="Không đăng nhập; trình duyệt Chromium"),
    dict(module="Buyer", sheet="BuyerUI", groups=BUYER,
         desc="Buyer: onboarding, tạo offer, ký, nhận hàng và xem hồ sơ đối tác (BF-01, BF-03, BF-04)",
         pre="buyer1 (VERIFIED) hoặc Buyer mới đăng ký; Playwright: phiên BUYER giả lập"),
    dict(module="Seller", sheet="SellerUI", groups=SELLER,
         desc="Seller: rút offer, chờ kích hoạt, bằng chứng và cân ở milestone, ký (BF-03, BF-04)",
         pre="seller1 (VERIFIED); Playwright: phiên SELLER giả lập"),
    dict(module="Operator", sheet="OperatorUI", groups=OPERATOR,
         desc="Operator: xử lý khoá cọc và cấp tiền milestone bị lỗi (BF-03, BF-04)", pre="operator1"),
    dict(module="Arbitrator", sheet="ArbitratorUI", groups=ARBITRATOR,
         desc="Arbitrator: hàng đợi và chi tiết vụ tranh chấp (BF-04, BF-05)", pre="arbitrator1; có vụ tranh chấp từ BF-04"),
    dict(module="Admin", sheet="AdminUI", groups=ADMIN,
         desc="Admin: kiểm chuỗi audit và xem bằng chứng hợp đồng", pre="admin1; có hợp đồng đã quyết toán"),
]

# Failed automated rows -> defect of Report 5.6 (#670)
DEFECTS = {
    "FE-016 the public home page reflows at 320 CSS pixels": "DEF-09",
    "FE-003 an uncertain withdrawal survives reload and reuses its key after the authoritative read": "DEF-09",
    "FE-015 an explicit retry after evidence upload 503 keeps the original key and bytes": "DEF-10",
}
