"""Report 5.2 (IT/ST test cases): builds Report-5.2_Test_Report.xlsx from the Bruno collection in backend/bruno.

Report 5.0 section II.2 puts the unit test cases in Report-5.1 and the other levels (IT, ST, AT) in a separate test
report workbook; this script writes that workbook. Styles come from Report-5.1 (its Cover, Test Cases, Test Statistics
and one module sheet are reused as templates), so both workbooks look the same.

One row = one scenario. A row whose Bruno request exists names the .bru file(s) in Note; a row designed with a test
technique (BVA, EP, error guessing) that has no request yet says "Chưa có request Bruno" so the gap is counted.
Test level is in the ID: ITC = integration test (one service called directly, X-Gateway-Secret / X-Internal-Secret or
the broker), STC = system test (through api-gateway with a Keycloak token, full Docker Compose stack).

Run: python docs/reports/tools/r5_2_test_report.py
"""
from __future__ import annotations

import copy
import math
from pathlib import Path

import openpyxl
from openpyxl.worksheet.datavalidation import DataValidation

REPORTS = Path(__file__).resolve().parents[1]
SRC = REPORTS / "Report-5.1_Unit_Test.xlsx"
OUT = REPORTS / "Report-5.2_Test_Report.xlsx"

CREATOR = "Nguyen Hai Long"
ISSUE_DATE = "24/11/2026"
NO_BRUNO = "Chưa có request Bruno"

ENV = (
    "1. Toàn bộ stack: docker compose up trong backend/ (api-gateway 8080, user 8081, product 8082, contract 8083, "
    "notification 8085, Keycloak 8180 realm agricontract, RabbitMQ 5672/15672, MailHog 8025, MySQL 8.0 mỗi service)\n"
    "2. Bruno CLI: cd backend/bruno && bru run <thư mục> --env local (biến trong environments/local.bru)\n"
    "3. Tài khoản Keycloak (agricontract-realm.json): buyer1, seller1, seller2 (người lạ), admin1, inspector1, "
    "operator1, arbitrator1; mật khẩu pass123\n"
    "4. Seed: backend/scripts/seed/01-product-db.sql, 02-user-db.sql (run-e2e.sh nạp lại trước mỗi lượt)\n"
    "5. product-service chạy với PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0, PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000 "
    "để plot vừa ghim được duyệt trước khi tạo listing\n"
    "6. notification-service: chạy backend/bruno/notification-service/reset.sh trước lượt chạy\n"
    "7. ITC = Integration test (gọi thẳng 1 service / broker); STC = System test (qua api-gateway với JWT)"
)

