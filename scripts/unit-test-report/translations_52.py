# Line-by-line Vietnamese -> English translation dictionary for Report 5.2 (Report-5.2_Test_Report.xlsx).
# Keys are exact single lines (after splitting cell text on "\n") containing Vietnamese text.
# Quoted test input / seed strings stay in single quotes with their English meaning in brackets where needed;
# JSON response bodies (e.g. SecurityApi line with organizationName) are intentionally kept verbatim.

TRANSLATIONS = {
    # ── Cover ──
    "Toàn bộ tài liệu": "Whole document",
    (
        "Bản đầu: test case tích hợp (ITC) từ Bruno collection (backend/bruno) của api-gateway, user-service, "
        "product-service; Pre-conditions ghi gọi thẳng service hay qua gateway; 1 dòng = 1 kịch bản; Note ghi "
        "request Bruno, Note trống = chưa có request"
    ): (
        "First version: integration test cases (ITC) from the Bruno collection (backend/bruno) of api-gateway, "
        "user-service and product-service; Pre-conditions state whether the service is called directly or through "
        "the gateway; 1 row = 1 scenario; Note records the Bruno request (empty Note = no request yet)"
    ),
    (
        "Mọi case có request Bruno (48 case, 1 giá trị 1 dòng); Round 1 và Round 2 lấy từ báo cáo JSON của Bruno "
        "CLI (results/round1, round2); Round 1 Failed do #647 (seed chưa có verified_email), sửa ở PR #648, "
        "Round 2 chạy lại toàn bộ"
    ): (
        "All cases have a Bruno request (48 cases, 1 value per row); Round 1 and Round 2 come from the Bruno CLI "
        "JSON reports (results/round1, round2); Round 1 Failed due to #647 (seed lacked verified_email), fixed in "
        "PR #648, Round 2 re-ran the full suite"
    ),
    (
        "Thêm module GoldenFlow: build cả stack từ commit + golden flow qua gateway; Round 1 Failed (DEF-17: image "
        "Java thiếu module shared/java-arch-rules), Round 2 Passed sau khi sửa Dockerfile"
    ): (
        "Added module GoldenFlow: builds the whole stack from commit + runs the golden flow through the gateway; "
        "Round 1 Failed (DEF-17: Java images lacked module shared/java-arch-rules), Round 2 Passed after fixing "
        "the Dockerfiles"
    ),
    "Round 3 mọi module, Test Statistics": "Round 3 all modules, Test Statistics",
    "8 module Bruno e2e, Test Cases, Test Statistics": "8 Bruno e2e modules, Test Cases, Test Statistics",
    (
        "Round 3 = regression ngày 28/11/2026 trên 90f7b9f1: 48 Passed, 1 Pending; Test Statistics đếm theo Round 3"
    ): (
        "Round 3 = regression run on 28/11/2026 on 90f7b9f1: 48 Passed, 1 Pending; Test Statistics counts Round 3"
    ),
    (
        "Chuyển 8 sheet Bruno e2e (BF-01..BF-07, Security; 128 case, Round 1..3) từ Report 5.3 sang, đổi STC → ITC "
        "và tên sheet thêm Api: gọi API qua gateway là integration test (D95); 5.3 chỉ còn system test qua UI"
    ): (
        "Moved 8 Bruno e2e sheets (BF-01..BF-07, Security; 128 cases, Round 1..3) from Report 5.3, changed STC -> "
        "ITC and appended Api to sheet names: calling APIs through the gateway is an integration test (D95); 5.3 "
        "keeps only system tests through the web UI"
    ),

    # ── Test Cases ──
    (
        "1. Stack: docker compose -f backend/docker-compose.yml -f scripts/unit-test-report/compose.report52.yml "
        "up -d api-gateway user-service product-service contract-service (gateway 8080; product 8082 publish qua "
        "override; Keycloak 8180, realm import mới; RabbitMQ; MySQL 8.0)"
    ): (
        "1. Stack: docker compose -f backend/docker-compose.yml -f scripts/unit-test-report/compose.report52.yml "
        "up -d api-gateway user-service product-service contract-service (gateway 8080; product 8082 published via "
        "override; Keycloak 8180, fresh realm import; RabbitMQ; MySQL 8.0)"
    ),
    (
        "2. Chạy: scripts/unit-test-report/run_bruno_report52.sh <round> <tester> (Bruno CLI 3.4.2, --env local, "
        "gateway_secret = GATEWAY_INTERNAL_SECRET); báo cáo JSON ở scripts/unit-test-report/results/<round>/"
    ): (
        "2. Run: scripts/unit-test-report/run_bruno_report52.sh <round> <tester> (Bruno CLI 3.4.2, --env local, "
        "gateway_secret = GATEWAY_INTERNAL_SECRET); JSON reports under scripts/unit-test-report/results/<round>/"
    ),
    "3. Tài khoản Keycloak: buyer1, seller1, seller2, operator1, admin1 (pass123)": (
        "3. Keycloak accounts: buyer1, seller1, seller2, operator1, admin1 (pass123)"
    ),
    (
        "6. Mọi case là integration test (ITC), 2 kiểu: gọi thẳng service với X-Gateway-Secret (product-service) "
        "hoặc qua gateway {{base_url}} với token Keycloak (api-gateway, user-service); system test theo luồng FE "
        "BF-01..BF-07 không nằm ở đây"
    ): (
        "6. All cases are integration tests (ITC) of 2 kinds: calling the service directly with X-Gateway-Secret "
        "(product-service) or through the gateway {{base_url}} with a Keycloak token (api-gateway, user-service); "
        "system tests through the FE flows BF-01..BF-07 are not here"
    ),
    (
        "7. Note ghi request Bruno; Defect IDs ghi issue gây ra case Failed (results/<round>/defects.json)"
    ): (
        "7. Note records the Bruno request; Defect IDs lists the issue behind a Failed case (results/<round>/defects.json)"
    ),
    (
        "8. GoldenFlow: .github/workflows/e2e.yml build cả stack từ commit rồi chạy npm test trong backend/e2e; "
        "Round 1/2 của module này là 2 lần chạy E2E của #288 (trước và sau sửa Dockerfile)"
    ): (
        "8. GoldenFlow: .github/workflows/e2e.yml builds the whole stack from commit then runs npm test in "
        "backend/e2e; Round 1/2 of this module are the 2 E2E runs of #288 (before and after the Dockerfile fix)"
    ),
    (
        "9. Bộ Bruno e2e BF-01..BF-07 và Security (chuyển từ Report 5.3 ngày 28/11/2026, D95: gọi API qua gateway "
        "là integration test): cd backend && ./scripts/run-e2e-full.sh --fresh --keep, Bruno CLI 3.4.2 --env "
        "local; Round 1..3 giữ nguyên như khi còn ở 5.3 (run-20261127-1603, run-20261127-1630, run-20261128-0350); "
        "log ở scripts/unit-test-report/results/st-round<N>/"
    ): (
        "9. Bruno e2e suites BF-01..BF-07 and Security (moved from Report 5.3 on 28/11/2026, D95: calling APIs "
        "through the gateway is an integration test): cd backend && ./scripts/run-e2e-full.sh --fresh --keep, "
        "Bruno CLI 3.4.2 --env local; Round 1..3 stay as recorded when in 5.3 (run-20261127-1603, "
        "run-20261127-1630, run-20261128-0350); logs in scripts/unit-test-report/results/st-round<N>/"
    ),
    "api-gateway: token Keycloak, route public/protected, thay header danh tính bằng claim JWT": (
        "api-gateway: Keycloak token, public/protected routes, replacing identity headers with JWT claims"
    ),
    "Stack docker compose chạy đủ; realm agricontract; gọi qua {{base_url}} (gateway 8080)": (
        "Docker Compose stack fully up; realm agricontract; called through {{base_url}} (gateway 8080)"
    ),
    "user-service: đăng ký profile tổ chức, xem profile của mình và profile công khai": (
        "user-service: register organization profile, view own profile and public profile"
    ),
    "Qua gateway với token seller1 (password grant); seed 02-user-db.sql có sẵn profile seller1 (VERIFIED)": (
        "Through the gateway with seller1 token (password grant); seed 02-user-db.sql provides seller1 profile (VERIFIED)"
    ),
    "product-service: đề xuất, duyệt, từ chối danh mục và gán commodity": (
        "product-service: propose, approve, reject categories and assign commodity"
    ),
    (
        'Gọi thẳng product-service 8082 với X-Gateway-Secret; X-User-Id/X-User-Role như gateway gửi; tên danh '
        'mục Bruno tạo có hậu tố {{run_tag}} của lần chạy (seed đã có "Cà phê")'
    ): (
        "Calling product-service 8082 directly with X-Gateway-Secret; X-User-Id/X-User-Role as sent by the "
        "gateway; Bruno-created category names carry the run's {{run_tag}} suffix (seed already has 'Cà phê' (Coffee))"
    ),
    "product-service: tạo sản phẩm và thay ảnh sản phẩm": (
        "product-service: create product and replace product images"
    ),
    "Như CategoryApi; X-User-Role SELLER; {{category_id}} APPROVED (COFFEE), {{category_id_reject}} REJECTED": (
        "Same as CategoryApi; X-User-Role SELLER; {{category_id}} APPROVED (COFFEE), {{category_id_reject}} REJECTED"
    ),
    "product-service: ghim lô đất, đăng listing cà phê, xem và sửa listing": (
        "product-service: pin plot, publish coffee listing, view and update listing"
    ),
    (
        "Như ProductApi; {{product_id}} đã tạo; product-service chạy với PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0, "
        "PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000"
    ): (
        "Same as ProductApi; {{product_id}} created; product-service runs with "
        "PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0, PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000"
    ),
    "e2e (cả stack)": "e2e (full stack)",
    "Cả stack: build mọi image từ commit, dựng compose, chạy golden flow qua gateway (BF-01 → BF-04 rút gọn)": (
        "Full stack: build all images from commit, start compose, run golden flow through the gateway (shortened BF-01 → BF-04)"
    ),
    (
        "Stack build từ chính commit cần kiểm (.github/workflows/e2e.yml), secret sinh mới mỗi lần chạy; "
        "e2e/compose.e2e.yml rút ngắn thời gian chờ"
    ): (
        "Stack built from the commit under test (.github/workflows/e2e.yml), fresh secrets per run; "
        "e2e/compose.e2e.yml shortens wait timeouts"
    ),
    "BF-01 User Onboarding & KYC: đăng ký, xác minh email qua MailHog, Operator duyệt hoặc từ chối KYC": (
        "BF-01 User Onboarding & KYC: register, verify email via MailHog, Operator approves or rejects KYC"
    ),
    "Stack vừa dựng (--fresh), seed 01/02; tài khoản Keycloak mới mỗi lần chạy": (
        "Freshly started stack (--fresh), seed 01/02; new Keycloak accounts on each run"
    ),
    "BF-02 Plot & Listing Publication: category cà phê, product, lô đất, listing": (
        "BF-02 Plot & Listing Publication: coffee category, product, plot, listing"
    ),
    "seller1 VERIFIED (seed); product-service rút ngắn kiểm tra geo": (
        "seller1 VERIFIED (seed); product-service shortens geo verification"
    ),
    "BF-03 Contract Signing & Activation: offer, giữ hàng, ký OTP 2 bên, khoá cọc, ACTIVE; rút offer": (
        "BF-03 Contract Signing & Activation: offer, stock reservation, two-party OTP signing, lock deposit, ACTIVE; withdraw offer"
    ),
    "buyer1/seller1 có tài khoản ngân hàng mock; listing của BF02-070": (
        "buyer1/seller1 have mock bank accounts; listing from BF02-070"
    ),
    (
        "BF-04 Milestone Delivery & Settlement: cấp tiền, cân, nhận, xác nhận hoặc tranh chấp, giám định Level 1.5, "
        "quyết toán, audit"
    ): (
        "BF-04 Milestone Delivery & Settlement: fund allocation, weigh, receive, confirm or dispute, Level 1.5 "
        "inspection, settlement, audit"
    ),
    "Hợp đồng ACTIVE của BF03-080; inspector1, arbitrator1": (
        "ACTIVE contract from BF03-080; inspector1, arbitrator1"
    ),
    "BF-05 Breach & Force Majeure: phần đã có controller": (
        "BF-05 Breach & Force Majeure: endpoints with controllers"
    ),
    "BF-06 Mutual Termination & Replacement: chưa có controller (#242, #243)": (
        "BF-06 Mutual Termination & Replacement: no controllers yet (#242, #243)"
    ),
    "BF-07 Emergency Security Lock: trạng thái khoá qua reputation": (
        "BF-07 Emergency Security Lock: lock state via reputation"
    ),
    "Tài khoản fixture": "Fixture accounts",
    (
        "Phòng thủ: xác thực, ma trận quyền đọc, retry idempotent, audit đọc, kiểm tra đầu vào, lộ thông tin "
        "(data-governance §5.4, hash-chain §4.6)"
    ): (
        "Security controls: authentication, read role matrix, idempotent retry, read audit, input validation, "
        "information leakage (data-governance §5.4, hash-chain §4.6)"
    ),

    # ── Test Statistics ──
    (
        "Integration test (ITC) của 3 module: api-gateway, user-service, product-service, lấy từ Bruno collection "
        "backend/bruno. 48 kịch bản: 36 gọi thẳng service, 12 qua gateway; 48 có request Bruno và 0 chưa có (Note "
        "trống). Round 1 chạy ngày 25/11/2026 trên commit 36caa3c9 (Bruno CLI 3.4.2): 31 Passed, 17 Failed. Log: "
        "scripts/unit-test-report/results/round1/. Round 2 (sau khi sửa defect của Round 1, chạy lại toàn bộ) ngày "
        "25/11/2026 trên commit 36caa3c9, seed từ commit eb11bd4d (PR #648): 48 Passed. Log: "
        "scripts/unit-test-report/results/round2/. Thêm module GoldenFlow (#288): 1 kịch bản qua gateway, cả stack "
        "build từ commit; Round 1 ngày 27/11/2026 trên main 44202a49 Failed (DEF-17, image Java thiếu module "
        "shared/java-arch-rules), Round 2 cùng ngày trên PR #678 e1d33257 Passed. Round 3 (regression) ngày "
        "28/11/2026 trên commit 90f7b9f1, tester Nguyen Minh Cuong, Bruno CLI 3.4.2, stack Docker Compose dựng mới: "
        "Bruno 48 Passed; GoldenFlow Pending (golden-flow.test.mjs tự skip: 28/11/2026 không phải ngày làm việc ở "
        "Việt Nam, không đợt nào được cấp tiền lúc kích hoạt (D63, D67)). Log: "
        "scripts/unit-test-report/results/round3/. Bảng dưới đếm theo Round 3. Ngày 28/11/2026 thêm 8 module Bruno "
        "e2e (BF-01..BF-07, Security; 128 case) chuyển từ Report 5.3: gọi API qua gateway nên là integration test "
        "(D95), Report 5.3 chỉ còn system test qua giao diện web. Các module này đếm theo Round 3 (28/11/2026, "
        "run-20261128-0350, chạy thứ Bảy nên chuỗi BF-03 ký 2 → BF-04 Pending, DEF-19)."
    ): (
        "Integration tests (ITC) for 3 modules: api-gateway, user-service, product-service, taken from the Bruno "
        "collection backend/bruno. 48 scenarios: 36 calling the service directly, 12 through the gateway; 48 have "
        "a Bruno request and 0 do not (empty Note). Round 1 ran on 25/11/2026 on commit 36caa3c9 (Bruno CLI 3.4.2): "
        "31 Passed, 17 Failed. Log: scripts/unit-test-report/results/round1/. Round 2 (after fixing Round 1 defects, "
        "full re-run) on 25/11/2026 on commit 36caa3c9 with seed from eb11bd4d (PR #648): 48 Passed. Log: "
        "scripts/unit-test-report/results/round2/. Added module GoldenFlow (#288): 1 scenario through the gateway, "
        "full stack built from commit; Round 1 on 27/11/2026 on main 44202a49 Failed (DEF-17, Java images lacked "
        "module shared/java-arch-rules), Round 2 on the same day on PR #678 e1d33257 Passed. Round 3 (regression) "
        "on 28/11/2026 on commit 90f7b9f1, tester Nguyen Minh Cuong, Bruno CLI 3.4.2, freshly built Docker Compose "
        "stack: Bruno 48 Passed; GoldenFlow Pending (golden-flow.test.mjs skipped itself: 28/11/2026 is not a "
        "business day in Vietnam, so no milestone is funded on activation (D63, D67)). Log: "
        "scripts/unit-test-report/results/round3/. The table below counts Round 3. On 28/11/2026 added 8 Bruno e2e "
        "modules (BF-01..BF-07, Security; 128 cases) moved from Report 5.3: calling APIs through the gateway is an "
        "integration test (D95); Report 5.3 keeps only system tests through the web client. These modules are "
        "counted at Round 3 (28/11/2026, run-20261128-0350, run on Saturday so the BF-03 second-signature chain → "
        "BF-04 went Pending, DEF-19)."
    ),
    "Kiểu integration test": "Integration test kind",
    "IT gọi thẳng service (X-Gateway-Secret)": "IT calling the service directly (X-Gateway-Secret)",
    "IT qua gateway (token Keycloak)": "IT through the gateway (Keycloak token)",

    # ── GatewayRouting ──
    "Return: 200, có access_token": "Return: 200, returns access_token",
    "buyer1 / sai mật khẩu": "buyer1 / wrong password",
    "Return: 401 invalid_grant, không có access_token": "Return: 401 invalid_grant, no access_token",
    "Không có token": "No token",
    "Return: 401, request không tới contract-service": "Return: 401, request does not reach contract-service",
    "Token buyer1 còn hạn": "Valid buyer1 token",
    "Return: 200, body phân trang": "Return: 200, paginated body",
    "buyer1 đã có profile": "buyer1 already has a profile",
    "Token buyer1, kèm X-User-Id của seller1 và X-User-Role ADMIN": (
        "buyer1 token with spoofed X-User-Id of seller1 and X-User-Role ADMIN"
    ),
    "Return: 200, profile của buyer1 (header giả bị thay bằng claim JWT)": (
        "Return: 200, buyer1's profile (spoofed headers replaced by JWT claims)"
    ),

    # ── UserProfileApi ──
    "seller1 chưa có profile (xoá dòng seed của seller1)": (
        "seller1 has no profile yet (delete seller1 seed row)"
    ),
    "Return: 201, userId = sub của seller1; role SELLER; verificationStatus PENDING; không bọc ApiResponse": (
        "Return: 201, userId = seller1's sub; role SELLER; verificationStatus PENDING; not wrapped in ApiResponse"
    ),
    "Profile seller1 từ seed (VERIFIED)": "seller1 profile from seed (VERIFIED)",
    "Return: 200, userId = sub của seller1; role SELLER; không tạo profile mới": (
        "Return: 200, userId = seller1's sub; role SELLER; no new profile created"
    ),
    "Đã đăng ký": "Already registered",
    "Gửi lại đúng request": "Resend the exact same request",
    "Return: 200, cùng userId, không tạo profile thứ hai": (
        "Return: 200, same userId, no second profile created"
    ),
    "Return: 200, profile đủ phone và address": "Return: 200, profile includes phone and address",
    "userId của seller1": "seller1's userId",
    "Return: 200, có organizationName, role, verificationStatus; không có email, phone, address": (
        "Return: 200, includes organizationName, role, verificationStatus; omits email, phone, address"
    ),

    # ── CategoryApi ──
    'name "Cà Phê Bruno {{run_tag}}"': "name 'Cà Phê Bruno {{run_tag}}' (Bruno Coffee)",
    'Seed có "Cà phê" APPROVED; BUYER': "Seed has 'Cà phê' (Coffee) APPROVED; BUYER",
    "Return: 409, ErrorEnvelope có code và traceId": "Return: 409, ErrorEnvelope with code and traceId",
    "Return: 400 VALIDATION_FAILED, không tạo danh mục": "Return: 400 VALIDATION_FAILED, no category created",
    "Danh mục PENDING; OPERATOR": "Category PENDING; OPERATOR",
    "Danh mục PENDING; SELLER": "Category PENDING; SELLER",
    "Return: 403 FORBIDDEN, vẫn PENDING": "Return: 403 FORBIDDEN, stays PENDING",
    "Danh mục đã có commodity; OPERATOR": "Category already has a commodity; OPERATOR",
    'Danh mục "Rau Củ Không Rõ {{run_tag}}" PENDING; OPERATOR': (
        "Category 'Rau Củ Không Rõ {{run_tag}}' (Unclear Vegetables) PENDING; OPERATOR"
    ),
    'reason "Tên không rõ ràng, đề nghị đặt lại"': (
        "reason 'Tên không rõ ràng, đề nghị đặt lại' (Unclear name, please rename)"
    ),
    "Return: 200, status REJECTED; rejectionReason đúng chuỗi gửi lên": (
        "Return: 200, status REJECTED; rejectionReason matches the submitted string"
    ),
    "Return: 400 VALIDATION_FAILED, vẫn PENDING": "Return: 400 VALIDATION_FAILED, stays PENDING",

    # ── ProductApi ──
    "Danh mục APPROVED": "Category APPROVED",
    '"Ca phe Robusta Dak Lak", kg, 2 ảnh, cropYear 2026, harvestSeason "Nien vu 2026/27", 1 chứng nhận': (
        '"Ca phe Robusta Dak Lak", kg, 2 images, cropYear 2026, harvestSeason "Nien vu 2026/27", 1 certification'
    ),
    "Return: 201, productId; images 2 phần tử": "Return: 201, productId; 2 image items",
    "Danh mục REJECTED": "Category REJECTED",
    "categoryId = {{category_id_reject}}, 1 ảnh": "categoryId = {{category_id_reject}}, 1 image",
    "Return: 400, ErrorEnvelope có code và traceId, không tạo sản phẩm": (
        "Return: 400, ErrorEnvelope with code and traceId, no product created"
    ),
    "Sản phẩm có 2 ảnh": "Product has 2 images",
    "Return: 200, images chỉ còn URL mới": "Return: 200, images contains only the new URL",
    "Sản phẩm có ảnh": "Product has images",
    "Return: 400, ErrorEnvelope có code và traceId; ảnh không đổi": (
        "Return: 400, ErrorEnvelope with code and traceId; images unchanged"
    ),
    "Return: 200, images 5 phần tử": "Return: 200, 5 image items",
    "Return: 400 VALIDATION_FAILED, ảnh không đổi": "Return: 400 VALIDATION_FAILED, images unchanged",

    # ── PlotListingApi ──
    "Hộ Nguyễn Văn A, 12.666789 / 108.050123, 1.5 ha, DAK_LAK, Idempotency-Key cố định": (
        "'Hộ Nguyễn Văn A' (Nguyen Van A household), 12.666789 / 108.050123, 1.5 ha, DAK_LAK, fixed Idempotency-Key"
    ),
    "Return: 201 (lần đầu) hoặc 200 (đã ghim); geometryType POINT; source MANUAL_PIN; geometryHash SHA-256 hex": (
        "Return: 201 (first call) or 200 (already pinned); geometryType POINT; source MANUAL_PIN; geometryHash SHA-256 hex"
    ),
    "Plot đã qua kiểm tra địa lý": "Plot passed geo verification",
    'offeredQuantity 500, minOrderQuantity 100, askingUnitPrice "15000.00", ROBUSTA, Idempotency-Key mới': (
        'offeredQuantity 500, minOrderQuantity 100, askingUnitPrice "15000.00", ROBUSTA, new Idempotency-Key'
    ),
    (
        "Return: 201, status AVAILABLE; quantityAvailable 500; commodity COFFEE; sellerId của seller1; không có "
        "priceFloor/deliveryDeadline/coverImageUrl"
    ): (
        "Return: 201, status AVAILABLE; quantityAvailable 500; commodity COFFEE; seller1's sellerId; omits "
        "priceFloor/deliveryDeadline/coverImageUrl"
    ),
    "Đã tạo listing": "Listing created",
    "Gửi lại cùng Idempotency-Key và body": "Resend the same Idempotency-Key and body",
    "Return: 200, cùng listingId, không tạo thêm": "Return: 200, same listingId, no duplicate created",
    "Không có Idempotency-Key": "Missing Idempotency-Key",
    'askingUnitPrice "15000.0" (1 chữ số thập phân)': 'askingUnitPrice "15000.0" (1 decimal place)',
    'askingUnitPrice "15000.000" (3 chữ số thập phân)': 'askingUnitPrice "15000.000" (3 decimal places)',
    "Listing đã tạo": "Listing exists",
    "Return: 200, đúng listingId; status AVAILABLE": "Return: 200, matching listingId; status AVAILABLE",
    "Có listing AVAILABLE": "An AVAILABLE listing exists",
    "Không tham số": "No parameters",
    "Return: 200, content ít nhất 1 phần tử; totalElements >= 1": (
        "Return: 200, content has at least 1 item; totalElements >= 1"
    ),
    "seller1 có listing": "seller1 has listings",
    "Return: 200, mọi listing có sellerId của seller1": "Return: 200, all listings have seller1's sellerId",
    "Listing chưa có reservation": "Listing has no reservations",
    'offeredQuantity 600, askingUnitPrice "15500.00", Idempotency-Key mới': (
        'offeredQuantity 600, askingUnitPrice "15500.00", new Idempotency-Key'
    ),
    "Return: 200, quantityAvailable 600; listingVersionToken mới": (
        "Return: 200, quantityAvailable 600; new listingVersionToken"
    ),
    "Đã sửa": "Already updated",
    "Return: 200, listingVersionToken không đổi": "Return: 200, listingVersionToken unchanged",

    # ── GoldenFlow ──
    "Dựng cả stack compose từ commit + chạy golden flow (.github/workflows/e2e.yml)": (
        "Build full compose stack from commit + run golden flow (.github/workflows/e2e.yml)"
    ),
    (
        "Commit cần kiểm (Round 1: main 44202a49, Round 2: PR #678 e1d33257); docker compose -f docker-compose.yml "
        "-f e2e/compose.e2e.yml up -d --build; npm test trong backend/e2e"
    ): (
        "Commit under test (Round 1: main 44202a49, Round 2: PR #678 e1d33257); docker compose -f "
        "docker-compose.yml -f e2e/compose.e2e.yml up -d --build; npm test in backend/e2e"
    ),
    "Return: mọi image build được, stack healthy, golden flow pass (offer → ký 2 bên → ACTIVE → giao nhận → SETTLED)": (
        "Return: all images build, stack healthy, golden flow passes (offer → two-party sign → ACTIVE → delivery → SETTLED)"
    ),
    (
        "Round 3: golden-flow.test.mjs tự skip: 28/11/2026 không phải ngày làm việc ở Việt Nam, không đợt nào được "
        "cấp tiền lúc kích hoạt (D63, D67)"
    ): (
        "Round 3: golden-flow.test.mjs skipped itself: 28/11/2026 is not a business day in Vietnam, so no milestone "
        "is funded on activation (D63, D67)"
    ),

    # ── Common across BF01..BF07 & SecurityApi ──
    "Chưa chạy được (backend/scripts/e2e/status.tsv)": "Not yet runnable (backend/scripts/e2e/status.tsv)",
    "ST đọc thẳng service / broker / DB để kiểm kết quả": "Reads service / broker / DB directly to verify result",
    "Return: Mọi queue .dlq có mặt và rỗng": "Return: All .dlq queues exist and are empty",
    "Round 1-3: 84 queue .dlq, tất cả 0 message": "Round 1-3: 84 .dlq queues, all 0 messages",
    "Round 1-3: HTTP 204; body rỗng": "Round 1-3: HTTP 204; empty body",
    "Round 1: HTTP 204; body rỗng": "Round 1: HTTP 204; empty body",
    "Round 2: HTTP 204; body rỗng": "Round 2: HTTP 204; empty body",
    "Round 3: Không có kết quả: request trước đó dừng flow (--bail)": (
        "Round 3: No result: earlier request stopped the flow (--bail)"
    ),
    "Round 3: không chạy (request trước đó dừng flow)": (
        "Round 3: not run (earlier request stopped the flow)"
    ),
    "Round 1: HTTP 200; mảng 3 phần tử": "Round 1: HTTP 200; 3-item array",
    "Round 2: HTTP 200; mảng 3 phần tử": "Round 2: HTTP 200; 3-item array",
    "Round 1: HTTP 200; mảng 6 phần tử": "Round 1: HTTP 200; 6-item array",
    "Round 2: HTTP 200; mảng 6 phần tử": "Round 2: HTTP 200; 6-item array",
    "Round 1: HTTP 200; mảng 7 phần tử": "Round 1: HTTP 200; 7-item array",
    "Round 2: HTTP 200; mảng 7 phần tử": "Round 2: HTTP 200; 7-item array",
    "Round 1: HTTP 200; mảng 168 phần tử": "Round 1: HTTP 200; 168-item array",
    "Round 2: HTTP 200; mảng 168 phần tử": "Round 2: HTTP 200; 168-item array",
    "Round 1: HTTP 200; mảng 342 phần tử": "Round 1: HTTP 200; 342-item array",
    "Round 2: HTTP 200; mảng 342 phần tử": "Round 2: HTTP 200; 342-item array",
    "Round 1-3: HTTP 200; mảng 4 phần tử": "Round 1-3: HTTP 200; 4-item array",
    "Round 1-3: HTTP 200; mảng 0 phần tử": "Round 1-3: HTTP 200; 0-item array",
    "Round 1: HTTP 201; body rỗng": "Round 1: HTTP 201; empty body",
    "Round 2: HTTP 201; body rỗng": "Round 2: HTTP 201; empty body",

    # ── BF01_OnboardingApi ──
    "e2e/bf01-onboarding-kyc (Luồng chính)": "e2e/bf01-onboarding-kyc (Main flow)",
    "Đầu nhóm: Stack vừa dựng (--fresh), seed 01/02; tài khoản Keycloak mới mỗi lần chạy": (
        "Group setup: Freshly started stack (--fresh), seed 01/02; new Keycloak accounts on each run"
    ),
    "Chạy sau BF01-010": "Runs after BF01-010",
    "Chạy sau BF01-020": "Runs after BF01-020",
    "Chạy sau BF01-030": "Runs after BF01-030",
    "Chạy sau BF01-040": "Runs after BF01-040",
    "Chạy sau BF01-060": "Runs after BF01-060",
    "Chạy sau BF01-070": "Runs after BF01-070",
    "Chạy sau BF01-080": "Runs after BF01-080",
    "Round 1-3: không chạy: Keycloak creation timestamp drives the purge; no time-shift fixture is wired.": (
        "Round 1-3: not run: Keycloak creation timestamp drives the purge; no time-shift fixture is wired."
    ),
    "Sau mọi case của BF01": "After all BF01 cases",

    # ── BF02_PlotListingApi ──
    "e2e/bf02-plot-listing (Luồng chính)": "e2e/bf02-plot-listing (Main flow)",
    "Đầu nhóm: seller1 VERIFIED (seed); product-service rút ngắn kiểm tra geo": (
        "Group setup: seller1 VERIFIED (seed); product-service shortens geo verification"
    ),
    "Chạy sau BF02-010": "Runs after BF02-010",
    "Chạy sau BF02-020": "Runs after BF02-020",
    "Chạy sau BF02-030": "Runs after BF02-030",
    "Chạy sau BF02-040": "Runs after BF02-040",
    "Chạy sau BF02-050": "Runs after BF02-050",
    "Chạy sau BF02-060": "Runs after BF02-060",
    "Sau mọi case của BF02": "After all BF02 cases",

    # ── BF03_SigningApi ──
    "Đầu nhóm happy: buyer1/seller1 có tài khoản ngân hàng mock; listing của BF02-070": (
        "Group setup (happy): buyer1/seller1 have mock bank accounts; listing from BF02-070"
    ),
    "Chạy sau BF03-010": "Runs after BF03-010",
    "Chạy sau BF03-020": "Runs after BF03-020",
    "Chạy sau BF03-030": "Runs after BF03-030",
    "Chạy sau BF03-040": "Runs after BF03-040",
    "Chạy sau BF03-050": "Runs after BF03-050",
    "Chạy sau BF03-060": "Runs after BF03-060",
    (
        "Round 3: HTTP 409; DELIVERY_SCHEDULE_TOO_SOON: Milestone 1 is due on 2026-12-02, fewer than 3 business "
        "days from today; move its delivery date and sign again.; lỗi: expected 409 to equal 204"
    ): (
        "Round 3: HTTP 409; DELIVERY_SCHEDULE_TOO_SOON: Milestone 1 is due on 2026-12-02, fewer than 3 business "
        "days from today; move its delivery date and sign again.; error: expected 409 to equal 204"
    ),
    "Chạy sau BF03-070": "Runs after BF03-070",
    "Chạy sau BF03-080": "Runs after BF03-080",
    "e2e/bf03-signing-activation/unhappy (Unhappy path: rút offer)": (
        "e2e/bf03-signing-activation/unhappy (Unhappy path: withdraw offer)"
    ),
    "Đầu nhóm unhappy: buyer1/seller1 có tài khoản ngân hàng mock; listing của BF02-070": (
        "Group setup (unhappy): buyer1/seller1 have mock bank accounts; listing from BF02-070"
    ),
    "Chạy sau BF03-110": "Runs after BF03-110",
    "Chạy sau BF03-120": "Runs after BF03-120",
    "Chạy sau BF03-130": "Runs after BF03-130",
    "Chạy sau BF03-140": "Runs after BF03-140",
    "Chạy sau BF03-150": "Runs after BF03-150",
    "Round 1-3: không chạy: No deterministic insufficient-funds fixture was exercised.": (
        "Round 1-3: not run: No deterministic insufficient-funds fixture was exercised."
    ),
    "Sau mọi case của BF03": "After all BF03 cases",

    # ── BF04_MilestoneApi ──
    "e2e/bf04-milestone-delivery-settlement/clean (Nhận hàng sạch)": (
        "e2e/bf04-milestone-delivery-settlement/clean (Clean delivery)"
    ),
    "Đầu nhóm clean: Hợp đồng ACTIVE của BF03-080; inspector1, arbitrator1": (
        "Group setup (clean): ACTIVE contract from BF03-080; inspector1, arbitrator1"
    ),
    "Round 3: không có response; lỗi: Fixture 070-sign-seller returned 409 DELIVERY_SCHEDULE_TOO_SOON": (
        "Round 3: no response; error: Fixture 070-sign-seller returned 409 DELIVERY_SCHEDULE_TOO_SOON"
    ),
    "Chạy sau BF04-010": "Runs after BF04-010",
    "Chạy sau BF04-015": "Runs after BF04-015",
    "Chạy sau BF04-017": "Runs after BF04-017",
    "Chạy sau BF04-020": "Runs after BF04-020",
    "Chạy sau BF04-030": "Runs after BF04-030",
    "Chạy sau BF04-035": "Runs after BF04-035",
    "Chạy sau BF04-040": "Runs after BF04-040",
    "Chạy sau BF04-050": "Runs after BF04-050",
    "Chạy sau BF04-055": "Runs after BF04-055",
    "Chạy sau BF04-060": "Runs after BF04-060",
    "Chạy sau BF04-065": "Runs after BF04-065",
    "Chạy sau BF04-070": "Runs after BF04-070",
    "Chạy sau BF04-085": "Runs after BF04-085",
    "Chạy sau BF04-090": "Runs after BF04-090",
    "Chạy sau BF04-091": "Runs after BF04-091",
    "Chạy sau BF04-092": "Runs after BF04-092",
    "Chạy sau BF04-093": "Runs after BF04-093",
    "Giới hạn: auto-confirm đúng 14 ngày sau khi nhận (D78)": (
        "Boundary: auto-confirm at exact 14 days after receipt (D78)"
    ),
    "Round 1: không có response; lỗi: spawnSync <repo>/backend\\scripts\\e2e\\time-travel.sh EFTYPE": (
        "Round 1: no response; error: spawnSync <repo>/backend\\scripts\\e2e\\time-travel.sh EFTYPE"
    ),
    "e2e/bf04-milestone-delivery-settlement/completion (Hoàn tất hợp đồng)": (
        "e2e/bf04-milestone-delivery-settlement/completion (Contract completion)"
    ),
    "Đầu nhóm completion: Hợp đồng ACTIVE của BF03-080; inspector1, arbitrator1": (
        "Group setup (completion): ACTIVE contract from BF03-080; inspector1, arbitrator1"
    ),
    "Round 3: không có response; lỗi: Fixture 010-funded returned error ": (
        "Round 3: no response; error: Fixture 010-funded returned error"
    ),
    "Chạy sau BF04-310": "Runs after BF04-310",
    "Chạy sau BF04-320": "Runs after BF04-320",
    "Chạy sau BF04-330": "Runs after BF04-330",
    "Chạy sau BF04-340": "Runs after BF04-340",
    "Chạy sau BF04-350": "Runs after BF04-350",
    "Chạy sau BF04-360": "Runs after BF04-360",
    "Round 1: Gateway chưa có route audit cho các bên (#630): 404": (
        "Round 1: Gateway lacks party audit route (#630): 404"
    ),
    "Round 2: Gateway chưa có route audit cho các bên (#630): 404": (
        "Round 2: Gateway lacks party audit route (#630): 404"
    ),
    "e2e/bf04-milestone-delivery-settlement/dispute (Tranh chấp chất lượng)": (
        "e2e/bf04-milestone-delivery-settlement/dispute (Quality dispute)"
    ),
    "Đầu nhóm dispute: Hợp đồng ACTIVE của BF03-080; inspector1, arbitrator1": (
        "Group setup (dispute): ACTIVE contract from BF03-080; inspector1, arbitrator1"
    ),
    "Chạy sau BF04-110": "Runs after BF04-110",
    "Chạy sau BF04-115": "Runs after BF04-115",
    "Chạy sau BF04-117": "Runs after BF04-117",
    "Chạy sau BF04-120": "Runs after BF04-120",
    "Chạy sau BF04-130": "Runs after BF04-130",
    "Chạy sau BF04-135": "Runs after BF04-135",
    "Chạy sau BF04-140": "Runs after BF04-140",
    "Chạy sau BF04-150": "Runs after BF04-150",
    "Chạy sau BF04-155": "Runs after BF04-155",
    "Chạy sau BF04-160": "Runs after BF04-160",
    "Chạy sau BF04-170": "Runs after BF04-170",
    "Chạy sau BF04-175": "Runs after BF04-175",
    "Chạy sau BF04-180": "Runs after BF04-180",
    "Chạy sau BF04-190": "Runs after BF04-190",
    "Chạy sau BF04-195": "Runs after BF04-195",
    "Chạy sau BF04-200": "Runs after BF04-200",
    "Chạy sau BF04-210": "Runs after BF04-210",
    "Chạy sau BF04-220": "Runs after BF04-220",
    "Chạy sau BF04-225": "Runs after BF04-225",
    "Chạy sau BF04-230": "Runs after BF04-230",
    "Round 1-3: không chạy: Time-dependent cases not exercised.": (
        "Round 1-3: not run: Time-dependent cases not exercised."
    ),
    "Round 1-3: không chạy: D92 subflows not exercised.": (
        "Round 1-3: not run: D92 subflows not exercised."
    ),
    "Round 1-3: không chạy: Future funding schedule and failure fixture not exercised.": (
        "Round 1-3: not run: Future funding schedule and failure fixture not exercised."
    ),
    "Round 1-3: không chạy: Time-dependent case not exercised.": (
        "Round 1-3: not run: Time-dependent case not exercised."
    ),
    "Round 1-3: không chạy: Audit-service has not implemented publishing notification.milestone_anchor_requested.": (
        "Round 1-3: not run: Audit-service has not implemented publishing notification.milestone_anchor_requested."
    ),
    "Round 1-3: không chạy: Notification consumer work is pending.": (
        "Round 1-3: not run: Notification consumer work is pending."
    ),
    "Sau mọi case của BF04": "After all BF04 cases",

    # ── BF05_BreachApi ──
    "e2e/bf05-breach-force-majeure (Luồng chính)": "e2e/bf05-breach-force-majeure (Main flow)",
    "Đầu nhóm: arbitrator1": "Group setup: arbitrator1",
    "Round 1-3: không chạy: Implementation is pending.": "Round 1-3: not run: Implementation is pending.",
    "Sau mọi case của BF05": "After all BF05 cases",

    # ── BF06_TerminationApi ──
    "Round 1-3: không chạy: No matching controller.": "Round 1-3: not run: No matching controller.",
    "Sau mọi case của BF06": "After all BF06 cases",

    # ── BF07_SecurityLockApi ──
    "e2e/bf07-emergency-security-lock (Luồng chính)": "e2e/bf07-emergency-security-lock (Main flow)",
    "Đầu nhóm: Tài khoản fixture": "Group setup: Fixture accounts",
    "Round 1-3: không chạy: Bank has no controller for the frozen lock and unlock paths.": (
        "Round 1-3: not run: Bank has no controller for the frozen lock and unlock paths."
    ),
    "Round 1-3: không chạy: Reputation read exists; a lock-producing fixture was not exercised.": (
        "Round 1-3: not run: Reputation read exists; a lock-producing fixture was not exercised."
    ),
    "Sau mọi case của BF07": "After all BF07 cases",

    # ── SecurityApi ──
    "Đầu nhóm audit-evidence-log: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (audit-evidence-log): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Chạy sau SEC-100": "Runs after SEC-100",
    "Round 1: 401/403 body rỗng thay vì ErrorEnvelope": "Round 1: 401/403 empty body instead of ErrorEnvelope",
    "Round 2: 401/403 body rỗng thay vì ErrorEnvelope": "Round 2: 401/403 empty body instead of ErrorEnvelope",
    "Round 3: 401/403 body rỗng thay vì ErrorEnvelope": "Round 3: 401/403 empty body instead of ErrorEnvelope",
    "Round 1-3: HTTP 403; body rỗng; lỗi: expected '' to be an object": (
        "Round 1-3: HTTP 403; empty body; error: expected '' to be an object"
    ),
    "Chạy sau SEC-110": "Runs after SEC-110",
    "Round 3: không có response; lỗi: Fixture 010-settled-contract returned error ": (
        "Round 3: no response; error: Fixture 010-settled-contract returned error"
    ),
    "Chạy sau SEC-130": "Runs after SEC-130",
    "Round 3: HTTP 404; AUDIT_TRAIL_NOT_FOUND: No evidence log for this contract; lỗi: expected 404 to equal 200": (
        "Round 3: HTTP 404; AUDIT_TRAIL_NOT_FOUND: No evidence log for this contract; error: expected 404 to equal 200"
    ),
    "Chạy sau SEC-140": "Runs after SEC-140",
    (
        'Round 3: HTTP 200; {"content": [{"recordId": "b49078c2-e0c4-4c83-a17e-1da61d70200d", "globalSeq": 3, '
        '"subjectType": "SYSTEM", "subjectId": "SYSTEM", "sourceType": "AUDIT_VIEWED...; lỗi: Target cannot be null '
        "or undefined."
    ): (
        'Round 3: HTTP 200; {"content": [{"recordId": "b49078c2-e0c4-4c83-a17e-1da61d70200d", "globalSeq": 3, '
        '"subjectType": "SYSTEM", "subjectId": "SYSTEM", "sourceType": "AUDIT_VIEWED...; error: Target cannot be '
        "null or undefined."
    ),
    "Chạy sau SEC-150": "Runs after SEC-150",
    "Chạy sau SEC-160": "Runs after SEC-160",
    (
        'Round 3: HTTP 200; {"scope": "GLOBAL", "contractId": null, "status": "INTACT", "checkedRecords": 2, '
        '"brokenRecordId": null, "brokenReason": null, "checkedAt": "2026-11-27T20:51...; lỗi: expected 2 to equal NaN'
    ): (
        'Round 3: HTTP 200; {"scope": "GLOBAL", "contractId": null, "status": "INTACT", "checkedRecords": 2, '
        '"brokenRecordId": null, "brokenReason": null, "checkedAt": "2026-11-27T20:51...; error: expected 2 to equal NaN'
    ),
    "Đầu nhóm authn: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (authn): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Chạy sau SEC-010": "Runs after SEC-010",
    "Round 1-3: HTTP 401; body rỗng; lỗi: expected '' to be an object": (
        "Round 1-3: HTTP 401; empty body; error: expected '' to be an object"
    ),
    "Chạy sau SEC-020": "Runs after SEC-020",
    "Giới hạn: khoá tạm sau đúng 5 lần sai mật khẩu": "Boundary: temporary lock after exactly 5 wrong passwords",
    "Đầu nhóm authz-role-matrix: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (authz-role-matrix): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Chạy sau SEC-040": "Runs after SEC-040",
    "Chạy sau SEC-041": "Runs after SEC-041",
    "Chạy sau SEC-042": "Runs after SEC-042",
    "Chạy sau SEC-043": "Runs after SEC-043",
    "Chạy sau SEC-044": "Runs after SEC-044",
    "Chạy sau SEC-045": "Runs after SEC-045",
    "Chạy sau SEC-060": "Runs after SEC-060",
    "Chạy sau SEC-061": "Runs after SEC-061",
    "Chạy sau SEC-062": "Runs after SEC-062",
    "Chạy sau SEC-063": "Runs after SEC-063",
    "Chạy sau SEC-064": "Runs after SEC-064",
    "Đầu nhóm idempotency-replay: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (idempotency-replay): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Chạy sau SEC-080": "Runs after SEC-080",
    "Đầu nhóm info-leak: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (info-leak): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Đầu nhóm input-validation: buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)": (
        "Group setup (input-validation): buyer1, seller1, operator1, inspector1, arbitrator1, admin1 (Report 5.0 §II.1.1)"
    ),
    "Giới hạn: page = -1 (min - 1)": "Boundary: page = -1 (min - 1)",
    "Sau mọi case của Security": "After all Security cases",
}
