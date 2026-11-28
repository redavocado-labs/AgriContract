# Line-by-line Vietnamese -> English translation dictionary for Report 5.1 (Report-5.1_Unit_Test.xlsx).
# Keys are exact single lines (after splitting cell text on "\n") containing Vietnamese text.
# Quoted Vietnamese literal strings (e.g. 'Cà Phê', 'Hộ Lê Văn A') stay in single quotes.

TRANSLATIONS = {
    # ── Cover & Test Cases & Test Statistics ──
    "Toàn bộ tài liệu": "Whole document",
    "Bản đầu, lấy từ unit test trong backend/": "First version, extracted from unit tests in backend/",
    (
        "Round 2 (07/11) cho module cũ; thêm module mới (Round 1 theo ngày merge) và 3 service NestJS; Tester ghi "
        "theo người phụ trách service; 1 dòng = 1 kịch bản"
    ): (
        "Round 2 (07/11) for existing modules; added new modules (Round 1 dated by merge) and 3 NestJS services; "
        "Tester recorded by service owner; 1 row = 1 scenario"
    ),
    "Sheet của api-gateway, product-service, user-service": "Sheets of api-gateway, product-service, user-service",
    (
        "api-gateway, product-service, user-service: mỗi dòng gắn test method; thêm sheet cho UT chưa có (use case, "
        "consumer, client, @WebMvcTest); bỏ 2 sheet của test đã xoá (#431); Boundary chỉ cho min-1/min/max/max+1; "
        "Round 3 = lần chạy surefire có commit và kết quả từng test"
    ): (
        "api-gateway, product-service, user-service: each row links its test methods; added sheets for missing unit "
        "tests (use case, consumer, client, @WebMvcTest); dropped 2 sheets of deleted tests (#431); Boundary "
        "reserved for min-1/min/max/max+1; Round 3 = surefire run with commit and per-test results"
    ),
    "Test Statistics, Round 3 các service": "Test Statistics, Round 3 across services",
    (
        "Phân loại round: Round 1 lần chạy đầu, Round 2 confirmation, Round 3 regression; mỗi dòng ghi test và đọc "
        "Round 3 từ lần chạy thật (không còn Passed mặc định); UTC004 Failed → Passed theo DEF-01; thay đổi giữa "
        "Round 2 và Round 3 đếm bằng git"
    ): (
        "Round classification: Round 1 initial run, Round 2 confirmation, Round 3 regression; every row lists its "
        "tests and reads Round 3 from the real run (no default Passed); UTC004 Failed → Passed per DEF-01; changes "
        "between Round 2 and Round 3 counted via git"
    ),
    "Round 3 mọi sheet, Test Statistics": "Round 3 all sheets, Test Statistics",
    (
        "Round 3 = regression ngày 28/11/2026 trên 42a48dd5: mọi service chạy lại, 1224 dòng đọc lại kết quả từ lần "
        "chạy thật"
    ): (
        "Round 3 = regression run on 28/11/2026 on 42a48dd5: all services re-run, 1224 rows refreshed from actual "
        "test results"
    ),
    "5. Không cần DB/RabbitMQ: dependency ngoài được mock hoặc fake in-memory": (
        "5. No DB/RabbitMQ needed: external dependencies are mocked or faked in-memory"
    ),
    "6. Chạy: mvn test trong backend/ (target/surefire-reports); npx jest --json trong từng service Node": (
        "6. Run: mvn test in backend/ (target/surefire-reports); npx jest --json in each Node service"
    ),
    "7. Ngoài phạm vi: test dựng Spring context, Testcontainers, MySQL thật, *.int-spec.ts (integration)": (
        "7. Out of scope: tests booting a Spring context, Testcontainers, real MySQL, *.int-spec.ts (integration)"
    ),
    "Kiến trúc analytics-service: layout và chiều phụ thuộc": "analytics-service architecture: layout and dependency direction",
    "Quét mã nguồn src/": "Scan src/ source tree",
    "Fact kết thúc hợp đồng: ai vi phạm, ai yêu cầu": "Contract outcome facts: breaching party and requester",
    "Ingest event qua consumer thật, DB in-memory": "Ingest events through real consumer, in-memory DB",
    "DLQ monitor của analytics": "Analytics DLQ monitor",
    "Channel giả": "Fake channel",
    "Validator của analytics khớp 7 event schema đã chốt": "Analytics validator matches the 7 frozen event schemas",
    "GET /health của analytics": "Analytics GET /health",
    "Consumer analytics: validate, lưu, dedup, ack": "Analytics consumer: validate, persist, dedup, ack",
    "Broker và DB in-memory": "In-memory broker and DB",
    "Event không hợp lệ vào DLQ mà không để lại dấu": "Invalid events go to DLQ without leaving a trace",
    "Tắt analytics an toàn": "Graceful analytics shutdown",
    "Thứ tự khoá theo milestone khi chiếu dữ liệu": "Per-milestone lock ordering during projection",
    "Ánh xạ event thành lệnh ingest và lưu MySQL": "Map events to ingest commands and persist to MySQL",
    "Projection fact/dimension của analytics": "Analytics fact/dimension projections",
    "Wiring production: tắt, bật lại, replay": "Production wiring: shutdown, restart, replay",
    "Topology, contract, migration và wiring của analytics": "Analytics topology, contracts, migrations and wiring",
    "UserContextInjectionFilter: thay header danh tính bằng claim JWT, gắn gateway secret": (
        "UserContextInjectionFilter: replace identity headers with JWT claims, attach gateway secret"
    ),
    "Filter dựng với SECRET cố định; chain mock giữ lại request được forward": (
        "Filter built with fixed SECRET; mock chain captures forwarded request"
    ),
    "ClientIpRateLimitGatewayFilterFactory: giới hạn request theo IP client": (
        "ClientIpRateLimitGatewayFilterFactory: rate-limit requests by client IP"
    ),
    "Giới hạn N request / cửa sổ; clock điều khiển được": "Limit N requests / window; controllable clock",
    "CorsConfig: đọc allowlist origin": "CorsConfig: load origin allowlist",
    "Filter gán X-Correlation-Id cho mọi request và response": (
        "Filter attaches X-Correlation-Id to every request and response"
    ),
    "Exchange giả, chain giả": "Fake exchange, fake chain",
    "Giới hạn kích thước body theo route": "Per-route request body size limit",
    "Route giả, body giả": "Fake route, fake body",
    "LedgerEntry: quy tắc tạo bút toán (fund, milestone, remedy, id)": (
        "LedgerEntry: ledger entry creation rules (fund, milestone, remedy, id)"
    ),
    "AMOUNT và NOW cố định; id() sinh UUID ngẫu nhiên": "Fixed AMOUNT and NOW; id() generates random UUID",
    "Money (bank): chuẩn hoá 2 chữ số thập phân, giới hạn DECIMAL(18,2)": (
        "Money (bank): normalize to 2 decimal places, DECIMAL(18,2) bound"
    ),
    "LedgerCommandConsumer: parse lệnh bank.*_requested thành LedgerCommand": (
        "LedgerCommandConsumer: parse bank.*_requested commands into LedgerCommand"
    ),
    "Event bank.lock_requested hợp lệ theo schema đã chốt": "Valid bank.lock_requested event per frozen schema",
    "ProcessLedgerCommandUseCaseImpl: ghi bút toán 1 lần theo sourceEventId và báo kết quả": (
        "ProcessLedgerCommandUseCaseImpl: record ledger entry once by sourceEventId and report result"
    ),
    "Ledger và outbox là fake in-memory": "In-memory fake ledger and outbox",
    "Aggregate Contract: offer, counter-offer, ký, kích hoạt, domain event": (
        "Contract aggregate: offer, counter-offer, signing, activation, domain events"
    ),
    "Terms mẫu: 100 kg, 500.000 VND, giao sau 30 ngày, Grade A": (
        "Sample terms: 100 kg, 500,000 VND, delivery in 30 days, Grade A"
    ),
    "CreateContractUseCase: tạo hợp đồng từ listing": "CreateContractUseCase: create contract from listing",
    "ListingPort, UserPort, ContractRepository được mock": "Mocked ListingPort, UserPort, ContractRepository",
    "GetContractUseCase: xem chi tiết, kiểm quyền truy cập": "GetContractUseCase: view details, enforce access control",
    "ContractRepository mock; contract-1 giữa buyer-1 và seller-1": (
        "Mocked ContractRepository; contract-1 between buyer-1 and seller-1"
    ),
    "ListContractsUseCase: liệt kê theo buyer/seller, lọc status, phân trang": (
        "ListContractsUseCase: list by buyer/seller, filter status, paginate"
    ),
    "contract-1 ở OFFERED": "contract-1 in OFFERED",
    "ContractTerms (cà phê): luật điều khoản theo LegalProfile và hồ sơ chất lượng": (
        "ContractTerms (coffee): term rules by LegalProfile and quality spec"
    ),
    "Terms cà phê mẫu hợp lệ: Robusta, 2 đợt giao, có plot, LegalProfile thương mại": (
        "Valid sample coffee terms: Robusta, 2 delivery milestones, with plot, commercial LegalProfile"
    ),
    "Contract.reviseCoffeeTerms(): sửa điều khoản khi đàm phán": (
        "Contract.reviseCoffeeTerms(): revise terms during negotiation"
    ),
    "Hợp đồng cà phê OFFERED giữa buyer và seller; khối lượng đã giữ hàng 1.000 kg": (
        "Coffee contract in OFFERED between buyer and seller; reserved quantity 1,000 kg"
    ),
    "Contract.consentToSign(): ghi nhận đồng ý câu cam kết trước khi ký": (
        "Contract.consentToSign(): record consent statement before signing"
    ),
    "Hợp đồng cà phê NEGOTIATING; câu cam kết phiên bản hiện hành; vân tay terms hiện tại": (
        "Coffee contract in NEGOTIATING; current consent text version; current terms hash"
    ),
    "Contract.sign(): ghi chữ ký theo OTP challenge và vân tay terms": (
        "Contract.sign(): record signature with OTP challenge and terms hash"
    ),
    "Hợp đồng cà phê NEGOTIATING; OTP challenge hợp lệ của từng bên": (
        "Coffee contract in NEGOTIATING; valid OTP challenge per party"
    ),
    "Contract.withdraw(): rút offer trước chữ ký thứ hai": (
        "Contract.withdraw(): withdraw offer before the second signature"
    ),
    "Hợp đồng cà phê giữa buyer và seller": "Coffee contract between buyer and seller",
    "ActivationRecovery: báo cáo khoá cọc lỗi và cửa sổ huỷ kích hoạt": (
        "ActivationRecovery: failed deposit-lock report and activation-cancel window"
    ),
    "Hợp đồng SIGNED, escrow báo khoá cọc lỗi": "SIGNED contract, escrow reports deposit lock failure",
    "DepositAmount: tính cọc làm tròn tới đồng (HALF_UP)": (
        "DepositAmount: compute deposit rounded to VND (HALF_UP)"
    ),
    "OtpChallenge: hạn dùng, số lần sai, trạng thái đã dùng": (
        "OtpChallenge: expiry, failed attempts, consumed state"
    ),
    "Policy: hết hạn 300 s, tối đa 5 lần sai; clock cố định": (
        "Policy: expires in 300 s, max 5 failed attempts; fixed clock"
    ),
    "OtpPolicy: giới hạn gửi mã (cooldown 60 s, tối đa 5 mã/giờ)": (
        "OtpPolicy: OTP send rate limits (60 s cooldown, max 5/hour)"
    ),
    "Lịch sử gửi mã của 1 người cho 1 hợp đồng": "Send history of 1 user for 1 contract",
    "HmacOtpCodes: sinh mã OTP và băm có khoá (pepper)": (
        "HmacOtpCodes: generate OTP code and keyed hash (pepper)"
    ),
    "Pepper cấu hình đủ dài": "Configured pepper of sufficient length",
    "JcsTermsCanonicalizer: vân tay điều khoản (RFC 8785 + SHA-256)": (
        "JcsTermsCanonicalizer: terms fingerprint (RFC 8785 + SHA-256)"
    ),
    "Terms cà phê mẫu": "Sample coffee terms",
    "SignerEligibility: điều kiện người ký và bên kia trước khi gửi OTP": (
        "SignerEligibility: signer and counterparty eligibility before sending OTP"
    ),
    "UserPort mock; clock cố định": "Mocked UserPort; fixed clock",
    "OutboxContractEventPublisher: ghi event/command vào outbox, khớp schema đã chốt": (
        "OutboxContractEventPublisher: write events/commands to outbox matching frozen schemas"
    ),
    "Outbox writer fake; validate bằng schema trong contracts/events": (
        "Fake outbox writer; validated against schemas in contracts/events"
    ),
    "Khai báo trước queue của service nhận, cùng tham số với service chủ": (
        "Pre-declare consumer queues with same arguments as owning service"
    ),
    "Đọc cấu hình RabbitMQ của contract-service": "Load contract-service RabbitMQ configuration",
    "Event hợp lệ theo schema": "Valid event per schema",
    "POST retry-deposit-lock: Operator thử khoá cọc lại": (
        "POST retry-deposit-lock: Operator retries deposit lock"
    ),
    "Endpoint ký: POST /sign/initiate và POST /sign/verify": (
        "Signing endpoints: POST /sign/initiate and POST /sign/verify"
    ),
    "MockMvc standalone; use case mock; header danh tính từ gateway": (
        "Standalone MockMvc; mocked use cases; identity headers from gateway"
    ),
    "GET /contracts/{id}: trả ContractResponse theo contract đã chốt": (
        "GET /contracts/{id}: return ContractResponse per frozen contract"
    ),
    "PATCH /contracts/{id}/terms: sửa điều khoản": "PATCH /contracts/{id}/terms: revise terms",
    "POST /contracts/{id}/withdraw: rút offer": "POST /contracts/{id}/withdraw: withdraw offer",
    "GatewayAuthDetails: đọc thời điểm đăng nhập từ header gateway": (
        "GatewayAuthDetails: read auth time from gateway header"
    ),
    "UserPortAdapter: gọi user-service lấy thông tin người ký": (
        "UserPortAdapter: call user-service for signer info"
    ),
    "ContractSignedConsumer: parse contract.signed thành ContractSignedCommand": (
        "ContractSignedConsumer: parse contract.signed into ContractSignedCommand"
    ),
    "Event contract.signed hợp lệ: buyer deposit 40.000.000,00, seller deposit 20.000.000,00": (
        "Valid contract.signed event: buyer deposit 40,000,000.00, seller deposit 20,000,000.00"
    ),
    "RequestActivationDepositsUseCaseImpl: tạo deposit leg và gửi lệnh khoá cọc": (
        "RequestActivationDepositsUseCaseImpl: create deposit legs and send lock commands"
    ),
    "Repository và publisher là fake ghi lại leg đã insert / message đã gửi; NOW cố định": (
        "Fake repository and publisher recording inserted legs / sent messages; fixed NOW"
    ),
    "DepositLeg và Money (escrow): tạo leg cọc, validate dữ liệu": (
        "DepositLeg and Money (escrow): create deposit leg, validate inputs"
    ),
    "CONTRACT_ID, BUYER_ID là UUID; AMOUNT, NOW cố định": (
        "CONTRACT_ID, BUYER_ID are UUIDs; fixed AMOUNT, NOW"
    ),
    "ActivationDeposits: gom trạng thái các leg cọc để biết bên nào lỗi": (
        "ActivationDeposits: aggregate deposit leg states to identify failing party"
    ),
    "Hợp đồng có leg cọc BUYER và SELLER; lịch retry 1 lần": (
        "Contract has BUYER and SELLER deposit legs; 1-step retry schedule"
    ),
    "Event kết quả khoá hợp lệ theo schema": "Valid lock-result event per schema",
    "DepositLeg: nhận kết quả khoá, lịch retry khi lỗi": (
        "DepositLeg: handle lock result, retry schedule on failure"
    ),
    "Leg REQUESTED; lịch retry 5m / 30m / 2h; clock cố định": (
        "Leg REQUESTED; retry schedule 5m / 30m / 2h; fixed clock"
    ),
    "DepositLeg: trả cọc khi huỷ kích hoạt": "DepositLeg: refund deposit on activation cancellation",
    "Leg ở các trạng thái LOCKED / FAILED / REQUESTED; lịch retry trả cọc": (
        "Legs in LOCKED / FAILED / REQUESTED; refund retry schedule"
    ),
    "InspectionAuthorization: inspector chỉ truy cập commission/milestone được giao cho mình": (
        "InspectionAuthorization: inspector accesses only assigned commissions/milestones"
    ),
    "InspectorEligibilityGate: kiểm role, KYC, hạn giấy phép inspector; lỗi thì fail-closed": (
        "InspectorEligibilityGate: check inspector role, KYC, licence expiry; fail-closed on errors"
    ),
    "SecurityContext có ACTOR role INSPECTOR; UserEligibilityPort mock; NOW cố định": (
        "SecurityContext with ACTOR role INSPECTOR; mocked UserEligibilityPort; fixed NOW"
    ),
    "InternalUserEligibilityClient: gọi /internal/v1/users/{id}, mọi lỗi đều fail-closed": (
        "InternalUserEligibilityClient: call /internal/v1/users/{id}, fail-closed on all errors"
    ),
    "HTTP server giả trong JVM trả response cấu hình được; secret test-service-secret": (
        "In-JVM fake HTTP server returning configurable responses; secret test-service-secret"
    ),
    "GatewayAuthenticationFilter: chỉ tin header danh tính khi có X-Gateway-Secret đúng": (
        "GatewayAuthenticationFilter: trust identity headers only with valid X-Gateway-Secret"
    ),
    "Request có X-User-Id = inspector-a, X-User-Role = INSPECTOR; secret gateway-test-secret": (
        "Request with X-User-Id = inspector-a, X-User-Role = INSPECTOR; secret gateway-test-secret"
    ),
    "DlqMonitor (inspection-service): cảnh báo khi DLQ có message": (
        "DlqMonitor (inspection-service): warn when DLQ has messages"
    ),
    "RabbitMQ connection factory giả lập; appender bắt log": (
        "Mocked RabbitMQ connection factory; log-capturing appender"
    ),
    "InspectionHash: băm biên bản giám định (RFC 8785) và kiểm tra cam kết": (
        "InspectionHash: hash inspection report (RFC 8785) and verify commitment"
    ),
    "Biên bản và kết quả giám định mẫu hợp lệ": "Valid sample inspection report and result",
    "InspectionHashes: biên số liệu trước khi băm (thời điểm, UUID, khối lượng, phần trăm)": (
        "InspectionHashes: metric boundaries before hashing (timestamp, UUID, weight, percentage)"
    ),
    "Kết quả giám định mẫu": "Sample inspection result",
    "Kết quả giám định khớp 2 schema đã chốt": "Inspection result matches both frozen schemas",
    "Cấu hình phân công giám định và lịch ngày làm việc": (
        "Inspection assignment configuration and business-day calendar"
    ),
    "Consumer yêu cầu giám định Level 1.5": "Level 1.5 inspection request consumer",
    "Event hợp lệ; use case và bảng processed_event mock": (
        "Valid event; mocked use case and processed_event table"
    ),
    "NotificationSender.send: gửi email 1 lần cho mỗi (event, người nhận, loại thông báo)": (
        "NotificationSender.send: send email once per (event, recipient, notification kind)"
    ),
    "Repository notification_log in-memory; email adapter giả; clock và sleep điều khiển được": (
        "In-memory notification_log repository; fake email adapter; controllable clock and sleep"
    ),
    "parseOtpEmailRequest: kiểm body yêu cầu gửi email OTP": (
        "parseOtpEmailRequest: validate OTP email request body"
    ),
    "Body mẫu hợp lệ theo OtpEmailRequest": "Valid sample body per OtpEmailRequest",
    "renderOtpEmail: nội dung email OTP": "renderOtpEmail: OTP email content",
    "Yêu cầu OTP mẫu": "Sample OTP request",
    "SendOtpEmailUseCase: gửi email OTP 1 lần theo requestId": (
        "SendOtpEmailUseCase: send OTP email once per requestId"
    ),
    "Repository và email adapter giả": "Fake repository and email adapter",
    "NotifyContractAnchorUseCase: email neo hợp đồng đã ký": (
        "NotifyContractAnchorUseCase: signed contract anchor email"
    ),
    "Lệnh contract_anchor_requested mẫu": "Sample contract_anchor_requested command",
    "NotifyActivationCancelRequestedUseCase: báo bên kia có yêu cầu huỷ kích hoạt": (
        "NotifyActivationCancelRequestedUseCase: notify counterparty of activation-cancel request"
    ),
    "Lệnh activation_cancel_requested mẫu": "Sample activation_cancel_requested command",
    "9 use case thông báo Phase 1 (giữ nguyên hành vi bản Java)": (
        "9 Phase 1 notification use cases (preserving Java behavior)"
    ),
    "Fixture email chụp từ service Java": "Email fixtures captured from Java service",
    "NotificationLog: bản ghi thông báo": "NotificationLog: notification log record",
    "Danh mục loại thông báo (lệnh Phase 2 và event Phase 1)": (
        "Catalog of notification kinds (Phase 2 commands and Phase 1 events)"
    ),
    "canonicalJson: chuẩn hoá JSON theo RFC 8785": "canonicalJson: canonicalize JSON per RFC 8785",
    "validate(env): đọc biến môi trường": "validate(env): parse environment variables",
    "Hàm hỗ trợ hạ tầng: describeError, eventingLogger, smoke test thư viện eventing": (
        "Infrastructure helpers: describeError, eventingLogger, eventing library smoke test"
    ),
    "NodemailerEmailAdapter: gửi email qua SMTP": "NodemailerEmailAdapter: send email via SMTP",
    "Transport giả": "Fake transport",
    "Health check RabbitMQ và MySQL": "RabbitMQ and MySQL health check",
    "App Nest với use case thật, email adapter giả": "Nest app with real use case, fake email adapter",
    "AmqpService: kết nối RabbitMQ": "AmqpService: RabbitMQ connection",
    "Broker giả": "Fake broker",
    "DlqMonitor (notification): cảnh báo khi DLQ có message": (
        "DlqMonitor (notification): warn when DLQ has messages"
    ),
    "Payload phẳng mẫu cho từng loại event": "Sample flat payloads per event type",
    "Parser lệnh notification.* Phase 2 (envelope chuẩn)": (
        "Phase 2 notification.* command parsers (standard envelope)"
    ),
    "Lệnh mẫu hợp lệ theo schema đã chốt": "Valid sample command per frozen schema",
    "NotificationEventConsumer: nhận message từ các queue và gọi use case": (
        "NotificationEventConsumer: consume messages from queues and invoke use cases"
    ),
    "AmqpService và use case giả": "Fake AmqpService and use cases",
    "Thứ tự tắt service và không log dữ liệu cá nhân": (
        "Service shutdown sequence and no PII logging"
    ),
    "Vòng đời Nest thật, broker và DB giả": "Real Nest lifecycle, fake broker and DB",
    "Topology RabbitMQ của notification (khớp bản Java)": (
        "Notification RabbitMQ topology (matching Java)"
    ),
    "MySQL repository của notification_log và MysqlService": (
        "MySQL repository for notification_log and MysqlService"
    ),
    "Pool MySQL giả ghi lại câu SQL": "Fake MySQL pool recording SQL statements",
    "Đối chiếu code với contract đã chốt (2 lệnh notification và OtpEmailRequest)": (
        "Cross-check code against frozen contracts (2 notification commands and OtpEmailRequest)"
    ),
    "Kiến trúc pricing-service: layout và chiều phụ thuộc": (
        "pricing-service architecture: layout and dependency direction"
    ),
    "Đối chiếu pricing-service với contract đã chốt": (
        "Cross-check pricing-service against frozen contracts"
    ),
    "POST /api/v1/prices/manual: Operator nhập giá tay": (
        "POST /api/v1/prices/manual: Operator records manual price"
    ),
    "App Nest; MySQL và Redis giả": "Nest app; fake MySQL and Redis",
    "Cache Redis không làm treo pricing-service": "Redis cache failures do not hang pricing-service",
    "Redis giả: từ chối kết nối / không bắt tay / ngừng trả lời": (
        "Fake Redis: connection refused / no handshake / stops responding"
    ),
    "Gửi lại cùng Idempotency-Key": "Resend same Idempotency-Key",
    "MySQL và Redis giả (5 test cần MySQL thật bị skip)": (
        "Fake MySQL and Redis (5 tests requiring real MySQL skipped)"
    ),
    "Hàm và adapter của pricing-service": "pricing-service functions and adapters",
    "recordManualPrice và ánh xạ lỗi sang HTTP": "recordManualPrice and HTTP error mapping",
    "Reservation: giữ hàng cho hợp đồng, so sánh request, chuyển trạng thái": (
        "Reservation: reserve stock for contract, compare request, transition state"
    ),
    "CONTRACT_ID, LISTING_ID cố định": "Fixed CONTRACT_ID, LISTING_ID",
    "PlotRegistryEntry: đăng ký lô đất, re-import, chuẩn hoá nhãn hộ": (
        "PlotRegistryEntry: register plot, re-import, normalize household label"
    ),
    "Geometry mẫu từ PlotFixtures; T0, T1 cố định": "Sample geometry from PlotFixtures; fixed T0, T1",
    "Category: đề xuất, duyệt, từ chối danh mục": "Category: propose, approve, reject category",
    "Category PENDING mẫu, người đề xuất có email": "Sample PENDING category, proposer has email",
    "Listing: số lượng khả dụng, version token, phân loại lý do giữ hàng thất bại": (
        "Listing: available quantity, version token, classify reservation failure reasons"
    ),
    "Listing 100 đơn vị, token TOKEN": "Listing with 100 units, token TOKEN",
    "Product: tạo sản phẩm và quản lý ảnh (1–5 ảnh, URL http/https)": (
        "Product: create product and manage images (1–5 images, http/https URLs)"
    ),
    "PlotGeometries: dựng point/polygon, tính diện tích, hash hình học": (
        "PlotGeometries: build point/polygon, compute area, geometry hash"
    ),
    "Toạ độ quanh (108.05, 12.66) – Đắk Lắk": "Coordinates around (108.05, 12.66) – Dak Lak",
    "ApproveCategoryUseCaseImpl: duyệt danh mục đang chờ": (
        "ApproveCategoryUseCaseImpl: approve pending category"
    ),
    "ProposeCategoryUseCaseImpl: đề xuất danh mục, chặn trùng tên đã chuẩn hoá": (
        "ProposeCategoryUseCaseImpl: propose category, reject normalized-name duplicates"
    ),
    "CategoryRepository mock; người đề xuất seller-1": "Mocked CategoryRepository; proposer seller-1",
    "RejectCategoryUseCaseImpl: từ chối danh mục kèm lý do": (
        "RejectCategoryUseCaseImpl: reject category with reason"
    ),
    "ImportPlotsFromKmlUseCaseImpl: import lô đất từ KML, từ chối từng placemark lỗi": (
        "ImportPlotsFromKmlUseCaseImpl: import plots from KML, reject invalid placemarks individually"
    ),
    "PlotRegistrar mock; seller cố định": "Mocked PlotRegistrar; fixed seller",
    "PlotRegistrar: tạo mới hoặc cập nhật lô đất theo (seller, nhãn hộ)": (
        "PlotRegistrar: create or update plot by (seller, household label)"
    ),
    "PlotRegistryRepository mock; NOW cố định": "Mocked PlotRegistryRepository; fixed NOW",
    "CreateListingUseCaseImpl: tạo listing cà phê gắn lô đất của seller": (
        "CreateListingUseCaseImpl: create coffee listing linked to seller's plot"
    ),
    "Product cà phê Robusta tồn tại; ListingRepository, PlotRegistryRepository mock": (
        "Robusta coffee product exists; mocked ListingRepository, PlotRegistryRepository"
    ),
    "CreateProductUseCaseImpl: tạo sản phẩm trong category cà phê đã duyệt, idempotent theo id": (
        "CreateProductUseCaseImpl: create product in approved coffee category, idempotent by id"
    ),
    "UpdateProductImagesUseCaseImpl: thay toàn bộ ảnh sản phẩm": (
        "UpdateProductImagesUseCaseImpl: replace all product images"
    ),
    "KmlPlacemarkReader: đọc placemark point/polygon từ KML, chặn XXE": (
        "KmlPlacemarkReader: parse point/polygon placemarks from KML, block XXE"
    ),
    "DlqMonitor (product-service): cảnh báo khi DLQ có message": (
        "DlqMonitor (product-service): warn when DLQ has messages"
    ),
    "Gán commodity cho category cũ": "Assign commodity to legacy category",
    "Kiểm tra plot chồng lấn rừng tự nhiên theo lô": "Batch check plots for natural-forest overlap",
    "Plot đang chờ kiểm tra; dữ liệu nền rừng mock": (
        "Plot pending verification; mocked forest baseline data"
    ),
    "Consumer contract.created: giữ hàng cho listing": (
        "Consumer contract.created: reserve listing stock"
    ),
    "Event contract.created hợp lệ": "Valid contract.created event",
    "Snapshot listing cho contract-service khi tạo offer": (
        "Listing snapshot for contract-service when creating an offer"
    ),
    "Listing cà phê có plot và category; token phiên bản hiện tại": (
        "Coffee listing with plot and category; current version token"
    ),
    "Danh sách listing kèm uy tín seller": "Listing list with seller reputation",
    "Repository và client reputation mock": "Mocked repository and reputation client",
    "Client gọi reputation-service lấy điểm theo lô": (
        "Client calling reputation-service for batch scores"
    ),
    "HTTP server giả lập reputation": "Mocked reputation HTTP server",
    "Chính sách retry của listener RabbitMQ": "RabbitMQ listener retry policy",
    "Listener container cấu hình như production": "Listener container configured as in production",
    "Job dọn giữ hàng mồ côi": "Orphan reservation reaper job",
    "Clock cố định; thời gian ân hạn cấu hình": "Fixed clock; configurable grace period",
    "Consumer event vòng đời hợp đồng: giữ/nhả/chốt hàng của listing": (
        "Contract lifecycle event consumer: reserve/release/commit listing stock"
    ),
    "Use case giả; payload theo schema đã chốt": "Fake use case; payload per frozen schema",
    "Đọc chi tiết sản phẩm": "Read product details",
    "Repository giả": "Fake repository",
    "Client gọi /internal/v1/users của user-service (điều kiện seller, email người đề xuất)": (
        "Client calling user-service /internal/v1/users (seller eligibility, proposer email)"
    ),
    "HTTP server giả (MockWebServer)": "Fake HTTP server (MockWebServer)",
    "Seller xem danh sách lô đất của mình": "Seller lists own plots",
    "Giữ hàng của listing cho một hợp đồng": "Reserve listing stock for a contract",
    "REST danh mục: duyệt, từ chối, gán commodity, danh sách đã duyệt (@WebMvcTest, use case giả)": (
        "Category REST: approve, reject, assign commodity, approved list (@WebMvcTest, fake use case)"
    ),
    "MockMvc; X-Gateway-Secret, X-User-Id, X-User-Role như gateway gửi": (
        "MockMvc; X-Gateway-Secret, X-User-Id, X-User-Role as sent by gateway"
    ),
    "REST tạo và sửa listing (@WebMvcTest, use case giả)": (
        "Create and update listing REST (@WebMvcTest, fake use case)"
    ),
    "Lỗi 400 không bị Spring Security biến thành 403 rỗng (#424)": (
        "400 errors are not turned into empty 403 by Spring Security (#424)"
    ),
    "MockMvc với security thật": "MockMvc with real security",
    "REST nội bộ: bản sao listing cho contract-service (@WebMvcTest)": (
        "Internal REST: listing snapshot for contract-service (@WebMvcTest)"
    ),
    "REST nội bộ giữ / nhả hàng cho contract-service (@WebMvcTest)": (
        "Internal REST: reserve/release stock for contract-service (@WebMvcTest)"
    ),
    "REST tìm listing công khai kèm uy tín seller (@WebMvcTest)": (
        "Public listing search REST with seller reputation (@WebMvcTest)"
    ),
    "MockMvc; use case giả": "MockMvc; fake use case",
    "REST lô đất: ghim tay, nhập KML, danh sách của seller (@WebMvcTest)": (
        "Plot REST: manual pin, KML import, seller's plot list (@WebMvcTest)"
    ),
    "REST chi tiết sản phẩm công khai (@WebMvcTest)": "Public product details REST (@WebMvcTest)",
    "Biên application layer: use case chỉ nhận/trả DTO bất biến, map command sang fact": (
        "Application layer boundary: use cases accept/return immutable DTOs only, map commands to facts"
    ),
    "FactRepository, ReputationReadRepository mock; thời điểm cố định": (
        "Mocked FactRepository, ReputationReadRepository; fixed instant"
    ),
    "Công thức điểm uy tín và validate fact (CompletedContractFact, DisputeOutcomeFact)": (
        "Reputation score formula and fact validation (CompletedContractFact, DisputeOutcomeFact)"
    ),
    "RabbitConfiguration: DLX riêng của reputation, mỗi queue có DLQ cùng routing key": (
        "RabbitConfiguration: dedicated reputation DLX, each queue has DLQ with matching routing key"
    ),
    "Fixture event dùng trong test khớp schema frozen trong contracts/": (
        "Test event fixtures match frozen schemas in contracts/"
    ),
    "Schema frozen nạp từ contracts/events": "Frozen schemas loaded from contracts/events",
    "DlqMonitor: đo độ sâu DLQ, log WARN an toàn, không lộ dữ liệu nhạy cảm": (
        "DlqMonitor: measure DLQ depth, log safe WARN without exposing sensitive data"
    ),
    "ConnectionFactory, Channel mock; log appender ghi lại": (
        "Mocked ConnectionFactory, Channel; capturing log appender"
    ),
    "ReputationFactConsumer: parse contract.settled / milestone.dispute_resolved thành command": (
        "ReputationFactConsumer: parse contract.settled / milestone.dispute_resolved into commands"
    ),
    "Event hợp lệ theo contract frozen; AppendReputationFact mock": (
        "Valid event per frozen contract; mocked AppendReputationFact"
    ),
    "GatewayIdentityFilter: thiếu gateway secret thì không cho khởi động": (
        "GatewayIdentityFilter: fail startup if gateway secret is missing"
    ),
    "Đọc tóm tắt uy tín theo lô": "Batch read reputation summary",
    "Repository fake; clock cố định": "Fake repository; fixed clock",
    "Filter xác thực gọi nội bộ giữa service": "Internal service-to-service auth filter",
    "ErrorEnvelopeExceptionHandler: trả lỗi theo error envelope chung": (
        "ErrorEnvelopeExceptionHandler: return errors in shared error envelope"
    ),
    "MockMvc standalone với TestController; clock cố định 2026-10-27T03:00:00Z": (
        "Standalone MockMvc with TestController; fixed clock 2026-10-27T03:00:00Z"
    ),
    "EventContext: lan truyền correlationId / causationId giữa các event": (
        "EventContext: propagate correlationId / causationId across events"
    ),
    "Bộ field hợp lệ: contract.signed v1, contract-service, contract-1": (
        "Valid fields: contract.signed v1, contract-service, contract-1"
    ),
    "EventSubject: type + id của chủ thể event": "EventSubject: event subject type + id",
    "EventEnvelopeCodec: serialize/parse envelope JSON chặt theo schema, không lộ dữ liệu trong lỗi": (
        "EventEnvelopeCodec: strictly serialize/parse JSON envelope per schema without leaking data in errors"
    ),
    "JSON envelope hợp lệ mẫu, occurredAt 2026-10-23T03:00:00Z": (
        "Valid sample JSON envelope, occurredAt 2026-10-23T03:00:00Z"
    ),
    "VietnamBusinessDays: cộng ngày làm việc (bỏ cuối tuần, ngày lễ)": (
        "VietnamBusinessDays: add business days (skipping weekends and holidays)"
    ),
    "Danh sách ngày lễ cấu hình qua VIETNAM_HOLIDAYS": "Holiday list configured via VIETNAM_HOLIDAYS",
    "EventingAutoConfiguration: bean outbox/codec tự cấu hình theo classpath và property": (
        "EventingAutoConfiguration: auto-configure outbox/codec beans by classpath and properties"
    ),
    "ApplicationContextRunner với các tổ hợp classpath và property": (
        "ApplicationContextRunner with classpath and property combinations"
    ),
    "UserProfile: đăng ký, quyết định KYC (verify/reject/resubmit), đổi role": (
        "UserProfile: register, KYC decisions (verify/reject/resubmit), role assignment"
    ),
    'user-123, "Cong ty A", SELLER; ACTOR_ID, DECIDED_AT, EXPIRES_AT cố định': (
        'user-123, "Cong ty A", SELLER; fixed ACTOR_ID, DECIDED_AT, EXPIRES_AT'
    ),
    "AssignUserRoleUseCaseImpl: ADMIN gán role, giữ tách biệt OPERATOR/ARBITRATOR": (
        "AssignUserRoleUseCaseImpl: ADMIN assigns role, keeping OPERATOR/ARBITRATOR separated"
    ),
    "GetInternalUserInfoUseCaseImpl: thông tin đủ điều kiện cho service nội bộ": (
        "GetInternalUserInfoUseCaseImpl: eligibility info for internal services"
    ),
    "GetKycProfileUseCaseImpl: xem hồ sơ KYC, bắt buộc ghi audit đọc": (
        "GetKycProfileUseCaseImpl: view KYC profile, enforce read audit"
    ),
    "ListUsersByVerificationStatusUseCaseImpl: hàng đợi KYC theo status, phân trang": (
        "ListUsersByVerificationStatusUseCaseImpl: KYC queue by status, paginated"
    ),
    "RejectUserUseCaseImpl: từ chối KYC với giờ server và actor": (
        "RejectUserUseCaseImpl: reject KYC with server time and actor"
    ),
    "UserProfileRepository mock; clock cố định NOW": "Mocked UserProfileRepository; fixed clock NOW",
    "VerifyUserUseCaseImpl: duyệt KYC với giờ server và actor": (
        "VerifyUserUseCaseImpl: verify KYC with server time and actor"
    ),
    "DlqMonitor (user-service): cảnh báo khi DLQ có message": (
        "DlqMonitor (user-service): warn when DLQ has messages"
    ),
    "Áp khoá/mở khoá uy tín vào hồ sơ người dùng": (
        "Apply reputation lock/unlock to user profile"
    ),
    "Repository và publisher mock": "Mocked repository and publisher",
    "Endpoint nội bộ danh sách inspector khi secret rỗng": (
        "Internal inspector-list endpoint when secret is blank"
    ),
    "Lấy thông tin công khai của user": "Get user's public info",
    "Danh sách inspector đủ điều kiện": "Eligible inspectors list",
    "Repository mock; clock cố định": "Mocked repository; fixed clock",
    "Ghi kết quả KYC vào outbox": "Write KYC result to outbox",
    "Outbox fake; schema đã chốt": "Fake outbox; frozen schema",
    "Ghi thay đổi khoá vào outbox": "Write lock change to outbox",
    "Event hợp lệ theo schema đã chốt": "Valid event per frozen schema",
    "Xác nhận email bằng mã OTP (#344)": "Confirm email with OTP code (#344)",
    "Repository, Keycloak client giả; mã 6 số đã gửi": (
        "Fake repository and Keycloak client; 6-digit code sent"
    ),
    "Phát mã xác minh email: lưu hash, gửi mã, cooldown": (
        "Issue email verification code: store hash, send code, cooldown"
    ),
    "Repository, notification client giả": "Fake repository and notification client",
    "Job xoá tài khoản chưa xác minh email sau 24 giờ (D59)": (
        "Job to purge unverified-email accounts after 24 hours (D59)"
    ),
    "Repository, Keycloak client giả": "Fake repository and Keycloak client",
    "Đăng ký profile: email trong body chưa xác minh, gửi mã đầu tiên": (
        "Register profile: unverified email in body, send initial code"
    ),
    "Repository, code issuer giả": "Fake repository and code issuer",
    "UserProfile: email xác minh và email liên hệ": "UserProfile: verified email and contact email",
    "REST quản trị user: duyệt KYC, xem hồ sơ, đổi role (@WebMvcTest)": (
        "Admin user REST: verify KYC, view profile, assign role (@WebMvcTest)"
    ),
    "MockMvc; danh tính qua X-Gateway-Secret, X-User-Id, X-User-Role": (
        "MockMvc; identity via X-Gateway-Secret, X-User-Id, X-User-Role"
    ),
    "REST nội bộ: danh sách inspector đủ điều kiện cho inspection-service (@WebMvcTest)": (
        "Internal REST: eligible inspectors list for inspection-service (@WebMvcTest)"
    ),
    "REST nội bộ /internal/v1/users/{id} (@WebMvcTest)": (
        "Internal REST /internal/v1/users/{id} (@WebMvcTest)"
    ),
    "REST user: đăng ký, profile của mình, profile công khai, xác minh email (@WebMvcTest)": (
        "User REST: register, own profile, public profile, email verification (@WebMvcTest)"
    ),
    "MockMvc; danh tính qua X-Gateway-Secret": "MockMvc; identity via X-Gateway-Secret",
    (
        "Unit test của 12 module: analytics-service, api-gateway, bank-service, contract-service, escrow-service, "
        "inspection-service, notification-service, pricing-service, product-service, reputation-service, shared, "
        "user-service. Round 1 = lần chạy đầu (31/10/2026 với module cũ, ngày merge với module mới); Round 2 = "
        "chạy lại module cũ ngày 07/11/2026. 1 dòng = 1 kịch bản: test lặp lại trên bảng dữ liệu gộp thành 1 dòng. "
        "Round 3 = regression: chạy lại toàn bộ unit test của mọi service ngày 28/11/2026 trên commit 42a48dd5 (cd "
        "backend && mvn -B -fae test; cd backend/<svc> && npx jest --json (notification, pricing, analytics, file)), "
        "kết quả từng test lưu trong scripts/unit-test-report/results/ut-round3-20261128/cases.json. Số test đã "
        "chạy: 2225 (JUnit) + 2452 (Jest), 0 Failed, 24 không chạy (test cần MySQL / Redis thật, bật bằng biến môi "
        "trường). Mỗi dòng ghi test method trong Note; dòng Passed khi mọi test của dòng pass; dòng có test đã bị "
        "xoá hoặc viết lại giữ N/A. 1224 dòng có Round 3: 1224 Passed, 0 Failed. Round 1 = lần chạy đầu "
        "(31/10/2026 với module cũ, ngày merge với module mới) và Round 2 = confirmation chạy lại ngày 07/11/2026 "
        "các module của 31/10: lịch sử của báo cáo 10/11, không lưu log chạy, trừ AnalyticsTopology UTC004 có CI "
        "run trong Report 5.6 DEF-01. Lần regression trước (25/11 và 27/11/2026, trên 614e6ab6 / 94d46500) lưu ở "
        "scripts/unit-test-report/results/unit-round3*/."
    ): (
        "Unit tests across 12 modules: analytics-service, api-gateway, bank-service, contract-service, "
        "escrow-service, inspection-service, notification-service, pricing-service, product-service, "
        "reputation-service, shared, user-service. Round 1 = initial run (31/10/2026 for existing modules, merge "
        "date for new modules); Round 2 = re-run of existing modules on 07/11/2026. 1 row = 1 scenario: "
        "parameterized tests over data tables are grouped into 1 row. Round 3 = regression: re-ran all unit tests "
        "across every service on 28/11/2026 on commit 42a48dd5 (cd backend && mvn -B -fae test; cd backend/<svc> "
        "&& npx jest --json (notification, pricing, analytics, file)), per-test results saved in "
        "scripts/unit-test-report/results/ut-round3-20261128/cases.json. Executed tests: 2225 (JUnit) + 2452 "
        "(Jest), 0 Failed, 24 skipped (tests requiring real MySQL / Redis, enabled via env vars). Each row lists "
        "its test methods in Note; a row is Passed when all its tests pass; rows whose tests were deleted or "
        "rewritten remain N/A. 1224 rows have Round 3: 1224 Passed, 0 Failed. Round 1 = initial run (31/10/2026 for "
        "existing modules, merge date for new modules) and Round 2 = confirmation re-run on 07/11/2026 for the "
        "31/10 modules: history from the 10/11 report without stored run logs, except AnalyticsTopology UTC004 "
        "whose CI run is recorded in Report 5.6 DEF-01. Earlier regression runs (25/11 and 27/11/2026, on "
        "614e6ab6 / 94d46500) are stored under scripts/unit-test-report/results/unit-round3*/."
    ),

    # ── Analytics & ApiGateway & Bank & Contract (rows 287..520) ──
    "Layout và phụ thuộc": "Layout and dependencies",
    "Thư mục src/; 31 file nguồn": "src/ directory; 31 source files",
    "Return: đúng layout; mỗi file chỉ import tầng và package được phép": (
        "Return: matches layout; each file imports only allowed layers and packages"
    ),
    "TERMINATION_FOR_BREACH có final role": "TERMINATION_FOR_BREACH with final role",
    "Return: là vi phạm, quy cho role đó": "Return: counted as breach, attributed to that role",
    "Kết thúc thoả thuận có role; bất khả kháng; kích hoạt thất bại; rút offer": (
        "Mutual termination with role; force majeure; activation failed; offer withdrawn"
    ),
    "Return: không phải vi phạm / ngoài phạm vi": "Return: not a breach / out of scope",
    "Vi phạm không có final role; có requestedBy": "Breach without final role; has requestedBy",
    "Return: không quy cho ai; không đọc requestedBy": "Return: unattributed; ignores requestedBy",
    "Seller yêu cầu, buyer vi phạm (và ngược lại)": "Seller requested, buyer breached (and vice versa)",
    "Return: requestedBy và finalBreachingRole tách riêng": "Return: requestedBy and finalBreachingRole kept separate",
    "Mọi nhánh kết thúc; kết thúc thoả thuận": "All termination branches; mutual termination",
    "Return: đều lưu; chỉ 2 nhánh vi phạm có quy kết tính là vi phạm": (
        "Return: all stored; only the 2 attributed breach branches count as breach"
    ),
    "Gửi lại cùng eventId / eventId mới; contract.settled lặp": (
        "Redeliver same eventId / new eventId; duplicate contract.settled"
    ),
    "Return: chỉ 1 fact": "Return: only 1 fact",
    "Event phát khi analytics tắt": "Events emitted while analytics was down",
    "Return: bật lại thì lưu đủ, mỗi cái 1 lần": "Return: all stored on restart, once each",
    "terminationType lạ": "Unknown terminationType",
    "Return: vào DLQ, không ghi fact": "Return: sent to DLQ, no fact written",
    "Mọi event đã đăng ký": "All registered events",
    "Return: kiểm DLQ của từng event; có message thì cảnh báo queue và số lượng; rỗng thì im": (
        "Return: checks each event's DLQ; warns with queue and count if non-empty; silent if empty"
    ),
    "1 lần kiểm lỗi": "1 probe fails",
    "Return: log và vẫn kiểm các queue còn lại": "Return: logs and continues probing remaining queues",
    "Return: kiểm ngay rồi lặp; stop thì dừng; mỗi lúc 1 vòng": (
        "Return: probes immediately then loops; stops on stop(); one cycle at a time"
    ),
    "So với schema": "Against schema",
    "Return: cùng producer, field bắt buộc, không thừa thiếu, cùng enum; mọi event có bộ luật": (
        "Return: same producer, required fields, no extra/missing fields, same enums; every event has rules"
    ),
    "Fixture hợp lệ; bỏ / thêm / thay từng field": "Valid fixture; drop / add / mutate each field",
    "Return: validator và schema YAML cho cùng kết quả": "Return: validator and YAML schema give identical results",
    "Người nhận": "Recipients",
    "Return: chỉ userId, email, role BUYER/SELLER": "Return: only userId, email, role BUYER/SELLER",
    "Ngày không tồn tại; field chỉ có trên prototype; producer lạ payload rỗng; version không hỗ trợ; sai queue; payload không phải object": (
        "Non-existent date; prototype-only field; unknown producer with empty payload; unsupported version; wrong queue; non-object payload"
    ),
    "Return: từ chối, không ném lỗi; message không có giá trị payload": (
        "Return: rejected without throwing; error message omits payload values"
    ),
    "Đang chờ MySQL/RabbitMQ; đã bắt đầu ingest; path khác": (
        "Waiting for MySQL/RabbitMQ; ingestion started; other paths"
    ),
    "Mỗi queue đã đăng ký": "Each registered queue",
    "Return: 1 consumer; event được validate, lưu, đánh dấu đã xử lý, ack": (
        "Return: 1 consumer; event is validated, stored, marked processed, acked"
    ),
    "Event gửi lại cùng eventId; nhiều event cùng hợp đồng": (
        "Redelivered event with same eventId; multiple events for same contract"
    ),
    "Return: lưu đúng 1 lần; lưu đủ": "Return: stored exactly once; all stored",
    "Ghi lỗi; projection lỗi": "Write error; projection error",
    "Return: không còn dòng nào, retry lưu được (1 transaction)": (
        "Return: no partial rows left, retry succeeds (single transaction)"
    ),
    "Hết retry": "Retries exhausted",
    "Return: vào DLQ; replay eventId gốc được ingest": "Return: sent to DLQ; replay with original eventId is ingested",
    "Biên ingest": "Ingest boundaries",
    "Producer lạ payload rỗng; số tiền sai; field lạ; version không hỗ trợ; envelope sai; sai queue": (
        "Unknown producer empty payload; invalid amount; extra field; unsupported version; invalid envelope; wrong queue"
    ),
    "Return: vào DLQ, không đoán": "Return: sent to DLQ, no guessing",
    "Event hợp lệ sau 1 event bị từ chối": "Valid event after a rejected event",
    "Return: vẫn được ingest": "Return: still ingested",
    "milestone.settled có email người nhận": "milestone.settled contains recipient email",
    "Return: email không bao giờ vào DB": "Return: email never reaches DB",
    "Không chặn producer": "Non-blocking to producers",
    "Analytics sập; projection chậm hoặc lỗi": "Analytics down; slow or failing projection",
    "Return: producer vẫn publish được, message chờ trong queue": (
        "Return: producer still publishes, messages wait in queue"
    ),
    "Bật lại với backlog; prefetch lớn": "Restart with backlog; large prefetch",
    "Return: lưu đủ theo thứ tự, mỗi event 1 lần": "Return: all stored in order, once each",
    "Ràng buộc": "Constraints",
    "Projection cho event không có queue; consumer khởi động": (
        "Projection for event without queue; consumer startup"
    ),
    "Return: từ chối; log chỉ có loại event và queue": "Return: rejected; log contains only event type and queue",
    "signedAt ngày không tồn tại; giờ 24; field lạ tên constructor / toString": (
        "signedAt non-existent date; hour 24; extra field named constructor / toString"
    ),
    "Return: vào DLQ, không có dòng nào, không đánh dấu đã xử lý, không retry": (
        "Return: sent to DLQ, no row written, not marked processed, no retry"
    ),
    "Event đã sửa, cùng eventId": "Fixed event, same eventId",
    "Return: được ingest": "Return: ingested",
    "Tắt bình thường": "Normal shutdown",
    "Return: thứ tự: not ready, ngừng nhận, xả, đóng MySQL, đóng RabbitMQ": (
        "Return: order: not ready, stop consuming, drain, close MySQL, close RabbitMQ"
    ),
    "1 bước lỗi; 1 bước treo": "1 step errors; 1 step hangs",
    "Return: log và chạy tiếp các bước sau; bỏ bước treo sau stepTimeoutMs": (
        "Return: logs and continues remaining steps; skips hung step after stepTimeoutMs"
    ),
    "Có handler đang chạy": "Handler in flight",
    "Return: chờ xong, ack rồi mới đóng DB; handler không xong thì không chặn": (
        "Return: waits for completion, acks then closes DB; unfinished handler does not block"
    ),
    "Message còn trong queue; message tới sau khi tắt": (
        "Messages left in queue; messages arriving after shutdown"
    ),
    "Return: instance sau xử lý; trả lại broker (requeue)": (
        "Return: handled by next instance; requeued to broker"
    ),
    "settled / cancelled; force majeure; quyết định bị từ chối": (
        "settled / cancelled; force majeure; rejected decision"
    ),
    "Return: khoá trước khi đọc cờ; khoá trước khi tìm fact; không đụng gì kể cả khoá": (
        "Return: locks before reading flag; locks before querying fact; touches nothing including locks"
    ),
    "Envelope có người nhận": "Envelope with recipients",
    "Return: giữ định danh, giờ UTC, tracing id; bỏ email, giữ userId và role; không sửa envelope gốc": (
        "Return: preserves ids, UTC time, tracing ids; strips email, keeps userId and role; does not mutate input"
    ),
    "Payload không có người nhận": "Payload without recipients",
    "Return: giữ nguyên": "Return: kept unchanged",
    "Return: 1 dòng đủ cột trong transaction được truyền vào; giờ UTC DATETIME(6)": (
        "Return: 1 row with all columns in passed transaction; UTC DATETIME(6)"
    ),
    "Dòng ghi DATETIME(6); test hiện tại kiểm giờ UTC DATETIME(3)": (
        "Row says DATETIME(6); current test checks UTC DATETIME(3)"
    ),
    "Khởi tạo module": "Initialize module",
    "Return: cung cấp đủ các projection": "Return: provides all projections",
    "Tiền": "Money",
    "Trừ số âm, số dưới 1 đơn vị; giá trị không có 2 số lẻ": (
        "Subtract negative, sub-unit amounts; value without 2 decimals"
    ),
    "Return: trừ chính xác; từ chối": "Return: subtracts accurately; rejects invalid",
    "contract.signed lặp": "Duplicate contract.signed",
    "Return: upsert dim_contract, không trùng": "Return: upserts dim_contract, no duplicate",
    "Return: 1 fact với chênh lệch = khoá − thực tế; fact CANCELLED_WITH_PENALTY": (
        "Return: 1 fact with delta = locked − actual; CANCELLED_WITH_PENALTY fact"
    ),
    "Bất khả kháng duyệt trước / sau khi có fact; bị từ chối; khác milestone": (
        "Force majeure approved before / after fact; rejected; different milestone"
    ),
    "Return: gộp vào fact mới / cập nhật dòng; không đổi; chỉ milestone của nó": (
        "Return: merges into new fact / updates row; unchanged; affects only its own milestone"
    ),
    "Event phát khi analytics tắt; thứ tự tới khác nhau": (
        "Events emitted while analytics was down; different arrival orders"
    ),
    "Return: bắt kịp đủ; cùng trạng thái cuối": "Return: catches up completely; same final state",
    "Projection lỗi; event thiếu số tiền": "Projection error; event missing amount",
    "Return: rollback toàn bộ; vào DLQ không ghi fact": "Return: rolls back all; sent to DLQ without writing fact",
    "Event phát khi analytics tắt rồi bật lại": "Events emitted while analytics was down then restarted",
    "Return: ingest đủ, mỗi event 1 lần": "Return: ingests all, once each",
    "Replay cùng eventId; replay DLQ của event đã sửa": (
        "Replay same eventId; replay DLQ of fixed event"
    ),
    "Return: không làm gì; ingest được": "Return: no-op; ingested",
    "Khởi động; event bị từ chối": "Startup; rejected event",
    "Return: tự khai báo topology, chạy DLQ monitor; cảnh báo DLQ chỉ có queue và số lượng": (
        "Return: declares topology, starts DLQ monitor; DLQ alert includes only queue and count"
    ),
    "Khai báo exchange, queue, DLQ": "Declare exchange, queue, DLQ",
    "Return: giống service Java; queue không giới hạn độ dài/TTL; khai báo 2 lần không đổi gì; tên theo quy ước": (
        "Return: matches Java services; unbounded queue length/TTL; idempotent re-declaration; naming convention"
    ),
    "Event đã đăng ký": "Registered events",
    "Return: đều có trong golden-flow-events.yaml; phủ đủ dòng của bản đồ tích hợp; đủ 6 projection": (
        "Return: all present in golden-flow-events.yaml; covers integration map; all 6 projections present"
    ),
    "Không nằm trên đường giao dịch (Matrix 23)": "Off the transaction path (Matrix 23)",
    "Return: không có client/URL tới analytics; analytics không gọi HTTP ra ngoài, không publish ngược": (
        "Return: no client/URL to analytics; analytics makes no outbound HTTP calls and publishes no events back"
    ),
    "Return: không có client/URL tới analytics": "Return: no client/URL to analytics",
    (
        "Round 1: CI https://github.com/redavocado-labs/AgriContract/actions/runs/43699113645 (main 36bf2fcd), "
        "test bắt chữ 'analytics' trong comment của contract-service; sửa ở #449, PR #468 (e62bc34c): test bỏ qua "
        "comment. Round 2: chạy lại CI https://github.com/redavocado-labs/AgriContract/actions/runs/43725489719 "
        "(main e62bc34c), Passed. Report 5.5 DEF-01"
    ): (
        "Round 1: CI https://github.com/redavocado-labs/AgriContract/actions/runs/43699113645 (main 36bf2fcd), "
        "test matched the word 'analytics' in a contract-service comment; fixed in #449, PR #468 (e62bc34c): test "
        "ignores comments. Round 2: re-ran CI https://github.com/redavocado-labs/AgriContract/actions/runs/43725489719 "
        "(main e62bc34c), Passed. Report 5.5 DEF-01"
    ),
    "V1–V4; config mặc định; wiring Nest": "V1–V4; default config; Nest wiring",
    "Return: chỉ thêm bảng, tiền DECIMAL(18,2); chạy theo thứ tự; /health lên trước MySQL và RabbitMQ; tắt êm khi SIGTERM/SIGINT": (
        "Return: additive tables only, money DECIMAL(18,2); runs in order; /health starts before MySQL and RabbitMQ; graceful shutdown on SIGTERM/SIGINT"
    ),
    "filter() – request có JWT": "filter() – request with JWT",
    "Header giả: X-User-Id=attacker, X-User-Role=ADMIN, X-Gateway-Secret=forged, Authorization": (
        "Spoofed headers: X-User-Id=attacker, X-User-Role=ADMIN, X-Gateway-Secret=forged, Authorization"
    ),
    "Return: forward X-User-Id=user-123, X-User-Role=BUYER, X-User-Email=user@agri.test, X-Gateway-Secret=SECRET; bỏ Authorization": (
        "Return: forwards X-User-Id=user-123, X-User-Role=BUYER, X-User-Email=user@agri.test, X-Gateway-Secret=SECRET; strips Authorization"
    ),
    "Return: X-User-Role = SELLER (bỏ role housekeeping của realm)": (
        "Return: X-User-Role = SELLER (drops realm housekeeping roles)"
    ),
    "JWT đủ 6 role nghiệp vụ + offline_access": "JWT with all 6 domain roles + offline_access",
    "JWT role rỗng": "JWT with empty roles",
    "Return: không forward": "Return: not forwarded",
    "JWT chỉ có offline_access": "JWT with offline_access only",
    "JWT không có email": "JWT without email",
    "Return: forward, không có header X-User-Email": "Return: forwarded without X-User-Email header",
    "JWT có auth_time dạng số": "JWT with numeric auth_time",
    "Return: forward auth_time dạng epoch seconds": "Return: forwards auth_time as epoch seconds",
    "JWT có auth_time dạng Instant": "JWT with Instant auth_time",
    "JWT không có auth_time": "JWT without auth_time",
    "Client tự gửi header auth_time": "Client sends auth_time header",
    "Return: không forward auth_time": "Return: auth_time not forwarded",
    "filter() – request ẩn danh": "filter() – anonymous request",
    "Không JWT": "No JWT",
    "Return: bỏ X-User-*; X-Gateway-Secret = SECRET": "Return: strips X-User-*; sets X-Gateway-Secret = SECRET",
    "Return: bỏ mọi header x-user-* (không phân biệt hoa thường) và X-Internal-Secret; giữ X-Correlation-Id": (
        "Return: strips all x-user-* headers (case-insensitive) and X-Internal-Secret; keeps X-Correlation-Id"
    ),
    "Return: không forward Authorization": "Return: Authorization not forwarded",
    "Header auth_time giả": "Spoofed auth_time header",
    "Return: bị xoá": "Return: stripped",
    "Vượt N request trong cửa sổ": "Exceed N requests in window",
    "Return: 429 tới hết cửa sổ": "Return: 429 until window resets",
    "2 IP khác nhau": "2 different IPs",
    "Return: mỗi IP một bucket riêng": "Return: each IP has its own bucket",
    "Không có proxy tin cậy": "No trusted proxy",
    "Client tự gửi X-Forwarded-For": "Client sends X-Forwarded-For",
    "Return: không đổi được bucket (vẫn 429)": "Return: cannot switch bucket (still 429)",
    "1 proxy tin cậy": "1 trusted proxy",
    "Return: bucket theo địa chỉ proxy thêm vào": "Return: bucket keyed by proxy-appended address",
    "Giới hạn ≤ 0": "Limit ≤ 0",
    "Danh sách origin cụ thể": "Explicit origin list",
    "Return: đúng các origin, không cho credentials": "Return: exact origins, credentials disabled",
    'Origin "*" hoặc có wildcard': 'Origin "*" or wildcard',
    "Exception: IllegalStateException lúc khởi động": "Exception: IllegalStateException at startup",
    "Không có X-Correlation-Id": "Missing X-Correlation-Id",
    "Return: Sinh UUID mới; gửi xuống service và trả trong response": (
        "Return: generates new UUID; forwards to service and returns in response"
    ),
    "X-Correlation-Id là UUID hợp lệ": "X-Correlation-Id is a valid UUID",
    "Return: Giữ nguyên giá trị client gửi": "Return: preserves client-supplied value",
    "Service phía sau tự đặt header": "Downstream service sets header itself",
    "Response có X-Correlation-Id khác": "Response has a different X-Correlation-Id",
    "Return: Gateway ghi đè bằng id của request": "Return: gateway overwrites with request's id",
    "Chuỗi rỗng, không phải UUID, UUID có ký tự lạ": "Empty string, non-UUID, UUID with invalid characters",
    "Return: Thay bằng UUID mới": "Return: replaced with new UUID",
    "UUID viết hoa": "Uppercase UUID",
    "Return: Chấp nhận, giữ nguyên": "Return: accepted, kept unchanged",
    "Route không khai báo giới hạn riêng": "Route without custom limit",
    "Body lớn hơn mặc định": "Body larger than default",
    "Return: Dùng giới hạn mặc định": "Return: applies default limit",
    "Return: 10 MiB qua; 10 MiB + 1 byte bị từ chối 413": "Return: 10 MiB passes; 10 MiB + 1 byte rejected with 413",
    'Giới hạn cấu hình dạng chuỗi "10MB"': 'Configured limit as string "10MB"',
    "Return: Đọc được giới hạn": "Return: parses limit",
    "Return: entryId là UUID hợp lệ; createdAt = NOW; amount = AMOUNT": (
        "Return: entryId is valid UUID; createdAt = NOW; amount = AMOUNT"
    ),
    "LOCK_MILESTONE không milestoneId; LOCK_SELLER_DEPOSIT có milestoneId; REFUND_TO_BUYER MILESTONE_PAYMENT có milestoneId": (
        "LOCK_MILESTONE without milestoneId; LOCK_SELLER_DEPOSIT with milestoneId; REFUND_TO_BUYER MILESTONE_PAYMENT with milestoneId"
    ),
    "Return: case 3 tạo được": "Return: case 3 succeeds",
    "REFUND_TO_BUYER chỉ có 1 trong 2 remedy id": "REFUND_TO_BUYER with only 1 of 2 remedy ids",
    "LOCK_BUYER_DEPOSIT kèm remedy id": "LOCK_BUYER_DEPOSIT with remedy id",
    "DEPOSIT_FORFEITURE / CONTRACTUAL_PENALTY không remedy; DAMAGES_COMPENSATION đủ remedy": (
        "DEPOSIT_FORFEITURE / CONTRACTUAL_PENALTY without remedy; DAMAGES_COMPENSATION with full remedy"
    ),
    "Return: DAMAGES_COMPENSATION tạo được": "Return: DAMAGES_COMPENSATION succeeds",
    'Exception: 2 case đầu: InvalidLedgerEntryException "needs a remedy decision"': (
        'Exception: first 2 cases: InvalidLedgerEntryException "needs a remedy decision"'
    ),
    "RELEASE_TO_SELLER có và không có remedy id": "RELEASE_TO_SELLER with and without remedy id",
    "Return: cả 2 tạo được": "Return: both succeed",
    "Return: scale = 2, giá trị 100.00": "Return: scale = 2, value 100.00",
    "Return: scale = 2, giá trị 100.50": "Return: scale = 2, value 100.50",
    "Return: scale = 2, giá trị 100.00 (không làm tròn)": "Return: scale = 2, value 100.00 (unrounded)",
    '"9999999999999999.99" rồi "10000000000000000.00"': '"9999999999999999.99" then "10000000000000000.00"',
    "Return: giá trị đầu tạo được": "Return: first value succeeds",
    'Exception: giá trị sau: InvalidLedgerEntryException "DECIMAL(18,2)"': (
        'Exception: second value: InvalidLedgerEntryException "DECIMAL(18,2)"'
    ),
    '"100.5" và "100.50"': '"100.5" and "100.50"',
    "Return: 2 Money bằng nhau": "Return: both Money values are equal",
    "bank.lock_requested hợp lệ": "Valid bank.lock_requested",
    "Return: entryType LOCK_BUYER_DEPOSIT, fundType BUYER_DEPOSIT, đủ id và số tiền": (
        "Return: entryType LOCK_BUYER_DEPOSIT, fundType BUYER_DEPOSIT, all ids and amount"
    ),
    "bank.release_requested / bank.refund_requested hợp lệ": (
        "Valid bank.release_requested / bank.refund_requested"
    ),
    "Return: đúng loại bút toán của từng lệnh": "Return: matching ledger entry type per command",
    "Queue của lệnh này nhận event hoặc loại bút toán của lệnh khác": (
        "Command queue receives another command's event or entry type"
    ),
    "Payload ngoài schema (thiếu field, field lạ, sai kiểu, số tiền sai định dạng)": (
        "Out-of-schema payload (missing field, extra field, wrong type, malformed amount)"
    ),
    "Lệnh lần đầu": "First-time command",
    "Return: ghi đúng 1 bút toán; publish kết quả completed": (
        "Return: records exactly 1 entry; publishes completed result"
    ),
    "Đã ghi lệnh này": "Command already recorded",
    "Lệnh lặp lại (cùng sourceEventId)": "Duplicate command (same sourceEventId)",
    "Return: không ghi thêm; xác nhận lại đúng bút toán cũ": (
        "Return: no new entry; re-confirms original entry"
    ),
    "Cùng sourceEventId nhưng nội dung khác": "Same sourceEventId with different content",
    "Return: không ghi thêm; không xác nhận": "Return: no new entry; does not confirm",
    "Lệnh vi phạm luật sổ cái": "Command violates ledger rules",
    "Return: publish kết quả failed": "Return: publishes failed result",
    "Request khác chèn trước cùng sourceEventId": "Concurrent request inserts same sourceEventId first",
    "Lệnh thua race khi insert": "Command loses insert race",
    "Return: xác nhận bút toán của bên thắng": "Return: confirms winner's ledger entry",
    "buyer-1, seller-1, listing-1, terms mẫu": "buyer-1, seller-1, listing-1, sample terms",
    "Return: status OFFERED; phát 1 ContractOfferedEvent": (
        "Return: status OFFERED; emits 1 ContractOfferedEvent"
    ),
    "buyer-1, terms mới (200 kg, 450.000 VND)": "buyer-1, new terms (200 kg, 450,000 VND)",
    "Return: status NEGOTIATING; terms = terms mới; phát ContractNegotiatingEvent": (
        "Return: status NEGOTIATING; terms = new terms; emits ContractNegotiatingEvent"
    ),
    "seller-1, terms mới lần 2": "seller-1, second new terms",
    "Return: vẫn NEGOTIATING; terms cập nhật; phát thêm 1 ContractNegotiatingEvent": (
        "Return: stays NEGOTIATING; terms updated; emits another ContractNegotiatingEvent"
    ),
    "buyer-1, terms mới": "buyer-1, new terms",
    "stranger-id, terms mới": "stranger-id, new terms",
    "Contract vừa offer, đã pull 1 lần": "Newly offered contract, already pulled once",
    "gọi lần 2": "second call",
    "Return: list rỗng": "Return: empty list",
    "(không tham số)": "(no parameters)",
    "Return: status ACTIVE; không phát event Phase 1": "Return: status ACTIVE; no Phase 1 event emitted",
    "Contract chưa SIGNED": "Contract not yet SIGNED",
    "buyer-1, listing-1, terms mẫu": "buyer-1, listing-1, sample terms",
    "Return: không gọi save": "Return: save not called",
    (
        "Test cũ của CreateContractUseCaseTest (31/10) đã được viết lại: lớp test hiện có các case giữ hàng, "
        "idempotency key và fail-closed của #232, không còn case nào ứng với dòng này"
    ): (
        "Old CreateContractUseCaseTest (31/10) was rewritten: the class now covers stock reservation, idempotency "
        "keys and fail-closed behavior from #232, with no remaining test for this row"
    ),
    "listing-1 ACTIVE; user buyer-1, seller-1 tồn tại": "listing-1 ACTIVE; users buyer-1, seller-1 exist",
    'Return: lưu contract OFFERED, buyer-1/seller-1, product "Rice"; response status OFFERED': (
        'Return: saves OFFERED contract, buyer-1/seller-1, product "Rice"; response status OFFERED'
    ),
    "Như UTC002": "Same as UTC002",
    'Return: contract lưu với id "fixed-id"': 'Return: contract saved with id "fixed-id"',
    "Return: id tự sinh, không rỗng": "Return: generated non-empty id",
    "Return: snapshot org name/email từ UserPort; sellerId lấy từ listing, không từ command": (
        "Return: snapshots org name/email from UserPort; sellerId taken from listing, not command"
    ),
    "Không có contract-1": "contract-1 does not exist",
    "contract-1 tồn tại": "contract-1 exists",
    "contract-1 giữa buyer-1 và seller-1": "contract-1 between buyer-1 and seller-1",
    "buyer-1 có 2 contract": "buyer-1 has 2 contracts",
    "Return: 2 contract, đều của buyer-1": "Return: 2 contracts, all belonging to buyer-1",
    "buyer-1 có 1 contract OFFERED": "buyer-1 has 1 OFFERED contract",
    "buyer-1 không có contract": "buyer-1 has no contracts",
    "Return: content rỗng, totalElements = 0, empty = true": (
        "Return: empty content, totalElements = 0, empty = true"
    ),
    "seller-1 có 2 contract": "seller-1 has 2 contracts",
    "Return: 2 contract, đều của seller-1": "Return: 2 contracts, all belonging to seller-1",
    "seller-1 có 1 contract OFFERED": "seller-1 has 1 OFFERED contract",
    "seller-1 không có contract": "seller-1 has no contracts",
    "Return: content rỗng, empty = true": "Return: empty content, empty = true",
    "Phân trang": "Pagination",
    "buyer-1 có 11 contract": "buyer-1 has 11 contracts",
}