# (module, sheet, description, pre-condition, [(group, [(id, description, procedure, expected, pre, note)])])
SHEETS = [
    ("api-gateway", "GatewayAccess", "Gateway: route public/protected, xác thực JWT, thay header danh tính",
     "Stack chạy đủ; realm agricontract có buyer1", [
        ("Keycloak token và route", [
            ("STC001", "Lấy access token bằng password grant",
             "1. POST {keycloak}/realms/agricontract/protocol/openid-connect/token\n2. grant_type=password, "
             "client_id=agricontract-frontend, username=buyer1, password=pass123",
             "200; body có access_token (JWT)", "Keycloak chạy, buyer1 enabled",
             "Kỹ thuật: EP (thông tin đăng nhập hợp lệ)\nBruno: gateway/get-token.bru, contract/01-get-token-buyer.bru"),
            ("STC002", "Đăng nhập sai mật khẩu bị từ chối",
             "1. Như STC001 nhưng password=wrong-pass",
             "401; error=invalid_grant; không có access_token", "Như STC001",
             "Kỹ thuật: EP (lớp không hợp lệ), error guessing\n" + NO_BRUNO),
            ("STC003", "Route public không cần token",
             "1. GET {base_url}/api/v1/listings không gửi Authorization",
             "200; trả trang listing", "Gateway chạy",
             "Kỹ thuật: EP (route public)\nBruno: gateway/public-listings.bru"),
            ("STC004", "Route protected không có token",
             "1. GET {base_url}/api/v1/contracts không gửi Authorization",
             "401; request không tới contract-service", "Gateway chạy",
             "Kỹ thuật: Security, EP\nBruno: gateway/protected-no-token.bru"),
            ("STC005", "Route protected với token hợp lệ",
             "1. Lấy token buyer1 (STC001)\n2. GET {base_url}/api/v1/contracts, Bearer token",
             "200; body phân trang (content, totalElements)", "Token buyer1 còn hạn",
             "Kỹ thuật: EP\nBruno: gateway/protected-with-token.bru (đang chỉ assert != 401, nên assert = 200)"),
            ("STC006", "Header danh tính giả qua gateway bị thay bằng claim JWT",
             "1. Token buyer1\n2. GET {base_url}/api/v1/users/me, Bearer token buyer1, kèm header "
             "X-User-Id = sub của seller1 và X-User-Role = ADMIN",
             "200; profile trả về là của buyer1 (userId = sub buyer1), không phải seller1",
             "buyer1 đã có profile trong user_db",
             "Kỹ thuật: Security, error guessing (UserContextInjectionFilter)\n" + NO_BRUNO),
        ]),
    ]),
    ("user-service", "UserProfile", "Đăng ký profile tổ chức, xem profile của mình và profile công khai",
     "Token seller1 (password grant); user_db KHÔNG có profile seller1 (seed 02-user-db.sql có sẵn seller1 → "
     "xoá dòng đó trước STC001)", [
        ("POST /api/v1/users/register", [
            ("STC001", "Đăng ký lần đầu",
             "1. POST {base_url}/api/v1/users/register, Bearer token seller1\n2. Body: organizationName "
             "\"Nong Trai ABC\", email seller1@test.com, phone 0901234567, address \"Ha Noi\"",
             "201; body không bọc ApiResponse (không có data); userId là chuỗi; role SELLER; "
             "verificationStatus PENDING", "seller1 chưa có profile",
             "Kỹ thuật: EP (hợp lệ)\nBruno: user-service/00-get-seller-token.bru, 00-register-seller.bru"),
            ("STC002", "Đăng ký lặp lại cùng tài khoản (idempotent)",
             "1. Gửi lại đúng request STC001", "200; userId trùng STC001; không tạo profile thứ hai",
             "STC001 đã chạy", "Kỹ thuật: Error guessing (gửi lại)\nBruno: user-service/01-register-idempotency.bru"),
        ]),
        ("GET /api/v1/users", [
            ("STC003", "Xem profile của mình", "1. GET {base_url}/api/v1/users/me, Bearer token seller1",
             "200; userId = userId STC001; có phone và address", "STC001 đã chạy",
             "Kỹ thuật: EP\nBruno: user-service/02-get-my-profile.bru"),
            ("STC004", "Xem profile người khác chỉ có dữ liệu công khai",
             "1. GET {base_url}/api/v1/users/{userId STC001}, Bearer token seller1",
             "200; có userId, organizationName, role, verificationStatus; KHÔNG có email, phone, address",
             "STC001 đã chạy", "Kỹ thuật: Security (IDOR, lộ PII)\nBruno: user-service/03-get-profile-by-id.bru"),
            ("STC005", "Xem profile khi không có token", "1. GET {base_url}/api/v1/users/me không Authorization",
             "401", "Gateway chạy", "Kỹ thuật: Security\nBruno: user-service/04-unauthorized-no-secret.bru"),
        ]),
    ]),
    ("product-service", "Category", "Đề xuất, duyệt, từ chối danh mục và gán commodity",
     "Gọi thẳng product-service :8082 với X-Gateway-Secret = gateway_secret; X-User-Id/X-User-Role giả lập gateway; "
     "chưa có danh mục \"Cà Phê\" do Bruno tạo (seed có \"Cà phê\" → chạy trên DB chưa seed hoặc đổi tên)", [
        ("POST /api/v1/categories", [
            ("ITC001", "Seller đề xuất danh mục mới",
             "1. POST /api/v1/categories, X-User-Role SELLER\n2. Body: {\"name\": \"Cà Phê\"}",
             "201; categoryId là chuỗi; status PENDING", "Tên chưa tồn tại",
             "Kỹ thuật: EP (hợp lệ)\nBruno: product-service/00-propose-category.bru"),
            ("ITC002", "Tên trùng khác dấu/hoa thường bị chặn",
             "1. POST /api/v1/categories, X-User-Role BUYER\n2. Body: {\"name\": \"ca phe\"}",
             "409; body ErrorEnvelope có code và traceId", "ITC001 + ITC004 đã chạy (danh mục đã APPROVED)",
             "Kỹ thuật: EP (chuẩn hoá tên), error guessing\nBruno: product-service/02-propose-category-duplicate.bru"),
            ("ITC003", "Tên rỗng bị từ chối", "1. POST /api/v1/categories\n2. Body: {\"name\": \"\"}",
             "400; code VALIDATION_FAILED; không tạo danh mục", "N/A",
             "Kỹ thuật: BVA (độ dài 0), @NotBlank\n" + NO_BRUNO),
        ]),
        ("PUT /api/v1/admin/categories/{id}", [
            ("ITC004", "Operator duyệt và gán commodity",
             "1. PUT /api/v1/admin/categories/{category_id}/approve, X-User-Role OPERATOR\n"
             "2. Body: {\"commodity\": \"COFFEE\"}",
             "200; status APPROVED; commodity COFFEE", "ITC001 đã chạy (PENDING)",
             "Kỹ thuật: State transition PENDING → APPROVED\nBruno: product-service/01-approve-category.bru"),
            ("ITC005", "Gán commodity cho danh mục đã có commodity",
             "1. PUT /api/v1/admin/categories/{category_id}/commodity, X-User-Role OPERATOR\n"
             "2. Body: {\"commodity\": \"COFFEE\"}",
             "409; ErrorEnvelope có code và traceId", "ITC004 đã chạy",
             "Kỹ thuật: State transition (chuyển không hợp lệ)\n"
             "Bruno: product-service/01b-assign-commodity-already-mapped.bru"),
            ("ITC006", "Operator từ chối danh mục có lý do",
             "1. POST /api/v1/categories {\"name\": \"Rau Củ Không Rõ\"} (SELLER)\n"
             "2. PUT /api/v1/admin/categories/{id}/reject (OPERATOR), body {\"reason\": "
             "\"Tên không rõ ràng, đề nghị đặt lại\"}",
             "Bước 1: 201 PENDING\nBước 2: 200; status REJECTED; rejectionReason đúng chuỗi đã gửi", "N/A",
             "Kỹ thuật: State transition PENDING → REJECTED\n"
             "Bruno: product-service/03-propose-category-for-reject.bru, 04-reject-category.bru"),
            ("ITC007", "Seller gọi API duyệt danh mục",
             "1. PUT /api/v1/admin/categories/{id}/approve, X-User-Role SELLER, body {\"commodity\": \"COFFEE\"}",
             "403; code FORBIDDEN; danh mục vẫn PENDING", "Có danh mục PENDING",
             "Kỹ thuật: Security (sai role)\n" + NO_BRUNO),
            ("ITC008", "Từ chối với lý do rỗng",
             "1. PUT /api/v1/admin/categories/{id}/reject (OPERATOR), body {\"reason\": \"\"}",
             "400; code VALIDATION_FAILED; danh mục vẫn PENDING", "Có danh mục PENDING",
             "Kỹ thuật: BVA (độ dài 0)\n" + NO_BRUNO),
        ]),
    ]),
    ("product-service", "Product", "Tạo sản phẩm và thay ảnh sản phẩm",
     "Như sheet Category; danh mục {category_id} APPROVED (COFFEE), {category_id_reject} REJECTED; X-User-Role SELLER", [
        ("POST /api/v1/products", [
            ("ITC001", "Tạo sản phẩm với danh mục đã duyệt và 2 ảnh",
             "1. POST /api/v1/products\n2. Body: name \"Ca phe Robusta Dak Lak\", unit kg, categoryId {category_id}, "
             "2 imageUrls, cropYear 2026, harvestSeason \"Nien vu 2026/27\", certifications [\"4C (cam ket)\"]",
             "201; productId là chuỗi; name đúng; categoryId đúng; images có 2 phần tử", "Danh mục APPROVED",
             "Kỹ thuật: EP (hợp lệ)\nBruno: product-service/05-create-product.bru"),
            ("ITC002", "Tạo sản phẩm với danh mục bị từ chối",
             "1. POST /api/v1/products, categoryId {category_id_reject}, 1 ảnh",
             "400; ErrorEnvelope có code và traceId; không tạo sản phẩm", "Danh mục REJECTED",
             "Kỹ thuật: EP (danh mục không hợp lệ)\n"
             "Bruno: product-service/06-create-product-category-not-approved.bru"),
            ("ITC003", "cropYear ở biên dưới và ngoài biên",
             "1. Như ITC001 với cropYear = 2000\n2. Như ITC001 với cropYear = 1999",
             "Bước 1: 201\nBước 2: 400; code VALIDATION_FAILED", "Danh mục APPROVED",
             "Kỹ thuật: BVA (@Min 2000, @Max 2100; nên thêm 2100/2101)\n" + NO_BRUNO),
        ]),
        ("PUT /api/v1/products/{id}/images", [
            ("ITC004", "Thay toàn bộ ảnh bằng 1 ảnh",
             "1. PUT /api/v1/products/{product_id}/images\n2. Body: imageUrls [\"https://cdn.agricontract.test/"
             "products/ca-phe-robusta-new-cover.jpg\"]",
             "200; images có đúng 1 phần tử là URL mới", "ITC001 đã chạy",
             "Kỹ thuật: BVA (min = 1)\nBruno: product-service/13-update-product-images.bru"),
            ("ITC005", "Danh sách ảnh rỗng", "1. PUT .../images, body {\"imageUrls\": []}",
             "400; ErrorEnvelope có code và traceId; ảnh không đổi", "ITC001 đã chạy",
             "Kỹ thuật: BVA (0 < min)\nBruno: product-service/14-update-product-images-invalid.bru"),
            ("ITC006", "Số ảnh tại biên trên và vượt biên",
             "1. PUT .../images với 5 URL\n2. PUT .../images với 6 URL",
             "Bước 1: 200; images có 5 phần tử\nBước 2: 400; code VALIDATION_FAILED; ảnh giữ 5",
             "ITC001 đã chạy", "Kỹ thuật: BVA (@Size max = 5)\n" + NO_BRUNO),
        ]),
    ]),
    ("product-service", "PlotListing", "Ghim lô đất, đăng listing cà phê, xem và sửa listing",
     "Như sheet Product; sản phẩm {product_id} đã tạo; product-service chạy với PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0", [
        ("POST /api/v1/plots", [
            ("ITC001", "Ghim lô đất thủ công",
             "1. POST /api/v1/plots, Idempotency-Key bruno-plot-ho-nguyen-van-a\n2. Body: householdLabel "
             "\"Hộ Nguyễn Văn A\", lat 12.666789, long 108.050123, areaHectares 1.5, accuracyMeters 8.5, "
             "declaredProvince DAK_LAK",
             "201 (lần đầu) hoặc 200 (đã ghim); registryEntryId là chuỗi; geometryType POINT; source MANUAL_PIN; "
             "geometryHash là SHA-256 hex 64 ký tự", "N/A",
             "Kỹ thuật: EP, error guessing (gửi lại)\nBruno: product-service/06b-register-plot.bru"),
        ]),
        ("POST /api/v1/listings", [
            ("ITC002", "Tạo listing cà phê",
             "1. POST /api/v1/listings, Idempotency-Key mới\n2. Body: productId, offeredQuantity 500, "
             "minOrderQuantity 100, askingUnitPrice \"15000.00\", registryEntryIds [{plot_id}], "
             "declaredQualitySpec ROBUSTA (ẩm 12.5, tạp chất 0.5, đen vỡ 5.0)",
             "201; sellerId = sub seller1; unitOfMeasure KG; offeredQuantity = quantityAvailable = 500; "
             "askingUnitPrice \"15000.00\"; status AVAILABLE; commodity COFFEE; cropYear 2026; "
             "không có priceFloor/deliveryDeadline/coverImageUrl", "Plot {plot_id} đã qua kiểm tra địa lý",
             "Kỹ thuật: EP (hợp lệ)\nBruno: product-service/07-create-listing.bru"),
            ("ITC003", "Gửi lại cùng Idempotency-Key và body",
             "1. Gửi lại đúng request ITC002", "200; listingId = listing ITC002; không tạo listing thứ hai",
             "ITC002 đã chạy", "Kỹ thuật: Error guessing (retry)\nBruno: product-service/07b-create-listing-replay.bru"),
            ("ITC004", "Thiếu Idempotency-Key", "1. Như ITC002 nhưng bỏ header Idempotency-Key",
             "400; code MALFORMED_REQUEST; có traceId", "N/A",
             "Kỹ thuật: EP (thiếu header bắt buộc)\nBruno: product-service/07c-create-listing-missing-key.bru"),
            ("ITC005", "Giá chào phải đúng 2 chữ số thập phân",
             "1. Như ITC002 (key mới) với askingUnitPrice \"15000.0\"\n2. Như ITC002 (key mới) với \"15000.000\"",
             "Cả 2 bước: 400; code VALIDATION_FAILED; không tạo listing", "N/A",
             "Kỹ thuật: BVA (số chữ số thập phân 1 / 2 / 3)\n" + NO_BRUNO),
            ("ITC006", "minOrderQuantity tại biên offeredQuantity",
             "1. Như ITC002 (key mới) với minOrderQuantity = 500\n"
             "2. Như ITC002 (key mới) với minOrderQuantity = 500.001",
             "Bước 1: 201\nBước 2: 400; code VALIDATION_FAILED", "N/A",
             "Kỹ thuật: BVA (minOrderQuantity <= offeredQuantity)\n" + NO_BRUNO),
            ("ITC007", "offeredQuantity bằng 0 và đơn vị khác KG",
             "1. Như ITC002 (key mới) với offeredQuantity = 0\n2. Như ITC002 (key mới) với unitOfMeasure \"TAN\"",
             "Cả 2 bước: 400; code VALIDATION_FAILED", "N/A",
             "Kỹ thuật: BVA (> 0), EP (đơn vị không hợp lệ)\n" + NO_BRUNO),
        ]),
        ("GET /api/v1/listings", [
            ("ITC008", "Xem chi tiết listing", "1. GET /api/v1/listings/{listing_id}",
             "200; listingId đúng; status AVAILABLE", "ITC002 đã chạy",
             "Kỹ thuật: EP\nBruno: product-service/08-get-listing.bru"),
            ("ITC009", "Danh sách listing đang mở", "1. GET /api/v1/listings",
             "200; content là mảng, ít nhất 1 phần tử; totalElements >= 1", "Có listing AVAILABLE",
             "Kỹ thuật: EP\nBruno: product-service/09-get-active-listings.bru"),
            ("ITC010", "Danh sách listing của seller", "1. GET /api/v1/listings/seller, X-User-Id seller1",
             "200; mọi phần tử có sellerId = sub seller1", "ITC002 đã chạy",
             "Kỹ thuật: Security (lọc theo chủ sở hữu)\nBruno: product-service/10-get-seller-listings.bru"),
            ("ITC011", "Kích thước trang tại biên",
             "1. GET /api/v1/listings?size=100\n2. GET /api/v1/listings?size=101\n3. GET /api/v1/listings?size=0",
             "Bước 1: 200\nBước 2, 3: 400; code VALIDATION_FAILED", "N/A",
             "Kỹ thuật: BVA (@Min 1, @Max 100)\n" + NO_BRUNO),
        ]),
        ("PUT /api/v1/listings/{id}", [
            ("ITC012", "Seller sửa khối lượng và giá chào",
             "1. PUT /api/v1/listings/{listing_id}, Idempotency-Key mới\n2. Body như ITC002 với "
             "offeredQuantity 600, askingUnitPrice \"15500.00\"",
             "200; offeredQuantity = quantityAvailable = 600; askingUnitPrice \"15500.00\"; status AVAILABLE; "
             "listingVersionToken mới khác rỗng", "ITC002 đã chạy, chưa có reservation",
             "Kỹ thuật: EP\nBruno: product-service/11-update-listing.bru"),
            ("ITC013", "Gửi lại request sửa cùng Idempotency-Key",
             "1. Gửi lại đúng request ITC012", "200; listingVersionToken bằng token ITC012 (không sửa lần 2)",
             "ITC012 đã chạy", "Kỹ thuật: Error guessing (retry)\nBruno: product-service/11b-update-listing-replay.bru"),
        ]),
    ]),
    ("contract-service", "ContractOffer", "Tạo offer hợp đồng, xem, liệt kê, chặn gọi vòng gateway, ký cần đăng nhập mới",
     "Token buyer1, seller1, seller2 (người lạ) lấy bằng password grant (contract/01-03); listing {listing_id} là "
     "listing COFFEE, AVAILABLE, quantityAvailable >= 2500.5 kg; biến listing_version_token = listingVersionToken "
     "của GET /api/v1/listings/{listing_id} (local.bru chưa khai báo biến này và listing seed 51ef… là gạo 500 kg "
     "nên phải tạo listing trước)", [
        ("Chặn gọi vòng gateway", [
            ("ITC001", "Gọi thẳng contract-service không có X-Gateway-Secret",
             "1. POST {contract_service_url}/api/v1/contracts, không header\n2. Body {\"listingId\": "
             "\"any-listing\", \"terms\": {}}",
             "401 hoặc 403; không tạo hợp đồng", "contract-service :8083 mở cổng ra host",
             "Kỹ thuật: Security\nBruno: contract/04-bypass-gateway-direct-call.bru"),
            ("ITC002", "Gọi thẳng với X-User-Id/X-User-Role giả, không secret",
             "1. Như ITC001, thêm X-User-Id = sub buyer1, X-User-Role BUYER",
             "401 hoặc 403; không tạo hợp đồng", "Như ITC001",
             "Kỹ thuật: Security (giả mạo header)\nBruno: contract/05-bypass-gateway-fake-header.bru"),
        ]),
        ("POST /api/v1/contracts", [
            ("STC001", "Buyer tạo offer cà phê 2 đợt giao",
             "1. POST {base_url}/api/v1/contracts, Bearer buyer1, Idempotency-Key 3c0f2b8e-…7a90\n2. Body: "
             "listingId, listingVersionToken, terms cà phê (agreedPrice \"95000.00\", 2 milestone 1000 và 1500.5 kg, "
             "priceBand 90000–100000, LegalProfile COFFEE_SALE)",
             "201; status OFFERED; reservedQuantity 2500.5; buyerId = sub buyer1; listing giảm quantityAvailable "
             "2500.5", "Listing COFFEE đủ 2500.5 kg; token phiên bản đúng",
             "Kỹ thuật: EP, State transition (→ OFFERED)\nBruno: contract/06-create-contract.bru"),
            ("STC002", "Gửi lại cùng Idempotency-Key và body",
             "1. Gửi lại đúng request STC001",
             "201; contractId = STC001; quantityAvailable của listing không giảm lần 2", "STC001 đã chạy",
             "Kỹ thuật: Error guessing (retry)\nBruno: contract/07-create-contract-idempotent.bru (chưa kiểm "
             "quantityAvailable)"),
            ("STC003", "Cùng Idempotency-Key nhưng body khác",
             "1. Như STC001 với agreedPrice \"96000.00\", giữ Idempotency-Key cũ",
             "409; code IDEMPOTENCY_KEY_REUSED; không tạo hợp đồng mới", "STC001 đã chạy",
             "Kỹ thuật: Error guessing\n" + NO_BRUNO),
            ("STC004", "listingVersionToken cũ",
             "1. Seller sửa listing (token đổi)\n2. Buyer tạo offer với token cũ, Idempotency-Key mới",
             "409; code LISTING_VERSION_MISMATCH; không giữ hàng", "Listing AVAILABLE",
             "Kỹ thuật: State transition, error guessing (docs của 06-create-contract.bru)\n" + NO_BRUNO),
            ("STC005", "Tổng khối lượng milestone vượt khối lượng còn lại",
             "1. Tạo offer có tổng committedQuantity = quantityAvailable + 0.001",
             "409; code INSUFFICIENT_LISTING_QUANTITY; quantityAvailable không đổi",
             "Listing AVAILABLE, biết quantityAvailable",
             "Kỹ thuật: BVA (= còn lại → 201; còn lại + 0.001 → 409)\n" + NO_BRUNO),
        ]),
        ("GET /api/v1/contracts", [
            ("STC006", "Người không phải bên hợp đồng xem chi tiết",
             "1. GET {base_url}/api/v1/contracts/{contract_id}, Bearer seller2", "403", "STC001 đã chạy",
             "Kỹ thuật: Security (IDOR)\nBruno: contract/08-get-contract-as-stranger.bru"),
            ("STC007", "Xem hợp đồng không tồn tại",
             "1. GET {base_url}/api/v1/contracts/non-existent-contract-id-12345, Bearer buyer1",
             "404; code CONTRACT_NOT_FOUND", "N/A",
             "Kỹ thuật: EP (id không tồn tại)\nBruno: contract/30-get-nonexistent-contract.bru"),
            ("STC008", "Buyer liệt kê hợp đồng",
             "1. GET {base_url}/api/v1/contracts?page=0&size=20, Bearer buyer1",
             "200; có content (mảng) và totalElements; chứa {contract_id}", "STC001 đã chạy",
             "Kỹ thuật: EP\nBruno: contract/28-list-contracts-as-buyer.bru"),
            ("STC009", "Seller liệt kê hợp đồng (role=SELLER)",
             "1. GET {base_url}/api/v1/contracts?role=SELLER&page=0&size=20, Bearer seller1",
             "200; phân trang; chứa {contract_id}", "STC001 đã chạy",
             "Kỹ thuật: EP\nBruno: contract/29-list-contracts-as-seller.bru"),
            ("STC010", "Lọc theo status OFFERED (buyer và seller)",
             "1. GET ...?status=OFFERED&page=0&size=10, Bearer buyer1\n"
             "2. GET ...?role=SELLER&status=OFFERED&page=0&size=10, Bearer seller1",
             "Cả 2: 200; mọi phần tử có status OFFERED; bước 2 totalElements > 0", "STC001 đã chạy",
             "Kỹ thuật: EP (theo trạng thái)\n"
             "Bruno: contract/31-list-contracts-filter-by-status.bru, 32-list-contracts-seller-filter-by-status.bru"),
            ("STC011", "Tham số phân trang và status ngoài miền",
             "1. ...?size=100\n2. ...?size=101\n3. ...?size=0\n4. ...?page=-1\n5. ...?status=FOO",
             "Bước 1: 200\nBước 2–5: 400; code VALIDATION_FAILED", "Token buyer1",
             "Kỹ thuật: BVA (size 1..100, page >= 0), EP (status không thuộc enum)\n" + NO_BRUNO),
        ]),
        ("POST /api/v1/contracts/{id}/sign/verify", [
            ("STC012", "Ký với token không có auth_time (không đăng nhập lại)",
             "1. POST {base_url}/api/v1/contracts/{bất kỳ}/sign/verify, Bearer buyer1 (password grant), "
             "Idempotency-Key mới\n2. Body {\"otpId\": \"…\", \"otpCode\": \"123456\"}",
             "403; code STEP_UP_REQUIRED (kiểm tra trước khi tìm hợp đồng)", "Token password grant (không có auth_time)",
             "Kỹ thuật: Security (re-login trong 300 s)\nBruno: contract/11-sign-verify-without-fresh-login.bru"),
        ]),
    ]),
    ("notification-service", "NotificationEmail", "Email theo event category.* và email OTP ký hợp đồng",
     "Đã chạy backend/bruno/notification-service/reset.sh (xoá notification_logs, purge queue, xoá MailHog); "
     "notification-service đang consume; X-Internal-Secret = service_internal_secret", [
        ("Event category.*", [
            ("ITC001", "category.approved gửi email cho người đề xuất",
             "1. Publish lên exchange agricontract.events, routing key category.approved, payload eventId, "
             "categoryId, name, proposedByEmail = seller1@test.com\n2. Tìm trong MailHog theo categoryId",
             "Bước 1: routed = true\nBước 2: đúng 1 email tới seller1@test.com; subject "
             "\"[AgriContract] Category proposal approved\"", "reset.sh đã chạy",
             "Kỹ thuật: Event contract (consumer)\nBruno: notification-service/16-publish-category-approved.bru, "
             "17-verify-category-approved-email.bru"),
            ("ITC002", "category.rejected gửi email kèm lý do",
             "1. Publish category.rejected, proposedByEmail = buyer1@test.com, rejectionReason "
             "\"Trung ten voi danh muc da co\"\n2. Tìm trong MailHog theo categoryId",
             "Bước 1: routed = true\nBước 2: đúng 1 email tới buyer1@test.com; subject "
             "\"[AgriContract] Category proposal rejected\"; body chứa lý do", "reset.sh đã chạy",
             "Kỹ thuật: Event contract (consumer)\nBruno: notification-service/18-publish-category-rejected.bru, "
             "19-verify-category-rejected-email.bru"),
            ("ITC003", "Cùng eventId được giao lại không gửi email lần 2",
             "1. Publish lại đúng payload của ITC001 (cùng eventId)\n2. Tìm trong MailHog theo categoryId",
             "Vẫn đúng 1 email", "ITC001 đã chạy",
             "Kỹ thuật: Error guessing (at-least-once, dedup eventId)\n" + NO_BRUNO),
        ]),
        ("POST /internal/v1/notifications/otp-email", [
            ("ITC004", "Gửi email OTP ký hợp đồng",
             "1. POST {notification_service_url}/internal/v1/notifications/otp-email, X-Internal-Secret\n"
             "2. Body: requestId, recipientUserId, recipientEmail buyer1@test.com, contractId, otpCode 6 số, "
             "signedContentHash, expiresAt = now + 300 s\n3. Tìm trong MailHog theo contractId",
             "Bước 1: 200\nBước 3: đúng 1 email tới buyer1; subject \"[AgriContract] Your contract signing code\" "
             "không chứa mã; body có mã trên 1 dòng riêng và content hash", "reset.sh đã chạy",
             "Kỹ thuật: EP (hợp lệ)\nBruno: notification-service/20-send-otp-email.bru, 21-verify-otp-email.bru"),
            ("ITC005", "Gửi lại cùng requestId (contract-service retry)",
             "1. Gửi lại đúng request ITC004\n2. Tìm trong MailHog theo contractId",
             "Bước 1: 200\nBước 2: vẫn đúng 1 email", "ITC004 đã chạy",
             "Kỹ thuật: Error guessing (retry sau timeout)\n"
             "Bruno: notification-service/22-send-otp-email-repeat.bru, 23-verify-otp-email-not-duplicated.bru"),
            ("ITC006", "Thiếu X-Internal-Secret", "1. Như ITC004 nhưng bỏ X-Internal-Secret",
             "401; code UNAUTHORIZED; body đúng 7 khoá timestamp, status, error, code, message, path, traceId",
             "N/A", "Kỹ thuật: Security\nBruno: notification-service/24-otp-email-without-secret.bru"),
            ("ITC007", "otpCode sai định dạng không bị trả lại trong response",
             "1. Như ITC004 với otpCode \"12x4\"",
             "400; code VALIDATION_ERROR; message \"otpCode is invalid\"; response không chứa \"12x4\" và email",
             "N/A", "Kỹ thuật: EP (ký tự không phải số), Security (không lộ dữ liệu)\n"
             "Bruno: notification-service/25-otp-email-invalid-payload.bru"),
            ("ITC008", "Độ dài otpCode tại biên",
             "1. otpCode \"12345\" (5 số)\n2. otpCode \"1234567\" (7 số)\n3. otpCode \"000000\" (6 số, requestId mới)",
             "Bước 1, 2: 400; message \"otpCode is invalid\"\nBước 3: 200", "N/A",
             "Kỹ thuật: BVA (đúng 6 chữ số)\n" + NO_BRUNO),
            ("ITC009", "Độ dài requestId tại biên",
             "1. requestId 36 ký tự [A-Za-z0-9._:-]\n2. requestId 37 ký tự",
             "Bước 1: 200\nBước 2: 400; message \"requestId is invalid\"", "N/A",
             "Kỹ thuật: BVA (1..36 ký tự)\n" + NO_BRUNO),
        ]),
    ]),
]


