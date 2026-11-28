# System test cases through the web client (Report 5.3 from 28/11/2026): one entry per Playwright test of
# tests/st/*.st.ts. The key is the test-title prefix; results come from results/st-ui/results-chromium.json.
# Steps the web client has no screen for yet (Operator KYC decision, seller catalogue) go through the API and say so.
# The web client is in Vietnamese: labels and messages on screen are quoted as shown, with their meaning in brackets.

SHEETS = [
    # sheet, module, description (Test Cases), pre-condition (Test Cases)
    ("BF01_Onboarding", "BF01",
     "BF-01 User Onboarding & KYC through the screens: sign up on the Keycloak page (choosing a role), complete the "
     "organization profile, verify the e-mail with the code from the mail, see the KYC result; the Operator's KYC "
     "decision goes through the API (no screen yet)",
     "Stack running; a new e-mail on every run (st-buyer-<ms>@test.com, st-seller-<ms>@test.com); MailHog on 8025"),
    ("BF02_Marketplace", "BF02",
     "BF-02 Plot & Listing Publication as the marketplace shows it: the seller publishes a coffee listing (API, no "
     "seller screen yet), a Guest finds and reads it without signing in, and is sent to sign in to make an offer",
     "seller1 VERIFIED (seed); product-service checks the plot at once (overlay PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0)"),
    ("BF03_Signing", "BF03",
     "BF-03 Contract Signing & Activation through the screens: the buyer offers from the listing page, the seller "
     "revises the price, both sign with the OTP from their mail, the contract turns ACTIVE once escrow locks the "
     "deposits; branch: the offer withdrawn before the second signature",
     "buyer1, seller1 VERIFIED with mock bank accounts; a new listing of 5,000 kg at 96,000 VND/kg for each flow"),
    ("BF04_Milestone", "BF04",
     "BF-04 Milestone Delivery & Settlement through the screens, continuing the BF-03 chain (same contract, same "
     "sessions): the seller weighs with a photo, the buyer weighs on receipt and confirms clean, the milestone and the "
     "contract settle",
     "The ACTIVE contract of ST-BF03-060 with one 1,000 kg milestone funded at activation (only on a business day)"),
    ("RolePortals", "Roles",
     "Admin, Operator and Arbitrator screens and role isolation without fallback (data-governance §5.4): evidence log "
     "and hash-chain check, resending failed money orders, dispute queue, routes refused to the wrong role",
     "admin1, operator1, arbitrator1, buyer1 (Report 5.0 §II.1.1); ADM-020 creates and withdraws one offer through the "
     "API so the contract has a trail"),
]

PRE_STACK = "ST through the web client (Playwright Chromium 1366x900, signing in on the real Keycloak page)"