TRANSLATIONS.update({
    # ── Contract (cont.) & Escrow & Inspection & NotificationSender..NotifyLegacy (521..1040) ──
    "contract-1, buyer-1, terms mới": "contract-1, buyer-1, new terms",
    "Return: không save": "Return: not saved",
    "contract-1, buyer-1, terms mới (120 kg, 480.000 VND)": "contract-1, buyer-1, new terms (120 kg, 480,000 VND)",
    "Return: status NEGOTIATING; terms mới; có save": "Return: status NEGOTIATING; new terms; saved",
    "contract-1, seller-1, terms mới": "contract-1, seller-1, new terms",
    "Return: status NEGOTIATING; có save": "Return: status NEGOTIATING; saved",
    "contract-1, stranger-99, terms mới": "contract-1, stranger-99, new terms",
    "Terms hợp lệ, giá × khối lượng các đợt": "Valid terms, price × milestone weights",
    "Return: tổng giá trị = đơn giá × tổng khối lượng các đợt": (
        "Return: total value = unit price × sum of milestone weights"
    ),
    "Hàng không phải cà phê": "Non-coffee commodity",
    "Hồ sơ chất lượng không phải cà phê": "Non-coffee quality spec",
    "Cà phê không gắn plot nào": "Coffee with no plot attached",
    "LegalProfile Luật Thương mại (trần phạt 8%)": "Commercial Law LegalProfile (8% penalty cap)",
    "Phạt vi phạm 9%": "Breach penalty 9%",
    "Client gửi trần phạt cao hơn trần luật": "Client submits penalty cap above statutory cap",
    "LegalProfile Bộ luật Dân sự": "Civil Code LegalProfile",
    "Phạt vi phạm vượt 8%": "Breach penalty over 8%",
    "Return: chấp nhận; chỉ áp chính sách bồi thường của profile dân sự": (
        "Return: accepted; applies only the Civil Code compensation policy"
    ),
    "Giá thoả thuận nằm ngoài biên giá": "Agreed price outside price band",
    "Giám định Level 2 không có tổ chức giám định": "Level 2 inspection without inspection agency",
    "Chỉ số các đợt giao 1, 3 (thiếu 2)": "Delivery milestone indices 1, 3 (missing 2)",
    "Ngưỡng lệch chất lượng không tăng dần dung sai → phạt → từ chối": (
        "Quality deviation thresholds not strictly increasing tolerance → penalty → rejection"
    ),
    "Phần trăm chất lượng có 3 chữ số thập phân": "Quality percentage with 3 decimal places",
    "Cọc bên mua mới nằm ngoài biên đàm phán": "Buyer deposit outside negotiation band",
    "Vân tay mẫu hợp đồng không phải SHA-256": "Contract template hash is not SHA-256",
    "Hợp đồng OFFERED": "Contract in OFFERED",
    "Buyer hoặc seller gửi terms mới": "Buyer or seller submits new terms",
    "Return: status NEGOTIATING; terms = terms mới": "Return: status NEGOTIATING; terms = new terms",
    "Người ngoài hợp đồng sửa": "Non-party attempts to revise",
    "Đã có chữ ký thứ nhất": "First signature already recorded",
    "Một bên sửa terms": "One party revises terms",
    "Hợp đồng SIGNED": "Contract in SIGNED",
    "Tổng khối lượng khác khối lượng đã giữ hàng": "Total quantity differs from reserved stock",
    "Đổi lịch giao, giữ nguyên tổng khối lượng": "Change delivery schedule, keep total quantity",
    "Return: chấp nhận": "Return: accepted",
    "Đổi hàng hoá sang loại khác": "Change commodity to another type",
    "Hợp đồng Phase 1 không có terms cà phê": "Phase 1 contract without coffee terms",
    "Seller đồng ý, đúng phiên bản câu cam kết và vân tay terms": (
        "Seller consents with matching consent version and terms hash"
    ),
    "Return: bằng chứng đồng ý gắn với câu cam kết và terms hiện tại": (
        "Return: consent evidence bound to current statement and terms"
    ),
    "Buyer đồng ý": "Buyer consents",
    "Return: nhận câu cam kết của bên mua; trạng thái hợp đồng không đổi": (
        "Return: accepts buyer consent; contract status unchanged"
    ),
    "Bên kia đã ký": "Counterparty already signed",
    "Bên còn lại đồng ý": "Remaining party consents",
    "Người ngoài hợp đồng": "Non-party",
    "Buyer khai là seller": "Buyer claims to be seller",
    "Phiên bản câu cam kết cũ": "Stale consent statement version",
    "Vân tay terms cũ (terms đã đổi)": "Stale terms hash (terms changed)",
    "Một bên đồng ý": "One party consents",
    "Bên đó đã ký": "That party already signed",
    "Bên đó đồng ý lại": "That party consents again",
    "Chữ ký thứ nhất": "First signature",
    "Return: ghi chữ ký với challenge và vân tay terms hiện tại; hợp đồng chưa SIGNED": (
        "Return: records signature with challenge and current terms hash; contract not yet SIGNED"
    ),
    "Bên kia đã ký cùng terms": "Counterparty already signed on same terms",
    "Chữ ký thứ hai": "Second signature",
    "Chữ ký thứ nhất trên terms khác": "First signature on different terms",
    "Return: status vẫn NEGOTIATING": "Return: status stays NEGOTIATING",
    "Cùng bên ký lần 2": "Same party signs a second time",
    "Challenge cấp cho terms khác": "Challenge issued for different terms",
    "Người gọi không phải bên đó": "Caller is not that party",
    "Ký thêm": "Additional signature",
    "buyerId / sellerId / người lạ": "buyerId / sellerId / stranger",
    "Return: BUYER / SELLER / rỗng": "Return: BUYER / SELLER / empty",
    "Status OFFERED / NEGOTIATING, chưa đủ 2 chữ ký": "Status OFFERED / NEGOTIATING, fewer than 2 signatures",
    "Buyer hoặc seller rút": "Buyer or seller withdraws",
    "Bên đã ký rút": "Signed party withdraws",
    "Bên chưa ký rút": "Unsigned party withdraws",
    "Status SIGNED / ACTIVE / đã kết thúc": "Status SIGNED / ACTIVE / terminal",
    "Một bên rút": "One party withdraws",
    "Người ngoài hợp đồng rút": "Non-party withdraws",
    "Return: hợp đồng không đổi": "Return: contract unchanged",
    "Return: status không đổi": "Return: status unchanged",
    "Đã có báo cáo lỗi": "Failure already reported",
    "Lỗi mới tới sau": "Later failure arrives",
    "Return: chỉ cập nhật báo cáo mới nhất": "Return: updates only with latest report",
    "Thời điểm = đúng lúc mở cửa sổ huỷ một bên": "Timestamp = exact start of unilateral cancel window",
    "Return: cho phép huỷ một bên": "Return: allows unilateral cancellation",
    "Cửa sổ huỷ kết thúc trước thời điểm lỗi": "Cancel window ended before failure timestamp",
    "Tổng giá trị × tỉ lệ cọc ra số lẻ": "Total value × deposit rate has fractional VND",
    "Return: làm tròn tới đồng": "Return: rounds to nearest VND",
    "Kết quả lẻ đúng 0,5 đồng": "Fractional result of exact 0.5 VND",
    "Return: làm tròn lên": "Return: rounds half up",
    "Kết quả chẵn đồng": "Exact whole VND result",
    "Kiểm tra trong 300 s": "Verified within 300 s",
    "Return: challenge còn hiệu lực": "Return: challenge still valid",
    "Kiểm tra đúng giây thứ 300": "Verified at exact 300th second",
    "Nhập sai lần thứ 5 rồi nhập tiếp": "5th wrong attempt then another attempt",
    "Challenge đã dùng": "Challenge already consumed",
    "Dùng lại": "Reuse",
    "Policy có giá trị 0 hoặc âm": "Policy with 0 or negative value",
    "Mã đầu tiên; mã sau cooldown": "First code; code after cooldown",
    "Return: được gửi": "Return: sent",
    "Mã thứ hai trong 60 s": "Second code within 60 s",
    "Mã thứ 6 trong 1 giờ": "6th code within 1 hour",
    "Sinh nhiều mã": "Generate multiple codes",
    "Return: luôn 6 chữ số, giữ số 0 đầu": "Return: always 6 digits, preserves leading zeros",
    "Băm 1 mã": "Hash 1 code",
    "Return: giá trị băm phụ thuộc khoá, không bao giờ là mã gốc": (
        "Return: hash depends on key, never matches raw code"
    ),
    "Pepper ngắn hoặc null": "Short or null pepper",
    "Cùng terms băm nhiều lần": "Hash same terms multiple times",
    "Return: cùng vân tay": "Return: same fingerprint",
    "Cùng giá trị viết số khác nhau (1.50 / 1.5)": "Same numeric value formatted differently (1.50 / 1.5)",
    "Đổi bất kỳ điều khoản nào": "Change any term",
    "Return: vân tay khác": "Return: different fingerprint",
    "Chỉ đổi mẫu hợp đồng hoặc hồ sơ chất lượng": "Change only contract template or quality spec",
    "Xem dạng chuẩn hoá": "Inspect canonical form",
    "Return: phẳng, sắp xếp key, giữ key null": "Return: flat, sorted keys, keeps null keys",
    "Terms đọc lại từ DB": "Terms reloaded from DB",
    "Return: ra đúng vân tay đã lưu": "Return: produces stored fingerprint",
    "Terms lưu trong DB bị sửa tay": "DB-stored terms tampered with",
    "Code mới thêm field": "Newer code adds a field",
    "Return: vân tay của terms cũ không đổi": "Return: old terms fingerprint unchanged",
    "Đăng nhập trong 5 phút": "Logged in within 5 minutes",
    "Đăng nhập cũ, thiếu hoặc ở tương lai": "Stale, missing or future login timestamp",
    "Đã KYC, không bị khoá, thẩm quyền còn hạn": "KYC verified, unlocked, valid authorization",
    "Return: đủ điều kiện": "Return: eligible",
    "Thẩm quyền hết hạn sau này; khoá đã hết": "Authorization expires in future; lock expired",
    "Chưa KYC": "Not yet KYC verified",
    "Thẩm quyền hết hạn đúng hoặc trước hiện tại": "Authorization expired at or before now",
    "Đang bị khoá uy tín": "Currently reputation-locked",
    "Không có email nhận mã": "No email to receive OTP",
    "User không tồn tại": "User does not exist",
    "user-service sập": "user-service down",
    "Bên kia ký được, không có email": "Counterparty can sign, has no email",
    "Bên kia chưa KYC / hết thẩm quyền / bị khoá / không tồn tại": (
        "Counterparty unverified / expired auth / locked / missing"
    ),
    "Exception: ContractConflictException (COUNTERPARTY_NOT_ELIGIBLE), không nêu lý do của bên kia": (
        "Exception: ContractConflictException (COUNTERPARTY_NOT_ELIGIBLE), omits counterparty's reason"
    ),
    "user-service sập khi kiểm bên kia": "user-service down while checking counterparty",
    "Return: khớp schema; cọc và vân tay lấy từ terms": (
        "Return: matches schema; deposits and hash taken from terms"
    ),
    "Hợp đồng chưa SIGNED": "Contract not yet SIGNED",
    "buyerId không phải UUID": "buyerId is not a UUID",
    "Exception: IllegalStateException (chặn ở đây, không để vào DLQ bên nhận)": (
        "Exception: IllegalStateException (blocked here, prevents DLQ downstream)"
    ),
    "Publish lệnh gửi email neo hợp đồng": "Publish contract-anchor email command",
    "Return: khớp schema; đủ 2 bên và đúng terms đã ký": (
        "Return: matches schema; both parties and signed terms included"
    ),
    "Publish lệnh": "Publish command",
    "Hợp đồng WITHDRAWN": "Contract WITHDRAWN",
    "Return: khớp schema, kèm khối lượng đã giữ": "Return: matches schema with reserved quantity",
    "Return: khớp schema, không breach, không remedy": "Return: matches schema, no breach, no remedy",
    "Thông báo rút offer": "Notify offer withdrawal",
    "Return: chỉ gửi bên kia; khớp schema": "Return: sent only to counterparty; matches schema",
    "Hợp đồng chưa WITHDRAWN": "Contract not WITHDRAWN",
    "Các event rút offer": "Offer withdrawal events",
    "Khai báo queue": "Declare queues",
    "Queue anchor_requested của notification": "Notification anchor_requested queue",
    "Return: cùng tham số notification khai báo": "Return: matches parameters declared by notification",
    "Queue contract.activated của product": "Product contract.activated queue",
    "Return: cùng tham số product khai báo": "Return: matches parameters declared by product",
    "Queue activation_failed của notification": "Notification activation_failed queue",
    "Return: cùng tham số": "Return: matching parameters",
    "Các queue nhánh huỷ kích hoạt": "Activation-cancellation branch queues",
    "Return: cùng tham số service chủ": "Return: matches owning service's parameters",
    "Các queue nhánh rút offer": "Offer-withdrawal branch queues",
    "Return: được khai báo": "Return: declared",
    "Event hợp lệ, có hoặc không có danh sách leg đã trả": (
        "Valid event, with or without refunded-legs list"
    ),
    "Return: đọc đúng contractId": "Return: extracts contractId",
    "Payload sai schema (thiếu field, field lạ, sai kiểu)": (
        "Invalid schema payload (missing field, extra field, wrong type)"
    ),
    "OPERATOR, có Idempotency-Key": "OPERATOR with Idempotency-Key",
    "BUYER / SELLER / ADMIN; hoặc thiếu Idempotency-Key": "BUYER / SELLER / ADMIN; or missing Idempotency-Key",
    "Body hợp lệ, có Idempotency-Key": "Valid body with Idempotency-Key",
    "Return: 200, challenge trả trần; truyền auth_time từ gateway": (
        "Return: 200, unwrapped challenge; passes auth_time from gateway"
    ),
    "Field lạ, thiếu field, hash sai định dạng": "Extra field, missing field, malformed hash",
    "Thiếu Idempotency-Key": "Missing Idempotency-Key",
    "Role khác BUYER/SELLER": "Role other than BUYER/SELLER",
    "Use case từ chối (các loại lỗi)": "Use case rejects (various error types)",
    "Return: đúng mã HTTP của từng lỗi": "Return: exact HTTP status per error",
    "Mã OTP hợp lệ": "Valid OTP code",
    "Return: 204; truyền mã, auth_time, không truyền IP": (
        "Return: 204; passes code and auth_time, omits IP"
    ),
    "Return: 400, không lặp lại mã trong response": "Return: 400, does not echo code in response",
    "Sai mã; bên kia không đủ điều kiện": "Wrong code; counterparty ineligible",
    "Hợp đồng cà phê": "Coffee contract",
    "Return: body đúng ContractResponse, có vân tay terms, không bọc wrapper": (
        "Return: ContractResponse body with terms hash, unwrapped"
    ),
    "Hợp đồng có / chưa có lỗi khoá cọc": "Contract with / without deposit-lock failure",
    "Return: activationRecovery chỉ xuất hiện sau lỗi khoá cọc": (
        "Return: activationRecovery appears only after deposit-lock failure"
    ),
    "Return: giữ body cũ có wrapper": "Return: preserves legacy wrapped body",
    "Body hợp lệ": "Valid body",
    "Return: 200; trả nguyên terms đã lưu, không wrapper": (
        "Return: 200; returns saved terms directly, unwrapped"
    ),
    "Body có field do server tự tính": "Body includes server-computed fields",
    "ADMIN sửa thay các bên": "ADMIN attempts to revise on parties' behalf",
    "Buyer/seller, có Idempotency-Key": "Buyer/seller with Idempotency-Key",
    "Return: 204, không body; người gọi lấy từ principal": (
        "Return: 204, empty body; caller resolved from principal"
    ),
    "OPERATOR / ADMIN rút thay": "OPERATOR / ADMIN attempts to withdraw",
    "Đã có chữ ký thứ hai": "Second signature already recorded",
    "Rút": "Withdraw",
    "Người ngoài hợp đồng; hợp đồng không tồn tại": "Non-party; contract does not exist",
    "Header epoch seconds hợp lệ": "Valid epoch-seconds header",
    "Return: auth_time tương ứng": "Return: matching auth_time",
    "Thiếu header hoặc không đọc được": "Missing or unparseable header",
    "Return: không có auth_time": "Return: no auth_time",
    "user-service trả 404": "user-service returns 404",
    "Return: rỗng (user không tồn tại)": "Return: empty (user not found)",
    "Lỗi khác (5xx, timeout)": "Other errors (5xx, timeout)",
    "Exception: UserServiceUnavailableException, không bao giờ coi là qua": (
        "Exception: UserServiceUnavailableException, never treated as passed"
    ),
    "Event hợp lệ": "Valid event",
    "Return: map eventId, correlationId, contractId, buyerId, sellerId, 2 khoản cọc": (
        "Return: maps eventId, correlationId, contractId, buyerId, sellerId, both deposits"
    ),
    "Event không có correlationId": "Event without correlationId",
    "Return: correlationId = eventId (bắt đầu chuỗi mới)": (
        "Return: correlationId = eventId (starts new chain)"
    ),
    "version không hỗ trợ": "Unsupported version",
    "thiếu field bắt buộc": "Missing required field",
    "payload có field lạ": "Payload has extra field",
    "deposit 1 chữ số thập phân": "Deposit with 1 decimal place",
    "deposit dạng number thay vì string": "Deposit as number instead of string",
    "deposit âm": "Negative deposit",
    "Return: 2 leg BUYER, SELLER trạng thái REQUESTED, createdAt = NOW; gửi 2 lệnh kèm correlationId, eventId": (
        "Return: 2 REQUESTED legs (BUYER, SELLER), createdAt = NOW; sends 2 commands with correlationId, eventId"
    ),
    "Return: chỉ 1 leg BUYER, 1 lệnh": "Return: only 1 BUYER leg, 1 command",
    "Return: không lưu, không gửi gì": "Return: nothing saved, nothing sent",
    "Return: không lưu leg buyer, không gửi gì": "Return: buyer leg not saved, nothing sent",
    "Return: leg REQUESTED đủ field; createdAt = updatedAt = NOW": (
        "Return: REQUESTED leg with all fields; createdAt = updatedAt = NOW"
    ),
    "Tạo 2 leg liên tiếp": "Create 2 consecutive legs",
    "Return: mỗi leg có legId, requestEventId là UUID riêng, không trùng nhau": (
        "Return: each leg gets distinct UUIDs for legId and requestEventId"
    ),
    "party / amount / time = null (từng cái)": "party / amount / time = null (each)",
    "0; 10.005; 10.5 so với 10.50": "0; 10.005; 10.5 vs 10.50",
    "Exception: 0 và 10.005: InvalidDepositLegException": "Exception: 0 and 10.005: InvalidDepositLegException",
    "Buyer LOCKED, seller hết lượt retry": "Buyer LOCKED, seller retries exhausted",
    "Một leg hết lượt, leg kia còn đang xử lý": "One leg exhausted, other leg still processing",
    "Return: chưa coi là lỗi": "Return: not yet failed",
    "Cả 2 leg hết lượt; hợp đồng chỉ có cọc bên mua lỗi": (
        "Both legs exhausted; buyer-only deposit contract failed"
    ),
    "Return: BOTH; hợp đồng 1 leg cũng lỗi được": "Return: BOTH; 1-leg contract also fails",
    "Return: leg lỗi về REQUESTED với vòng retry mới; leg LOCKED giữ nguyên": (
        "Return: failed leg resets to REQUESTED with new retry cycle; LOCKED leg untouched"
    ),
    "Return: lệnh LOCKED cho leg tương ứng": "Return: LOCKED command for matching leg",
    "Return: lệnh FAILED kèm lý do": "Return: FAILED command with reason",
    "Không có milestoneId": "No milestoneId",
    "Return: chấp nhận (cọc cấp hợp đồng)": "Return: accepted (contract-level deposit)",
    "Payload ngoài schema": "Out-of-schema payload",
    "Sai eventType hoặc producer": "Wrong eventType or producer",
    "Kết quả completed (kể cả gửi lặp)": "Completed result (including duplicate)",
    "Return: leg LOCKED, chỉ 1 lần": "Return: leg LOCKED, applied once",
    "Leg đã lỗi 1 lần": "Leg failed once",
    "Lần retry thành công": "Retry succeeds",
    "Lỗi liên tiếp": "Consecutive failures",
    "Return: RETRY_SCHEDULED theo lịch; hết lịch → RETRIES_EXHAUSTED, leg FAILED, 4 lần lỗi": (
        "Return: RETRY_SCHEDULED per schedule; exhausted → RETRIES_EXHAUSTED, leg FAILED, 4 failures"
    ),
    "Kết quả lỗi tới muộn": "Late failure result arrives",
    "Return: IGNORED_ALREADY_LOCKED, leg vẫn LOCKED": "Return: IGNORED_ALREADY_LOCKED, leg stays LOCKED",
    "Leg chờ retry": "Leg awaiting retry",
    "Gọi trước / đúng giờ retry; gọi khi leg không lỗi": (
        "Called before / at retry time; called when leg not failed"
    ),
    "Return: chỉ đúng giờ mới về REQUESTED": "Return: transitions to REQUESTED only when due",
    "Exception: IllegalStateException khi leg không lỗi": "Exception: IllegalStateException when leg not failed",
    "Danh sách rỗng hoặc có khoảng 0": "Empty list or zero interval",
    "Yêu cầu dừng để hoàn cọc (kể cả gửi lặp)": "Request stop for refund (including duplicate)",
    "Return: RETURN_REQUESTED với id lệnh mới; lần lặp → ALREADY_STOPPED": (
        "Return: RETURN_REQUESTED with new command id; duplicate → ALREADY_STOPPED"
    ),
    "Yêu cầu dừng": "Request stop",
    "Return: CANCELLED; lỗi tới sau → IGNORED_CANCELLED, không retry nữa": (
        "Return: CANCELLED; later failure → IGNORED_CANCELLED, no further retry"
    ),
    "Lệnh khoá đang bay": "Lock command in flight",
    "Return: WAITING_FOR_LOCK_RESULT; khoá xong → RETURN_REQUESTED, khoá lỗi → CANCELLED": (
        "Return: WAITING_FOR_LOCK_RESULT; lock succeeded → RETURN_REQUESTED, lock failed → CANCELLED"
    ),
    "Kết quả trả thành công (kể cả lặp)": "Refund succeeded result (including duplicate)",
    "Return: xác nhận đã trả 1 lần": "Return: marks refunded once",
    "Bank từ chối trả liên tiếp": "Bank repeatedly rejects refund",
    "Return: gửi lại theo lịch; hết lịch → RETURN_FAILED": (
        "Return: retries per schedule; exhausted → RETURN_FAILED"
    ),
    "Leg không có lệnh trả": "Leg has no refund command",
    "Kết quả trả tới": "Refund result arrives",
    "Các leg CANCELLED / RETURNED / còn đang trả": "Legs in CANCELLED / RETURNED / refunding",
    "Return: chỉ xong khi mọi leg CANCELLED hoặc RETURNED": (
        "Return: complete only when all legs are CANCELLED or RETURNED"
    ),
    "inspector-a đủ điều kiện; commission giao cho inspector-a": (
        "inspector-a eligible; commission assigned to inspector-a"
    ),
    "Return: trả assignment đó": "Return: returns that assignment",
    "Không có commission": "No commission",
    'Exception: InspectionAssignmentAccessDeniedException "No authorized inspection assignment" (không lộ việc không tồn tại)': (
        'Exception: InspectionAssignmentAccessDeniedException "No authorized inspection assignment" (hides non-existence)'
    ),
    "Assignment của inspector-a đã kết thúc": "inspector-a's assignment is closed",
    "Gate ném InspectorNotEligibleException": "Gate throws InspectorNotEligibleException",
    "Return: không đọc assignment": "Return: does not query assignment",
    "Assignment active (contract, milestone) của inspector-a": (
        "Active (contract, milestone) assignment for inspector-a"
    ),
    "(contract, milestone) rồi (other-contract, milestone)": (
        "(contract, milestone) then (other-contract, milestone)"
    ),
    "Return: case 1 trả inspector-a": "Return: case 1 returns inspector-a",
    "Assignment active thuộc inspector-b": "Active assignment belongs to inspector-b",
    "Gate ném EligibilityUnavailableException (user-service sập)": (
        "Gate throws EligibilityUnavailableException (user-service down)"
    ),
    "Profile INSPECTOR, VERIFIED, hết hạn NOW + 1s": "INSPECTOR profile, VERIFIED, expires NOW + 1s",
    "Return: trả ACTOR": "Return: returns ACTOR",
    "Profile INSPECTOR, VERIFIED, không hạn (null)": "INSPECTOR profile, VERIFIED, no expiry (null)",
    "Đăng nhập không phải INSPECTOR": "Logged in as non-INSPECTOR",
    "Role đăng nhập = BUYER": "Logged-in role = BUYER",
    "Return: không gọi user-service": "Return: does not call user-service",
    "Role đăng nhập = SELLER": "Logged-in role = SELLER",
    "Role đăng nhập = ADMIN": "Logged-in role = ADMIN",
    "Role đăng nhập = OPERATOR": "Logged-in role = OPERATOR",
    "Role đăng nhập = ARBITRATOR": "Logged-in role = ARBITRATOR",
    "Đăng nhập INSPECTOR": "Logged in as INSPECTOR",
    "Profile VERIFIED, hết hạn = NOW - 1s": "VERIFIED profile, expired = NOW - 1s",
    "Profile VERIFIED, hết hạn = NOW": "VERIFIED profile, expires = NOW",
    "user-service lỗi": "user-service error",
    "Không có profile": "No profile",
    "Profile trả về userId = someone-else": "Returned profile has userId = someone-else",
    "SecurityContext rỗng": "Empty SecurityContext",
    "Token chưa authenticated": "Unauthenticated token",
    "AnonymousAuthenticationToken có ROLE_INSPECTOR": "AnonymousAuthenticationToken with ROLE_INSPECTOR",
    "Server trả profile hợp lệ": "Server returns valid profile",
    "Return: gọi đúng /internal/v1/users/{ID} kèm X-Internal-Secret; profile đủ điều kiện": (
        "Return: calls /internal/v1/users/{ID} with X-Internal-Secret; returns eligible profile"
    ),
    "Return: parse đúng thời điểm hết hạn": "Return: parses expiry timestamp",
    'Body hợp lệ nhưng userId = "x"': 'Valid body but userId = "x"',
    "Body thiếu field userId": "Body missing userId",
    "Body thiếu field role": "Body missing role",
    "Body thiếu field verificationStatus": "Body missing verificationStatus",
    "Body thiếu field authorizationExpiresAt": "Body missing authorizationExpiresAt",
    "Return: không được hiểu thành vô hạn": "Return: not treated as no-expiry",
    "Return: không lộ body response": "Return: does not leak response body",
    "Server đã tắt": "Server is down",
    "Server treo không trả header": "Server hangs without sending headers",
    'Exception: EligibilityUnavailableException "Authoritative inspector eligibility could not be validated", không kèm cause': (
        'Exception: EligibilityUnavailableException "Authoritative inspector eligibility could not be validated", without cause'
    ),
    "Body bị ngắt giữa chừng": "Truncated response body",
    "Body chứa email/secret nhạy cảm, sai định dạng": "Malformed body containing sensitive email/secret",
    "Return: không lộ nội dung vào message": "Return: does not leak body into error message",
    "Đọc danh sách inspector": "Fetch inspector list",
    "Return: gọi endpoint nội bộ riêng; response chỉ có danh tính tối thiểu": (
        "Return: calls dedicated internal endpoint; response has minimal identity only"
    ),
    "Danh sách rỗng": "Empty list",
    "Return: hợp lệ": "Return: valid",
    "Response hỏng": "Malformed response",
    "Exception: EligibilityUnavailableException (không coi là rỗng)": (
        "Exception: EligibilityUnavailableException (not treated as empty)"
    ),
    "user-service không trả lời": "user-service unresponsive",
    "Danh sách có id trùng": "List has duplicate ids",
    "Return: không làm lệch việc chọn inspector": "Return: does not skew inspector selection",
    "Secret đúng": "Valid secret",
    "Return: SecurityContext = inspector-a trong request; xoá sau request": (
        "Return: sets SecurityContext = inspector-a for request; clears after request"
    ),
    "Không có X-Gateway-Secret (chỉ có X-Internal-Secret)": (
        "Missing X-Gateway-Secret (only X-Internal-Secret present)"
    ),
    "Return: 401, không vào chain": "Return: 401, skips filter chain",
    "SecurityContext đã có inspector-b": "SecurityContext already has inspector-b",
    "Return: giữ inspector-b": "Return: keeps inspector-b",
    "Header X-User-Id xuất hiện 2 lần": "Duplicate X-User-Id header",
    "Header X-User-Role xuất hiện 2 lần": "Duplicate X-User-Role header",
    "Header X-Gateway-Secret xuất hiện 2 lần": "Duplicate X-Gateway-Secret header",
    "Return: 401, không set SecurityContext": "Return: 401, does not set SecurityContext",
    "Return: authority ROLE_BUYER, ROLE_INSPECTOR (trim, bỏ trùng)": (
        "Return: authorities ROLE_BUYER, ROLE_INSPECTOR (trimmed, deduplicated)"
    ),
    "GET /actuator/health không header": "GET /actuator/health without headers",
    "Return: vào chain, không set SecurityContext": "Return: enters chain without setting SecurityContext",
    "DLQ có 7 message": "DLQ has 7 messages",
    "Return: 1 dòng WARN event=inspection_dlq_nonempty, nêu queue và số message": (
        "Return: 1 WARN line event=inspection_dlq_nonempty with queue and count"
    ),
    "Mọi DLQ rỗng": "All DLQs empty",
    "Return: không log; chỉ đếm thụ động": "Return: no log; passive count only",
    "Probe lỗi": "Probe error",
    "Return: log chỉ nêu queue, không lộ chi tiết broker; vẫn đóng connection": (
        "Return: logs queue only, hides broker details; still closes connection"
    ),
    "Broker không kết nối được": "Broker unreachable",
    "Return: lỗi không thoát ra khỏi lần chạy theo lịch": "Return: exception does not escape scheduled run",
    "Return: dùng cấu hình connection riêng, có giới hạn thời gian": (
        "Return: uses isolated connection config with timeouts"
    ),
    "Cấu hình bean": "Bean configuration",
    "Property tắt monitor": "Property disables monitor",
    "Return: không tạo bean monitor": "Return: monitor bean not created",
    "Mặc định": "Default",
    "Return: bật, dùng probe riêng": "Return: enabled, uses isolated probe",
    "Bộ vector chuẩn RFC 8785 (số, thứ tự key lồng nhau)": (
        "RFC 8785 standard test vectors (numbers, nested key order)"
    ),
    "Return: đúng chuỗi chuẩn hoá": "Return: exact canonical string",
    "Cùng dữ liệu, cách viết khác nhau": "Same data, different formatting",
    "Return: cùng bytes, cùng hash": "Return: identical bytes, identical hash",
    "Unicode, escape, surrogate không hợp lệ": "Unicode, escapes, invalid surrogates",
    "Return: sắp xếp/escape đúng; surrogate lỗi bị từ chối": (
        "Return: sorts/escapes properly; invalid surrogates rejected"
    ),
    "Danh sách lý do": "Reason list",
    "Return: giữ nguyên có/không và thứ tự": "Return: preserves presence/absence and order",
    "Thời điểm và phần chiếu biên bản": "Timestamps and report projection fields",
    "Return: giữ chính xác": "Return: preserved verbatim",
    "Kiểm tra đầu vào": "Input validation",
    "Field bắt buộc vắng hoặc null": "Required field absent or null",
    "Field chủ quan ở cả 2 cấp": "Subjective fields at both levels",
    "Ép kiểu, key trùng, ký tự thừa cuối, giá trị sai": (
        "Type coercion, duplicate keys, trailing garbage, invalid values"
    ),
    "Metric thiếu field không null": "Metric missing non-null field",
    "Field chuỗi của biên bản không đúng kiểu": "Report string field has wrong type",
    "Giá trị sai khác; danh sách người gọi bị sửa sau khi đưa vào": (
        "Mismatched values; caller list mutated after passing"
    ),
    "Exception: IllegalArgumentException / không bị ảnh hưởng": (
        "Exception: IllegalArgumentException / unaffected"
    ),
    "Sửa bất kỳ field nào của kết quả": "Mutate any result field",
    "Sửa bất kỳ field nào của biên bản": "Mutate any report field",
    "Hash đúng / lệch / sai định dạng": "Valid / mismatched / malformed hash",
    "Return: đúng thì qua, lệch và sai định dạng bị từ chối": (
        "Return: valid passes, mismatched or malformed rejected"
    ),
    "Kiểm tra biên": "Boundary checks",
    "Thời điểm không chuẩn hoặc sai": "Non-canonical or invalid timestamp",
    "Thời điểm chuẩn, id chữ thường": "Canonical timestamp, lowercase id",
    "UUID chữ hoa": "Uppercase UUID",
    "Exception: IllegalArgumentException (không tạo cam kết thứ hai cho cùng id)": (
        "Exception: IllegalArgumentException (prevents second commitment for same id)"
    ),
    "Khối lượng có số 0 thừa cuối, đúng biên": "Weight with trailing zeros, at boundaries",
    "Khối lượng ngoài miền giá trị": "Weight out of range",
    "Phần trăm có số 0 thừa cuối, đúng biên": "Percentage with trailing zeros, at boundaries",
    "Phần trăm ngoài miền giá trị": "Percentage out of range",
    "Khối lượng chấp nhận > khối lượng đo": "Accepted weight > measured weight",
    "2 số khác nhau rất gần nhau": "2 very close distinct numbers",
    "Return: không bị gộp thành 1 khi chuẩn hoá": "Return: not collapsed into one on canonicalization",
    "Hash lệch vs hash sai định dạng": "Mismatched hash vs malformed hash",
    "Return: 2 lỗi phân biệt": "Return: 2 distinct errors",
    "Sinh kết quả": "Produce outcome",
    "Kết quả sinh ra; thêm field chính sách": "Generated outcome; extra policy field",
    "Return: khớp cả 2 schema; field chính sách bị từ chối": (
        "Return: matches both schemas; policy field rejected"
    ),
    "Thời điểm nguồn của reportHash": "Source timestamp for reportHash",
    "Return: lấy confirmedAt, không đổi tên key": "Return: uses confirmedAt without renaming key",
    "Khởi động": "Startup",
    "Lịch sai định dạng": "Malformed calendar",
    "Exception: Khởi động lỗi (không âm thầm chỉ bỏ cuối tuần)": (
        "Exception: startup fails (does not silently fall back to weekends-only)"
    ),
    "Thiếu cấu hình": "Missing configuration",
    "Exception: Khởi động lỗi": "Exception: startup fails",
    "Cấu hình ngoài hợp lệ": "Valid external configuration",
    "Return: dùng đúng cấu hình": "Return: uses exact configuration",
    "Event chuẩn": "Valid event",
    "Return: dùng codec chung, gọi use case qua application boundary": (
        "Return: uses shared codec, invokes use case across application boundary"
    ),
    "Payload hỏng; payload tự chỉ định inspector": "Malformed payload; payload tries to pick inspector",
    "Return: không thay đổi gì": "Return: no state change",
    "Lỗi DB hoặc dependency": "DB or dependency failure",
    "Return: vẫn retry được": "Return: remains retryable",
    "eventId đã xử lý": "eventId already processed",
    "Event lặp": "Duplicate event",
    "Return: không gọi use case": "Return: use case not called",
    "Field hợp đồng sai": "Invalid contract field",
    "Event mới, 1 người nhận": "New event, 1 recipient",
    "Return: gửi; ghi SENT kèm email người nhận": "Return: sends; records SENT with recipient email",
    "Người nhận đã SENT cho event này": "Recipient already SENT for this event",
    "Gửi lại": "Redeliver",
    "Return: bỏ qua, không gửi": "Return: skipped, not sent",
    "Provider từ chối": "Provider rejects",
    "Exception: Ném lỗi để retry": "Exception: throws to trigger retry",
    "Lần trước FAILED": "Previous attempt FAILED",
    "Event gửi lại": "Redelivered event",
    "Return: gửi lại, ghi SENT": "Return: resends, records SENT",
    "Cùng người nhận, event khác": "Same recipient, different event",
    "Return: là email riêng": "Return: sent as separate email",
    "Message id của provider": "Provider message id",
    "Provider trả id / không trả id": "Provider returns id / returns no id",
    "Return: ghi id nếu có; SENT không id nếu không có": "Return: stores id if present; SENT without id otherwise",
    "Lần lỗi rồi gửi lại; nhiều người nhận 1 event": "Failed attempt then resend; multiple recipients per event",
    "Return: lần lỗi không ghi id; mỗi người nhận giữ id riêng": (
        "Return: failed attempt stores no id; each recipient keeps own id"
    ),
    "Chống trùng theo (event, người nhận, loại)": "Dedup by (event, recipient, kind)",
    "Gửi thành công": "Send succeeded",
    "Return: ghi loại email, thời điểm gửi, id nền tảng của người nhận nếu có": (
        "Return: records email kind, sent timestamp, recipient user id if available"
    ),
    "1 event 2 người nhận; 1 người nhận 2 loại thông báo": (
        "1 event 2 recipients; 1 recipient 2 notification kinds"
    ),
    "Return: 2 email, 2 bản ghi": "Return: 2 emails, 2 records",
    "Retry sau nhiều lần lỗi": "Retry after multiple failures",
    "Return: dùng lại 1 bản ghi; ghi nội dung của lần gửi này": (
        "Return: reuses single record; stores content from this attempt"
    ),
    "Bản ghi cũ trước migration V2 ở trạng thái SENT / FAILED / PENDING": (
        "Pre-V2 legacy record in SENT / FAILED / PENDING"
    ),
    "Return: SENT thì không gửi; FAILED/PENDING thì gửi với bản ghi mới; bản ghi của người khác không chặn": (
        "Return: SENT skipped; FAILED/PENDING sends with new record; others' records do not block"
    ),
    "Nhiều lần giao cùng event chạy song song": "Concurrent deliveries of same event",
    "2 lần giao cùng lúc": "2 concurrent deliveries",
    "Return: chỉ bên giữ claim gửi; bên kia chờ, thấy SENT thì thôi": (
        "Return: only claim holder sends; other waits and skips once SENT"
    ),
    "Bản ghi PENDING quá hạn lease (bên gửi đã chết)": "PENDING record past lease expiry (dead sender)",
    "Lần giao mới": "New delivery",
    "Return: lấy lại và gửi ngay": "Return: reclaims and sends immediately",
    "Bản ghi FAILED": "FAILED record",
    "Return: đúng 1 bên lấy lại; bên kia thấy SENT": "Return: exactly 1 worker reclaims; other sees SENT",
    "Chờ claim của lần giao khác": "Waiting on another delivery's claim",
    "Claim còn sống": "Active claim",
    "Return: chờ tối đa 50 s, hỏi lại mỗi 1 s": "Return: waits up to 50 s, polls every 1 s",
    "Trong lúc chờ: SENT / FAILED / bên giữ chết quá 45 s": (
        "While waiting: SENT / FAILED / holder dead past 45 s"
    ),
    "Return: bỏ qua / lấy claim và gửi / lấy claim và gửi": (
        "Return: skips / takes claim and sends / takes claim and sends"
    ),
    "Bên giữ còn sống sau 50 s": "Holder still alive after 50 s",
    "Return: không gửi": "Return: does not send",
    "Exception: SendInProgressError (retry được)": "Exception: SendInProgressError (retryable)",
    "Đang tắt service": "Service shutting down",
    "Đang chờ claim": "Waiting on claim",
    "Return: nhìn lần cuối rồi dừng; SENT thì bỏ qua; sleep kết thúc ngay khi bắt đầu tắt": (
        "Return: checks once more then stops; skips if SENT; sleep aborts immediately on shutdown"
    ),
    "Heartbeat giữ claim khi đang gửi": "Heartbeat renews claim during send",
    "Gửi lâu": "Slow send",
    "Return: lease 45 s, làm mới mỗi 15 s; lần giao khác không lấy được": (
        "Return: 45 s lease, renewed every 15 s; other delivery cannot steal"
    ),
    "Gửi xong / gửi lỗi": "Send finished / send failed",
    "Return: dừng heartbeat": "Return: stops heartbeat",
    "1 nhịp heartbeat lỗi; nhịp trước chưa xong": "1 heartbeat tick fails; previous tick still running",
    "Return: log lỗi chỉ có eventId và mã, vẫn gửi tiếp; không chồng nhịp": (
        "Return: logs only eventId and code, continues sending; no overlapping ticks"
    ),
    "Message lỗi an toàn để log": "Safe-to-log error message",
    "Gửi lỗi; repository lỗi; claim còn sống sau khi chờ": (
        "Send error; repository error; claim still active after wait"
    ),
    "Return: message chỉ có event và mã lỗi, không có người nhận hay text của driver": (
        "Return: message contains only event and error code, no recipient or driver text"
    ),
    "Body hợp lệ; expiresAt có offset và phần lẻ giây": (
        "Valid body; expiresAt with offset and fractional seconds"
    ),
    "Return: trả đúng các field của contract": "Return: returns exact contract fields",
    "Body là null, [], chuỗi, số": "Body is null, [], string, number",
    "Exception: Lỗi validate": "Exception: validation error",
    "Thiếu từng field": "Each field missing",
    "Return: báo từng field thiếu": "Return: reports each missing field",
    "requestId rỗng / quá 36 ký tự / có khoảng trắng; recipientUserId, contractId rỗng": (
        "Empty / >36-char / whitespace requestId; empty recipientUserId, contractId"
    ),
    "Email sai định dạng hoặc quá dài": "Malformed or oversized email",
    "otpCode 5 / 7 chữ số, có chữ, dạng số": "5 / 7-digit otpCode, alphabetic, numeric type",
    "signedContentHash viết hoa hoặc thiếu 1 ký tự": "Uppercase or 1-char-short signedContentHash",
    "expiresAt chỉ có ngày / thiếu múi giờ / ngày không tồn tại": (
        "expiresAt date-only / missing timezone / non-existent date"
    ),
    "Field lạ": "Extra field",
    "Return: message không nêu tên field lạ": "Return: error message omits extra field name",
    "Mọi lỗi": "All errors",
    "Return: message không bao giờ trích giá trị": "Return: error message never echoes values",
    "Không có purpose": "No purpose",
    "Return: SIGN_CONTRACT; không bị báo thiếu": "Return: SIGN_CONTRACT; not flagged missing",
    "null, chữ thường, TERMINATE_CONTRACT, số 1, rỗng": "null, lowercase, TERMINATE_CONTRACT, 1, empty",
    "renderOtpEmail() – ký hợp đồng": "renderOtpEmail() – contract signing",
    "Yêu cầu SIGN_CONTRACT": "SIGN_CONTRACT request",
    "Return: subject không chứa mã; mã nằm riêng 1 dòng, sau đó là hợp đồng và hash đầy đủ": (
        "Return: subject omits code; code on its own line, followed by contract and full hash"
    ),
    "expiresAt có offset": "expiresAt with offset",
    "Return: hiện giờ Việt Nam và UTC": "Return: displays Vietnam time and UTC",
    "So với bản otp-email/v1": "Compare against otp-email/v1",
    "Return: giống hệt từng ký tự": "Return: character-for-character identical",
    "renderOtpEmail() – huỷ kích hoạt": "renderOtpEmail() – cancel activation",
    "Yêu cầu CANCEL_ACTIVATION": "CANCEL_ACTIVATION request",
    "Return: subject riêng không có mã; nói rõ mã xác nhận yêu cầu huỷ, không phải chữ ký; có mã, hợp đồng, hạn, hash": (
        "Return: distinct subject without code; states code confirms cancel request, not signing; includes code, contract, expiry, hash"
    ),
    "Định dạng email": "Email formatting",
    "Contract id 66 ký tự, cả 2 loại email": "66-char contract id, both email types",
    "Return: mỗi dòng ≤ 76 ký tự; gửi 7bit nên mã và hash không bị cắt": (
        "Return: every line ≤ 76 chars; 7bit encoding keeps code and hash unbroken"
    ),
    "Nội dung lưu vào log": "Content persisted to log",
    "Return: chỉ là placeholder, không chứa mã": "Return: placeholder only, never contains code",
    "Yêu cầu mới": "New request",
    "Return: gửi email; ghi SENT không lưu mã; ghi id provider nếu có": (
        "Return: sends email; logs SENT without code; stores provider id if any"
    ),
    "Mã ký và mã huỷ cùng địa chỉ": "Signing code and cancel code to same address",
    "Return: 2 email, 2 bản ghi; mã huỷ dùng template và loại riêng": (
        "Return: 2 emails, 2 records; cancel code uses separate template and kind"
    ),
    "requestId đã SENT": "requestId already SENT",
    "Gọi lại": "Retry call",
    "Return: không gửi lại": "Return: does not resend",
    "Exception: Lỗi chỉ có mã": "Exception: error contains code only",
    "requestId đã FAILED": "requestId previously FAILED",
    "Return: gửi lại": "Return: resends",
    "Lần gọi khác đang giữ claim": "Another call holds claim",
    "Gọi": "Call",
    "Return: trả về ngay": "Return: returns immediately",
    "Claim quá hạn lease": "Claim past lease",
    "Return: lấy lại và gửi": "Return: reclaims and sends",
    "Không claim được log": "Cannot claim log",
    "Return: không gửi gì": "Return: sends nothing",
    "Không ghi được SENT / FAILED": "Failed to record SENT / FAILED",
    "Return: vẫn thành công, chỉ log mã / vẫn báo lỗi provider": (
        "Return: still succeeds logging code only / still reports provider error"
    ),
    "Lệnh có 2 người nhận": "Command with 2 recipients",
    "Return: gửi từng người theo thứ tự; body có hash và toàn bộ terms đã ký": (
        "Return: sends sequentially; body includes hash and full signed terms"
    ),
    "Dòng terms chuẩn hoá in trong email (fixture terms, mẫu RFC 8785)": (
        "Canonicalized terms line printed in email (terms fixture, RFC 8785 vectors)"
    ),
    "Return: băm lại ra đúng hash đã ký": "Return: re-hashing yields exact signed hash",
    "Return: 1 bản ghi / người nhận, có id nền tảng và phiên bản template; không có id thì null": (
        "Return: 1 record per recipient with user id and template version; null if no id"
    ),
    "Lệnh gửi lại": "Redelivered command",
    "Return: không gửi lần 2": "Return: does not send a second time",
    "Người nhận thứ 2 lỗi": "Second recipient fails",
    "Return: chỉ gửi lại người thứ 2": "Return: resends only to second recipient",
    "2 người nhận cùng địa chỉ": "2 recipients with same address",
    "Lệnh hợp lệ": "Valid command",
    "Return: gửi bên kia, subject có hợp đồng; ghi loại, phiên bản template, id nền tảng": (
        "Return: sends to counterparty, subject has contract; records kind, template version, user id"
    ),
    "Gửi lỗi": "Send fails",
    "Return: ném lại lỗi; lần giao sau gửi được": "Return: rethrows error; subsequent delivery succeeds",
    "Buyer / seller yêu cầu": "Buyer / seller requested",
    "Return: nói ai yêu cầu, bên kia làm được gì, từ khi nào im lặng là đủ": (
        "Return: states requester, counterparty options, and timestamp after which silence suffices"
    ),
    "Người nhận là OPERATOR hoặc chính bên yêu cầu": "Recipient is OPERATOR or requester itself",
    "Return: chỉ có thông tin, không có dòng hướng dẫn hành động": (
        "Return: informational only, no action instructions"
    ),
    "Thời điểm có offset; người nhận BUYER / SELLER / OPERATOR": (
        "Timestamp with offset; recipient BUYER / SELLER / OPERATOR"
    ),
    "Return: đổi múi giờ đúng; mọi dòng trong giới hạn ký tự": (
        "Return: converts timezone properly; all lines within length limit"
    ),
    "9 loại event Phase 1": "9 Phase 1 event types",
    "Return: mỗi loại 1 use case; subject, body, người nhận và thứ tự giống bản Java": (
        "Return: 1 use case per type; subject, body, recipients and order match Java"
    ),
    "contract.cancelled / disputed thiếu lý do": "contract.cancelled / disputed missing reason",
})