def copy_style(src, dst):
    dst.font = copy.copy(src.font)
    dst.fill = copy.copy(src.fill)
    dst.border = copy.copy(src.border)
    dst.alignment = copy.copy(src.alignment)
    dst.number_format = src.number_format
    dst.protection = copy.copy(src.protection)


def clear_from(ws, first_row):
    for rng in list(ws.merged_cells.ranges):
        if rng.min_row >= first_row:
            ws.unmerge_cells(str(rng))
    if ws.max_row >= first_row:
        ws.delete_rows(first_row, ws.max_row - first_row + 1)


def build_module_sheet(ws, sheet_name, groups, styles):
    hdr, grp, data = styles
    ws["B2"] = sheet_name
    ws["B3"] = '=COUNTIF(A13:A1005,"ITC*")+COUNTIF(A13:A1005,"STC*")'
    ws["A8"], ws["B8"], ws["C8"], ws["D8"] = "Test Level", "IT", "ST", NO_BRUNO
    ws["B9"] = '=COUNTIF($A12:$A1003,"ITC*")'
    ws["C9"] = '=COUNTIF($A12:$A1003,"STC*")'
    ws["D9"] = f'=COUNTIF($P12:$P1003,"*{NO_BRUNO}*")'
    clear_from(ws, 11)
    ws.data_validations.dataValidation = []
    ws.conditional_formatting = type(ws.conditional_formatting)()
    headers = ["Test Case ID", "Test Case Description", "Test Case Procedure", "Expected Results", "Pre-conditions",
               "Round 1", "Test date", "Tester", "Round 2", "Test date", "Tester", "Round 3", "Test date", "Tester",
               "Defect IDs", "Note"]
    for c, h in enumerate(headers, start=1):
        cell = ws.cell(11, c, h)
        copy_style(hdr[c - 1], cell)
    r = 12
    for group, cases in groups:
        cell = ws.cell(r, 1, group)
        copy_style(grp, cell)
        r += 1
        for tc_id, desc, proc, exp, pre, note in cases:
            values = [tc_id, desc, proc, exp, pre, "Pending", None, None, None, None, None, None, None, None, None,
                      note]
            for c, v in enumerate(values, start=1):
                cell = ws.cell(r, c, v)
                copy_style(data[c - 1], cell)
            r += 1
    dv = DataValidation(type="list", formula1='"Passed,Failed,Pending,N/A"', allow_blank=True)
    dv.add(f"F13:F{r + 50}")
    dv.add(f"I13:I{r + 50}")
    dv.add(f"L13:L{r + 50}")
    ws.add_data_validation(dv)
    widths = {"A": 14.4, "B": 30, "C": 48, "D": 44, "E": 30, "P": 44}
    for col, w in widths.items():
        ws.column_dimensions[col].width = w
    fit_rows(ws, 11, r)