# test-title prefix -> sheet, group, pre-condition, steps, expected, type, observed when passed
CASES = [
    # ── BF-01 ──
    ("ST-BF01-010", "BF01_Onboarding", "Buyer signs up and is approved", "Guest without an account",
     "/register → 'Đăng ký' (Sign up) → Keycloak page: e-mail st-buyer-<ms>@test.com, role 'Người mua' (Buyer), a "
     "password meeting the realm policy → 'Tạo tài khoản' (Create account)",
     "Lands on /onboarding, heading 'Hoàn tất hồ sơ doanh nghiệp' (Complete the organization profile), role Buyer "
     "already selected", "Normal",
     "Landed on /onboarding, profile form shown with Buyer selected"),
    ("ST-BF01-020", "BF01_Onboarding", None, "Runs after ST-BF01-010",
     "Organization name, phone 0901000111, address → 'Gửi hồ sơ doanh nghiệp' (Submit the organization profile)",
     "Lands on /account with the banner 'Hồ sơ đang chờ Operator xác minh' (Profile waiting for Operator "
     "verification), status 'Chờ xác minh' (Pending verification)", "Normal",
     "/account shows the pending banner, status Pending verification"),
    ("ST-BF01-030", "BF01_Onboarding", None, "Runs after ST-BF01-020",
     "Read the 6-digit code of the 'email verification code' mail in MailHog, type it into the e-mail verification "
     "boxes → 'Xác minh email' (Verify e-mail)",
     "The OTP e-mail section turns 'Đã xác minh' (Verified)", "Normal", "OTP e-mail: Verified"),
    ("ST-BF01-040", "BF01_Onboarding", None, "Runs after ST-BF01-030",
     "Operator verifies the KYC through the API (POST /api/v1/admin/users/{id}/verify, no review screen yet); the "
     "buyer reloads /account",
     "The pending banner disappears, profile status Verified", "Normal",
     "Within 60 s the banner disappeared, profile Verified"),
    ("ST-BF01-050", "BF01_Onboarding", None, "Runs after ST-BF01-040",
     "Wait for mail to the buyer's e-mail in MailHog",
     "A KYC result mail arrives besides the e-mail verification code", "Normal", "KYC result mail found in MailHog"),
    ("ST-BF01-060", "BF01_Onboarding", "Seller signs up and is rejected", "Guest without an account",
     "Sign up with role 'Người bán' (Seller), submit the profile, verify the e-mail with the code from the mail",
     "Role Seller selected; after submitting the profile the status is Pending verification", "Normal",
     "Seller selected, status Pending verification"),
    ("ST-BF01-070", "BF01_Onboarding", None, "Runs after ST-BF01-060",
     "Operator rejects the KYC through the API with the reason 'Thiếu giấy phép kinh doanh' (Business licence "
     "missing); the seller reloads /account",
     "The screen says 'Hồ sơ chưa được chấp thuận' (Profile not accepted), status Not accepted; the reason comes in "
     "the mail to the seller (SRS BF-01: the result is sent by e-mail)", "Abnormal",
     "Profile not accepted shown; the rejection mail carries the exact reason"),
    # ── BF-02 ──
    ("ST-BF02-010", "BF02_Marketplace", "Listing published on the marketplace", "seller1 VERIFIED",
     "API: COFFEE category (Operator approves one if missing), product Robusta ST <id>, a Dak Lak plot (wait for the "
     "geo check), listing 5,000 kg, minimum 100 kg, 96000.00 VND/kg",
     "The listing is created and has a listingId", "Normal", "listingId returned as a UUID"),
    ("ST-BF02-020", "BF02_Marketplace", None, "Runs after ST-BF02-010; browser not signed in",
     "Open /listing", "Heading 'Sàn nguồn hàng' (Marketplace) with at least one listing card", "Normal",
     "Marketplace shows the listings"),
    ("ST-BF02-030", "BF02_Marketplace", None, "Runs after ST-BF02-020",
     "Open /listings/{listingId}",
     "Product name, 'Đang mở bán' (Open for sale), 96.000 ₫/kg, 5.000 kg, Robusta; no e-mail, user name or phone "
     "number of the seller (SRS IV.2.3)", "Normal", "Full listing data, no contact data shown"),
    ("ST-BF02-040", "BF02_Marketplace", None, "Runs after ST-BF02-030",
     "Guest clicks 'Gửi đề nghị' (Make an offer)", "Sent to sign in (Keycloak page or /login)", "Abnormal",
     "Sent to the sign-in page"),
    # ── BF-03 + BF-04 (one serial chain) ──
    ("ST-BF03-010", "BF03_Signing", "Offer → negotiation → signing → ACTIVE", "buyer1, seller1 signed in; a new listing",
     "buyer1: /listings/{id} → 'Gửi đề nghị' (Make an offer) → quantity 1000, delivery date = today + 7 business days "
     "(the milestone funding date is today) → 'Gửi đề nghị'",
     "Lands on /contracts/{id}, contract details, status 'Đã gửi đề nghị' (Offered)", "Normal",
     "Contract created, status Offered"),
    ("ST-BF03-020", "BF03_Signing", None, "Runs after ST-BF03-010",
     "Reopen the listing page", "Available quantity down to 4.000 kg (reserved by event)", "Normal",
     "Available 4.000 kg"),
    ("ST-BF03-030", "BF03_Signing", None, "Runs after ST-BF03-020",
     "seller1 opens the contract → proposed unit price 95000.00 → 'Xem lại thay đổi' (Review changes) → 'Lưu điều "
     "khoản' (Save terms)",
     "'Đã lưu điều khoản mới' (New terms saved), unit price 95.000 ₫, the terms fingerprint changes", "Normal",
     "Saved, price 95.000 ₫, fingerprint changed"),
    ("ST-BF03-040", "BF03_Signing", None, "Runs after ST-BF03-030",
     "buyer1: tick the consent → 'Gửi mã OTP' (Send OTP) → read the code of the 'contract signing code' mail (the mail "
     "names the contractId) → enter it → 'Xác nhận chữ ký' (Confirm signature)",
     "'Máy chủ đã xác nhận chữ ký của bạn' (The server confirmed your signature)", "Normal",
     "Buyer signature confirmed"),
    ("ST-BF03-050", "BF03_Signing", None, "Runs after ST-BF03-040; today is a business day",
     "seller1 signs with the OTP as in ST-BF03-040", "Seller signature confirmed, contract SIGNED", "Normal", None),
    ("ST-BF03-060", "BF03_Signing", None, "Runs after ST-BF03-050",
     "Wait for escrow to lock the deposits, reload the contract page of both parties",
     "Both parties see 'Đang hiệu lực' (Active)", "Normal", None),
    ("ST-BF04-010", "BF04_Milestone", "Clean milestone: weigh → receive → confirm → settle",
     "The ACTIVE contract of ST-BF03-060",
     "Open the contract", "Milestone 1 (the only one), status funded or preparing the goods", "Normal", None),
    ("ST-BF04-020", "BF04_Milestone", None, "Runs after ST-BF04-010",
     "seller1: milestone page → 'Cân hàng trước khi xe chạy' (Weigh before dispatch): 1000 kg, weighing photo (wait "
     "for the virus scan to show 'Sẵn sàng', Ready) → review → consent checkbox → 'Xác nhận đã cân' (Confirm weighed)",
     "Milestone becomes 'Đã cân, đang vận chuyển' (Weighed, in transit)", "Normal", None),
    ("ST-BF04-030", "BF04_Milestone", None, "Runs after ST-BF04-020",
     "buyer1: the milestone page shows the seller's 1.000 → received weight 998 kg + photo → 'Ghi nhận cân nhận hàng' "
     "(Record received weight)",
     "Milestone becomes 'Đã nhận, chờ xác nhận' (Received, awaiting confirmation)", "Normal", None),
    ("ST-BF04-040", "BF04_Milestone", None, "Runs after ST-BF04-030 (0.2% short < 5% tolerance)",
     "buyer1: 'Hàng đạt, xác nhận đúng' (Goods conform) → checkbox → 'Xác nhận đúng và tất toán' (Confirm and settle)",
     "Milestone 'Đã tất toán' (Settled)", "Normal", None),
    ("ST-BF04-050", "BF04_Milestone", None, "Runs after ST-BF04-040",
     "Reload the contract page of both parties",
     "Contract Settled once escrow returns the deposits and reports contract_lock_cleared (D94)", "Normal", None),
    ("ST-BF03-110", "BF03_Signing", "Offer withdrawn before the second signature", "buyer1 signed in; a new listing",
     "Offer 1000 kg, delivery today + 12 business days", "Available quantity down to 4.000 kg", "Normal",
     "Contract created, available 4.000 kg"),
    ("ST-BF03-120", "BF03_Signing", None, "Runs after ST-BF03-110",
     "'Rút đề nghị' (Withdraw offer) → dialog 'Rút đề nghị hợp đồng?' → 'Rút đề nghị'",
     "Status 'Đã rút đề nghị' (Withdrawn)", "Normal", "Status Withdrawn"),
    ("ST-BF03-130", "BF03_Signing", None, "Runs after ST-BF03-120",
     "Reopen the listing page; read the contract through the API",
     "Available back to 5.000 kg; contract WITHDRAWN", "Normal", "Available 5.000 kg, status WITHDRAWN"),
    # ── role portals ──
    ("ST-ADM-010", "RolePortals", "Admin: evidence log", "admin1 signed in",
     "/admin/audit → 'Kiểm tra toàn chuỗi' (Check the whole chain)",
     "Heading 'Nhật ký bằng chứng' (Evidence log), notice that every opening is recorded; result 'Toàn chuỗi nguyên "
     "vẹn' (Whole chain intact)", "Normal", "Whole chain intact"),
    ("ST-ADM-020", "RolePortals", None, "An offer just created and withdrawn through the API (contract.terminated trail)",
     "Tab 'Theo hợp đồng' (By contract) → contract UUID → 'Tra cứu' (Look up)",
     "Shows 'Mã bằng chứng hiện tại' (Current evidence hash) and 'Nguyên vẹn' (Intact); the SYSTEM log has an "
     "audit.viewed record for this contract", "Normal", "Trail intact, audit.viewed recorded"),
    ("ST-OPR-010", "RolePortals", "Operator: resend money orders", "operator1 signed in",
     "/operations/remedy → contract id 'not-a-uuid', then a random UUID → 'Gửi lại lệnh tiền lỗi' (Resend failed "
     "money orders)",
     "Bad id: 'Nhập mã hợp đồng đầy đủ.' (Enter the full contract id) and the button disabled; a UUID without failed "
     "orders: a result message", "Abnormal", "Bad id blocked; unknown UUID answered with a message"),
    ("ST-ARB-010", "RolePortals", "Arbitrator: dispute queue", "arbitrator1 signed in",
     "/disputes", "Heading 'Tranh chấp chờ duyệt' (Disputes awaiting review); cases or the empty state", "Normal",
     "Queue loaded from the real backend"),
    ("ST-SEC-010", "RolePortals", "Role isolation", "admin1, buyer1 signed in",
     "admin1 opens /disputes, /operations/remedy; buyer1 opens /admin/audit, /disputes, /operations/remedy",
     "Every route of another role shows 'Tài khoản chưa có vai trò phù hợp để truy cập trang này.' (This account has "
     "no role for this page; ADMIN does not stand in for OPERATOR/ARBITRATOR)", "Abnormal",
     "All 5 routes refused with the right message"),
]
