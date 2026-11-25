# Test cases of the Bruno collection (backend/bruno) for api-gateway, user-service and product-service.
# Same row shape as the data_*.py files: (pre-conditions, input, return, exception, type, note).
# Rows whose note is empty have no Bruno request yet; they come from boundary values and equivalence classes
# of the request validation.
# Every Bruno case is an integration test (ITC). "kind" says how the module's requests reach the service:
# DIRECT = the service called on its own port with X-Gateway-Secret, GATEWAY = through api-gateway with a Keycloak
# token. System tests follow the FE user flows (BF-01..BF-07) and are not in this workbook.
N, A, B = "Normal", "Abnormal", "Boundary"
NONE = "N/A"
DIRECT, GATEWAY = "direct", "gateway"
KIND_LABEL = {
    DIRECT: "IT gọi thẳng service (X-Gateway-Secret)",
    GATEWAY: "IT qua gateway (token Keycloak)",
}

MODULES = [
    {
        "sheet": "GatewayRouting",
        "module": "api-gateway",
        "kind": GATEWAY,
        "desc": "api-gateway: token Keycloak, route public/protected, thay header danh tính bằng claim JWT",
        "pre": "Stack docker compose chạy đủ; realm agricontract; gọi qua {{base_url}} (gateway 8080)",
        "groups": [
            ("POST /realms/agricontract/protocol/openid-connect/token", [
                (NONE, "grant_type=password, buyer1 / pass123", "200, có access_token", NONE, N,
                 "gateway/get-token.bru"),
                (NONE, "buyer1 / sai mật khẩu", "401 invalid_grant, không có access_token", NONE, A,
                 "gateway/get-token-wrong-password.bru"),
            ]),
            ("GET /api/v1/listings", [
                (NONE, "Không có token", "200", NONE, N, "gateway/public-listings.bru"),
            ]),
            ("GET /api/v1/contracts", [
                (NONE, "Không có token", "401, request không tới contract-service", NONE, A,
                 "gateway/protected-no-token.bru"),
                ("Token buyer1 còn hạn", "Bearer token buyer1", "200, body phân trang", NONE, N,
                 "gateway/protected-with-token.bru"),
            ]),
            ("GET /api/v1/users/me", [
                ("buyer1 đã có profile", "Token buyer1, kèm X-User-Id của seller1 và X-User-Role ADMIN",
                 "200, profile của buyer1 (header giả bị thay bằng claim JWT)", NONE, A,
                 "gateway/forged-identity-headers.bru"),
            ]),
        ],
    },
    {
        "sheet": "UserProfileApi",
        "module": "user-service",
        "kind": GATEWAY,
        "desc": "user-service: đăng ký profile tổ chức, xem profile của mình và profile công khai",
        "pre": "Qua gateway với token seller1 (password grant); seed 02-user-db.sql có sẵn profile seller1 "
               "(VERIFIED)",
        "groups": [
            ("POST /api/v1/users/register", [
                ("seller1 chưa có profile (xoá dòng seed của seller1)",
                 "Nong Trai ABC, seller1@test.com, 0901234567, Ha Noi",
                 "201, userId = sub của seller1; role SELLER; verificationStatus PENDING; không bọc ApiResponse",
                 NONE, N, "user-service/00-register-seller.bru"),
                ("Profile seller1 từ seed (VERIFIED)", "Nong Trai ABC, seller1@test.com, 0901234567, Ha Noi",
                 "200, userId = sub của seller1; role SELLER; không tạo profile mới", NONE, A,
                 "user-service/00-register-seller.bru"),
                ("Đã đăng ký", "Gửi lại đúng request", "200, cùng userId, không tạo profile thứ hai", NONE, A,
                 "user-service/01-register-idempotency.bru"),
            ]),
            ("GET /api/v1/users/me", [
                ("Đã đăng ký", "Token seller1", "200, profile đủ phone và address", NONE, N,
                 "user-service/02-get-my-profile.bru"),
                (NONE, "Không có token", "401", NONE, A, "user-service/04-unauthorized-no-secret.bru"),
            ]),
            ("GET /api/v1/users/{userId}", [
                ("Đã đăng ký", "userId của seller1", "200, có organizationName, role, verificationStatus; "
                 "không có email, phone, address", NONE, N, "user-service/03-get-profile-by-id.bru"),
            ]),
        ],
    },
    {
        "sheet": "CategoryApi",
        "module": "product-service",
        "kind": DIRECT,
        "desc": "product-service: đề xuất, duyệt, từ chối danh mục và gán commodity",
        "pre": "Gọi thẳng product-service 8082 với X-Gateway-Secret; X-User-Id/X-User-Role như gateway gửi; "
               "tên danh mục Bruno tạo có hậu tố {{run_tag}} của lần chạy (seed đã có \"Cà phê\")",
        "groups": [
            ("POST /api/v1/categories", [
                ("SELLER", "name \"Cà Phê Bruno {{run_tag}}\"", "201, categoryId; status PENDING", NONE, N,
                 "product-service/00-propose-category.bru"),
                ("Seed có \"Cà phê\" APPROVED; BUYER", "name \"ca phe\"", "409, ErrorEnvelope có code và traceId",
                 NONE, A, "product-service/02-propose-category-duplicate.bru"),
                ("SELLER", "name \"\"", "400 VALIDATION_FAILED, không tạo danh mục", NONE, B,
                 "product-service/00b-propose-category-blank-name.bru"),
            ]),
            ("PUT /api/v1/admin/categories/{id}/approve", [
                ("Danh mục PENDING; OPERATOR", "commodity COFFEE", "200, status APPROVED; commodity COFFEE",
                 NONE, N, "product-service/01-approve-category.bru"),
                ("Danh mục PENDING; SELLER", "commodity COFFEE", "403 FORBIDDEN, vẫn PENDING", NONE, A,
                 "product-service/03b-approve-category-as-seller.bru"),
            ]),
            ("PUT /api/v1/admin/categories/{id}/commodity", [
                ("Danh mục đã có commodity; OPERATOR", "commodity COFFEE", "409, ErrorEnvelope có code và traceId",
                 NONE, A, "product-service/01b-assign-commodity-already-mapped.bru"),
            ]),
            ("PUT /api/v1/admin/categories/{id}/reject", [
                ("Danh mục \"Rau Củ Không Rõ {{run_tag}}\" PENDING; OPERATOR", "reason \"Tên không rõ ràng, đề nghị đặt lại\"",
                 "200, status REJECTED; rejectionReason đúng chuỗi gửi lên", NONE, N,
                 "product-service/03-propose-category-for-reject.bru, 04-reject-category.bru"),
                ("Danh mục PENDING; OPERATOR", "reason \"\"", "400 VALIDATION_FAILED, vẫn PENDING", NONE, B,
                 "product-service/03c-reject-category-blank-reason.bru"),
            ]),
        ],
    },
    {
        "sheet": "ProductApi",
        "module": "product-service",
        "kind": DIRECT,
        "desc": "product-service: tạo sản phẩm và thay ảnh sản phẩm",
        "pre": "Như CategoryApi; X-User-Role SELLER; {{category_id}} APPROVED (COFFEE), {{category_id_reject}} REJECTED",
        "groups": [
            ("POST /api/v1/products", [
                ("Danh mục APPROVED", "\"Ca phe Robusta Dak Lak\", kg, 2 ảnh, cropYear 2026, "
                 "harvestSeason \"Nien vu 2026/27\", 1 chứng nhận", "201, productId; images 2 phần tử", NONE, N,
                 "product-service/05-create-product.bru"),
                ("Danh mục REJECTED", "categoryId = {{category_id_reject}}, 1 ảnh",
                 "400, ErrorEnvelope có code và traceId, không tạo sản phẩm", NONE, A,
                 "product-service/06-create-product-category-not-approved.bru"),
                ("Danh mục APPROVED", "cropYear 2000 (min)", "201", NONE, B,
                 "product-service/05b-create-product-crop-year-2000.bru"),
                ("Danh mục APPROVED", "cropYear 2100 (max)", "201", NONE, B,
                 "product-service/05c-create-product-crop-year-2100.bru"),
                ("Danh mục APPROVED", "cropYear 1999 (min - 1)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/05d-create-product-crop-year-1999.bru"),
                ("Danh mục APPROVED", "cropYear 2101 (max + 1)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/05e-create-product-crop-year-2101.bru"),
            ]),
            ("PUT /api/v1/products/{id}/images", [
                ("Sản phẩm có 2 ảnh", "1 URL", "200, images chỉ còn URL mới", NONE, B,
                 "product-service/13-update-product-images.bru"),
                ("Sản phẩm có ảnh", "imageUrls []", "400, ErrorEnvelope có code và traceId; ảnh không đổi", NONE, B,
                 "product-service/14-update-product-images-invalid.bru"),
                ("Sản phẩm có ảnh", "5 URL", "200, images 5 phần tử", NONE, B,
                 "product-service/15-update-product-images-five.bru"),
                ("Sản phẩm có ảnh", "6 URL", "400 VALIDATION_FAILED, ảnh không đổi", NONE, B,
                 "product-service/16-update-product-images-six.bru"),
            ]),
        ],
    },
    {
        "sheet": "PlotListingApi",
        "module": "product-service",
        "kind": DIRECT,
        "desc": "product-service: ghim lô đất, đăng listing cà phê, xem và sửa listing",
        "pre": "Như ProductApi; {{product_id}} đã tạo; product-service chạy với "
               "PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0, PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000",
        "groups": [
            ("POST /api/v1/plots", [
                (NONE, "Hộ Nguyễn Văn A, 12.666789 / 108.050123, 1.5 ha, DAK_LAK, Idempotency-Key cố định",
                 "201 (lần đầu) hoặc 200 (đã ghim); geometryType POINT; source MANUAL_PIN; geometryHash SHA-256 hex",
                 NONE, N, "product-service/06b-register-plot.bru"),
            ]),
            ("POST /api/v1/listings", [
                ("Plot đã qua kiểm tra địa lý", "offeredQuantity 500, minOrderQuantity 100, askingUnitPrice "
                 "\"15000.00\", ROBUSTA, Idempotency-Key mới",
                 "201, status AVAILABLE; quantityAvailable 500; commodity COFFEE; sellerId của seller1; "
                 "không có priceFloor/deliveryDeadline/coverImageUrl", NONE, N,
                 "product-service/07-create-listing.bru"),
                ("Đã tạo listing", "Gửi lại cùng Idempotency-Key và body", "200, cùng listingId, không tạo thêm",
                 NONE, A, "product-service/07b-create-listing-replay.bru"),
                (NONE, "Không có Idempotency-Key", "400 MALFORMED_REQUEST", NONE, A,
                 "product-service/07c-create-listing-missing-key.bru"),
                (NONE, "askingUnitPrice \"15000.0\" (1 chữ số thập phân)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/07d-create-listing-price-one-decimal.bru"),
                (NONE, "askingUnitPrice \"15000.000\" (3 chữ số thập phân)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/07e-create-listing-price-three-decimals.bru"),
                (NONE, "minOrderQuantity = offeredQuantity = 500", "201", NONE, B,
                 "product-service/07f-create-listing-min-order-equals-offered.bru"),
                (NONE, "minOrderQuantity 500.001 > offeredQuantity 500", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/07g-create-listing-min-order-above-offered.bru"),
                (NONE, "offeredQuantity 0", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/07h-create-listing-offered-zero.bru"),
                (NONE, "unitOfMeasure \"TAN\"", "400 VALIDATION_FAILED", NONE, A,
                 "product-service/07i-create-listing-unit-tan.bru"),
            ]),
            ("GET /api/v1/listings/{id}", [
                ("Listing đã tạo", "{{listing_id}}", "200, đúng listingId; status AVAILABLE", NONE, N,
                 "product-service/08-get-listing.bru"),
            ]),
            ("GET /api/v1/listings", [
                ("Có listing AVAILABLE", "Không tham số", "200, content ít nhất 1 phần tử; totalElements >= 1",
                 NONE, N, "product-service/09-get-active-listings.bru"),
                (NONE, "size = 100 (max)", "200", NONE, B, "product-service/09b-get-active-listings-size-100.bru"),
                (NONE, "size = 101 (max + 1)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/09c-get-active-listings-size-101.bru"),
                (NONE, "size = 0 (min - 1)", "400 VALIDATION_FAILED", NONE, B,
                 "product-service/09d-get-active-listings-size-0.bru"),
            ]),
            ("GET /api/v1/listings/seller", [
                ("seller1 có listing", "X-User-Id seller1", "200, mọi listing có sellerId của seller1", NONE, N,
                 "product-service/10-get-seller-listings.bru"),
            ]),
            ("PUT /api/v1/listings/{id}", [
                ("Listing chưa có reservation", "offeredQuantity 600, askingUnitPrice \"15500.00\", "
                 "Idempotency-Key mới", "200, quantityAvailable 600; listingVersionToken mới", NONE, N,
                 "product-service/11-update-listing.bru"),
                ("Đã sửa", "Gửi lại cùng Idempotency-Key và body", "200, listingVersionToken không đổi", NONE, A,
                 "product-service/11b-update-listing-replay.bru"),
            ]),
        ],
    },
]