def fit_rows(ws, first, last):
    """Row heights follow the wrapped text (openpyxl cannot auto-fit; the template rows carry fixed heights)."""
    for k in [k for k in ws.row_dimensions if k >= first]:
        ws.row_dimensions[k].height = None
    for r in range(first, last):
        lines = 1
        for cell in ws[r]:
            if not isinstance(cell.value, str) or cell.value.startswith("="):
                continue
            width = ws.column_dimensions[cell.column_letter].width or 9
            per_line = max(1, int(width * 1.05))
            n = sum(max(1, math.ceil(len(part) / per_line)) for part in cell.value.split("\n"))
            lines = max(lines, n)
        ws.row_dimensions[r].height = lines * 13 + 4


def main():
    wb = openpyxl.load_workbook(SRC)
    tpl = wb["CreateContract"]
    hdr = [copy.copy(tpl.cell(11, c)) for c in range(1, 17)]
    grp = copy.copy(tpl.cell(12, 1))
    data = [copy.copy(tpl.cell(13, c)) for c in range(1, 17)]
    styles = (hdr, grp, data)
    keep = {"Cover", "Test Cases", "Test Statistics", "CreateContract"}
    for ws in list(wb.worksheets):
        if ws.title not in keep:
            wb.remove(ws)

    for _, sheet, _, _, groups in SHEETS:
        ws = wb.copy_worksheet(tpl)
        ws.title = sheet
        build_module_sheet(ws, sheet, groups, styles)
    wb.remove(tpl)

    # Cover
    cv = wb["Cover"]
    cv["B2"] = "TEST REPORT (INTEGRATION & SYSTEM TEST)"
    cv["F4"] = CREATOR
    cv["F5"] = ISSUE_DATE
    cv["B6"] = '=B5&"_TestReport_v1.0"'
    row_tpl = [copy.copy(cv.cell(11, c)) for c in range(1, 7)]
    clear_from(cv, 11)
    rec = [ISSUE_DATE, "v1.0", "Toàn bộ tài liệu", "A",
           "Bản đầu: test case IT/ST từ Bruno collection (backend/bruno) và case bổ sung theo BVA/EP/error "
           "guessing chưa có request; Round 1 chưa chạy (Pending)", "SRS v1.7, Report 5.0"]
    for c, v in enumerate(rec, start=1):
        copy_style(row_tpl[c - 1], cv.cell(11, c, v))

    # Test Cases list
    tl = wb["Test Cases"]
    tl["D1"] = "TEST CASE LIST"
    tl["D5"] = ENV
    row_tpl = [copy.copy(tl.cell(9, c)) for c in range(2, 7)]
    link_font = copy.copy(tl.cell(9, 4).font)
    clear_from(tl, 9)
    for i, (module, sheet, desc, pre, _) in enumerate(SHEETS, start=1):
        r = 8 + i
        for c, v in enumerate([i, module, sheet, desc, pre], start=2):
            cell = tl.cell(r, c, v)
            copy_style(row_tpl[c - 2], cell)
        tl.cell(r, 4).hyperlink = f"#'{sheet}'!A1"
        tl.cell(r, 4).font = link_font

    # Test Statistics
    st = wb["Test Statistics"]
    st["A2"] = "TEST REPORT STATISTICS"
    st["F4"] = CREATOR
    st["F6"] = ISSUE_DATE
    st["B7"] = ("Test case integration (ITC) và system (STC) của api-gateway, user-service, product-service, "
                "contract-service, notification-service, lấy từ Bruno collection backend/bruno; cột "
                f"\"{NO_BRUNO}\" đếm case thiết kế theo BVA/EP/error guessing chưa có request")
    st["F11"], st["G11"], st["H11"] = "IT", "ST", NO_BRUNO
    row_tpl = [copy.copy(st.cell(12, c)) for c in range(1, 10)]
    sub_tpl = [copy.copy(st.cell(164, c)) for c in range(1, 10)]
    foot_tpl = [copy.copy(st.cell(166, c)) for c in range(1, 10)]
    clear_from(st, 12)
    for i, (_, sheet, _, _, _) in enumerate(SHEETS, start=1):
        r = 11 + i
        vals = [i, sheet, f"='{sheet}'!B5", f"='{sheet}'!C5", f"='{sheet}'!D5", f"='{sheet}'!B9",
                f"='{sheet}'!C9", f"='{sheet}'!D9", f"='{sheet}'!B3"]
        for c, v in enumerate(vals, start=1):
            copy_style(row_tpl[c - 1], st.cell(r, c, v))
    last = 11 + len(SHEETS)
    sub = last + 1
    vals = [None, "Sub total"] + [f"=SUM({col}12:{col}{last})" for col in "CDEFGHI"]
    for c, v in enumerate(vals, start=1):
        copy_style(sub_tpl[c - 1], st.cell(sub, c, v))
    foot = [("Test coverage", f"=(C{sub}+D{sub})*100/(I{sub})"),
            ("Test successful coverage", f"=C{sub}*100/(I{sub})"),
            ("Integration test case", f"=F{sub}*100/I{sub}"),
            ("System test case", f"=G{sub}*100/I{sub}"),
            ("Case chưa có request Bruno", f"=H{sub}*100/I{sub}")]
    for k, (label, formula) in enumerate(foot):
        r = sub + 2 + k
        copy_style(foot_tpl[1], st.cell(r, 2, label))
        copy_style(foot_tpl[3], st.cell(r, 4, formula))
        copy_style(foot_tpl[4], st.cell(r, 5, "%"))

    wb.active = 0
    wb.save(OUT)
    total = sum(len(cases) for *_, groups in SHEETS for _, cases in groups)
    gap = sum(1 for *_, groups in SHEETS for _, cases in groups for c in cases if NO_BRUNO in c[5])
    print(f"{OUT.name}: {len(SHEETS)} sheets, {total} test cases, {gap} without a Bruno request")


if __name__ == "__main__":
    main()