TRANSLATIONS.update({
    # ── NotifyLegacy..Product (1041..1560) ──
    'Return: in "null" như bản Java': 'Return: prints "null" as in Java',
    (
        "Handler Java của event Phase 1 này đã bị gỡ; use-cases.spec.ts chỉ còn golden fixture của category "
        "approved/rejected, không còn test nào ứng với dòng này"
    ): (
        "Java handler for this Phase 1 event was removed; use-cases.spec.ts keeps only the category "
        "approved/rejected golden fixtures, with no test remaining for this row"
    ),
    "Return: gửi admin, seller rồi buyer": "Return: sends to admin, seller then buyer",
    "Return: không gửi lại người thứ nhất": "Return: does not resend to first recipient",
    "Event gửi lại khi mọi người đã SENT; gửi lại qua lần deploy V2": (
        "Event redelivered when all are SENT; redelivered across V2 deploy"
    ),
    "Return: không gửi / chỉ gửi người chưa SENT": "Return: does not send / sends only to unsent recipients",
    "Tạo mới": "Create new",
    "Return: PENDING, chưa retry, id UUID; khoá theo event, người nhận, loại; lấy phiên bản template của loại": (
        "Return: PENDING, 0 retries, UUID id; keyed by event, recipient, kind; takes template version of kind"
    ),
    "Có / không có id nền tảng của người nhận": "With / without recipient user id",
    "Return: giữ id / null": "Return: keeps id / null",
    "Return: SENT không đổi retry_count / FAILED và đếm mỗi lần lỗi": (
        "Return: SENT leaves retry_count unchanged / FAILED increments on each failure"
    ),
    "reconstitute; giá trị enum": "reconstitute; enum values",
    "Return: giữ nguyên trạng thái lưu; enum giống bản Java": (
        "Return: preserves stored state; enum values match Java"
    ),
    "Mỗi lệnh tiêu thụ": "Each consumed command",
    "Return: đúng 1 loại; loại và phiên bản template không trùng; vừa cột VARCHAR(50)": (
        "Return: exactly 1 kind; unique kind and template version; fits VARCHAR(50)"
    ),
    "Mỗi routing key Phase 1": "Each Phase 1 routing key",
    "Return: đúng 1 loại riêng, không dùng LEGACY_P1 hay p1-unknown; tên loại đã lưu không đổi": (
        "Return: 1 distinct kind each, never LEGACY_P1 or p1-unknown; stored kind names unchanged"
    ),
    "Mẫu RFC 8785 §3.2.4; fixture terms": "RFC 8785 §3.2.4 vectors; terms fixture",
    "Return: đúng chuỗi của RFC; 1 dòng không khoảng trắng": (
        "Return: exact RFC output; single line without whitespace"
    ),
    "Key cần sắp theo UTF-16; object lồng; mảng; null": (
        "Keys requiring UTF-16 order; nested objects; arrays; null"
    ),
    "Return: sắp key đúng, giữ thứ tự mảng và field null": (
        "Return: sorts keys properly, preserves array order and null fields"
    ),
    "Exception: Từ chối": "Exception: Rejected",
    "Không đặt gì": "Unset env",
    "Return: dùng mặc định như bản Java": "Return: uses defaults matching Java",
    "Đặt giá trị; port dạng chuỗi số": "Explicit values; port as numeric string",
    "Return: đọc đúng, đổi port sang số": "Return: parses properly, converts port to number",
    "SERVICE_INTERNAL_SECRET rỗng": "Empty SERVICE_INTERNAL_SECRET",
    "Port không hợp lệ (âm, quá 65535, có chữ, rỗng)": "Invalid port (negative, > 65535, non-numeric, empty)",
    "Nhiều biến sai": "Multiple invalid variables",
    "Return: báo tất cả một lần, không in secret": "Return: reports all at once without printing secrets",
    "AggregateError; lỗi chỉ có code / name; giá trị không phải Error": (
        "AggregateError; code/name-only error; non-Error value"
    ),
    "Return: liệt kê lỗi con; dùng code rồi name; chuyển thành chuỗi": (
        "Return: lists inner errors; prefers code then name; converts to string"
    ),
    "Các mức log; meta; meta vòng lặp": "Log levels; metadata; circular metadata",
    "Return: map đúng mức; nối meta dạng JSON; không ném lỗi": (
        "Return: maps levels properly; appends metadata as JSON; never throws"
    ),
    "Nạp thư viện thật dưới Jest": "Load real library under Jest",
    "Return: ack kết quả PROCESSED; NonRetryableError vào DLQ ngay không retry": (
        "Return: acks PROCESSED result; NonRetryableError goes straight to DLQ without retry"
    ),
    "Cấu hình": "Configuration",
    "Return: SMTP thường không auth/STARTTLS như bản Java; timeout theo cấu hình (OTP < 5 s)": (
        "Return: plain SMTP without auth/STARTTLS like Java; configured timeouts (OTP < 5 s)"
    ),
    "Gửi email": "Send email",
    "Return: text thường từ MAIL_FROM, mặc định noreply@agricontract.com": (
        "Return: plain text from MAIL_FROM, defaulting to noreply@agricontract.com"
    ),
    "Server trả Message-ID; không trả / rỗng / transport không trả gì; id có khoảng trắng": (
        "Server returns Message-ID; missing / empty / void transport return; id with whitespace"
    ),
    "Return: trả id / null / id đã trim": "Return: returns id / null / trimmed id",
    "SMTP từ chối; AggregateError; lỗi không có code": (
        "SMTP rejection; AggregateError; error without code"
    ),
    "Return: EmailDeliveryError chỉ có mã, không có địa chỉ; bỏ qua code không hợp lệ": (
        "Return: EmailDeliveryError with code only, no address; ignores invalid codes"
    ),
    "Mọi thứ chạy": "All dependencies up",
    "RabbitMQ chưa có channel; database ping lỗi": "RabbitMQ has no channel; database ping fails",
    "Body hợp lệ, secret đúng": "Valid body, valid secret",
    "Return: 204, body rỗng sau khi provider nhận": "Return: 204, empty body after provider accepts",
    "Dòng ghi 204; test hiện tại assert 200 với body rỗng": (
        "Row states 204; current test asserts 200 with empty body"
    ),
    "Gọi lại cùng requestId": "Replay with same requestId",
    "Return: 204, không gửi email thứ 2": "Return: 204, no second email sent",
    "Dòng ghi 204; test hiện tại assert 200": "Row states 204; current test asserts 200",
    "Thiếu secret; sai secret; secret kèm giá trị khác; dùng secret của gateway": (
        "Missing secret; wrong secret; secret with extra value; gateway secret used"
    ),
    "Return: 401, không gửi": "Return: 401, does not send",
    "JSON hỏng; field sai; body quá giới hạn": "Malformed JSON; invalid field; oversized body",
    "Return: 400 / 413, chỉ nêu tên field, không trích body": (
        "Return: 400 / 413, names field only, never echoes body"
    ),
    "Dòng ghi 413 cho body quá giới hạn; test hiện tại assert 400": (
        "Row states 413 for oversized body; current test asserts 400"
    ),
    "Return: 502, log chỉ có requestId và mã": "Return: 502, log contains only requestId and code",
    "Đang có lần gọi khác cùng requestId": "Concurrent call with same requestId",
    "Dòng ghi 409; test hiện tại assert 503": "Row states 409; current test asserts 503",
    "Route nội bộ không tồn tại": "Non-existent internal route",
    "Return: 404 dạng error envelope": "Return: 404 as error envelope",
    "Return: controller và use case dùng transport OTP timeout ngắn": (
        "Return: controller and use case use short-timeout OTP transport"
    ),
    "Kết nối": "Connection",
    "Broker chưa sẵn sàng": "Broker not yet ready",
    "Return: khởi động không chờ; retry 1 s, 2 s… tối đa 30 s": (
        "Return: starts without waiting; retries 1 s, 2 s… up to 30 s"
    ),
    "Kết nối / kết nối lại": "Connect / reconnect",
    "Return: đặt prefetch, khai báo topology, chạy callback; callback đăng ký sau cũng chạy ngay": (
        "Return: sets prefetch, declares topology, invokes callbacks; late callbacks run immediately"
    ),
    "Khai báo topology lỗi": "Topology declaration fails",
    "Return: log lỗi và retry": "Return: logs error and retries",
    "Đã destroy": "Destroyed",
    "Return: ngừng retry": "Return: stops retrying",
    "Tắt": "Shutdown",
    "Module destroy rồi application shutdown": "Module destroy then application shutdown",
    "Return: giữ channel cho consumer xả hết; sau đó đóng channel rồi connection": (
        "Return: keeps channel open while consumer drains; then closes channel and connection"
    ),
    "Chu kỳ": "Schedule",
    "Return: chạy mỗi 5 phút như bản Java": "Return: runs every 5 minutes as in Java",
    "Mọi DLQ rỗng / có DLQ có message": "All DLQs empty / a DLQ has messages",
    "Return: im lặng / cảnh báo từng DLQ": "Return: silent / warns per non-empty DLQ",
    "Channel chưa sẵn sàng; 1 lần kiểm lỗi": "Channel not ready; 1 probe fails",
    "Return: bỏ qua không crash; log và dừng vòng này": (
        "Return: skips without crashing; logs and aborts current cycle"
    ),
    "Payload hợp lệ (object hoặc chuỗi), có field thừa": (
        "Valid payload (object or string) with extra fields"
    ),
    "Return: map đúng lệnh, bỏ field thừa, không trim hay đổi kiểu": (
        "Return: maps command, drops extra fields, preserves whitespace and types"
    ),
    "Mỗi field bắt buộc: thiếu, null, rỗng, là số, là object": (
        "Each required field: missing, null, empty, number, object"
    ),
    "Exception: NonRetryableError → DLQ (nêu tên field)": (
        "Exception: NonRetryableError → DLQ (names field)"
    ),
    "Body: JSON hỏng, mảng, null, số, chuỗi": "Body: malformed JSON, array, null, number, string",
    "Field tuỳ chọn": "Optional fields",
    "Thiếu lý do; categoryId": "Missing reason; categoryId",
    (
        "Dòng ghi thiếu lý do vẫn được chấp nhận; test hiện tại dead-letter category.rejected không có "
        "rejectionReason (dead-letters a payload without rejectionReason)"
    ): (
        "Row states missing reason is accepted; current test dead-letters category.rejected payloads without "
        "rejectionReason"
    ),
    "Lý do sai kiểu": "Reason has wrong type",
    "Exception: NonRetryableError → DLQ (như bản Java)": (
        "Exception: NonRetryableError → DLQ (as in Java)"
    ),
    "Quy tắc sai kiểu của PayloadReader dùng chung, kiểm trên 1 field đại diện": (
        "Shared PayloadReader type-mismatch rule tested on a representative field"
    ),
    "penalizedParty BUYER / SELLER / giá trị khác": "penalizedParty BUYER / SELLER / other value",
    "Return: chọn email buyer / seller / seller như bản Java; chỉ bắt buộc email bên được chọn": (
        "Return: picks buyer / seller / seller email like Java; requires email only for selected party"
    ),
    (
        "Parser escrow.penalized (penalizedParty) đã bị gỡ cùng event Phase 1; spec chỉ còn parser category, "
        "không còn test nào ứng với dòng này"
    ): (
        "escrow.penalized (penalizedParty) parser was removed with Phase 1 events; spec retains only category "
        "parsers, with no test remaining for this row"
    ),
    "Message lỗi": "Error messages",
    "Field sai; JSON hỏng có dữ liệu cá nhân; eventId không phải id thường": (
        "Invalid field; malformed JSON containing PII; non-standard eventId"
    ),
    "Return: chỉ nêu kiểu field và eventId, không bao giờ có giá trị payload": (
        "Return: includes only field type and eventId, never payload values"
    ),
    "Return: đọc eventId của envelope và mọi field; dedup theo eventId envelope; userId null giữ null": (
        "Return: reads envelope eventId and all fields; deduplicates by envelope eventId; preserves null userId"
    ),
    "Không phải JSON; không có envelope (dạng Phase 1); field envelope sai; có subject": (
        "Non-JSON; missing envelope (Phase 1 shape); invalid envelope field; unexpected subject"
    ),
    "contractId, terms snapshot, hash (sai / sai độ dài), signedAt sai; field payload lạ": (
        "Invalid contractId, terms snapshot, hash (bad chars / length), signedAt; extra payload field"
    ),
    "Không có người nhận; người nhận sai kiểu / thiếu userId / userId không phải UUID / email sai / role ngoài contract / field lạ": (
        "No recipients; wrong recipient type / missing userId / non-UUID userId / bad email / non-party role / extra field"
    ),
    "Return: message nêu lệnh, field, event, chỉ số người nhận; không trích địa chỉ; bỏ eventId lạ": (
        "Return: message includes command, field, event, recipient index; omits email; drops unusual eventId"
    ),
    "Yêu cầu của buyer / seller": "Buyer / seller request",
    "Return: đọc đủ field": "Return: parses all fields",
    "requestedByRole sai / null / thiếu; oneSidedCancelAllowedFrom, contractId, trigger sai": (
        "Invalid / null / missing requestedByRole; invalid oneSidedCancelAllowedFrom, contractId, trigger"
    ),
    "Exception: NonRetryableError → DLQ (nêu field, không nêu giá trị)": (
        "Exception: NonRetryableError → DLQ (names field, omits value)"
    ),
    "Lệnh neo hợp đồng gửi nhầm vào queue này": "Contract-anchor command misrouted to this queue",
    "Đăng ký": "Registration",
    "Khởi động; kết nối lại": "Startup; reconnect",
    "Return: đăng ký qua onChannelReady; consume mọi queue trên mỗi channel": (
        "Return: registers via onChannelReady; consumes all queues on each channel"
    ),
    "Xử lý message": "Message handling",
    "9 queue Phase 1, queue neo hợp đồng, queue huỷ kích hoạt": (
        "9 Phase 1 queues, contract-anchor queue, activation-cancel queue"
    ),
    "Return: gọi đúng use case với lệnh đã parse rồi ack": (
        "Return: invokes matching use case with parsed command then acks"
    ),
    "Dòng ghi 9 queue Phase 1; nay chỉ còn 2 queue category, 7 queue Phase 1 đã bỏ (topology retired queues)": (
        "Row states 9 Phase 1 queues; now only 2 category queues remain and 7 Phase 1 queues were retired"
    ),
    "Lệnh sai eventType so với queue; payload hỏng": "Command eventType mismatches queue; malformed payload",
    "Lỗi tạm thời": "Transient error",
    "Return: retry 3 lần (1 s, 2 s) rồi nack không requeue; thành công khi retry thì ack": (
        "Return: retries 3 times (1 s, 2 s) then nacks without requeue; acks on retry success"
    ),
    "Return: nack ngay không retry": "Return: nacks immediately without retry",
    "Tắt service": "Service shutdown",
    "Đang có message": "Messages in flight",
    "Return: huỷ consumer rồi chờ message đang chạy; xả tối đa 40 s (giữa timeout SMTP 30 s và stop_grace 60 s)": (
        "Return: cancels consumers then awaits in-flight messages; drains up to 40 s (between 30 s SMTP timeout and 60 s stop_grace)"
    ),
    "Message xong sớm / quá timeout": "Message finishes early / exceeds timeout",
    "Return: trả ngay khi ack xong / dừng chờ, để message chưa ack": (
        "Return: returns once acked / stops waiting leaving message unacked"
    ),
    "Channel mở trong lúc tắt": "Channel opens during shutdown",
    "Return: không gắn consumer": "Return: does not attach consumers",
    "Lần xử lý đang chạy lúc bắt đầu tắt": "Handler running when shutdown begins",
    "Return: chạy xong và ack; lỗi hẳn thì nack vào DLQ": (
        "Return: finishes and acks; permanent failure nacks to DLQ"
    ),
    "Retry, backoff, message mới tới, message chờ claim trong lúc tắt": (
        "Retries, backoffs, new messages, claim-waiting messages during shutdown"
    ),
    "Return: đỗ lại: không chạy, không ack, không nack": (
        "Return: parked: does not execute, ack, or nack"
    ),
    "Thứ tự tắt": "Shutdown order",
    "Có message đang xử lý": "Message in flight",
    "Return: ack message, đóng channel, rồi đóng pool DB": (
        "Return: acks message, closes channel, then closes DB pool"
    ),
    "Quá timeout xả; retry tới hạn lúc đang tắt": "Drain timeout exceeded; retry wakes during shutdown",
    "Return: đóng channel không ack/nack rồi mới đóng pool; retry bị đỗ lại": (
        "Return: closes channel without ack/nack before closing pool; retry is parked"
    ),
    "Không log dữ liệu cá nhân (pii-logging.spec.ts)": "No PII in logs (pii-logging.spec.ts)",
    "SMTP từ chối có trích người nhận; JSON hỏng có dữ liệu cá nhân; lệnh bị từ chối; lỗi lạ có dữ liệu cá nhân": (
        "SMTP rejection echoing recipient; malformed JSON with PII; rejected command; unexpected error with PII"
    ),
    "Return: log retry và DLQ chỉ có eventId và mã lỗi, không có địa chỉ hay payload": (
        "Return: retry and DLQ logs include only eventId and error code, no address or payload"
    ),
    "Khai báo": "Declarations",
    "Tên queue và DLQ": "Queue and DLQ names",
    "Return: đúng số lượng và tên": "Return: exact counts and names",
    "Return: exchange durable không auto-delete; queue chính chỉ có x-dead-letter-exchange; DLQ bind DLX cùng routing key": (
        "Return: durable non-auto-delete exchanges; main queues set only x-dead-letter-exchange; DLQs bind DLX with matching routing key"
    ),
    "Bản ghi cũ trước V2 đã SENT": "Pre-V2 record already SENT",
    "Return: ALREADY_SENT, không ghi gì": "Return: ALREADY_SENT, nothing written",
    "Lần giao đầu": "First delivery",
    "Return: insert PENDING kèm loại, thời gian UTC của DB; recipient_user_id NULL nếu chỉ có địa chỉ": (
        "Return: inserts PENDING with kind, DB UTC timestamp; NULL recipient_user_id if address-only"
    ),
    "Bản ghi đã có": "Existing record",
    "Return: chỉ lấy lại nếu FAILED hoặc PENDING quá lease; ghi đè nội dung lần trước; không lấy được thì ALREADY_SENT / IN_PROGRESS": (
        "Return: reclaims only if FAILED or expired-lease PENDING; overwrites prior content; returns ALREADY_SENT / IN_PROGRESS otherwise"
    ),
    "Lỗi insert khác trùng khoá": "Insert error other than duplicate key",
    "Exception: Ném lại lỗi": "Exception: rethrows error",
    "Return: chỉ đổi updated_at theo giờ DB": "Return: updates only updated_at using DB clock",
    "markSent có / không có id; id quá dài": "markSent with / without id; oversized id",
    "Return: ghi SENT, id, sent_at; không id; bỏ id quá dài thay vì lỗi": (
        "Return: records SENT, id, sent_at; omits id; drops oversized id instead of failing"
    ),
    "Return: FAILED, tăng retry_count trong SQL, lưu mã lỗi": (
        "Return: FAILED, increments retry_count in SQL, stores error code"
    ),
    "Khởi động; DB không tới được; tắt": "Startup; DB unreachable; shutdown",
    "Return: ping SELECT 1; lỗi nêu đích không nêu mật khẩu; đóng pool": (
        "Return: pings SELECT 1; error names target without password; closes pool"
    ),
    "Return: dựng các class theo token và route mọi queue": (
        "Return: instantiates classes by token and routes all queues"
    ),
    "Đọc schema": "Read schema",
    "Return: là lệnh service này tiêu thụ; eventType, producer, field và role khớp code": (
        "Return: consumed by this service; eventType, producer, fields and roles match code"
    ),
    "Message hợp lệ; người nhận không id; 2 người nhận": (
        "Valid message; recipient without id; 2 recipients"
    ),
    "Return: schema và parser cùng chấp nhận": "Return: both schema and parser accept",
    "Field lạ; không người nhận; hash viết hoa; contractId sai; role sai; producer / eventType khác; thiếu correlationId; có subject": (
        "Extra field; no recipients; uppercase hash; invalid contractId; wrong role; wrong producer/eventType; missing correlationId; unexpected subject"
    ),
    "Return: schema và parser cùng từ chối": "Return: both schema and parser reject",
    "Return: khớp eventType, producer, field, role bên yêu cầu": (
        "Return: matches eventType, producer, fields, requester roles"
    ),
    "Message hợp lệ; seller yêu cầu; OPERATOR không id; thời điểm có offset": (
        "Valid message; seller requested; OPERATOR without id; timestamp with offset"
    ),
    "Return: cả 2 bên cùng chấp nhận": "Return: both accept",
    "Field lạ; không người nhận; role sai / chữ thường / thiếu; ngày không giờ; contractId sai; producer khác; nhầm eventType; thiếu correlationId": (
        "Extra field; no recipients; wrong/lowercase/missing role; date without time; invalid contractId; wrong producer; wrong eventType; missing correlationId"
    ),
    "Return: cả 2 bên cùng từ chối": "Return: both reject",
    "Đọc OpenAPI": "Read OpenAPI",
    "Return: đúng operation, path, xác thực service; field khớp, purpose mặc định SIGN_CONTRACT": (
        "Return: exact operation, path, service auth; matching fields, default purpose SIGN_CONTRACT"
    ),
    "20 mẫu body hợp lệ và không hợp lệ": "20 valid and invalid sample bodies",
    "Return: code và schema cho cùng kết quả": "Return: code and schema yield identical results",
    "Dòng ghi 20 mẫu body; bảng mẫu của test nay lớn hơn (thêm VERIFY_EMAIL, CONCEDE/SETTLE_DISPUTE)": (
        "Row states 20 sample bodies; the test fixture table has grown (added VERIFY_EMAIL, CONCEDE/SETTLE_DISPUTE)"
    ),
    "requestId dài hơn cột event_id; contractId / recipientUserId rỗng": (
        "requestId longer than event_id column; empty contractId / recipientUserId"
    ),
    "Return: code chặt hơn schema": "Return: code is stricter than schema",
    "Thư mục src/": "src/ directory",
    "Return: chỉ domain/, application/, infrastructure/, main.ts; use case, port, adapter đúng chỗ": (
        "Return: only domain/, application/, infrastructure/, main.ts; use cases, ports, adapters in place"
    ),
    "Chiều phụ thuộc": "Dependency direction",
    "Từng file trong 22 file nguồn": "Each of the 22 source files",
    "Return: chỉ import tầng được phép": "Return: imports only allowed layers",
    "Return: không dùng framework hay driver; HTTP status chỉ ở tầng web": (
        "Return: no framework or driver imports; HTTP status only in web layer"
    ),
    "Vị trí Nest; guard; main.ts; tắt service": "Nest placement; guard; main.ts; shutdown",
    "Return: Nest chỉ ở infrastructure và main.ts; phân quyền là guard chạy trước khi đọc body; main.ts chỉ bootstrap; đóng MySQL và Redis khi tắt": (
        "Return: Nest only in infrastructure and main.ts; authz guard runs before body parsing; main.ts only bootstraps; closes MySQL and Redis on shutdown"
    ),
    "Path nhập giá tay": "Manual price entry path",
    "Return: có trong contract, thuộc pricing; bắt Idempotency-Key; chỉ OPERATOR (không ADMIN); đúng mã response; chỉ COFFEE": (
        "Return: in contract, owned by pricing; requires Idempotency-Key; OPERATOR only (not ADMIN); matching status codes; COFFEE only"
    ),
    "Response lỗi validate, role, gateway, trùng key": (
        "Validation, role, gateway, key-conflict error responses"
    ),
    "Return: khớp schema ErrorEnvelope chung": "Return: matches shared ErrorEnvelope schema",
    "Không nằm trên đường đồng bộ": "Not on synchronous critical path",
    "Return: không có client, URL hay route giữa chúng; pricing không gọi HTTP ra ngoài": (
        "Return: no clients, URLs or routes between them; pricing makes no outbound HTTP calls"
    ),
    "Phân quyền": "Authorization",
    "OPERATOR; nhiều role có OPERATOR, không phân biệt hoa thường": (
        "OPERATOR; multi-role header containing OPERATOR, case-insensitive"
    ),
    "Return: 403, không lưu": "Return: 403, not saved",
    "Không qua gateway; secret giả; thiếu danh tính": "Bypassed gateway; spoofed secret; missing identity",
    "Return: 401, không lưu": "Return: 401, not saved",
    "Body sai và không có quyền": "Invalid body and unauthorized",
    "Return: kiểm quyền trước khi đọc body": "Return: checks authorization before parsing body",
    "Lưu giá": "Persist price",
    "Return: lưu nguồn OPERATOR_MANUAL, ngày áp dụng, thời điểm nhập, nguồn tham chiếu, lý do; trim chữ": (
        "Return: stores OPERATOR_MANUAL source, effective date, recorded time, reference source, reason; trims strings"
    ),
    "Sửa giá": "Update price",
    "Return: thêm dòng mới, dòng cũ giữ nguyên (append-only)": (
        "Return: appends new row, leaves prior row untouched (append-only)"
    ),
    "Return: chỉ cập nhật sau khi ghi DB; Redis lỗi không làm request lỗi": (
        "Return: updates cache only after DB write; Redis failure does not fail request"
    ),
    "Không phải cà phê; thiếu tỉnh / lý do / nguồn; field lạ; itemName không có chữ": (
        "Non-coffee; missing province / reason / source; extra field; itemName without letters"
    ),
    "Return: 400, không lưu": "Return: 400, not saved",
    "Giá dạng số, không có 2 số lẻ, 0, âm, vượt DECIMAL, số 0 đầu, 1 số lẻ": (
        "Numeric price, missing 2 decimals, 0, negative, exceeds DECIMAL, leading zero, 1 decimal"
    ),
    "Ngày không tồn tại; dạng date-time; ngày tương lai": (
        "Non-existent date; date-time string; future date"
    ),
    "Giá nhỏ viết chuẩn; hôm nay theo giờ Việt Nam": "Small formatted price; today in Vietnam time",
    "JSON hỏng; content type không phải JSON": "Malformed JSON; non-JSON content type",
    "Thiếu key": "Missing key",
    "Gửi lại cùng key cùng body; 2 request đồng thời cùng key": (
        "Resend same key and body; 2 concurrent requests with same key"
    ),
    "Return: trả lại 201 cũ, chỉ 1 dòng": "Return: replays prior 201, only 1 row stored",
    "Cùng key khác body": "Same key, different body",
    "Cùng key, actor khác": "Same key, different actor",
    "Return: tính riêng theo actor": "Return: scoped per actor",
    "GET path nhập giá; path lạ; health; X-Request-Id; lỗi repository": (
        "GET on manual-price path; unknown path; health; X-Request-Id; repository failure"
    ),
    "Return: 405; 404 error envelope; health trả lời; traceId = X-Request-Id; 500 không lộ chi tiết": (
        "Return: 405; 404 error envelope; health responds; traceId = X-Request-Id; 500 hides internal details"
    ),
    "Redis từ chối kết nối; không bắt tay; ngừng trả lời": (
        "Redis refuses connection; stalls handshake; stops responding"
    ),
    "Return: factory trả ngay; cập nhật lỗi ngay hoặc sau commandTimeoutMs": (
        "Return: factory returns immediately; write fails immediately or after commandTimeoutMs"
    ),
    "POST nhập giá khi Redis sập / treo": "POST price when Redis is down / stalled",
    "Return: 201 vẫn lưu dòng, log cảnh báo": "Return: 201 still saves row, logs warning",
    "Khởi động khi Redis sập": "Startup while Redis is down",
    "Return: /health vẫn trả lời": "Return: /health still responds",
    "Giá có số 0 cuối": "Price with trailing zeros",
    "Return: body lần gửi lại giống hệt lần đầu": "Return: replay body matches first response identically",
    "Giá mà MySQL trả về dạng khác": "Price formatted differently by MySQL",
    "Return: bị từ chối ngay từ đầu": "Return: rejected up front",
    "Redis giữ giá A; B lưu nhưng ghi cache lỗi": "Redis holds price A; B persisted but cache write failed",
    "Gửi lại B": "Replay B",
    "Return: Redis có B; không ghi đè giá mới hơn": "Return: Redis updated to B; never overwrites newer price",
    "Hàm thuần": "Pure functions",
    "Return: đúng ví dụ design; chấp nhận body đúng, từ chối body không phải object, giới hạn độ dài, hash bỏ khoảng trắng ngoài; qua ngày theo giờ ICT": (
        "Return: matches design examples; accepts valid body, rejects non-object, enforces lengths, hash ignores outer whitespace; day rollover in ICT"
    ),
    "Ghi giá; sửa giá lùi ngày; sửa giá cùng ngày": "Write price; backdated update; same-day update",
    "Return: key price:coffee:{tỉnh}:{loại} không TTL; giá ngày sau không bị thay; cùng ngày thì thay": (
        "Return: key price:coffee:{province}:{grade} has no TTL; later date not overwritten; same date replaced"
    ),
    "Insert; trùng key; đọc lại; mã nguồn": "Insert; duplicate key; read back; source scan",
    "Return: ghi đủ cột nguồn, UTC; trùng key → DuplicateIdempotencyKeyError; đọc lại đúng; không có UPDATE/DELETE": (
        "Return: stores all source columns, UTC; duplicate key → DuplicateIdempotencyKeyError; reads back exact; no UPDATE/DELETE"
    ),
    "Migration và config": "Migrations and config",
    "Return: chỉ tạo bảng mới, chỉ COFFEE, chạy theo thứ tự; bắt buộc gateway secret, timeout Redis là số nguyên dương": (
        "Return: additive tables only, COFFEE only, runs in order; requires gateway secret, positive integer Redis timeout"
    ),
    "Thiếu key; body sai hoặc key quá dài; cùng key khác body": (
        "Missing key; invalid body or oversized key; same key different body"
    ),
    "Tầng web": "Web layer",
    "3 lỗi trên; lỗi khác": "Above 3 errors; other errors",
    "Return: 400 / 400 / 409 đúng mã; lỗi khác thành 500": (
        "Return: 400 / 400 / 409 with exact codes; other errors map to 500"
    ),
    "Return: status RESERVED; contractId, listingId, quantity đúng": (
        "Return: status RESERVED; matching contractId, listingId, quantity"
    ),
    "Reservation 40 trên LISTING_ID": "Reservation 40 on LISTING_ID",
    "Mọi cặp trạng thái": "All state pairs",
    "Return: chỉ cho RESERVED→SIGNED_RELEASABLE/RELEASED, SIGNED_RELEASABLE→COMMITTED/RELEASED; COMMITTED, RELEASED là terminal": (
        "Return: allows only RESERVED→SIGNED_RELEASABLE/RELEASED, SIGNED_RELEASABLE→COMMITTED/RELEASED; COMMITTED, RELEASED are terminal"
    ),
    'plot-1, seller-1, nhãn "  Ho A ", MANUAL_PIN point, accuracy 8.5, DAK_LAK': (
        'plot-1, seller-1, label "  Ho A ", MANUAL_PIN point, accuracy 8.5, DAK_LAK'
    ),
    'Return: nhãn "Ho A"; PENDING, SELF_DECLARED; importedAt = T0; chưa dùng cho listing được': (
        'Return: label "Ho A"; PENDING, SELF_DECLARED; importedAt = T0; not yet usable for listing'
    ),
    "MANUAL_PIN với polygon": "MANUAL_PIN with polygon",
    "KML_IMPORT polygon kèm accuracy 5": "KML_IMPORT polygon with accuracy 5",
    "Entry đã CHECKED, LOW_RISK": "Entry already CHECKED, LOW_RISK",
    "Cùng geometry, tỉnh LAM_DONG, T1": "Same geometry, province LAM_DONG, T1",
    "Return: giữ CHECKED, LOW_RISK; tỉnh = LAM_DONG; importedAt = T1": (
        "Return: keeps CHECKED, LOW_RISK; province = LAM_DONG; importedAt = T1"
    ),
    "Entry đã CHECKED": "Entry already CHECKED",
    "Geometry khác (polygon)": "Different geometry (polygon)",
    "Return: về PENDING, risk = null, không dùng cho listing được": (
        "Return: resets to PENDING, risk = null, unusable for listing"
    ),
    "Return: cả 2 true": "Return: both true",
    '"Hộ Lê Văn A" dạng NFD; "Hồ" so với "Hổ"': "'Hộ Lê Văn A' in NFD; 'Hồ' vs 'Hổ'",
    "Return: NFD → NFC bằng bản gốc; giữ dấu nên Hồ ≠ Hổ": (
        "Return: NFD → NFC equals original; preserves diacritics so 'Hồ' ≠ 'Hổ'"
    ),
    '255 ký tự; 256 ký tự; 255 "ộ" dạng NFD (765 char)': (
        "255 chars; 256 chars; 255 'ộ' in NFD (765 chars)"
    ),
    "Return: 255 ok; NFD đếm sau NFC = 255": "Return: 255 ok; NFD counted after NFC = 255",
    'Exception: 256 ký tự: IllegalArgumentException "255"': (
        'Exception: 256 chars: IllegalArgumentException "255"'
    ),
    "Entry của seller-1": "seller-1's entry",
    "Chồng lấn rừng tự nhiên": "Overlaps natural forest",
    "Return: CHECKED, HIGH_RISK; vẫn dùng được cho listing": (
        "Return: CHECKED, HIGH_RISK; still usable for listing"
    ),
    "Chỉ chồng vùng không rõ / không chồng": "Overlaps unknown zone only / no overlap",
    "Plot không PENDING": "Plot not PENDING",
    "Kiểm tra": "Verify",
    "Return: mức rủi ro giữ nguyên": "Return: risk level unchanged",
    "Tên hợp lệ": "Valid name",
    "Return: PENDING; name, normalizedName, proposedBy đúng; không phát event": (
        "Return: PENDING; exact name, normalizedName, proposedBy; no event emitted"
    ),
    'Tên " "': 'Name " "',
    "Return: APPROVED, commodity COFFEE; phát CategoryApprovedEvent kèm email người đề xuất": (
        "Return: APPROVED, commodity COFFEE; emits CategoryApprovedEvent with proposer email"
    ),
    "Đã APPROVED": "Already APPROVED",
    "approve lần 2": "second approve",
    "Return: vẫn PENDING": "Return: stays PENDING",
    "Return: REJECTED, lưu lý do; phát CategoryRejectedEvent kèm lý do": (
        "Return: REJECTED, stores reason; emits CategoryRejectedEvent with reason"
    ),
    'lý do " "': 'reason " "',
    "Đã REJECTED": "Already REJECTED",
    "reject lần 2": "second reject",
    "Đã approve và pull 1 lần": "Approved and pulled once",
    "pull lần 2": "second pull",
    "Category APPROVED cũ chưa có commodity": "Legacy APPROVED category without commodity",
    "Return: commodity COFFEE, không phát event": "Return: commodity COFFEE, no event emitted",
    "Đã có commodity": "Already has commodity",
    "Category chưa APPROVED": "Category not yet APPROVED",
    "Category đã có commodity": "Category already has commodity",
    "Không có Idempotency-Key": "Missing Idempotency-Key",
    "Không tham số": "No parameters",
    "Return: null được chấp nhận": "Return: null accepted",
    "Exception: 100.001 và 0: IllegalArgumentException": "Exception: 100.001 and 0: IllegalArgumentException",
    "Dòng cũ quantityAvailable null": "Legacy row with null quantityAvailable",
    "Return: quantityAvailable vẫn null": "Return: quantityAvailable stays null",
    "Giữ 30 khi: còn null; còn 20; còn 60 nhưng min 40; còn 0 và CLOSED": (
        "Reserve 30 when: null left; 20 left; 60 left with min 40; 0 left and CLOSED"
    ),
    "Còn 60, CLOSED hoặc EXPIRED": "60 left, CLOSED or EXPIRED",
    "Còn 60, ACTIVE, token cũ": "60 left, ACTIVE, stale token",
    "Listing ACTIVE 100 kg, đã giữ 30": "ACTIVE listing 100 kg, 30 reserved",
    "Nâng offer lên 150": "Raise offer to 150",
    "Return: quantityAvailable = 120 (phần đã giữ vẫn ngoài)": (
        "Return: quantityAvailable = 120 (reserved portion excluded)"
    ),
    "Hạ offer xuống đúng 30 (tổng đã giữ)": "Lower offer to exact 30 (total reserved)",
    "Return: quantityAvailable = 0; CLOSED lý do SELLER_CLOSE": (
        "Return: quantityAvailable = 0; CLOSED with reason SELLER_CLOSE"
    ),
    "Hạ offer xuống 29.999 (dưới tổng đã giữ)": "Lower offer to 29.999 (below total reserved)",
    "Return: Không đổi gì": "Return: no change",
    "Listing CLOSED, còn 0": "CLOSED listing, 0 left",
    "Nâng offer để có hàng lại": "Raise offer to restore stock",
    "Return: Mở lại ACTIVE (republish)": "Return: reopens ACTIVE (republish)",
    "Listing cũ quantityAvailable null, chưa giữ hàng": "Legacy listing with null quantityAvailable, no reservations",
    "Sửa offer": "Update offer",
    "Return: quantityAvailable khởi tạo từ offer mới": (
        "Return: quantityAvailable initialized from new offer"
    ),
    "Listing cũ thiếu quality spec": "Legacy listing missing quality spec",
    "minOrderQuantity lớn hơn offer mới": "minOrderQuantity exceeds new offer",
    "2 ảnh https": "2 https images",
    "Return: đủ field; cover = ảnh đầu": "Return: all fields set; cover = first image",
    "List ảnh rỗng": "Empty image list",
    "6 ảnh": "6 images",
    "2 ảnh trùng URL": "2 images with duplicate URL",
    "Product có 1 ảnh": "Product has 1 image",
    "2 ảnh mới x, y": "2 new images x, y",
    "Return: thay toàn bộ; cover = x": "Return: replaces all; cover = x",
    "List rỗng": "Empty list",
    "Return: giữ nguyên ảnh cũ": "Return: keeps existing images",
    "Return: WKT làm tròn 6 số; area 1.5000; hash 64 hex, trùng với toạ độ đã làm tròn": (
        "Return: WKT rounded to 6 decimals; area 1.5000; 64-hex hash matches rounded coordinates"
    ),
    "Return: 4 ha vẫn là POINT": "Return: 4 ha remains POINT",
    "Lat/lng đảo (12.66, 108.05); NaN": "Swapped lat/lng (12.66, 108.05); NaN",
    "Hình vuông cạnh 0.001° và 0.003°": "Squares of side 0.001° and 0.003°",
    "Return: ~1.18–1.24 ha và ~10.7–11.1 ha (tính trên mặt cầu)": (
        "Return: ~1.18–1.24 ha and ~10.7–11.1 ha (spherical calculation)"
    ),
    "Cạnh 0.08° (~7.800 ha); 0.1° (~12.000 ha); 1.0°": "Side 0.08° (~7,800 ha); 0.1° (~12,000 ha); 1.0°",
    "Return: 7.800 ha được nhận": "Return: 7,800 ha accepted",
    "Vuông 0.003° có lỗ 0.001°": "0.003° square with 0.001° hole",
    "Return: diện tích giảm ~1.18–1.24 ha": "Return: area reduced by ~1.18–1.24 ha",
    "Ring hình nơ tự cắt": "Self-intersecting bowtie ring",
    "Ring không đóng; ring 3 điểm": "Unclosed ring; 3-point ring",
    "Cùng hình, đổi điểm bắt đầu / đảo chiều; hình lệch 0.0001°": (
        "Same shape with rotated start / reversed winding; shape shifted 0.0001°"
    ),
    "Return: hash giống nhau; hình lệch có hash khác": (
        "Return: identical hash; shifted shape produces different hash"
    ),
    "Return: status APPROVED; có save": "Return: status APPROVED; saved",
    "Không có category": "Category not found",
    "category-1 đã APPROVED": "category-1 already APPROVED",
    "Lookup không thấy, nhưng save vi phạm unique (race)": (
        "Lookup missed, but save hits unique constraint (race)"
    ),
    'Chưa có tên "ca phe"': 'Name "ca phe" does not exist yet',
    '"Cà Phê"': "'Cà Phê' (Coffee)",
    "Return: lưu kèm email người đề xuất lấy từ user-service; trả PENDING": (
        "Return: saves with proposer email from user-service; returns PENDING"
    ),
    "Đã có tên trùng sau chuẩn hoá": "Normalized-duplicate name already exists",
    "Return: không gọi user-service, không lưu": "Return: does not call user-service, does not save",
    "Return: không lưu": "Return: not saved",
    "Return: status REJECTED, lưu lý do; có save": "Return: status REJECTED, saves reason; saved",
    "KML: Ho A, Ho B (đã có), không diện tích, 4.5 ha, toạ độ ngoài VN, Ho A lặp, tỉnh DAK_LAK": (
        "KML: Ho A, Ho B (existing), no area, 4.5 ha, out-of-VN coordinates, duplicate Ho A, province DAK_LAK"
    ),
    "Return: created [Ho A], updated [Ho B]; các placemark lỗi bị liệt kê theo tên": (
        "Return: created [Ho A], updated [Ho B]; failed placemarks listed by name"
    ),
    "Nhãn dài 300 ký tự, polygon cả độ vuông, cùng Ho A, Ho B": (
        "300-char label, 1-sq-degree polygon, alongside Ho A, Ho B"
    ),
    "Return: Ho A, Ho B vẫn vào; rejected [nhãn dài, Huge]": (
        "Return: Ho A, Ho B still imported; rejected [long label, Huge]"
    ),
    "1 polygon Ho D, tỉnh GIA_LAI": "1 Ho D polygon, province GIA_LAI",
    "Return: đăng ký POLYGON, diện tích > 10 ha, KML_IMPORT, GIA_LAI": (
        "Return: registers POLYGON, area > 10 ha, KML_IMPORT, GIA_LAI"
    ),
    "KML không placemark; tỉnh = null": "KML without placemarks; province = null",
    "Return: không gọi registrar": "Return: does not call registrar",
    "Return: không lưu gì": "Return: nothing saved",
    'Chưa có hộ "Ho A"': 'Household "Ho A" does not exist',
    'Return: created = true; nhãn "Ho A", PENDING, importedAt = NOW; không update': (
        'Return: created = true; label "Ho A", PENDING, importedAt = NOW; no update'
    ),
    'Đã có "Hộ Lê Văn A" (NFC)': "'Hộ Lê Văn A' (NFC) already exists",
    "Cùng nhãn dạng NFD, tỉnh LAM_DONG": "Same label in NFD, province LAM_DONG",
    "Return: created = false, cập nhật plot-1; giữ kết quả geo-check vì cùng geometry": (
        "Return: created = false, updates plot-1; retains geo-check result for identical geometry"
    ),
    "Lookup trống, create đụng unique (race)": "Lookup empty, create hits unique constraint (race)",
    '"Hộ Lê Văn A"': "'Hộ Lê Văn A'",
    "Return: đọc lại bản thắng và update plot-1": "Return: reloads winner record and updates plot-1",
    'create lỗi "Data too long"': 'create fails with "Data too long"',
    "Return: không coi là race, không update": "Return: not treated as race, no update",
    "Exception: ném lại đúng DataIntegrityViolationException": (
        "Exception: rethrows original DataIntegrityViolationException"
    ),
    "Nhãn 256 ký tự": "256-character label",
    "Return: không chạm DB": "Return: does not touch DB",
    "plot-1, plot-2 của seller, UNVERIFIED": "seller's plot-1, plot-2, UNVERIFIED",
    "Return: snapshot ảnh bìa, plot; quantityAvailable 100; min 10": (
        "Return: snapshots cover image, plots; quantityAvailable 100; min 10"
    ),
    "plot-1 hợp lệ": "Valid plot-1",
    "Request kèm goods khai báo": "Request with declared goods",
    "Return: category, commodity, mùa vụ lấy từ product; quality spec, chứng nhận, vùng lấy từ request": (
        "Return: category, commodity, harvest season from product; quality spec, certifications, region from request"
    ),
    "Category duyệt từ trước khi có commodity": "Category approved before commodity existed",
    "Return: không save, không đọc plot": "Return: does not save, does not query plots",
    "Độ ẩm 12.555": "Moisture 12.555",
    "plot-1 thuộc seller-2": "plot-1 belongs to seller-2",
    "Không có plot-404": "plot-404 does not exist",
    "Return: trả lỗi giống plot của người khác; không save": (
        "Return: returns same error as another seller's plot; does not save"
    ),
    "plot-2 còn PENDING geo-check": "plot-2 still PENDING geo-check",
    "plot-1 hai lần": "plot-1 twice",
    "listing-1 đã tạo trước": "listing-1 already created",
    "Replay cùng id: spec 12.50 (= 12.5); đổi sang plot-2; đổi sang ARABICA": (
        "Replay same id: spec 12.50 (= 12.5); switch to plot-2; switch to ARABICA"
    ),
    "Return: spec cùng giá trị trả listing-1; không save lần nào": (
        "Return: equal spec returns listing-1; no save executed"
    ),
    "Exception: đổi plot / chất lượng: IllegalStateException": (
        "Exception: changed plot / quality: IllegalStateException"
    ),
    "execute() – điều kiện seller": "execute() – seller eligibility",
    "Return: không đọc gì thêm": "Return: reads nothing further",
    "Seller đang bị khoá uy tín": "Seller currently reputation-locked",
    "Tạo listing": "Create listing",
    "Exception: Từ chối (403)": "Exception: Rejected (403)",
    "Khoá hết hạn đúng thời điểm hiện tại": "Lock expires at exact current instant",
    "Return: tạo được listing": "Return: listing created",
    "Seller chưa KYC": "Seller not KYC verified",
    "Thẩm quyền đại diện hết hạn": "Representative authorization expired",
    "Không có hồ sơ user": "No user profile",
    "Product của seller": "Seller's product",
    "productName khác tên product": "productName differs from product's name",
    "Return: Không save": "Return: not saved",
    "Return: Giữ đúng giá, không làm tròn": "Return: preserves exact price without rounding",
    "Key mới": "New key",
    "Return: Tạo listing mới": "Return: creates new listing",
    "Đã tạo với key K": "Already created with key K",
    "Cùng key K, cùng body": "Same key K, same body",
    "Return: Trả listing lần đầu, không insert lần 2": (
        "Return: returns initial listing, no second insert"
    ),
    "Cùng key K, body khác": "Same key K, different body",
    "Request khác insert trước với cùng key": "Concurrent request inserts same key first",
    "Insert trùng key": "Duplicate key insert",
    "Return: Trả listing của request thắng": "Return: returns winning request's listing",
    "Key rỗng; key quá dài": "Empty key; oversized key",
    "Return: Không đọc gì": "Return: reads nothing",
    "Return: trả categoryId, ảnh đúng": "Return: returns categoryId and exact images",
    'cropYear 2026, harvestSeason "  ", 1 chứng nhận': 'cropYear 2026, harvestSeason "  ", 1 certification',
    "Return: cropYear 2026; harvestSeason = null; giữ chứng nhận": (
        "Return: cropYear 2026; harvestSeason = null; retains certification"
    ),
    "product-1 đã tồn tại cùng thuộc tính": "product-1 already exists with same attributes",
    "Cùng request": "Same request",
    "Return: trả product cũ; không save, không đọc category": (
        "Return: returns existing product; does not save, does not query category"
    ),
    "product-1 đã tồn tại": "product-1 already exists",
    'Cùng id, tên "Gao khac"': 'Same id, name "Gao khac"',
    "Category cũ vừa được Operator gán commodity": "Legacy category just assigned commodity by Operator",
    "Tạo product": "Create product",
    "Return: tạo được product": "Return: product created",
    "product-1 có 1 ảnh": "product-1 has 1 image",
    "product-1, ảnh x, y": "product-1, images x, y",
    "Return: ảnh = [x, y]; có save": "Return: images = [x, y]; saved",
    "Không có product": "Product not found",
    "product-1 tồn tại": "product-1 exists",
    'Point "Hộ Lê Văn A", area_ha 1.5 trong ExtendedData': (
        "Point 'Hộ Lê Văn A', area_ha 1.5 in ExtendedData"
    ),
    "Return: 1 placemark POINT, toạ độ (108.050123, 12.666789), 1.5 ha": (
        "Return: 1 POINT placemark, coordinates (108.050123, 12.666789), 1.5 ha"
    ),
    "Return: area 2.25 (đọc được dấu phẩy thập phân)": (
        "Return: area 2.25 (parses decimal comma)"
    ),
    "Point không có diện tích": "Point without area",
    "Return: đọc được, area = null": "Return: parsed, area = null",
    "Polygon có 1 lỗ": "Polygon with 1 hole",
    "Return: POLYGON, shell 5 điểm, 1 hole 4 điểm": "Return: POLYGON, 5-point shell, 4-point hole",
    'Placemark không tên, MultiGeometry, LineString, toạ độ "abc", không geometry, 1 placemark tốt': (
        'Nameless placemark, MultiGeometry, LineString, "abc" coordinates, no geometry, 1 valid placemark'
    ),
    'Return: 5 cái đầu REJECTED kèm lý do ("#1", "MultiGeometry", "not a number"); cái cuối POINT': (
        'Return: first 5 REJECTED with reasons ("#1", "MultiGeometry", "not a number"); last one POINT'
    ),
    "KML có DOCTYPE + external entity": "KML with DOCTYPE + external entity",
    "DLQ có message": "DLQ has messages",
    "Return: log cảnh báo": "Return: logs warning",
    "DLQ rỗng": "DLQ is empty",
    "Return: không log": "Return: no log",
    "Return: log chỉ nêu queue, không ném lỗi": "Return: logs queue only, does not throw",
    "Return: connection probe có giới hạn thời gian và tách riêng": (
        "Return: connection probe has timeouts and is isolated"
    ),
    "Thời gian ngoài giới hạn": "Out-of-range timeout",
    "Gán COFFEE": "Assign COFFEE",
    "Return: lưu category đã gán": "Return: saves assigned category",
    "Category không tồn tại": "Category does not exist",
    "Gán lại": "Reassign",
    "Plot chồng rừng tự nhiên": "Plot overlaps natural forest",
    "Return: ghi rủi ro HIGH kèm bản ghi audit": "Return: records HIGH risk with audit entry",
    "Plot ngoài vùng có dữ liệu nền": "Plot outside baseline data coverage",
    "Return: không ghi gì, chờ hết hạn": "Return: writes nothing, awaits timeout",
    "Các plot được chọn": "Selected plots",
    "Return: đánh dấu đã thử trước khi kiểm": "Return: marks attempted before checking",
    "Plot đổi trong lúc kiểm": "Plot modified during check",
    "Kết quả bị bỏ": "Result discarded",
    "Return: không tính": "Return: ignored",
    "1 plot lỗi": "1 plot fails",
    "Return: lô vẫn chạy tiếp": "Return: batch continues",
    "Event hợp lệ; khối lượng thập phân": "Valid event; decimal quantity",
    "Return: gọi use case với đúng khối lượng": "Return: calls use case with exact quantity",
    "Fixture hợp lệ": "Valid fixture",
    "Return: khớp schema đã chốt": "Return: matches frozen schema",
    "Sai eventType / producer / version; field lạ ở envelope hoặc payload": (
        "Wrong eventType / producer / version; extra field in envelope or payload"
    ),
    "Khối lượng ≤ 0 hoặc không phải số": "Quantity ≤ 0 or non-numeric",
    "contractId không phải UUID; JSON hỏng": "Non-UUID contractId; malformed JSON",
    "Token đúng phiên bản": "Matching version token",
    "Return: trả bản sao listing khớp 2 schema đã chốt": (
        "Return: returns listing snapshot matching both frozen schemas"
    ),
    "Dữ liệu mùa vụ null": "Null harvest season",
    "Return: gửi null tường minh": "Return: serializes explicit null",
    "Token cũ (kể cả listing cũ)": "Stale token (including legacy listings)",
    "Return: 409 VERSION_MISMATCH, kiểm trước mọi thứ": (
        "Return: 409 VERSION_MISMATCH, checked before anything else"
    ),
    "Listing cũ thiếu plot/hàng; cà phê không có plot; thiếu category": (
        "Legacy listing missing plot/item; coffee without plot; missing category"
    ),
    "Listing không tồn tại": "Listing does not exist",
    "Token rỗng": "Empty token",
    "Return: không đọc gì": "Return: reads nothing",
    "Khách chưa đăng nhập": "Anonymous guest",
    "Return: listing không có uy tín, không gọi reputation": (
        "Return: listings omit reputation, reputation-service not called"
    ),
    "Khách chưa đăng nhập lọc theo uy tín": "Anonymous guest filters by reputation",
    "Đã đăng nhập": "Signed in",
    "Return: mỗi seller tra 1 lần, hiện uy tín": (
        "Return: looks up each seller once, includes reputation"
    ),
    "Lọc điểm tối thiểu": "Filter by minimum score",
    "Return: chỉ bỏ seller có điểm thấp hơn; chưa có điểm vẫn hiện": (
        "Return: drops only sellers below threshold; unrated sellers stay visible"
    ),
    "Reputation sập toàn bộ / một phần": "Reputation fully / partially down",
    "Return: giữ listing, đánh dấu không có dữ liệu; chỉ lọc seller biết điểm": (
        "Return: keeps listings marked unavailable; filters only sellers with known scores"
    ),
    "Trang rỗng": "Empty page",
    "Return: không gọi reputation": "Return: does not call reputation",
    "Return: 1 lần gọi; chỉ giữ điểm và trạng thái khoá": (
        "Return: single batch call; keeps score and lock status only"
    ),
    "Điểm dạng số nguyên; id viết hoa": "Integer score; uppercase id",
    "Thiếu 1 seller trong kết quả": "1 seller missing in response",
    "Return: chỉ seller đó là chưa biết": "Return: only that seller marked unknown",
    "Response hỏng; content type không phải JSON": "Malformed response; non-JSON content type",
    "Return: mọi seller chưa biết, không tin dữ liệu": (
        "Return: all sellers marked unknown, data untrusted"
    ),
    "Mã lỗi HTTP; service sập; quá timeout": "HTTP error status; service down; timeout",
    "Return: tra cứu không đủ, không ném lỗi": "Return: incomplete lookup without throwing",
    "Không có seller; quá giới hạn lô": "No sellers; exceeds batch limit",
    "Return: không gọi": "Return: does not call",
    "Khởi tạo": "Initialization",
    "Secret rỗng": "Empty secret",
    "Message hỏng": "Malformed message",
    "Return: chỉ thử 1 lần": "Return: tried once only",
    "Lỗi xử lý": "Processing error",
    "Return: thử 3 lần rồi từ chối": "Return: retries 3 times then rejects",
    "Giữ hàng tạo trước thời gian ân hạn": "Reservation created before grace period",
    "Return: bị dọn": "Return: reaped",
    "Thời gian ân hạn = 0": "Grace period = 0",
    "Exception: IllegalArgumentException lúc khởi động": "Exception: IllegalArgumentException at startup",
    "contract.signed; khối lượng thập phân": "contract.signed; decimal quantity",
    "Return: Gọi use case với đúng khối lượng": "Return: calls use case with exact quantity",
    "Return: Chuyển đúng sang use case tương ứng": "Return: dispatches to matching use case",
    "Fixture của các event": "Event fixtures",
    "Return: Khớp schema đã chốt": "Return: matches frozen schema",
    "Event loại khác trên queue; sai producer / version; field lạ trong payload": (
        "Unexpected event type on queue; wrong producer / version; extra payload field"
    ),
    "Return: Vào DLQ, không gọi use case": "Return: sent to DLQ, use case not called",
    "Khối lượng 0, âm, không phải số": "Zero, negative, or non-numeric quantity",
    "Return: Vào DLQ": "Return: sent to DLQ",
    "contractId không phải UUID; timestamp sai định dạng": "Non-UUID contractId; malformed timestamp",
    "Sản phẩm tồn tại": "Product exists",
    "Return: Trả các field đọc được": "Return: returns readable fields",
    "productId không tồn tại": "productId does not exist",
    "Profile tồn tại": "Profile exists",
    "Return: Gọi endpoint nội bộ kèm X-Internal-Secret; đọc đủ điều kiện": (
        "Return: calls internal endpoint with X-Internal-Secret; reads full eligibility"
    ),
    "User bị khoá": "User is locked",
    "Return: Trả kèm lockedUntil": "Return: includes lockedUntil",
    "Không có profile (404)": "Profile not found (404)",
    "Return: Optional rỗng, không coi là service sập": (
        "Return: empty Optional, not treated as service outage"
    ),
})

