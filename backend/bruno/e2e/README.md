# Bruno E2E

Các sơ đồ BF-01 đến BF-07 ở `docs/reports/figures/srs` xác định thứ tự nghiệp vụ. Dùng OpenAPI, event contract và lớp controller để xác định giao diện chạy được.

| Luồng | Kiểm tra dự kiến | Nguồn |
| --- | --- | --- |
| BF-01 | Đăng ký tài khoản thử, nhận OTP qua MailHog, xác minh email, OPERATOR duyệt hoặc từ chối hồ sơ | OpenAPI `registerUser`, `confirmEmailVerification`, `verifyUser`, `rejectUser`; user-service design §4.4 |
| BF-02 | Đề xuất và duyệt category, tạo product, đăng ký đất, tạo/sửa/đọc listing | product design §2; `CategoryController`, `PlotRegistryController`, `ListingController` |
| BF-03 | Tạo và rút offer, kiểm tra lượng hàng giữ và trả, ký và kiểm tra khoá cọc | OpenAPI `createContract`, `withdrawOffer`, `initiateSign`, `verifyOtpAndSign`; signature design §4–6 |
| BF-04 | Weigh, receive, confirm, kiểm tra settlement, ledger, audit và notification; kiểm tra topology cho milestone anchor | OpenAPI `recordSellerWeighed` đến `respondToMilestoneFlag`; milestone escrow design §7; hash-chain design §2.4, §4.6 |
| BF-05 | Phần tranh chấp đã có controller trong BF-04; phần còn thiếu theo #239, #240, #242, #244 | `BreachCaseController`; milestone escrow design |
| BF-06 | N/A cho chấm dứt và thay thế hợp đồng (#242, #243) | OpenAPI `proposeMutualTermination`, `proposeMutualReplacement`; chưa có controller |
| BF-07 | Kiểm tra trạng thái khoá qua reputation và đối chiếu route lock/unlock với code bank | OpenAPI `getPublicReputationSummary`, `emergencyLock`, `emergencyUnlock`; bank design §3; reputation design §6 |

Các kiểm tra phòng thủ dựa trên data-governance design §5.4 và hash-chain design §4.6. Collection cũ đã có một số bước trùng về listing, onboarding, xác thực và phân quyền. 

## Giả định

- Stack Compose local dành riêng cho test. MailHog và RabbitMQ management dùng dữ liệu test.
- Theo thiết kế hiện tại, đăng ký profile tạo hồ sơ KYC `PENDING`. Không có request nộp KYC riêng trong controller.
- Mail quyết toán đính kèm `.ots` là N/A vì audit-service chưa publish `notification.milestone_anchor_requested`. Notification-service đã có consumer (#632), nên vẫn kiểm tra binding trên `agricontract.audit` và DLQ. Mail thuộc #278 còn Pending.
- Gateway route cho audit của các bên còn Pending (#630).
