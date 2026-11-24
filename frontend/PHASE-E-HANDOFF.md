# Phase E — BF-04 giao nhận và tất toán đợt

Frontend dùng API Gateway qua Axios. Chế độ `VITE_MOCK_API=bf04` chỉ hoạt động trong DEV. Schema đọc D99 theo [PR #634](https://github.com/redavocado-labs/AgriContract/pull/634), với khả năng đọc response D84/D92 trong thời gian backend triển khai D99.

## Thao tác và API

Trong bảng, `C = /api/v1/contracts/{contractId}`, `M = C/milestones/{milestoneId}`, `B = C/breach-cases/{breachCaseId}`.

| Thao tác | Endpoint | Backend thật và mock |
|---|---|---|
| Hợp đồng và danh sách đợt | `GET C` | API thật trả trạng thái hợp đồng. Danh sách đợt và quyết định cuối theo D99 có fixture DEV. |
| Chi tiết đợt, số cân, hạn, kết quả tất toán | `GET M` | API thật đã kiểm chứng. Mock theo D84/D92/D99. |
| Upload, chờ quét và xem bằng chứng | `POST M/evidence`, `GET M/evidence/{fileId}` | API thật đã kiểm chứng upload và quét. Mock có cả trạng thái quét lỗi. FE poll mỗi 2 giây tối đa 60 giây cho mỗi tệp trong một lần mở màn, có nút tải lại thủ công. |
| Seller cân và sửa cân | `POST M/weigh` | API thật đã kiểm chứng lần khai 1 và 2. Mock dùng hạn có sẵn trên khai báo đầu. |
| Buyer nhận và xác nhận sạch | `POST M/receive`, `POST M/confirm` | API thật đã đi hết. Mock có trường hợp giám định bắt buộc. |
| Buyer báo lỗi, seller phản hồi | `POST M/flag`, `POST M/respond` | API thật đã kiểm chứng nhánh QUALITY với consent 1.1. Mock có cả QUALITY và QUANTITY. |
| Hồ sơ, quyết định và hạn phản đối | `GET B` | API thật đã trả hồ sơ REPORTED. Mock có kết quả giám định, quyết định và hạn phản đối. |
| Phản đối quyết định và bổ sung tệp | `POST B/contest`, `POST B/contest/evidence` | FE nối lệnh thật. Xác minh phản đối và trạng thái trong/ngoài hạn với mock và test component. Luồng có báo cáo giám định lần hai còn chờ dữ liệu E2E. |
| Nhận lỗi/rút báo lỗi | `POST M/dispute/concede/initiate`, `POST M/dispute/concede/verify` | FE nối lệnh thật và dùng số tiền hoàn từ D99. Luồng UI với OTP đã kiểm chứng bằng mock. |
| Đề xuất hoặc chấp nhận thỏa thuận | `POST M/dispute/settlement/initiate`, `POST M/dispute/settlement/verify` | Đề xuất của seller và chấp nhận của buyer đều qua OTP email thật. Mock dùng số hoàn của đề xuất từ D99. |
| Rút hoặc từ chối thỏa thuận | `DELETE M/dispute/settlement` | FE nối lệnh thật. Mock phản ánh đúng bên rút hoặc bên từ chối. |
| Hàng chờ, tiếp nhận, duyệt | `GET /api/v1/arbitration/breach-cases`, `POST B/review`, `POST B/resolve` | API thật trả hàng chờ trống trước khi có báo cáo. Mock có hồ sơ chờ duyệt theo kết quả tính sẵn. |
| Các lệnh tiền chế tài và hoàn tất | `GET M`, `GET C` | Luồng thật đã xác nhận hợp đồng còn ACTIVE sau tất toán đợt và chuyển SETTLED sau đối soát. Fixture D99 cấp `remedyDecision` và `terminalDecision` để kiểm thử cách hiển thị đầy đủ. |
| Gửi lại lệnh tiền lỗi | `POST C/remedy/retry-legs` | Route `/operations/remedy` cho OPERATOR, nhận mã từ thông báo lỗi hoặc từ trang đợt. API thật đã kiểm chứng 204 khi ACTIVE (không có leg hỏng trong fixture) và 409 khi SETTLED. Mock có cả hai mã. |
| Khóa tiền lỗi và bất khả kháng | `GET M` | Chỉ hiển thị tình trạng. Lệnh ghi thuộc BF-05. |

Consent dùng nguyên văn D84/D92, điền số và hash theo thiết kế. FLAG dùng 1.1, các câu khác dùng 1.0. Với OTP tranh chấp, `presentedContentHash` là `signedContentHash` và dùng luồng xác thực mới tối đa 300 giây. FE điều khiển nút theo role, tình trạng và hạn server, đồng thời hiển thị lỗi từ server. Mỗi lệnh có Idempotency-Key và không tự gửi lại. Chi tiết đợt hiển thị số tiền server tính và bỏ preview tất toán tính trong trình duyệt.

## BE cần bổ sung

Các vị trí sau tính theo `origin/main` ở `49fa5ec2`, đã có spec D99.

1. **D99 runtime.** `CoffeeContractResponse.from` còn trả `List.of()` ở `backend/contract-service/src/main/java/com/agricontract/contract/infrastructure/web/dto/CoffeeContractResponse.java:32`. Chưa có `terminalDecision`. Khi kiểm thử Compose, lấy ID đợt từ DB fixture để mở trang đợt trực tiếp vì màn hợp đồng chưa có danh sách. Banner chờ tiền về hiện khi có `terminalDecision.moneyCleared=false`. Backend cũ vẫn hiển thị “Đã hoàn tất” khi status thật là SETTLED.
2. **D99 runtime trên đợt.** `MilestoneDetailResponse.java:18` chưa có `remedyDecision`. `MilestoneDetailResponse.java:118` thiếu `buyerPositionRefundAmount`, `sellerPositionRefundAmount` và `openProposal.agreedBuyerRefundAmount`. FE chỉ mở nhận lỗi khi có số hoàn của vị thế được chấp nhận. Bảng lệnh chế tài và tiền hoàn lấy trực tiếp từ D99.
3. **Hạn huỷ giám định và phí trước khi quyết định.** `MilestoneDetailResponse.java:118` chưa có `freeCancelUntil` hay thông tin bên chịu phí dự kiến của từng cách kết thúc. Phí đã chốt được hiển thị qua `remedyDecision.inspectionFeeParty` theo D99.
4. **Enum đường tất toán.** `backend/contract-service/src/main/java/com/agricontract/contract/domain/model/milestone/SettlementPath.java:4` trả `INSPECTED_CONFORMING` và `DISPUTE_RESOLVED`, trong khi `contracts/openapi/golden-flow-api.yaml:2131` mô tả `INSPECTION_RESOLVED`, `CONCEDED` và `MUTUAL_SETTLEMENT`. FE đọc cả hai bộ giá trị và mock dùng tên của controller.
5. **BF-05.** OpenAPI mô tả `M/funding/retry` và `M/force-majeure[/resolve]` ở `contracts/openapi/golden-flow-api.yaml:671` và `:820`. Không tìm thấy handler tương ứng trong `MilestoneDeliveryController.java:52`. BF-04 giữ màn đọc và không bật nút gọi các lệnh đó.
6. **E2E giám định.** Chưa có báo cáo COMPLETE gửi từ inspection-service trong lần chạy. Chưa nghiệm thu duyệt kết quả, phản đối thật hay giám định lần hai qua Compose.

## Kiểm thử

| Lệnh | Kết quả |
|---|---|
| `npm run lint` | Qua, 4 cảnh báo Fast Refresh trong shared UI hiện có. |
| `npm run test` | 127 test qua trên 8 file, gồm parser, mã lỗi tiếng Việt, role, cửa sổ phản đối và OTP. |
| `npm run build` | Qua, Vite cảnh báo kích thước bundle. |

- Compose cô lập dùng `VITE_API_BASE_URL=http://localhost:8080`. Keycloak realm export cần chỉnh `webOrigins` thành `http://localhost:5173` trên client của container local để iframe trả 204. Nạp hồ sơ demo và listing cà phê vào DB local. Không thay đổi source backend.
- Luồng sạch thật gồm tạo hợp đồng thử, ký hai bên bằng OTP MailHog, khoá cọc và tiền đợt, upload raw 4.090 bytes mỗi bên, quét, seller cân và sửa, buyer nhận và xác nhận. API trả `CLEAN_CONFIRMED`, giải ngân 94.905.000 ₫ và hoàn 95.000 ₫. Sau lần đọc ACTIVE, escrow báo hết lock và hợp đồng chuyển SETTLED khoảng 18 giây sau tất toán đợt.
- Nhánh tranh chấp thật tới CONTESTED và hồ sơ REPORTED, rồi kiểm thử nhánh kết thúc sớm. Seller đề xuất 30.000.000 ₫, buyer chấp nhận cùng số tiền, mỗi bên dùng OTP email riêng. API trả `DISPUTE_RESOLVED`. Nhánh báo cáo giám định và phản đối được kiểm với mock.
- Kiểm tra trình duyệt mock dùng role BUYER, SELLER, ARBITRATOR và OPERATOR. Đã thử gửi phản đối, ẩn form quá hạn, nhận lỗi bằng OTP, thỏa thuận hai role, cùng phản hồi 204/409 khi gửi lại lệnh tiền. Để demo OTP trong DEV, dùng mã `123456`. Mock giữ tiến độ trong session của tab để đổi role bằng `?as=BUYER` hoặc `?as=SELLER`.

## Ảnh chụp

Ảnh lưu local tại `frontend/docs/screenshots/bf04/` trong checkout chính. Mỗi dòng có cả `-desktop.png` (1440 px) và `-mobile.png` (390 px). Các màn mock được kiểm tra không tràn ngang ở hai kích thước này.

| Tên ảnh | Trạng thái | Nguồn |
|---|---|---|
| `cho-can` | Chờ cân | Mock |
| `da-can` | Seller đã cân, buyer ghi nhận cân | Mock |
| `da-nhan` | Buyer đã nhận, chờ xác nhận | Mock |
| `bao-loi` | Form báo lỗi chất lượng và consent 1.1 | Mock |
| `cho-ben-ban` | Chờ seller phản hồi | Mock |
| `dang-giam-dinh` | Đang giám định | Mock |
| `co-quyet-dinh` | Đã có quyết định chế tài | Mock D99 |
| `trong-han-phan-doi` | Seller trong hạn phản đối | Mock |
| `da-tat-toan` | Đợt đã tất toán, có lệnh tiền | Mock D99 |
| `hop-dong-cho-tien` | Hợp đồng còn ACTIVE, chờ tiền về | Mock D99 |
| `hop-dong-hoan-tat` | Hợp đồng đã hoàn tất | Mock D99 |
| `hang-cho-trong-tai` | Hàng chờ ARBITRATOR | Mock |
| `nut-van-hanh` | OPERATOR gửi lại lệnh tiền | Mock |
| `thoa-thuan-otp` | Chấp nhận thỏa thuận và nhập OTP | Mock |
| `giam-dinh-bat-buoc` | Xác nhận sạch bị chặn do chờ giám định | Mock |
| `real-contract-settled` | Hợp đồng SETTLED | Compose |
| `real-milestone-settled` | Đợt CLEAN_CONFIRMED | Compose |