TRANSLATIONS.update({
    # ── ProductInternalUserClient..UserEndpoint (1561..2057) ──
    "Mã lỗi khác; service sập; quá read timeout; content type không phải JSON": (
        "Other error status; service down; read timeout; non-JSON content type"
    ),
    (
        "Body không phải JSON hoặc là mảng; thiếu verificationStatus / authorizationExpiresAt / lockedUntil; "
        "lockedUntil sai định dạng; userId khác người được hỏi"
    ): (
        "Non-JSON or array body; missing verificationStatus / authorizationExpiresAt / lockedUntil; malformed "
        "lockedUntil; mismatched userId"
    ),
    "Return: Fail closed, không coi thiếu field là không khoá": (
        "Return: fail-closed, never treats missing field as unlocked"
    ),
    "Profile có email đã xác minh": "Profile with verified email",
    "Return: Trả email; gửi kèm X-Internal-Secret": "Return: returns email; sends X-Internal-Secret",
    "Quá read timeout; service sập; mã lỗi khác": "Read timeout; service down; other error status",
    "Body không phải JSON hoặc là mảng; email null hoặc rỗng; userId khác người được hỏi": (
        "Non-JSON or array body; null or empty email; mismatched userId"
    ),
    "Seller có lô đất": "Seller has plots",
    "Return: Chỉ trả lô của người gọi; đếm số trang": "Return: returns only caller's plots; counts pages",
    "Seller chưa có lô": "Seller has no plots",
    "Return: Trang rỗng, 0 trang": "Return: empty page, 0 pages",
    "Request khác cùng contractId vừa giữ hàng": "Concurrent request with same contractId just reserved",
    "Giữ hàng trùng contractId": "Duplicate reservation for contractId",
    "Return: Trả reservation đã có (replay), không tạo mới": (
        "Return: returns existing reservation (replay), creates no duplicate"
    ),
    "Xung đột ghi, không có reservation thắng": "Write conflict with no winning reservation",
    "Giữ hàng": "Reserve stock",
    "Exception: Lỗi xung đột được ném tiếp": "Exception: conflict error rethrown",
    "Không có body; thiếu commodity; commodity không biết": "Missing body; missing commodity; unknown commodity",
    "ADMIN hoặc SELLER": "ADMIN or SELLER",
    "Danh mục đã có commodity": "Category already has commodity",
    "Thiếu commodity; commodity không biết": "Missing commodity; unknown commodity",
    "Người gọi đã đăng nhập": "Authenticated caller",
    "Return: Mảng CategorySummary theo schema; danh mục cũ có commodity null": (
        "Return: CategorySummary array per schema; legacy categories have null commodity"
    ),
    "Không có người gọi đăng nhập": "Unauthenticated caller",
    "Return: Bị từ chối": "Return: rejected",
    "Đường dẫn Phase 1": "Phase 1 paths",
    "Return: 404 (đã bỏ)": "Return: 404 (retired)",
    "Request và response mẫu": "Sample request and response",
    "Return: Khớp schema đã chốt; body đọc đúng từng field": (
        "Return: matches frozen schema; parses every body field properly"
    ),
    "Field lạ trong body": "Extra field in body",
    "Return: 400, use case không chạy": "Return: 400, use case not invoked",
    "Vi phạm quy tắc field của contract (đơn vị, giá, khối lượng, ...)": (
        "Violates contract field rules (unit, price, quantity, ...)"
    ),
    "Seller không đủ điều kiện": "Ineligible seller",
    "Return: 400 ErrorEnvelope dùng chung": "Return: 400 shared ErrorEnvelope",
    "Lần đầu; gửi lại cùng key và body": "First call; replay same key and body",
    "Return: 201 rồi 200, cả hai trả listing không bọc": (
        "Return: 201 then 200, both return unwrapped listing"
    ),
    "Đã dùng key K": "Key K already used",
    "Không có X-Gateway-Secret": "Missing X-Gateway-Secret",
    "Return: 403 ErrorEnvelope dùng chung": "Return: 403 shared ErrorEnvelope",
    "Seller sở hữu listing": "Seller owns listing",
    "BUYER; seller khác": "BUYER; another seller",
    "Sửa listing": "Update listing",
    "Return: 403 (NOT_LISTING_SELLER với seller khác)": "Return: 403 (NOT_LISTING_SELLER for another seller)",
    "Đã giữ 30 kg": "30 kg already reserved",
    "Offer dưới khối lượng đã giữ": "Offer below reserved quantity",
    "Gọi endpoint cũ": "Call legacy endpoint",
    "Request lỗi 400 bị dispatch sang /error": "400 request dispatched to /error",
    "Return: Trả đúng 400, không phải 403 rỗng": "Return: returns exact 400, not empty 403",
    "Route nghiệp vụ không có secret": "Business route without secret",
    "Return: Vẫn 403 (quy tắc /error không mở route khác)": (
        "Return: stays 403 (/error rule does not open other routes)"
    ),
    "GET /error trực tiếp": "Direct GET /error",
    "Return: Vẫn bị chặn": "Return: still blocked",
    "Chỉ có danh tính gateway": "Gateway identity only",
    "Không có X-Internal-Secret": "Missing X-Internal-Secret",
    "X-Internal-Secret đúng": "Valid X-Internal-Secret",
    "Return: 200, snapshot không bọc": "Return: 200, unwrapped snapshot",
    "Token cũ": "Stale token",
    "Thiếu token": "Missing token",
    "Chỉ có X-Gateway-Secret": "X-Gateway-Secret only",
    "Giữ hàng mới": "New reservation",
    "Return: 201, body không bọc": "Return: 201, unwrapped body",
    "Đã giữ": "Already reserved",
    "Gửi lại cùng contractId": "Replay same contractId",
    "Không đủ hàng / sai trạng thái": "Insufficient stock / invalid state",
    "Return: 409 kèm mã đã chốt": "Return: 409 with frozen error code",
    "quantity 0 hoặc âm": "Zero or negative quantity",
    "reason hợp lệ": "Valid reason",
    "reason không nằm trong danh sách": "Reason not in enum list",
    "Return: Tìm không kèm viewer": "Return: searches without viewer",
    "Return: Viewer lấy từ principal; reputation nằm cạnh field listing": (
        "Return: viewer resolved from principal; reputation sits alongside listing fields"
    ),
    "reputation-service sập": "reputation-service down",
    "Return: Vẫn 200": "Return: stays 200",
    "Hộ mới, Idempotency-Key": "New household, Idempotency-Key",
    "Return: 201; chủ lô lấy từ principal": "Return: 201; plot owner taken from principal",
    "Hộ đã ghim": "Household already pinned",
    "Cùng hộ": "Same household",
    "Client gửi geometryType / source": "Client sends geometryType / source",
    "Return: Bị bỏ qua: luôn POINT, MANUAL_PIN": "Return: ignored; always POINT, MANUAL_PIN",
    "Tỉnh không biết; thiếu diện tích; geometry bị domain từ chối": (
        "Unknown province; missing area; geometry rejected by domain"
    ),
    "BUYER; X-Internal-Secret kèm sellerId giả; không có X-Gateway-Secret": (
        "BUYER; X-Internal-Secret with spoofed sellerId; missing X-Gateway-Secret"
    ),
    "Ghim lô": "Pin plot",
    "KML có hộ mới và placemark lỗi": "KML with new household and invalid placemark",
    "Return: 201, liệt kê placemark bị từ chối": "Return: 201, lists rejected placemarks",
    "KML chỉ cập nhật; KML không lưu được gì": "Update-only KML; KML with nothing saveable",
    "Không có tỉnh; tỉnh sai": "Missing province; invalid province",
    "File lớn hơn 5 MB": "File larger than 5 MB",
    "Return: 400, không gọi use case": "Return: 400, use case not called",
    "Content type khác multipart / KML": "Non-multipart / non-KML content type",
    "Seller có lô": "Seller has plots",
    "Return: PlotRegistryPage theo schema, không có geometry; chủ lô từ principal": (
        "Return: PlotRegistryPage per schema without geometry; owner from principal"
    ),
    "page, size trong giới hạn (size 1; 100)": "page, size within bounds (size 1; 100)",
    "Return: Truyền nguyên cho use case": "Return: passed through to use case",
    "size 0; 101; page âm": "size 0; 101; negative page",
    "Xem lô": "List plots",
    "productId tồn tại": "productId exists",
    "Sản phẩm thiếu field tuỳ chọn": "Product missing optional fields",
    "Return: Field tuỳ chọn gửi null tường minh": "Return: optional fields serialized as explicit null",
    "productId không tồn tại; không phải UUID": "productId not found; non-UUID",
    "Kiểu dữ liệu use case": "Use case data types",
    "Return: tham số và kiểu trả về của execute() đều là record trong application.dto": (
        "Return: execute() parameters and return types are records in application.dto"
    ),
    "Repository trả INSERTED": "Repository returns INSERTED",
    "Return: fact đủ field; settledAt cắt về micro giây; kết quả INSERTED": (
        "Return: fact has all fields; settledAt truncated to microseconds; result INSERTED"
    ),
    "Repository trả DUPLICATE": "Repository returns DUPLICATE",
    "Return: fact giữ người flag và resolution; kết quả DUPLICATE": (
        "Return: fact preserves flaggedBy and resolution; result DUPLICATE"
    ),
    "Return: không chạm repository": "Return: does not touch repository",
    "Repository ném ImmutableFactConflictException": "Repository throws ImmutableFactConflictException",
    "Return: không biến thành thành công": "Return: does not turn into success",
    "buyer có 4 hợp đồng hoàn tất, 1 khoá đang active": (
        "buyer has 4 completed contracts, 1 active lock"
    ),
    "Return: score 80, locked = true; lockHistory là bản sao bất biến, chỉ lộ field cho phép": (
        "Return: score 80, locked = true; lockHistory is an immutable copy exposing only allowed fields"
    ),
    "Return: null (chưa có dữ liệu)": "Return: null (no data yet)",
    "Return: 67 (làm tròn, scale 0)": "Return: 67 (rounded, scale 0)",
    "Return: 33 (làm tròn, scale 0)": "Return: 33 (rounded, scale 0)",
    "Return: 50, không tràn số": "Return: 50, no overflow",
    "Thời điểm có nano giây": "Timestamp with nanoseconds",
    "Return: là record; thời gian cắt về micro giây": "Return: is a record; timestamp truncated to microseconds",
    "CompletedContractFact thiếu contractId": "CompletedContractFact missing contractId",
    "Exception: Exception (field bắt buộc)": "Exception: Exception (required field)",
    "CompletedContractFact thiếu buyerId": "CompletedContractFact missing buyerId",
    "CompletedContractFact thiếu sellerId": "CompletedContractFact missing sellerId",
    "CompletedContractFact thiếu settledAt": "CompletedContractFact missing settledAt",
    "CompletedContractFact thiếu eventId": "CompletedContractFact missing eventId",
    "DisputeOutcomeFact thiếu milestoneId": "DisputeOutcomeFact missing milestoneId",
    "DisputeOutcomeFact thiếu contractId": "DisputeOutcomeFact missing contractId",
    "DisputeOutcomeFact thiếu flaggedBy": "DisputeOutcomeFact missing flaggedBy",
    "DisputeOutcomeFact thiếu resolutionFavors": "DisputeOutcomeFact missing resolutionFavors",
    "DisputeOutcomeFact thiếu resolvedAt": "DisputeOutcomeFact missing resolvedAt",
    "DisputeOutcomeFact thiếu eventId": "DisputeOutcomeFact missing eventId",
    "Exception: IllegalArgumentException (UUID chặt)": "Exception: IllegalArgumentException (strict UUID)",
    'UUID = " 00000000-…-000000000001" (có khoảng trắng)': (
        'UUID = " 00000000-…-000000000001" (with whitespace)'
    ),
    "Topology khai báo": "Declared topology",
    "Return: 1 DLX agricontract.reputation.dlx durable; mỗi queue có x-dead-letter-exchange, không đổi routing key; DLQ bind đúng key gốc": (
        "Return: 1 durable DLX agricontract.reputation.dlx; each queue sets x-dead-letter-exchange keeping routing key; DLQs bind original key"
    ),
    "Return: khớp schema contractSettled": "Return: matches contractSettled schema",
    "Return: khớp schema milestoneDisputeResolved": "Return: matches milestoneDisputeResolved schema",
    "DLQ contract.settled có 7 message": "contract.settled DLQ has 7 messages",
    'Return: Log: 1 dòng WARN "event=reputation_dlq_nonempty queue=… messageCount=7", không stacktrace': (
        'Return: Log: 1 WARN line "event=reputation_dlq_nonempty queue=… messageCount=7", no stacktrace'
    ),
    "Return: không log; chỉ kiểm queue của mình; đóng connection mỗi lần": (
        "Return: no log; probes own queues only; closes connection every run"
    ),
    "Probe queue đầu ném IOException chứa secret": "First queue probe throws IOException containing secret",
    "Chạy 2 lần": "Run twice",
    'Return: Log: 1 dòng "reputation_dlq_probe_failed" không lộ secret; queue khác vẫn được kiểm': (
        'Return: Log: 1 line "reputation_dlq_probe_failed" without leaking secret; remaining queues still probed'
    ),
    "Mở connection: timeout rồi runtime error": "Open connection: timeout then runtime error",
    'Return: không ném ra ngoài; 2 dòng log không chứa "private"': (
        'Return: does not escape; 2 log lines without "private"'
    ),
    "Driver báo lỗi bất đồng bộ chứa secret": "Driver reports async error containing secret",
    "Return: không log gì": "Return: logs nothing",
    "Broker nhận socket nhưng không bắt tay": "Broker accepts socket without handshake",
    "Return: kết thúc trong giới hạn timeout": "Return: finishes within timeout",
    "Return: timeout 1000 ms mọi chặng; tắt auto recovery; dùng bản clone của factory": (
        "Return: 1000 ms timeout across all phases; auto recovery off; clones factory"
    ),
    "contract.settled hợp lệ": "Valid contract.settled",
    "Return: command với UUID contract/buyer/seller/event, thời điểm UTC": (
        "Return: command with contract/buyer/seller/event UUIDs and UTC timestamp"
    ),
    "Envelope: thiếu eventId": "Envelope: missing eventId",
    "Exception: AmqpRejectAndDontRequeueException; không gọi use case": (
        "Exception: AmqpRejectAndDontRequeueException; use case not called"
    ),
    "Envelope: thiếu aggregateId": "Envelope: missing aggregateId",
    'Envelope: field lạ "email" ở envelope': 'Envelope: extra field "email" in envelope',
    "Envelope: payload là string": "Envelope: payload is a string",
    "Envelope: correlationId rỗng": "Envelope: empty correlationId",
    "Envelope: payload.buyerId là số": "Envelope: payload.buyerId is numeric",
    'Envelope: field lạ "email" trong payload': 'Envelope: extra field "email" in payload',
    "Use case ném ImmutableFactConflictException; rồi DataAccessResourceFailureException": (
        "Use case throws ImmutableFactConflictException; then DataAccessResourceFailureException"
    ),
    "Return: conflict: không retry; DB sập: ném lại để retry": (
        "Return: conflict not retried; DB outage rethrown for retry"
    ),
    'Body "null", "{", "[]", JSON thừa ký tự, eventVersion trùng key': (
        'Body "null", "{", "[]", trailing JSON garbage, duplicate eventVersion key'
    ),
    "milestone.dispute_resolved hợp lệ": "Valid milestone.dispute_resolved",
    'Return: command map trực tiếp milestone, contract, người flag, "SELLER"': (
        'Return: command maps milestone, contract, flaggedBy, "SELLER" directly'
    ),
    "payload thêm flaggedBy = SELLER": "payload adds flaggedBy = SELLER",
    "eventVersion dạng chuỗi": "eventVersion as string",
    "Return: không ghi fact": "Return: no fact written",
    "Return: message lỗi chung, không lộ giá trị hay nguyên nhân": (
        "Return: generic error message without leaking values or root cause"
    ),
    "Lô nhiều user": "Batch of multiple users",
    "Return: 1 lần gọi repository, lấy thời gian 1 lần": (
        "Return: single repository call, single clock read"
    ),
    "Tóm tắt công khai": "Public summary",
    "Return: cùng phép chiếu và thời gian như lịch sử": (
        "Return: same projection and clock as lock history"
    ),
    "Danh sách id trùng; sửa danh sách sau khi gọi": "Duplicate id list; mutate list after call",
    "Exception: IllegalArgumentException; danh sách được copy": (
        "Exception: IllegalArgumentException; input list defensively copied"
    ),
    "Secret cấu hình thiếu hoặc rỗng": "Configured secret missing or empty",
    "Return: không bao giờ xác thực": "Return: never authenticates",
    "Return: principal là service, không có danh tính người dùng; context được dọn": (
        "Return: principal is service without user identity; context cleared"
    ),
    "Xử lý exception": "Exception handling",
    "GET /conflict không correlation id": "GET /conflict without correlation id",
    "Return: traceId vẫn được sinh": "Return: traceId still generated",
    "GET /boom (exception lạ)": "GET /boom (unexpected exception)",
    'Return: 500; code INTERNAL_ERROR; message "Internal server error" (giấu message gốc)': (
        'Return: 500; code INTERNAL_ERROR; message "Internal server error" (hides original message)'
    ),
    "Return: 404; code LISTING_NOT_FOUND (handler riêng của service thắng catch-all)": (
        "Return: 404; code LISTING_NOT_FOUND (service handler beats catch-all)"
    ),
    "Exception: IllegalArgumentException (code phải UPPER_SNAKE_CASE)": (
        "Exception: IllegalArgumentException (code must be UPPER_SNAKE_CASE)"
    ),
    "Return: không tự sinh correlationId": "Return: does not auto-generate correlationId",
    "Event cha gốc, corr-1": "Root parent event, corr-1",
    "Return: causationId = eventId cha; giữ corr-1": (
        "Return: causationId = parent eventId; preserves corr-1"
    ),
    'Event cha đã có causation "grandparent-id"': 'Parent event already has causation "grandparent-id"',
    "Return: causationId = eventId cha, không phải ông": (
        "Return: causationId = parent eventId, not grandparent"
    ),
    "Event cha không có correlationId": "Parent event without correlationId",
    "Chuỗi e1 → e2 → e3": "Chain e1 → e2 → e3",
    "Return: mỗi event trỏ về cha trực tiếp; cùng corr-1": (
        "Return: each event points to immediate parent; shares corr-1"
    ),
    "Constructor – hợp lệ": "Constructor – valid",
    "Đủ field, causationId cause-1": "All fields, causationId cause-1",
    "Return: giữ nguyên mọi giá trị": "Return: preserves all values",
    "Return: tạo được": "Return: constructed",
    "Return: tạo được (minLength 1)": "Return: constructed (minLength 1)",
    "Constructor – không hợp lệ": "Constructor – invalid",
    "Return: giữ type, id": "Return: preserves type, id",
    "Envelope có correlationId": "Envelope with correlationId",
    "Return: occurredAt là chuỗi ISO có Z": "Return: occurredAt is ISO string with Z",
    "Return: các field này vắng mặt, không ghi null": "Return: these fields omitted, not written as null",
    "payload có remedyLegId = null": "payload has remedyLegId = null",
    'Return: giữ "remedyLegId": null trong payload': 'Return: keeps "remedyLegId": null in payload',
    "Envelope đủ field": "Envelope with all fields",
    'Return: chỉ có tên field trong schema; subject.type = "CONTRACT"': (
        'Return: only schema-defined field names; subject.type = "CONTRACT"'
    ),
    "toJson rồi parse lại": "toJson then parse back",
    "Return: envelope bằng bản gốc": "Return: parsed envelope equals original",
    "Cùng envelope 2 lần": "Same envelope twice",
    "Return: chuỗi JSON giống hệt": "Return: identical JSON string",
    "parse() – hợp lệ": "parse() – valid",
    "JSON tối thiểu": "Minimal JSON",
    "Return: parse được; subject, correlationId = null": "Return: parsed; subject, correlationId = null",
    "correlationId, causationId = null tường minh": "Explicit null correlationId, causationId",
    "Return: parse được": "Return: parsed",
    "parse() – không hợp lệ": "parse() – invalid",
    "subject = null tường minh": "Explicit null subject",
    "field lạ ở envelope": "Extra field in envelope",
    "field lạ trong subject": "Extra field in subject",
    "subject.type BUYER (ngoài enum)": "subject.type BUYER (outside enum)",
    "eventId lặp key": "Duplicate eventId key",
    "Thiếu eventVersion": "Missing eventVersion",
    "occurredAt không có offset": "occurredAt without offset",
    "payload là string": "payload is a string",
    'JSON hợp lệ + "{}" thừa phía sau': 'Valid JSON + trailing "{}"',
    "Field lạ chứa email": "Extra field containing email",
    "Exception: InvalidEnvelopeException; message không chứa email": (
        "Exception: InvalidEnvelopeException; message omits email"
    ),
    "Exception: InvalidEnvelopeException; message và cause không chứa email": (
        "Exception: InvalidEnvelopeException; message and cause omit email"
    ),
    'Exception: InvalidEnvelopeException nêu đường dẫn "subject.type"': (
        'Exception: InvalidEnvelopeException names path "subject.type"'
    ),
    'Exception: InvalidEnvelopeException "eventVersion must be >= 1", không cause': (
        'Exception: InvalidEnvelopeException "eventVersion must be >= 1", without cause'
    ),
    'eventVersion "1" (sai kiểu)': 'eventVersion "1" (wrong type)',
    "Exception: InvalidEnvelopeException (không tự ép kiểu)": (
        "Exception: InvalidEnvelopeException (no implicit coercion)"
    ),
    "eventVersion 1.9 (sai kiểu)": "eventVersion 1.9 (wrong type)",
    "producer 123 (sai kiểu)": "producer 123 (wrong type)",
    "aggregateId true (sai kiểu)": "aggregateId true (wrong type)",
    "occurredAt 1700000000 (sai kiểu)": "occurredAt 1700000000 (wrong type)",
    "occurredAt 1700000000.5 (sai kiểu)": "occurredAt 1700000000.5 (wrong type)",
    "occurredAt true (sai kiểu)": "occurredAt true (wrong type)",
    "eventId 5 (sai kiểu)": "eventId 5 (wrong type)",
    'Exception: InvalidEnvelopeException nêu "occurredAt"': (
        'Exception: InvalidEnvelopeException names "occurredAt"'
    ),
    "Yêu cầu Level 1.5 có actor bắt buộc nhưng null": (
        "Level 1.5 request with required actor set to null"
    ),
    "Return: giữ null, không tự thêm field thiếu": "Return: preserves null, adds no missing field",
    "+5 ngày qua cuối tuần và ngày lễ liền nhau": "+5 days across adjacent weekend and holiday",
    "Return: bỏ qua đủ cuối tuần và lễ": "Return: skips weekends and holidays",
    "Thời điểm gần nửa đêm, JVM khác múi giờ": "Near-midnight timestamp, different JVM timezone",
    "Return: tính theo ngày giờ Việt Nam (ICT)": "Return: computes in Vietnam time (ICT)",
    "Cộng qua năm mới": "Add across New Year",
    "Return: bỏ qua Tết Dương lịch": "Return: skips New Year's Day",
    "+0 / +31 ngày": "+0 / +31 days",
    "+30 ngày": "+30 days",
    "Cấu hình ngày lễ": "Holiday configuration",
    "Có khoảng trắng, trùng, token rỗng": "Whitespace, duplicates, empty tokens",
    "Return: bỏ qua, đọc đúng": "Return: ignores blanks/dups, parses properly",
    "Thiếu hoặc sai định dạng": "Missing or malformed",
    "Exception: IllegalArgumentException nêu tên VIETNAM_HOLIDAYS": (
        "Exception: IllegalArgumentException naming VIETNAM_HOLIDAYS"
    ),
    "Không bật poller": "Poller disabled",
    "Return: có writer, store, codec; poller tắt": "Return: creates writer, store, codec; poller disabled",
    "Không có JdbcTemplate": "No JdbcTemplate",
    "Return: không tạo writer và store": "Return: does not create writer or store",
    "Chỉ có codec, không JDBC/Rabbit": "Codec only, no JDBC/Rabbit",
    "Return: khởi động với codec": "Return: starts with codec",
    "Bật poller": "Poller enabled",
    "Có exchange và publisher confirms": "Exchange and publisher confirms configured",
    "Return: có poller và stale monitor": "Return: creates poller and stale monitor",
    "Có kèm OutboxPoller cũ của service": "Legacy service OutboxPoller present",
    "Return: cả hai cùng tồn tại": "Return: both coexist",
    "Không khai báo chu kỳ": "No poll interval configured",
    "Return: dùng chu kỳ mặc định": "Return: uses default poll interval",
    "Chu kỳ dạng PT1S": "Interval as PT1S",
    "Exception: Khởi động lỗi, nêu tên key poll-interval": (
        "Exception: startup fails, names key poll-interval"
    ),
    "Chu kỳ dạng mili giây": "Interval in milliseconds",
    "Thiếu exchange": "Missing exchange",
    "Không bật publisher confirms": "Publisher confirms disabled",
    "Exception: Khởi động lỗi, nêu publisher-confirm-type=correlated": (
        "Exception: startup fails, names publisher-confirm-type=correlated"
    ),
    "Return: đủ field; status PENDING": "Return: all fields set; status PENDING",
    "Profile mới": "New profile",
    "Return: chưa có người duyệt, thời điểm duyệt, hạn, lý do": (
        "Return: reviewer, reviewedAt, expiry, reason all unset"
    ),
    "Return: phát 1 UserRegisteredEvent cho user-123": (
        "Return: emits 1 UserRegisteredEvent for user-123"
    ),
    "Dữ liệu VERIFIED từ DB": "VERIFIED record from DB",
    "Return: không phát event": "Return: emits no event",
    "Dữ liệu REJECTED kèm lý do": "REJECTED record with reason",
    "Return: khôi phục status, người duyệt, thời điểm, lý do": (
        "Return: restores status, reviewer, timestamp, reason"
    ),
    "Đã pull 1 lần": "Already pulled once",
    "Return: VERIFIED; ghi người duyệt, thời điểm, hạn; không lý do": (
        "Return: VERIFIED; sets reviewer, timestamp, expiry; clears reason"
    ),
    "Return: VERIFIED, uỷ quyền không thời hạn": "Return: VERIFIED, indefinite authorization",
    'actor null; actor " "; thời điểm null': 'actor null; actor " "; timestamp null',
    'lý do "  Giay phep het han  "': 'reason "  Giay phep het han  "',
    "Return: REJECTED; lý do đã trim; ghi người duyệt, thời điểm": (
        "Return: REJECTED; trimmed reason; sets reviewer, timestamp"
    ),
    'lý do null; ""; "   "': 'reason null; ""; "   "',
    'actor " "; thời điểm null': 'actor " "; timestamp null',
    "Tự duyệt hồ sơ": "Self-review profile",
    "PENDING của user-123": "PENDING profile of user-123",
    "verify / reject với actor = user-123": "verify / reject with actor = user-123",
    "Return: vẫn PENDING, chưa có người duyệt": "Return: stays PENDING, reviewer unset",
    "Return: về PENDING; xoá lý do; giữ lịch sử người duyệt": (
        "Return: resets to PENDING; clears reason; preserves reviewer history"
    ),
    "REJECTED rồi resubmit": "REJECTED then resubmitted",
    "verify bởi operator-2, sau 1 giờ": "verified by operator-2, 1 hour later",
    "Return: VERIFIED bởi operator-2 với thời điểm mới": (
        "Return: VERIFIED by operator-2 with new timestamp"
    ),
    "Chuyển trạng thái sai": "Invalid state transitions",
    "Return: vẫn REJECTED": "Return: stays REJECTED",
    "Return: email mới được lưu": "Return: new email saved",
    "Return: giữ thông tin cũ": "Return: preserves existing data",
    "Return: false; không đổi": "Return: false; unchanged",
    "Return: giữ OPERATOR": "Return: keeps OPERATOR",
    "Return: giữ ARBITRATOR": "Return: keeps ARBITRATOR",
    "Return: giữ BUYER": "Return: keeps BUYER",
    "actor = chính user đó, role khác": "actor = target user, different role",
    "Return: giữ role cũ": "Return: keeps previous role",
    "actor = chính user đó, cùng role": "actor = target user, same role",
    "Return: ném lỗi trước khi coi là không đổi": "Return: throws before treating as no-op",
    "actor rỗng": "Empty actor",
    "Không có user": "User not found",
    "Return: không update": "Return: does not update",
    "Return: trả OPERATOR, không update": "Return: returns OPERATOR, does not update",
    "Admin đang đăng nhập": "Logged-in admin",
    "Đổi role của chính mình": "Change own role",
    "Gán lại đúng role hiện tại của mình": "Re-assign own current role",
    "Return: không update (ném lỗi thay vì bỏ qua)": "Return: does not update (throws instead of no-op)",
    "User đang ARBITRATOR": "User is currently ARBITRATOR",
    "Return: Không update; ghi audit từ chối kèm actor": (
        "Return: does not update; logs rejection audit with actor"
    ),
    "VERIFIED, có hạn uỷ quyền và lockedUntil": "VERIFIED with authorization expiry and lockedUntil",
    "Return: trả đủ: tổ chức, email, role, status, hạn, lockedUntil": (
        "Return: returns organization, email, role, status, expiry, lockedUntil"
    ),
    "PENDING, không hạn, không khoá": "PENDING, no expiry, unlocked",
    "Email chưa xác minh": "Unverified email",
    "Return: email = null (không giao email chưa xác minh)": (
        "Return: email = null (never exposes unverified email)"
    ),
    "Hồ sơ PENDING tồn tại": "PENDING profile exists",
    "Return: ghi audit (actor, role, target) rồi trả hồ sơ đầy đủ": (
        "Return: logs audit (actor, role, target) then returns full profile"
    ),
    "Return: không ghi audit": "Return: does not log audit",
    "Ghi audit lỗi (DB down)": "Audit write fails (DB down)",
    "Return: không trả dữ liệu": "Return: returns no data",
    "1 hồ sơ PENDING": "1 PENDING profile",
    "Return: mặc định PENDING; 1 phần tử seller-1; page 0, size 20": (
        "Return: defaults to PENDING; 1 item seller-1; page 0, size 20"
    ),
    "10 hồ sơ REJECTED": "10 REJECTED profiles",
    "Return: content rỗng; page 2, size 5, total 10": (
        "Return: empty content; page 2, size 5, total 10"
    ),
    'lý do "Thieu giay phep"': 'reason "Thieu giay phep"',
    "Return: REJECTED; lý do, actor, thời điểm = NOW": (
        "Return: REJECTED; reason, actor, timestamp = NOW"
    ),
    'lý do "  "': 'reason "  "',
    "Đã REJECTED lần 1": "Already REJECTED once",
    'lý do "Lan 2"': 'reason "Lan 2"',
    "Profile đã bị người khác cập nhật (version cũ)": "Profile concurrently updated (stale version)",
    "Return: không publish": "Return: does not publish",
    "Return: VERIFIED; actor, thời điểm = NOW, hạn = EXPIRES_AT": (
        "Return: VERIFIED; actor, timestamp = NOW, expiry = EXPIRES_AT"
    ),
    "Return: verifiedAt cắt về .123456Z": "Return: verifiedAt truncated to .123456Z",
    "Đã VERIFIED": "Already VERIFIED",
    "actor = chính user đó": "actor = target user",
    "Return: 1 dòng WARN event=user_dlq_nonempty, nêu queue và số message": (
        "Return: 1 WARN line event=user_dlq_nonempty with queue and count"
    ),
    "Khoá được áp": "Lock applied",
    "Return: publish theo hồ sơ đọc lại": "Return: publishes from reloaded profile",
    "Mở khoá được áp": "Unlock applied",
    "Return: dùng câu thông báo mở khoá": "Return: uses unlock notification wording",
    "Revision cũ hơn": "Stale revision",
    "Return: bỏ qua, không publish": "Return: ignored, does not publish",
    "Truy cập nội bộ": "Internal access",
    "Secret cấu hình rỗng hoặc toàn khoảng trắng": "Configured secret empty or blank",
    "Return: không bao giờ cho truy cập": "Return: never grants access",
    "User tồn tại": "User exists",
    "Return: chỉ trả field công khai": "Return: returns public fields only",
    "Có inspector": "Inspectors exist",
    "Return: dùng thời gian hiện tại; chỉ trả userId theo thứ tự repository": (
        "Return: uses current time; returns userIds only in repository order"
    ),
    "Không có ai": "None eligible",
    "Return: danh sách rỗng": "Return: empty list",
    "Return: lệnh khớp schema, không có lý do": "Return: command matches schema, no reason",
    "Return: lệnh khớp schema, có lý do": "Return: command matches schema, with reason",
    "Gọi nhiều lần": "Called multiple times",
    "Return: mỗi lần một eventId mới": "Return: fresh eventId per call",
    "Return: Không ghi lệnh nào vào outbox": "Return: writes no command to outbox",
    "Khoá": "Lock",
    "Return: báo khoá đang lưu, không phải khoá vừa nhận": (
        "Return: reports persisted lock, not incoming event lock"
    ),
    "Mở khoá hoàn toàn": "Full unlock",
    "Return: khớp schema": "Return: matches schema",
    "Return: lệnh khoá giữ chuỗi correlation": "Return: lock command preserves correlation chain",
    "reputation.unlocked với khoá hiệu lực null": "reputation.unlocked with null effective lock",
    "Return: mở khoá hoàn toàn": "Return: full unlock",
    "Thời điểm có offset": "Timestamp with offset",
    "Return: chuẩn hoá UTC, micro giây": "Return: normalized to UTC, microseconds",
    "Thiếu correlationId": "Missing correlationId",
    "Return: bắt đầu chuỗi từ event này": "Return: starts chain from this event",
    "Locked thiếu lockedUntil; unlocked thiếu key effectiveLockedUntil; field lạ": (
        "Locked missing lockedUntil; unlocked missing effectiveLockedUntil; extra field"
    ),
    "Revision < 1 hoặc không phải số nguyên; user không phải UUID": (
        "Revision < 1 or non-integer; non-UUID user"
    ),
    "Sai producer hoặc eventType": "Wrong producer or eventType",
    "Mã còn hạn": "Code valid",
    "Mã đúng": "Correct code",
    "Return: Profile có verified_email; xoá challenge": (
        "Return: profile gets verified_email; challenge deleted"
    ),
    "Keycloak sập": "Keycloak down",
    "Return: Vẫn xác minh trong user-service": "Return: still verified in user-service",
    "Return: Khoá profile trước khi đọc challenge": "Return: locks profile before reading challenge",
    "Mã sai": "Wrong code",
    "Return: Kết quả bị từ chối; tăng số lần thử": "Return: rejected; increments attempt count",
    "Đã hết 5 lần thử": "5 attempts exhausted",
    "Mã đã hết hạn (300 s); mã gửi tới địa chỉ cũ": (
        "Code expired (300 s); code sent to previous address"
    ),
    "Exception: Conflict (coi như hết hạn)": "Exception: Conflict (treated as expired)",
    "Chưa yêu cầu mã": "No code requested",
    "Mã bất kỳ": "Any code",
    "Email đã xác minh": "Email already verified",
    "User khác vừa xác minh cùng địa chỉ": "Another user just verified same address",
    "Exception: Địa chỉ đã bị nhận": "Exception: address already claimed",
    "Yêu cầu mã": "Request code",
    "Return: Chỉ lưu hash của mã rồi mới gửi": "Return: stores code hash before sending",
    "notification-service lỗi": "notification-service error",
    "Return: Challenge đó hết hạn; lỗi được ném tiếp": (
        "Return: expires that challenge; rethrows error"
    ),
    "Exception: Lỗi gửi mail": "Exception: mail delivery error",
    "notification-service lỗi, ghi hết hạn cũng lỗi": (
        "notification-service fails, expiring challenge also fails"
    ),
    "Return: Vẫn báo lỗi gửi mail": "Return: still reports mail delivery error",
    "Return: Không lưu, không gửi": "Return: does not store, does not send",
    "Địa chỉ đã được user khác xác minh": "Address already verified by another user",
    "Return: Không gửi mã": "Return: does not send code",
    "Mã vừa gửi < 60 s": "Code sent < 60 s ago",
    "Mã trước hết hạn vì không gửi được": "Previous code expired after delivery failure",
    "Gửi lại trong 60 s": "Resend within 60 s",
    "Return: Cho gửi lại": "Return: allows resend",
    "Chạy job": "Run job",
    "Return: Chỉ hỏi tài khoản tạo trước hơn 24 giờ": (
        "Return: queries only accounts created over 24 hours ago"
    ),
    "Return: Không xoá gì": "Return: deletes nothing",
    "Có tài khoản quá hạn": "Expired accounts exist",
    "Return: Xoá dòng profile trước, rồi tới tài khoản Keycloak": (
        "Return: deletes profile row first, then Keycloak account"
    ),
    "1 tài khoản xoá lỗi": "1 account purge fails",
    "Return: Các tài khoản khác vẫn được xoá": "Return: other accounts still purged",
    "Email đã xác minh ở user-service": "Email already verified in user-service",
    "Return: Giữ tài khoản; báo lại Keycloak đã xác minh": (
        "Return: keeps account; syncs verified status to Keycloak"
    ),
    "User mới": "New user",
    "Return: Lưu email chưa xác minh rồi gửi mã đầu": (
        "Return: saves unverified email then sends initial code"
    ),
    "User mới, gửi mã lỗi": "New user, initial code send fails",
    "Như trên": "Same as above",
    "Return: Vẫn đăng ký được": "Return: registration still succeeds",
    "Địa chỉ bị user khác nhận ngay sau lúc kiểm tra": (
        "Address claimed by another user right after check"
    ),
    "Return: Vẫn đăng ký, không gửi mã": "Return: still registers, skips sending code",
    "User đã có profile": "User already has profile",
    "Gửi lại đăng ký": "Resend registration",
    "Return: Trả profile cũ; không kiểm địa chỉ, không gửi mã mới": (
        "Return: returns existing profile; skips address check and new code"
    ),
    "Return: Email chưa xác minh": "Return: email unverified",
    "Mã gửi tới địa chỉ hiện tại": "Code sent to current address",
    "Đánh dấu xác minh": "Mark verified",
    "Return: Email liên hệ là người nhận thông báo": (
        "Return: contact email receives notifications"
    ),
    "Mã gửi tới địa chỉ khác": "Code sent to different address",
    "Địa chỉ mới": "New address",
    "Return: Bỏ bằng chứng xác minh": "Return: clears verification proof",
    "Cùng địa chỉ": "Same address",
    "Return: Giữ bằng chứng xác minh": "Return: keeps verification proof",
    "Xác thực và phân quyền": "Authentication and authorization",
    "X-Gateway-Secret không kèm danh tính": "X-Gateway-Secret without identity headers",
    "X-Internal-Secret kèm role OPERATOR giả (verify, detail)": (
        "X-Internal-Secret with spoofed OPERATOR role (verify, detail)"
    ),
    "X-User-Id hoặc X-User-Role gửi 2 lần": "Duplicate X-User-Id or X-User-Role header",
    "BUYER, SELLER, ADMIN, ARBITRATOR, token vừa OPERATOR vừa ARBITRATOR": (
        "BUYER, SELLER, ADMIN, ARBITRATOR, token with both OPERATOR and ARBITRATOR"
    ),
    "Return: 403 (chỉ OPERATOR)": "Return: 403 (OPERATOR only)",
    "Đường dẫn admin không tồn tại": "Non-existent admin path",
    "Filter từ chối request": "Filter rejects request",
    "Return: Body là ErrorEnvelope": "Return: body is ErrorEnvelope",
    "Return: Actor lấy từ principal": "Return: actor resolved from principal",
    "Return: Được chấp nhận (uỷ quyền không thời hạn)": (
        "Return: accepted (indefinite authorization)"
    ),
    "Expiry có offset +07:00": "Expiry with +07:00 offset",
    "Return: Chuẩn hoá về UTC": "Return: normalized to UTC",
    "Thiếu body; thiếu key; sai định dạng; sai tên field; chuỗi rỗng; số epoch": (
        "Missing body; missing key; bad format; wrong field name; empty string; epoch number"
    ),
    "Expiry bị domain từ chối (không sau thời điểm duyệt)": (
        "Expiry rejected by domain (not after decision time)"
    ),
    "Profile không ở PENDING": "Profile not in PENDING",
    "Duyệt": "Approve",
    "OPERATOR tự duyệt mình": "OPERATOR approves self",
    "Return: Truyền lý do và actor": "Return: passes reason and actor",
    "Quyết định khác vừa ghi": "Concurrent decision just recorded",
    "Field lạ; lý do trống": "Extra field; blank reason",
    "Return: Trả trang": "Return: returns page",
    "Return: Trang đầu, size 20": "Return: first page, size 20",
    "verificationStatus không hợp lệ": "Invalid verificationStatus",
    "Return: Hồ sơ đầy đủ; truyền actor và role ứng dụng": (
        "Return: full profile; passes actor and application role"
    ),
    "Ghi audit đọc lỗi": "Read audit write fails",
    "Return: 503, không trả dữ liệu": "Return: 503, returns no data",
    "Return: Truyền target, actor và role": "Return: passes target, actor and role",
    "Đổi role": "Assign role",
    "Role không biết; số; chuỗi số; null; chữ thường; thiếu; field thừa": (
        "Unknown role; number; numeric string; null; lowercase; missing; extra field"
    ),
    "Return: Mảng chỉ có định danh tối thiểu": "Return: array with minimal identifiers only",
    "JWT công khai; role qua gateway": "Public JWT; role via gateway",
    "Return: Không đọc được danh sách": "Return: cannot read list",
    "Danh tính gateway, đường dẫn có context path": (
        "Gateway identity, path with context path"
    ),
    "Return: Không thành quyền service": "Return: does not escalate to service auth",
    "Return: Đúng các field đã chốt, ở cấp gốc": "Return: exact frozen fields at top level",
    "Uỷ quyền không hạn, không khoá": "Indefinite authorization, unlocked",
    "Return: null tường minh": "Return: explicit null",
    "Thiếu secret; secret sai": "Missing secret; wrong secret",
    "End user đã qua gateway": "End user through gateway",
    "Gọi đường dẫn nội bộ": "Call internal path",
    "Return: Bị chặn": "Return: blocked",
    "DB sập": "DB down",
    "Return: Fail closed, không trả dữ liệu user": "Return: fail-closed, returns no user data",
    "Return: Chỉ field công khai": "Return: public fields only",
    "Không có secret; X-Gateway-Secret không kèm danh tính; X-Internal-Secret": (
        "Missing secret; X-Gateway-Secret without identity; X-Internal-Secret"
    ),
    "Return: Chủ profile lấy từ principal": "Return: profile owner resolved from principal",
    "X-Internal-Secret kèm userId giả; X-Gateway-Secret không kèm danh tính": (
        "X-Internal-Secret with spoofed userId; X-Gateway-Secret without identity"
    ),
    "Return: 201; email lấy từ body": "Return: 201; email taken from body",
    "Header email giả": "Spoofed email header",
    "Return: Bị bỏ qua": "Return: ignored",
    "Thiếu email; email sai định dạng": "Missing email; malformed email",
    "Đăng ký lại": "Re-register",
    "Return: 200 với email đã lưu": "Return: 200 with stored email",
    "Return: 201 và báo chưa gửi được mã": "Return: 201 and reports code delivery failure",
    "Địa chỉ đã được user khác nhận": "Address already claimed by another user",
    "Return: 202; user lấy từ principal": "Return: 202; user resolved from principal",
    "Không có principal": "Missing principal",
    "Return: Profile đã xác minh": "Return: profile verified",
    "Mã không phải 6 chữ số": "Code is not 6 digits",
})
