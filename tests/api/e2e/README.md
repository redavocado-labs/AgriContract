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

## Chạy test

Cần Docker Compose v2, Bruno CLI 3.x, Node 22 trở lên, jq, curl và Python 3.

```bash
./tests/scripts/run-e2e-full.sh --fresh --keep
./tests/scripts/run-e2e-full.sh --flows bf02,bf03 --no-security --keep
cd tests/api
bru run e2e/bf04-milestone-delivery-settlement --env local --sandbox developer -r
```

Lệnh đầu tạo báo cáo JSON/HTML, log Compose, snapshot queue và hai báo cáo Markdown trong
`~/agricontract-e2e/run-<YYYYMMDD-HHMM>/`. Có thể đổi thư mục gốc qua `E2E_OUTPUT_DIR`.
`--fresh` xoá volume **của project test** rồi build và khởi chạy. Stack mặc định dùng project
`agricontract-bruno`, có thể đổi bằng `E2E_COMPOSE_PROJECT`. Các cổng host vẫn phải trống.
Runner tạo Compose override trong thư mục kết quả để dùng tên container riêng, chờ MySQL nhận
kết nối TCP, rút ngắn kiểm tra geo của plot và mở bank/audit trên loopback.

Có thể đặt `GATEWAY_INTERNAL_SECRET`, `SERVICE_INTERNAL_SECRET`, `KEYCLOAK_USER_SERVICE_SECRET`,
`INTAKE_IMAP_USERNAME`, `INTAKE_IMAP_PASSWORD`, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`
và `DB_ROOT_PASSWORD` trong môi trường của runner. Khi chạy Bruno trực tiếp trên stack đã
seed, dùng các giá trị tương ứng của `environments/local.bru` hoặc `--env-var`. Đặt
`VIETNAM_HOLIDAYS` nếu thay lịch trong stack. Chạy signing/funding vào ngày làm việc Việt Nam
để ngày yêu cầu khoá tiền vừa thoả D64 vừa thoả D67.

BF-02 dùng category COFFEE đã được duyệt nếu có, vì DB chỉ cho một category COFFEE. Trên stack
chưa có category COFFEE, test sẽ đề xuất và duyệt qua API. BF-03/BF-04 dùng buyer1/seller1 đã
seed để có tài khoản ngân hàng mock. Runner truyền ID listing từ BF-02 vào BF-03/BF-04 và ID
hợp đồng ACTIVE từ BF-03 vào BF-04 clean. Khi thiếu ID dùng chung, các case tạo fixture qua API.

BF04-250 tạo một hợp đồng riêng, ghi thời điểm nhận bình thường, sau đó lùi
`milestone.buyer_received_at` đúng 14 ngày cho một dòng đã xác nhận thuộc fixture. Helper ghi
giá trị UTC cũ/mới vào log Bruno. Job D78 mặc định chạy mỗi phút.

Các yêu cầu chưa chạy và issue đi kèm nằm ở [status.tsv](../../scripts/status.tsv).
Bộ phòng thủ hiện chạy xác thực phiên hợp lệ/ẩn danh, ma trận quyền đọc hàng đợi KYC và trọng tài,
retry idempotent, lỗi phân trang và audit đọc. Ma trận quyền ghi đầy đủ, các kiểm thử đối kháng,
các nhánh lỗi cọc, seller im lặng/contest có lỗi và D92 vẫn chưa phủ.

## Danh mục case

Các kỳ vọng quyền đọc áp dụng theo data-governance §5.4.

| Endpoint | BUYER | SELLER | INSPECTOR | OPERATOR | ARBITRATOR | ADMIN |
| --- | --- | --- | --- | --- | --- | --- |
| GET KYC queue | 403 | 403 | 403 | 200 | 403 | 403 |
| GET arbitration queue | 403 | 403 | 403 | 403 | 200 | 403 |

| TC ID | Mô tả | Kiểu |
| --- | --- | --- |
| [BF01-010](bf01-onboarding-kyc/010-register-buyer.bru) | Register buyer profile | IT-GW |
| [BF01-020](bf01-onboarding-kyc/020-verify-email-buyer.bru) | Confirm buyer email | IT-GW |
| [BF01-030](bf01-onboarding-kyc/030-review-buyer.bru) | Operator opens buyer KYC profile | IT-GW |
| [BF01-040](bf01-onboarding-kyc/040-verify-buyer.bru) | Operator marks buyer KYC VERIFIED | IT-GW |
| [BF01-060](bf01-onboarding-kyc/060-register-seller.bru) | Register seller profile | IT-GW |
| [BF01-070](bf01-onboarding-kyc/070-verify-email-seller.bru) | Confirm seller email | IT-GW |
| [BF01-080](bf01-onboarding-kyc/080-review-seller.bru) | Operator opens seller KYC profile | IT-GW |
| [BF01-090](bf01-onboarding-kyc/090-reject-seller.bru) | Operator marks seller KYC REJECTED | IT-GW |
| [BF02-010](bf02-plot-listing/010-coffee-category.bru) | Resolve an approved coffee category | IT-GW |
| [BF02-020](bf02-plot-listing/020-create-product.bru) | Create a Robusta product | IT-GW |
| [BF02-030](bf02-plot-listing/030-register-plot.bru) | Register one manual land plot | IT-GW |
| [BF02-040](bf02-plot-listing/040-plot-eligible.bru) | Wait for the plot geo check | IT-GW |
| [BF02-050](bf02-plot-listing/050-create-listing.bru) | Create an available listing | IT-GW |
| [BF02-060](bf02-plot-listing/060-edit-listing.bru) | Update quantity and price | IT-GW |
| [BF02-070](bf02-plot-listing/070-public-listing.bru) | Read public listing through the gateway | IT-GW |
| [BF03-010](bf03-signing-activation/happy/010-create-offer.bru) | Create a coffee offer | IT-GW |
| [BF03-020](bf03-signing-activation/happy/020-reservation.bru) | Confirm listing quantity is reserved | IT-GW |
| [BF03-030](bf03-signing-activation/happy/030-edit-terms.bru) | Revise the offered unit price | IT-GW |
| [BF03-040](bf03-signing-activation/happy/040-initiate-buyer.bru) | Request signing code as buyer | IT-GW |
| [BF03-050](bf03-signing-activation/happy/050-sign-buyer.bru) | Sign offer as buyer | IT-GW |
| [BF03-060](bf03-signing-activation/happy/060-initiate-seller.bru) | Request signing code as seller | IT-GW |
| [BF03-070](bf03-signing-activation/happy/070-sign-seller.bru) | Sign offer as seller | IT-GW |
| [BF03-080](bf03-signing-activation/happy/080-active.bru) | Wait for ACTIVE | IT-GW |
| [BF03-090](bf03-signing-activation/happy/090-deposit-ledger.bru) | Verify the two deposit locks | IT-DIRECT |
| [BF03-110](bf03-signing-activation/unhappy/010-create-offer.bru) | Create a coffee offer | IT-GW |
| [BF03-120](bf03-signing-activation/unhappy/020-reservation.bru) | Confirm listing quantity is reserved | IT-GW |
| [BF03-130](bf03-signing-activation/unhappy/030-withdraw.bru) | Withdraw the unsigned offer | IT-GW |
| [BF03-140](bf03-signing-activation/unhappy/040-withdrawn.bru) | Confirm WITHDRAWN | IT-GW |
| [BF03-150](bf03-signing-activation/unhappy/050-release-reservation.bru) | Verify the reservation is released | IT-GW |
| [BF03-160](bf03-signing-activation/unhappy/060-withdrawal-ledger.bru) | Confirm withdrawal left no locked money | IT-DIRECT |
| [BF04-010](bf04-milestone-delivery-settlement/clean/010-funded.bru) | Wait for a locked milestone | IT-GW |
| [BF04-015](bf04-milestone-delivery-settlement/clean/015-funding-ledger.bru) | Confirm the batch lock | IT-DIRECT |
| [BF04-017](bf04-milestone-delivery-settlement/clean/017-audit-signature.bru) | Assert audit record contract.signed | IT-DIRECT |
| [BF04-020](bf04-milestone-delivery-settlement/clean/020-upload-seller.bru) | Upload evidence as seller | IT-GW |
| [BF04-030](bf04-milestone-delivery-settlement/clean/030-weigh.bru) | Record seller weight | IT-GW |
| [BF04-035](bf04-milestone-delivery-settlement/clean/035-audit-seller-weight.bru) | Assert audit record milestone.seller_weighed | IT-DIRECT |
| [BF04-040](bf04-milestone-delivery-settlement/clean/040-upload-buyer.bru) | Upload evidence as buyer | IT-GW |
| [BF04-050](bf04-milestone-delivery-settlement/clean/050-receive.bru) | Record buyer weight | IT-GW |
| [BF04-055](bf04-milestone-delivery-settlement/clean/055-audit-buyer-receipt.bru) | Assert audit record milestone.buyer_received | IT-DIRECT |
| [BF04-060](bf04-milestone-delivery-settlement/clean/060-confirm.bru) | Buyer confirms clean receipt | IT-GW |
| [BF04-065](bf04-milestone-delivery-settlement/clean/065-audit-confirmation.bru) | Assert audit record milestone.buyer_confirmed | IT-DIRECT |
| [BF04-070](bf04-milestone-delivery-settlement/clean/070-settled.bru) | Wait for clean settlement | IT-GW |
| [BF04-085](bf04-milestone-delivery-settlement/clean/085-audit-settled.bru) | Assert audit record milestone.settled | IT-DIRECT |
| [BF04-090](bf04-milestone-delivery-settlement/clean/090-confirmation-mail.bru) | Verify WEIGHED and CONFIRMED mail recipients | IT-GW |
| [BF04-091](bf04-milestone-delivery-settlement/clean/091-ledger-before-replay.bru) | Record the final ledger before retrying confirmation | IT-DIRECT |
| [BF04-092](bf04-milestone-delivery-settlement/clean/092-replay-confirmation.bru) | Retry clean confirmation with its original idempotency key | IT-GW |
| [BF04-093](bf04-milestone-delivery-settlement/clean/093-ledger-after-replay.bru) | Confirm the exact retry leaves the bank ledger intact | IT-DIRECT |
| [BF04-110](bf04-milestone-delivery-settlement/dispute/010-funded.bru) | Wait for a locked milestone | IT-GW |
| [BF04-115](bf04-milestone-delivery-settlement/dispute/015-funding-ledger.bru) | Confirm the batch lock | IT-DIRECT |
| [BF04-117](bf04-milestone-delivery-settlement/dispute/017-audit-signature.bru) | Assert audit record contract.signed | IT-DIRECT |
| [BF04-120](bf04-milestone-delivery-settlement/dispute/020-upload-seller.bru) | Upload evidence as seller | IT-GW |
| [BF04-130](bf04-milestone-delivery-settlement/dispute/030-weigh.bru) | Record seller weight | IT-GW |
| [BF04-135](bf04-milestone-delivery-settlement/dispute/035-audit-seller-weight.bru) | Assert audit record milestone.seller_weighed | IT-DIRECT |
| [BF04-140](bf04-milestone-delivery-settlement/dispute/040-upload-buyer.bru) | Upload evidence as buyer | IT-GW |
| [BF04-150](bf04-milestone-delivery-settlement/dispute/050-receive.bru) | Record buyer weight | IT-GW |
| [BF04-155](bf04-milestone-delivery-settlement/dispute/055-audit-buyer-receipt.bru) | Assert audit record milestone.buyer_received | IT-DIRECT |
| [BF04-160](bf04-milestone-delivery-settlement/dispute/060-flag.bru) | Buyer flags a quality issue | IT-GW |
| [BF04-170](bf04-milestone-delivery-settlement/dispute/070-contest.bru) | Seller contests the reported quality defect | IT-GW |
| [BF04-175](bf04-milestone-delivery-settlement/dispute/075-audit-breach.bru) | Assert audit record breach.reported | IT-DIRECT |
| [BF04-180](bf04-milestone-delivery-settlement/dispute/080-inspection-file.bru) | Assigned inspector uploads report evidence | IT-GW |
| [BF04-190](bf04-milestone-delivery-settlement/dispute/090-inspection-report.bru) | Inspector submits a Level 1.5 report | IT-GW |
| [BF04-195](bf04-milestone-delivery-settlement/dispute/095-audit-inspection.bru) | Assert audit record inspection.report_confirmed | IT-DIRECT |
| [BF04-200](bf04-milestone-delivery-settlement/dispute/100-computed-remedy.bru) | Arbitrator reads the computed quality remedy | IT-GW |
| [BF04-210](bf04-milestone-delivery-settlement/dispute/110-review.bru) | Arbitrator opens the breach review | IT-GW |
| [BF04-220](bf04-milestone-delivery-settlement/dispute/120-resolve.bru) | Arbitrator finalizes the no-fault remedy | IT-GW |
| [BF04-225](bf04-milestone-delivery-settlement/dispute/125-audit-remedy.bru) | Assert audit record remedy.finalized | IT-DIRECT |
| [BF04-230](bf04-milestone-delivery-settlement/dispute/130-audit-disputed-completion.bru) | Confirm the disputed contract settled without a clean settlement event | IT-DIRECT |
| [BF04-240](bf04-milestone-delivery-settlement/dispute/140-dispute-ledger.bru) | Verify seller proceeds and buyer refund | IT-DIRECT |
| [BF04-250](bf04-milestone-delivery-settlement/clean/095-auto-confirm.bru) | Advance a separate fixture receipt and wait for system confirmation | IT-GW |
| [BF04-310](bf04-milestone-delivery-settlement/completion/010-settled-contract.bru) | Wait for final contract settlement | IT-GW |
| [BF04-320](bf04-milestone-delivery-settlement/completion/020-deposit-return-ledger.bru) | Verify the seller was paid and both deposits returned | IT-DIRECT |
| [BF04-330](bf04-milestone-delivery-settlement/completion/030-final-audit-records.bru) | Verify completion events in the party audit trail | IT-DIRECT |
| [BF04-340](bf04-milestone-delivery-settlement/completion/040-verify-audit.bru) | Verify the final chain is intact | IT-DIRECT |
| [BF04-350](bf04-milestone-delivery-settlement/completion/050-anchor-queues.bru) | Check anchor queue topology and empty DLQ | IT-DIRECT |
| [BF04-360](bf04-milestone-delivery-settlement/completion/060-anchor-binding.bru) | Check anchor routing and retired flag binding | IT-DIRECT |
| [BF04-390](bf04-milestone-delivery-settlement/completion/070-party-audit-gateway.bru) | Read the final party audit trail through the gateway | IT-GW |
| [BF05-010](bf05-breach-force-majeure/010-arbitration-queue.bru) | Read the Arbitrator breach queue | IT-GW |
| [BF07-010](bf07-emergency-security-lock/010-public-lock-summary.bru) | Read a fixture account lock summary | IT-GW |
| [SEC-010](../security/authn/010-authenticated-profile.bru) | A legitimate Buyer session gets its own profile | IT-GW |
| [SEC-020](../security/authn/020-anonymous-profile.bru) | A protected profile requires an authenticated session | IT-GW |
| [SEC-040](../security/authz-role-matrix/040-kyc-queue-buyer.bru) | BUYER reads kyc queue | IT-GW |
| [SEC-041](../security/authz-role-matrix/041-kyc-queue-seller.bru) | SELLER reads kyc queue | IT-GW |
| [SEC-042](../security/authz-role-matrix/042-kyc-queue-inspector.bru) | INSPECTOR reads kyc queue | IT-GW |
| [SEC-043](../security/authz-role-matrix/043-kyc-queue-operator.bru) | OPERATOR reads kyc queue | IT-GW |
| [SEC-044](../security/authz-role-matrix/044-kyc-queue-arbitrator.bru) | ARBITRATOR reads kyc queue | IT-GW |
| [SEC-045](../security/authz-role-matrix/045-kyc-queue-admin.bru) | ADMIN reads kyc queue | IT-GW |
| [SEC-060](../security/authz-role-matrix/060-arbitration-queue-buyer.bru) | BUYER reads arbitration queue | IT-GW |
| [SEC-061](../security/authz-role-matrix/061-arbitration-queue-seller.bru) | SELLER reads arbitration queue | IT-GW |
| [SEC-062](../security/authz-role-matrix/062-arbitration-queue-inspector.bru) | INSPECTOR reads arbitration queue | IT-GW |
| [SEC-063](../security/authz-role-matrix/063-arbitration-queue-operator.bru) | OPERATOR reads arbitration queue | IT-GW |
| [SEC-064](../security/authz-role-matrix/064-arbitration-queue-arbitrator.bru) | ARBITRATOR reads arbitration queue | IT-GW |
| [SEC-065](../security/authz-role-matrix/065-arbitration-queue-admin.bru) | ADMIN reads arbitration queue | IT-GW |
| [SEC-080](../security/idempotency-replay/080-repeat-valid-offer.bru) | Retry the same valid offer with the original key | IT-GW |
| [SEC-090](../security/idempotency-replay/090-replayed-reservation.bru) | Read inventory after the retried offer | IT-GW |
| [SEC-100](../security/audit-evidence-log/100-verify-global-as-admin.bru) | Admin verifies the global audit chain | IT-GW |
| [SEC-110](../security/audit-evidence-log/110-verify-global-as-operator.bru) | Operator role is denied global verification | IT-GW |
| [SEC-130](../security/audit-evidence-log/120-party-view.bru) | Party reads the minimized trail | IT-DIRECT |
| [SEC-140](../security/audit-evidence-log/130-admin-view.bru) | Admin reads the same contract trail | IT-GW |
| [SEC-150](../security/audit-evidence-log/140-global-view.bru) | Admin read logging records one system event per read | IT-GW |
| [SEC-160](../security/audit-evidence-log/150-party-view-unchanged.bru) | Admin reads preserve the party trail | IT-DIRECT |
| [SEC-170](../security/audit-evidence-log/160-global-view-count.bru) | Verify exactly two audit records were appended | IT-GW |
| [SEC-180](../security/input-validation/010-negative-page.bru) | Reject a negative contract page index | IT-GW |
| [SEC-190](../security/info-leak/010-validation-envelope.bru) | Validation errors omit system internals | IT-GW |
