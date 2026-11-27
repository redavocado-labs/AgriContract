"""SRS Part III screen wireframes (Report 3, section III), one PNG per screen.

Layout follows the real frontend (screenshots in real/), simplified to grayscale wireframes.
Screens not yet built in the frontend follow the SRS scope and the same layout.

Run:  python screens.py            -> all screens
      python screens.py 1 2 30     -> only these screen numbers
"""
import sys
from pathlib import Path

from wire import render

OUT = Path(__file__).resolve().parent

SCREENS = {}


def screen(no, slug, **spec):
    SCREENS[no] = (slug, spec)


# ======================================================== 1. User Authentication & Account
screen(1, "register_account", shell="auth", blocks=[
    ("text", "Create account", "t", "#222222"),
    ("text", "Register with your email and password."),
    ("fields", [("!Email *", ""), ("!Password *", ""), ("!Confirm password *", ""),
                ("!Account type *", "v:Buyer / Seller")], 1),
    ("banner", "Creating an account does not mean the organization profile is approved."),
    ("buttons", ["*Create account"]),
    ("text", "Already have an account?  Log in"),
])

screen(2, "login", shell="auth", blocks=[
    ("text", "Log in", "t", "#222222"),
    ("text", "Sign in to the AgriContract identity provider."),
    ("fields", [("!Email *", ""), ("!Password *", "")], 1),
    ("check", "Remember me"),
    ("buttons", ["*Log in"]),
    ("text", "Register  ·  Forgot password?"),
])

screen(3, "forgot_password", shell="auth", blocks=[
    ("text", "Forgot password", "t", "#222222"),
    ("text", "Enter the registered email. A reset link will be sent to this address."),
    ("fields", [("!Email *", "")], 1),
    ("buttons", ["*Send reset link"]),
    ("text", "Back to Log in"),
])

screen(4, "update_password", shell="auth", blocks=[
    ("text", "Update password", "t", "#222222"),
    ("text", "Set a new password for your account."),
    ("fields", [("!New password *", ""), ("!Confirm new password *", "")], 1),
    ("check", "Sign out from other devices", True),
    ("buttons", ["*Save password"]),
])

screen(5, "organization_onboarding", shell="app", role="BUYER", active="Profile", crumb="Account / Onboarding",
       blocks=[
           ("title", "Complete organization profile"),
           ("text", "Add your organization details so the Operator can review the KYC profile."),
           ("tabs", ["Profile & legal", "Organization documents"], 0),
           ("card", "Organization information", [
               ("text", "Account type *", "s"),
               ("radio", ["Buyer", "Seller"], 0),
               ("banner", "The account type must match the role assigned to your account."),
               ("fields", [("!Organization name *", ""), ("Tax ID *", ""), ("Contact email *", "buyer1@test.com"),
                           ("Phone", ""), ("Representative", ""), ("!Address", "ta:")]),
           ]),
           ("card", "Organization documents", [
               ("text", "Business registration and authorization letter (PDF, up to 10 MB)."),
               ("upload", "Select documents from device"),
           ]),
           ("buttons", ["*Submit organization profile"]),
       ])

screen(6, "my_profile", shell="app", role="SELLER", active="Profile", crumb="Account / Settings", blocks=[
    ("title", "Account settings"),
    ("text", "Ea Kar Coffee Coop"),
    ("tabs", ["Profile & legal", "Documents", "Security"], 0),
    ("card", "Organization profile", [
        ("text", "Information is provided by the system. Contact the Operator to change it."),
        ("fields", [("Organization name", "Ea Kar Coffee Coop"), ("Email", "seller1@test.com"),
                    ("Role", "Seller"), ("Phone", "0907654321"), ("!Address", "456 Hung Vuong, Ea Kar, Dak Lak"),
                    ("KYC status", "Verified")]),
    ], "VERIFIED"),
    ("card", "OTP email", [("text", "seller1@test.com - contract signing OTPs and notifications are sent here.")],
     "Verified"),
    ("card", "Organization documents", [("upload", "Select documents from device")]),
    ("card", "Account security", [("text", "A recent login is required for signing and evidence actions."),
                                  ("buttons", ["Re-authenticate"])]),
    ("banner", "Trading restriction: actions that need eligibility are blocked until KYC is VERIFIED."),
])

screen(7, "public_user_profile", shell="public", active="Marketplace", crumb="Marketplace / Seller profile",
       blocks=[
           ("title", "Ea Kar Coffee Coop", "SELLER  ·  VERIFIED"),
           ("text", "Public profile. Contact details are not shown."),
           ("cols", [
               [("card", "Reputation", [("text", "82 / 100", "t", "#222222"),
                                        "Trading lock: None", "Completed contracts: 14"])],
               [("card", "Organization", ["Role: Seller", "Verification: [VERIFIED]", "Member since: 03/2026"])],
           ]),
           ("card", "Lock history", [("table", ["From", "To", "Reason", "Status"],
                                      [["02/05/2026", "16/05/2026", "Funding breach", "[EXPIRED]"],
                                       ["11/01/2026", "18/01/2026", "Late delivery", "[EXPIRED]"]])]),
       ])


# ======================================================== 2. Account Administration
KYC_LIST = [("title", "KYC review queue"),
            ("filters", ["Search organization or tax ID", "Status: PENDING  v", "Role: All  v"], "Search"),
            ("table", ["Organization", "Tax ID", "Role", "Email", "Submitted", "Status", ""],
             [["Ea Kar Coffee Coop", "6001234567", "SELLER", "Verified", "25/11/2026", "[PENDING]", "<Open>"],
              ["Mekong Export JSC", "0312345678", "BUYER", "Verified", "24/11/2026", "[PENDING]", "<Open>"],
              ["Tay Nguyen Coffee Co.", "6009876543", "SELLER", "Verified", "22/11/2026", "[PENDING]", "<Open>"]])]
screen(8, "kyc_review_queue", shell="app", role="OPERATOR", active="KYC Review", crumb="Users & KYC / Review queue",
       blocks=KYC_LIST)

KYC_DETAIL = [
    ("title", "Ea Kar Coffee Coop", "PENDING", ["Reject", "*Verify"]),
    ("cols", [
        [("card", "Organization", ["Tax ID: 6001234567", "Role: SELLER", "Address: Ea Kar, Dak Lak"]),
         ("card", "Contact & representative", ["Contact email: seller1@test.com  (verified)", "Phone: 0907654321",
                                               "Representative: Nguyen Van A", "Position: Director"])],
        [("card", "Uploaded documents", [("table", ["File", "Type", ""],
                                          [["business_reg.pdf", "Registration", "<View>"],
                                           ["authorization.pdf", "Authorization", "<View>"]])]),
         ("card", "Decision history", [("table", ["Time", "By", "Decision"],
                                        [["25/11/2026 09:10", "System", "SUBMITTED"]])])],
    ]),
]
screen(9, "kyc_detail", shell="app", role="OPERATOR", active="KYC Review", crumb="Users & KYC / KYC detail",
       blocks=KYC_DETAIL)

screen(10, "verify_kyc_dialog", shell="app", role="OPERATOR", active="KYC Review", crumb="Users & KYC / KYC detail",
       base=KYC_DETAIL, dialog=dict(title="Verify KYC", blocks=[
           ("text", "Ea Kar Coffee Coop - status will change from PENDING to VERIFIED."),
           ("radio", ["Authorization expires on", "Indefinite authorization"], 0),
           ("fields", [("!Authorization expiry date *", "31/12/2027")], 1),
       ], buttons=["Cancel", "*Verify profile"]))

screen(11, "reject_kyc_dialog", shell="app", role="OPERATOR", active="KYC Review", crumb="Users & KYC / KYC detail",
       base=KYC_DETAIL, dialog=dict(title="Reject KYC", blocks=[
           ("text", "Ea Kar Coffee Coop - status will change from PENDING to REJECTED. The reason is emailed to the user."),
           ("fields", [("!Rejection reason *", "ta:")], 1),
       ], buttons=["Cancel", "*Reject profile"]))

REP_LIST = [("title", "Reputation action requests", None, ["*Propose action"]),
            ("filters", ["Status: PROPOSED  v", "Action: All  v"]),
            ("table", ["Target", "Action", "Reason", "Proposed by", "Status", ""],
             [["Tay Nguyen Coffee Co.", "LOCK 14 days", "Repeated late delivery", "operator1", "[PROPOSED]",
               "<Approve>"],
              ["Mekong Export JSC", "UNLOCK", "Funding cured", "operator1", "[PROPOSED]", "<Reject>"],
              ["Ea Kar Coffee Coop", "LOCK 7 days", "Funding breach", "operator1", "[APPROVED]", ""]]),
            ("banner", "The proposer cannot approve or reject their own request (MSG16).")]
screen(12, "reputation_action_requests", shell="app", role="ARBITRATOR", active="Reputation Requests",
       crumb="Reputation / Action requests", blocks=REP_LIST)

screen(13, "propose_reputation_action_dialog", shell="app", role="OPERATOR", active="Reputation Requests",
       crumb="Reputation / Action requests", base=REP_LIST, dialog=dict(title="Propose reputation action", blocks=[
           ("fields", [("!Target user *", "v:Tay Nguyen Coffee Co."), ("Action *", "v:LOCK"),
                       ("Duration (days)", "14"), ("!Reason *", "ta:")]),
           ("banner", "An Arbitrator must approve this proposal (MSG17)."),
       ], buttons=["Cancel", "*Submit proposal"]))

# ======================================================== 3. Plot & Listing
PLOTS = [("title", "My plots", None, ["Import KML", "Add GPS plot", "Upload cadastral"]),
         ("cols", [
             [("placeholder", 300, "Map: plot boundaries")],
             [("table", ["Plot", "Province", "Area", "Verification", "Geo risk", ""],
               [["EK-01", "Dak Lak", "2.1 ha", "[SELF_DECLARED]", "[LOW]", ""],
                ["EK-02", "Dak Lak", "1.4 ha", "[CADASTRAL]", "[LOW]", ""],
                ["EK-03", "Dak Lak", "0.8 ha", "[SELF_DECLARED]", "[HIGH_RISK]", "<Re-evaluate>"],
                ["EK-04", "Dak Lak", "-", "[POINT]", "[INCONCLUSIVE]", "<Re-evaluate>"]])],
         ], [2, 3])]
screen(14, "my_plots", shell="app", role="SELLER", active="My Plots", crumb="Plots / My plots", blocks=PLOTS)

screen(15, "import_kml_dialog", shell="app", role="SELLER", active="My Plots", crumb="Plots / My plots", base=PLOTS,
       dialog=dict(title="Import KML", w=640, blocks=[
           ("fields", [("!Province for all placemarks *", "v:Dak Lak")], 1),
           ("upload", "Select KML file", ".kml, up to 5 MB"),
           ("table", ["Placemark", "Type", "Area (ha)", "Result"],
            [["EK-05", "Polygon", "1.9", "[VALID]"], ["EK-06", "Point", "[enter area]", "[NEED AREA]"],
             ["EK-01", "Polygon", "2.1", "[EXISTS - UPDATE]"], ["X-1", "Line", "-", "[INVALID - SKIP]"]]),
       ], buttons=["Cancel", "*Import valid placemarks"]))

screen(16, "add_plot_by_gps_pin_dialog", shell="app", role="SELLER", active="My Plots", crumb="Plots / My plots",
       base=PLOTS, dialog=dict(title="Add plot by GPS pin", blocks=[
           ("placeholder", 160, "Current position pin"),
           "Latitude / Longitude: 12.8102, 108.4521",
           "Accuracy: 25 m",
           ("banner", "Accuracy is worse than 20 m (MSG22). You can still save the point."),
           ("fields", [("Household label *", "Nguyen Van A"), ("Area (ha) *", "1.2")]),
       ], buttons=["Cancel", "*Save point plot"]))

screen(17, "upload_cadastral_extract_dialog", shell="app", role="SELLER", active="My Plots", crumb="Plots / My plots",
       base=PLOTS, dialog=dict(title="Upload cadastral extract", blocks=[
           ("fields", [("!Plot *", "v:EK-01  ·  Dak Lak  ·  2.1 ha")], 1),
           ("upload", "Select cadastral extract", "PDF, JPG or PNG, up to 10 MB"),
           ("text", "The plot becomes CADASTRAL_BACKED after the file passes the scan."),
       ], buttons=["Cancel", "*Upload"]))

screen(18, "request_geo_reevaluation_dialog", shell="app", role="SELLER", active="My Plots", crumb="Plots / My plots",
       base=PLOTS, dialog=dict(title="Request geo re-evaluation", blocks=[
           "Plot: EK-03  ·  [HIGH_RISK]",
           ("placeholder", 150, "Camera: take field photo"),
           ("text", "Only a photo taken now with the device camera is accepted. Its GPS location is compared "
                    "with the plot boundary."),
       ], buttons=["Cancel", "*Submit for re-evaluation"]))

screen(19, "plot_risk_review_queue", shell="app", role="OPERATOR", active="Plot Risk Review",
       crumb="Plots / Risk review", blocks=[
           ("title", "Plot risk review queue"),
           ("filters", ["Signal: All  v", "Province: All  v", "Status: OPEN  v"]),
           ("cols", [
               [("table", ["Plot", "Seller", "Signal", "Ratio", ""],
                 [["EK-03", "Ea Kar Coffee Coop", "[OVERLAP HIGH]", "64%", "<Open>"],
                  ["TN-11", "Tay Nguyen Coffee", "[YIELD ANOMALY]", "-", "<Open>"],
                  ["KB-02", "Krong Bong Coop", "[FOREST RISK]", "-", "<Open>"]])],
               [("card", "Case EK-03", [("placeholder", 140, "Map: both sellers' plots"),
                                        "Overlap ratio: 64%", "Verification: SELF_DECLARED / CADASTRAL",
                                        ("fields", [("!Decision note", "ta:")], 1),
                                        ("buttons", ["Request evidence", "Accept plot", "*Reject plot"])])],
           ], [3, 2]),
       ])

screen(20, "my_products", shell="app", role="SELLER", active="My Products", crumb="Products / My products", blocks=[
    ("title", "My products", None, ["Propose category", "*Create product"]),
    ("table", ["Product", "Category", "Commodity", "Variety", "Updated", ""],
     [["Robusta Ea Kar grade 1", "Green coffee / Robusta", "COFFEE", "Robusta", "20/11/2026", "<Edit>"],
      ["Arabica Cau Dat", "Green coffee / Arabica", "COFFEE", "Arabica", "12/11/2026", "<Edit>"]]),
    ("card", "Create product", [("fields", [("Name *", ""), ("Category *", "v:Green coffee / Robusta"),
                                            ("Variety", ""), ("Origin province", "v:Dak Lak"),
                                            ("!Description", "ta:")]),
                                ("buttons", ["Cancel", "*Save product"])]),
    ("text", "Editing a product does not change existing listings or contracts - they keep their snapshot."),
])

LISTINGS = [("title", "My listings", None, ["*Create listing"]),
            ("filters", ["Status: All  v", "Product: All  v"]),
            ("table", ["Listing", "Offered", "Available", "Min order", "Status", "", ""],
             [["Robusta Ea Kar grade 1", "120,000 kg", "90,000 kg", "5,000 kg", "[PUBLISHED]", "<Edit>", "<Pause>"],
              ["Arabica Cau Dat", "30,000 kg", "30,000 kg", "2,000 kg", "[DRAFT]", "<Edit>", "<Publish>"],
              ["Robusta honey", "20,000 kg", "0 kg", "2,000 kg", "[PAUSED]", "<Republish>", "<Close>"]])]
screen(21, "my_listings", shell="app", role="SELLER", active="My Listings", crumb="Listings / My listings",
       blocks=LISTINGS)

screen(22, "listing_form", shell="app", role="SELLER", active="My Listings", crumb="Listings / Create listing", blocks=[
    ("title", "Create listing"),
    ("cols", [
        [("card", "Listing details", [("fields", [("!Product *", "v:Robusta Ea Kar grade 1"),
                                                  ("Offered quantity (kg) *", "120000"),
                                                  ("Minimum order (kg) *", "5000"),
                                                  ("Moisture max (%)", "12.5"), ("Defects max (%)", "5"),
                                                  ("Screen size", "16"), ("Crop year", "2026")])]),
         ("card", "Linked plots (at least one)", [("check", "EK-01  ·  2.1 ha  ·  LOW", True),
                                                  ("check", "EK-02  ·  1.4 ha  ·  LOW", True),
                                                  ("check", "EK-03  ·  HIGH_RISK (cannot be linked)")])],
        [("card", "Reference price", ["Dak Lak · Robusta: 95,000 VND/kg", "Source: VNSAT · 26/11/2026",
                                      ("text", "Advisory only - not a contract price.", "s")])],
    ], [3, 2]),
    ("buttons", ["Cancel", "Save as draft", "*Publish"]),
])

LISTING_CARDS = [
    ("cols", [[("card", "Robusta Ea Kar", ["95,000 VND/kg", "Available: 90,000 kg", "Min order: 5,000 kg",
                                           "Seller reputation: 82"], "Open")],
              [("card", "Arabica Cau Dat", ["120,000 VND/kg", "Available: 30,000 kg", "Min order: 2,000 kg",
                                            "Seller reputation: 77"], "Open")],
              [("card", "Robusta Krong Bong", ["93,000 VND/kg", "Available: 15,000 kg", "Min order: 1,000 kg",
                                               "Seller reputation: 69"], "Open")]]),
]
screen(23, "browse_listings", shell="public", active="Marketplace", blocks=[
    ("title", "Marketplace"),
    ("banner", "Log in to see seller reputation and to create a contract offer."),
    ("cols", [
        [("card", "Categories", ["COFFEE", "- Green coffee / Robusta", "- Green coffee / Arabica",
                                 "- Roasted / Robusta"])],
        [("filters", ["Search name, province", "Province  v", "Quality  v", "Reputation  v"]),
         *LISTING_CARDS, *LISTING_CARDS],
    ], [1, 4]),
])

screen(24, "listing_detail", shell="public", active="Marketplace", crumb="Marketplace / Listing detail", blocks=[
    ("title", "Robusta Ea Kar grade 1", "OPEN", ["*Create offer"]),
    ("cols", [
        [("card", "Quantity & quality", [("kv", [("Offered", "120,000 kg"), ("Available", "90,000 kg"),
                                                 ("Minimum order", "5,000 kg"), ("Moisture max", "12.5 %"),
                                                 ("Defects max", "5 %"), ("Screen size", "16")], 2)]),
         ("card", "Linked plots", [("table", ["Plot", "Province", "Area", "Verification"],
                                    [["EK-01", "Dak Lak", "2.1 ha", "[CADASTRAL]"],
                                     ["EK-02", "Dak Lak", "1.4 ha", "[SELF_DECLARED]"]])])],
        [("card", "Seller", ["Ea Kar Coffee Coop", "Reputation: 82 / 100", "Trading lock: None",
                             ("buttons", ["View seller profile"])]),
         ("card", "Reference price", ["Dak Lak · Robusta: 95,000 VND/kg", ("text", "Advisory only.", "s")])],
    ], [3, 2]),
])

screen(25, "category_listing_moderation", shell="app", role="OPERATOR", active="Moderation",
       crumb="Catalog / Moderation", blocks=[
           ("title", "Category & listing moderation"),
           ("tabs", ["Category proposals", "Products & listings"], 0),
           ("table", ["Proposed category", "Parent", "Proposed by", "Date", "Commodity", "", ""],
            [["Robusta honey", "Green coffee", "Ea Kar Coffee Coop", "24/11/2026", "[select]", "<Approve>",
              "<Reject>"],
             ["Arabica washed", "Green coffee", "Tay Nguyen Coffee", "21/11/2026", "[select]", "<Approve>",
              "<Reject>"]]),
           ("card", "Approve category", [("fields", [("Commodity *", "v:COFFEE"), ("Rejection reason", "")])]),
       ])

# ======================================================== 4. Market Prices
screen(26, "market_price_board", shell="public", active="Market Prices", blocks=[
    ("title", "Market price board"),
    ("text", "Reference prices for coffee. Prices in a contract are agreed by both parties."),
    ("filters", ["Province: Dak Lak  v", "Item: All  v", ""]),
    ("table", ["Commodity", "Province", "Item", "Price (VND/kg)", "Date", "Source"],
     [["COFFEE", "Dak Lak", "Robusta", "95,000", "26/11/2026", "VNSAT"],
      ["COFFEE", "Dak Lak", "Arabica", "120,000", "26/11/2026", "VNSAT"],
      ["COFFEE", "Lam Dong", "Robusta", "94,500", "05/11/2026", "[STALE] OPERATOR_MANUAL"]]),
    ("placeholder", 160, "Price chart (history)"),
])

screen(27, "manual_price_entry", shell="app", role="OPERATOR", active="Manual Prices", crumb="Prices / Manual entry",
       blocks=[
           ("title", "Manual price entry"),
           ("banner", "Use when the VNSAT source is interrupted. Entries are append-only."),
           ("card", "New coffee price", [("fields", [("Province *", "v:Dak Lak"), ("Item *", "v:Robusta"),
                                                     ("Price (VND/kg) *", ""), ("Price date *", "27/11/2026"),
                                                     ("!Source note *", "")]),
                                         ("buttons", ["*Save price"])]),
           ("card", "Recent manual entries", [("table", ["Date", "Province", "Item", "Price", "By"],
                                               [["05/11/2026", "Lam Dong", "Robusta", "94,500", "operator1"],
                                                ["01/11/2026", "Dak Lak", "Robusta", "93,800", "operator1"]])]),
       ])

# ======================================================== 5. Contract
screen(28, "create_offer", shell="app", role="BUYER", active="My Contracts", crumb="Contracts / Create offer", blocks=[
    ("title", "Create coffee contract offer"),
    ("steps", ["Choose listing", "Terms", "Review & send"], 1),
    ("cols", [
        [("card", "Price & quantity", [("fields", [("Unit price (VND/kg) *", "95000"), ("Price band (+/- %)", "5"),
                                                   ("Total quantity (kg) *", "30000"),
                                                   ("Tolerance (%)", "1")])]),
         ("card", "Milestone schedule", [("table", ["#", "Quantity (kg)", "Expected date", ""],
                                          [["1", "10,000", "20/12/2026", "<Remove>"],
                                           ["2", "10,000", "20/01/2027", "<Remove>"],
                                           ["3", "10,000", "20/02/2027", "<Remove>"]]),
                                         ("buttons", ["+ Add milestone"])]),
         ("card", "Quality & inspection", [("fields", [("Moisture max (%)", "12.5"), ("Defects max (%)", "5"),
                                                       ("Deviation policy", "v:Price discount"),
                                                       ("Level 2 organization", "v:Vinacontrol")])])],
        [("card", "Listing", ["Robusta Ea Kar grade 1", "Available: 90,000 kg", "Seller: Ea Kar Coffee Coop"]),
         ("card", "Money preview", [("kv", [("Contract value", "2,850,000,000"), ("Buyer deposit", "142,500,000"),
                                            ("Seller deposit", "142,500,000")])])],
    ], [3, 2]),
    ("buttons", ["Cancel", "Back", "*Send offer"]),
])

screen(29, "my_contracts", shell="app", role="SELLER", active="My Contracts", crumb="Contracts", blocks=[
    ("title", "My contracts", None, ["*Create offer"]),
    ("text", "Track offers, terms and activation status of the contracts you take part in."),
    ("filters", ["Your role: Seller  v", "Status: All  v"]),
    ("table", ["Contract ID", "Counterparty", "Total value", "Status", ""],
     [["c0ffee00...0142", "Mekong Export JSC", "7,600,000,000", "[ACTIVE]", "<Open>"],
      ["c0ffee38...0138", "Tay Nguyen Trading", "7,600,000,000", "[ACTIVE]", "<Open>"],
      ["c0ffee90...0190", "Mekong Export JSC", "7,600,000,000", "[NEGOTIATING]", "<Open>"],
      ["c0ffee91...0191", "Mekong Export JSC", "7,600,000,000", "[SETTLED]", "<Open>"]]),
])

CONTRACT_DETAIL = [
    ("title", "Contract detail", "ACTIVE", ["Reload status"]),
    "Contract ID: c0ffee00-2026-4142-8000-000000000142",
    ("tabs", ["Overview", "Milestones", "Signatures", "Money", "Evidence"], 0),
    ("card", "Signed content hash (SHA-256)", ["3f9a1c2e...e2d4b6f8   ·   template VN_COFFEE_B2B v1.0"]),
    ("cols", [
        [("card", "Terms", [("kv", [("Buyer", "Mekong Export JSC"), ("Seller", "Ea Kar Coffee Coop"),
                                    ("Unit price", "95,000 VND/kg"), ("Reserved", "110,000 kg"),
                                    ("Total value", "7,600,000,000")])])],
        [("card", "Signing & deposits", [("kv", [("Buyer signature", "[SIGNED]"), ("Seller signature", "[SIGNED]"),
                                                 ("Buyer deposit", "[LOCKED]"), ("Seller deposit", "[LOCKED]")])])],
    ]),
    ("card", "Milestones", [("table", ["#", "Quantity", "Amount", "Deadline", "Status", ""],
                             [["1", "10,000 kg", "950,000,000", "12/11/2026", "[SETTLED]", "<Open>"],
                              ["4", "10,000 kg", "950,000,000", "21/11/2026", "[AWAITING_SELLER]", "<Open>"],
                              ["7", "10,000 kg", "950,000,000", "03/12/2026", "[IN_PROGRESS]", "<Open>"]])]),
    ("buttons", ["~Edit terms", "~Sign", "~Withdraw offer", "Report breach", "Propose replacement",
                 "Mutual termination", "~Terminate", "Export statement"]),
]
screen(30, "contract_detail", shell="app", role="SELLER", active="My Contracts", crumb="Contracts / Contract detail",
       blocks=CONTRACT_DETAIL)

screen(31, "edit_contract_terms", shell="app", role="BUYER", active="My Contracts",
       crumb="Contracts / Contract detail / Edit terms", blocks=[
           ("title", "Edit contract terms", "NEGOTIATING"),
           ("banner", "Saving moves the contract to NEGOTIATING and notifies the other party (MSG52). "
                      "Any earlier signature is cleared."),
           ("cols", [
               [("card", "Price & quantity", [("fields", [("Unit price (VND/kg)", "96000"),
                                                          ("Price band (+/- %)", "5"),
                                                          ("Total quantity (kg)", "30000"),
                                                          ("Tolerance (%)", "1")])]),
                ("card", "Milestone schedule", [("table", ["#", "Quantity (kg)", "Expected date"],
                                                 [["1", "15,000", "20/12/2026"], ["2", "15,000", "20/02/2027"]])])],
               [("card", "Quality, delivery & windows", [("fields", [("!Moisture max (%)", "12.5"),
                                                                    ("!Delivery place", "Ea Kar warehouse"),
                                                                    ("!Buyer confirm window (days)", "3")], 1)])],
           ], [3, 2]),
           ("buttons", ["Cancel", "*Save terms"]),
       ])

SIGN = [("title", "Sign contract", "NEGOTIATING"),
        ("card", "Terms being signed", ["SHA-256: 3f9a1c2e7b...91e2d4b6f8",
                                        ("text", "The same hash is included in the OTP email.", "s"),
                                        ("buttons", ["View PDF preview"])]),
        ("card", "Step 1 - Re-authenticate", ["Last login: 27/11/2026 22:40  [RECENT]",
                                              ("text", "Request the OTP within 5 minutes of this login (MSG05).",
                                               "s"),
                                              ("buttons", ["Re-authenticate"])]),
        ("card", "Step 2 - Request OTP", [("check", "I have read and agree to the contract terms.", True),
                                          ("buttons", ["*Request OTP"])])]
screen(32, "sign_contract", shell="app", role="BUYER", active="My Contracts", crumb="Contracts / Contract detail / Sign",
       blocks=SIGN)

screen(33, "otp_verification", shell="app", role="BUYER", active="My Contracts",
       crumb="Contracts / Contract detail / Sign", base=SIGN, dialog=dict(title="OTP verification", blocks=[
           ("text", "Enter the 6-digit code sent to buyer1@test.com. The code expires in 10 minutes."),
           ("otp",),
           ("text", "Didn't get it?  Resend OTP (the previous code becomes invalid)."),
       ], buttons=["Cancel", "*Verify and sign"]))

screen(34, "propose_replacement_contract", shell="app", role="SELLER", active="My Contracts",
       crumb="Contracts / Contract detail / Replacement", blocks=[
           ("title", "Propose replacement contract"),
           ("banner", "The current contract stays ACTIVE. It is closed as SUPERSEDED only after both parties sign "
                      "the new contract."),
           ("cols", [
               [("card", "New terms", [("fields", [("Unit price (VND/kg)", "97000"), ("Remaining quantity (kg)", "60000"),
                                                   ("Milestones", "v:3 milestones"), ("Price band (+/- %)", "5"),
                                                   ("!Reason *", "ta:")])])],
               [("card", "Current contract", ["c0ffee00...0142  [ACTIVE]", "Settled milestones: 2",
                                              "Unsettled quantity: 60,000 kg"])],
           ], [3, 2]),
           ("buttons", ["Cancel", "*Send replacement proposal"]),
       ])

screen(35, "export_escrow_statement", shell="app", role="BUYER", active="Escrow Statement",
       crumb="Contracts / Escrow statement", blocks=[
           ("title", "Export escrow statement"),
           ("card", "Statement period", [("fields", [("Contract", "v:All my contracts"), ("Format", "v:PDF"),
                                                     ("From *", "01/11/2026"), ("To *", "30/11/2026")]),
                                         ("buttons", ["*Export statement"])]),
           ("card", "Preview", [("table", ["Date", "Contract", "Movement", "Amount"],
                                 [["12/11/2026", "c0ffee00...0142", "Milestone 1 release", "-950,000,000"],
                                  ["12/11/2026", "c0ffee00...0142", "Refund", "+1,900,000"],
                                  ["03/11/2026", "c0ffee00...0142", "Deposit lock", "-380,000,000"]])]),
       ])


# ======================================================== 6. Milestone & Delivery
def ms_side(done):
    return [("card", "Milestone info", [("kv", [("Committed qty", "10,000 kg"), ("Unit price", "95,000 VND/kg"),
                                                ("Batch amount", "950,000,000"), ("Funding", "[LOCKED]"),
                                                ("Expected date", "01/12/2026"), ("Deadline", "03/12/2026")])]),
            ("card", "Delivery progress", [("timeline", [("Funding locked", "15/11/2026"),
                                                         ("Preparing goods", "due 03/12/2026"),
                                                         ("Seller weighed", "before loading"),
                                                         ("Buyer received", "weighed at unloading"),
                                                         ("Settled", "release and refund")], done)])]


def ms(no, slug, role, title, badge, main, done, buttons=()):
    screen(no, slug, shell="app", role=role, active="My Contracts",
           crumb=f"Contracts / c0ffee00 / {title}", blocks=[
               ("title", title, badge, list(buttons) + ["Reload status"]),
               ("cols", [main, ms_side(done)], [3, 2]),
           ])


ms(36, "milestone_detail", "BUYER", "Milestone 7", "IN_PROGRESS", [
    ("card", "Status", [("kv", [("Milestone status", "[IN_PROGRESS]"), ("Funding status", "[LOCKED]"),
                                ("Seller delivery deadline", "03/12/2026"), ("Buyer confirm window", "3 days")])]),
    ("card", "Delivery evidence", [("table", ["File", "By", "Uploaded"], [["-", "-", "No evidence yet"]])]),
    ("card", "Actions", [("buttons", ["~Record receipt", "Price adjustment", "~Claim force majeure"]),
                         ("text", "Actions are enabled by milestone status and your role.", "s")]),
    ("card", "Provisional release rate (Level 2 buffer)", [("radio", ["Higher release rate", "Wait for report"], 1)]),
], 1)

ms(37, "record_weighing", "SELLER", "Milestone 7", "IN_PROGRESS", [
    ("card", "Weigh before loading", [
        ("text", "Enter the weighed quantity and take a photo of the scale. The Buyer sees this record on receipt."),
        ("fields", [("!Weighed quantity (kg) *", "")], 1),
        ("cols", [[("upload", "Scale photo *")], [("upload", "Delivery note (optional)")]]),
        ("check", "I confirm the weighed quantity and evidence are correct.", False),
        ("buttons", ["*Record weighing"]),
    ]),
], 1)

ms(38, "record_receipt", "BUYER", "Milestone 6", "SELLER_WEIGHED", [
    ("card", "Record receipt", [
        ("kv", [("Seller weighed", "9,875.5 kg"), ("Weighed at", "27/11/2026 22:34")]),
        ("placeholder", 90, "Seller scale photo"),
        ("fields", [("!Received quantity (kg) *", "")], 1),
        ("cols", [[("upload", "Scale photo *")], [("upload", "Delivery note (optional)")]]),
        ("buttons", ["*Record receipt"]),
    ]),
    ("card", "Confirm delivery (after receipt)", [
        ("kv", [("Committed vs received", "10,000 -> 9,680 kg"), ("Transport loss", "9,875 -> 9,680 kg")]),
        ("buttons", ["Confirm clean", "Flag issue"]),
        ("text", "No action within the Buyer confirm window = auto-confirm clean.", "s"),
    ]),
], 3)

ms(39, "respond_to_flag", "SELLER", "Milestone 4", "AWAITING_SELLER_RESPONSE", [
    ("card", "Respond to Buyer's issue", [
        ("kv", [("Issue type", "QUANTITY"), ("Response deadline", "29/11/2026 (1 day left)")]),
        ("banner", "No response within 2 business days = the Buyer's figures (9,610 kg) are accepted."),
        ("kv", [("Committed vs received", "10,000 -> 10,020 kg"), ("Transport loss", "10,020 -> 9,610 kg")]),
        ("placeholder", 90, "Buyer scale photo"),
        ("check", "I disagree and request a Level 1.5 inspection. If the result is against me, I bear the cost."),
        ("buttons", ["Accept flag", "*Contest flag"]),
    ]),
], 4)

ms(40, "claim_force_majeure", "SELLER", "Milestone 7", "IN_PROGRESS", [
    ("card", "Claim force majeure", [
        ("fields", [("Event type *", "v:Flood"), ("Event date *", "25/11/2026"),
                    ("Affected quantity (kg)", "4000"), ("!Description *", "ta:")]),
        ("upload", "Evidence files * (at least one)"),
        ("banner", "Report window: 5 days from the event. A late claim is recorded with a warning (MSG77)."),
        ("buttons", ["Cancel", "*Submit claim"]),
    ]),
], 1)

ms(41, "dispute_exit", "SELLER", "Milestone 4", "CONTESTED", [
    ("card", "End the dispute", [
        ("kv", [("Seller receives at Buyer's position", "912,950,000"),
                ("Seller receives at Seller's position", "950,000,000")]),
        ("text", "Calculated by the system; a settlement must lie between the two amounts.", "s"),
    ]),
    ("card", "Buyer's proposal", [
        ("kv", [("Seller receives", "930,000,000"), ("Buyer refund", "20,000,000"),
                ("Proposed at", "28/11/2026 09:15")]),
        ("buttons", ["Refuse proposal"]),
    ]),
    ("buttons", ["Concede", "*Accept proposal"]),
    ("card", "Confirm with OTP", [
        ("check", "I agree the Seller receives 930,000,000 VND for this batch; the rest is refunded to the Buyer. "
                  "No party is at fault.", True),
        ("buttons", ["Send OTP"]),
        ("otp",),
        ("buttons", ["*Confirm OTP"]),
    ]),
], 4)

ms(42, "milestone_price_adjustment", "BUYER", "Milestone 8", "CREATED", [
    ("card", "Milestone price adjustment", [
        ("kv", [("Signed unit price", "95,000 VND/kg"), ("Allowed band", "90,250 - 99,750")]),
        ("fields", [("!Proposed unit price (VND/kg) *", "93000")], 1),
        ("buttons", ["*Propose price"]),
        ("divider",),
        ("kv", [("Pending proposal", "93,000 by Buyer"), ("Status", "[PENDING]")]),
        ("buttons", ["Reject", "*Accept"]),
        ("text", "One accepted adjustment per milestone, no OTP, deposits not recalculated.", "s"),
    ]),
], 0)

# ======================================================== 7. Dispute & Inspection
REPORT_BASE = CONTRACT_DETAIL
screen(43, "report_breach_dialog", shell="app", role="BUYER", active="My Contracts",
       crumb="Contracts / Contract detail", base=REPORT_BASE, dialog=dict(title="Report breach", w=600, blocks=[
           ("fields", [("Scope *", "v:Milestone 4"), ("Reason *", "v:QUALITY"), ("Severity *", "v:MATERIAL"),
                       ("Alleged party", "SELLER"), ("!Description *", "ta:")]),
           ("upload", "Attach evidence"),
           ("table", ["File", "Scan", ""], [["photo_lot4.jpg", "[CLEAN]", "<Remove>"]]),
           ("text", "The counterparty and the Arbitrator are notified.", "s"),
       ], buttons=["Cancel", "*Submit report"]))

screen(44, "breach_case_detail_party", shell="app", role="SELLER", active="My Contracts",
       crumb="Contracts / c0ffee00 / Breach case", blocks=[
           ("title", "Breach case BC-0091", "RESOLVED", ["Contest decision", "~Request termination"]),
           ("cols", [
               [("card", "Case", [("kv", [("Reason", "QUALITY"), ("Severity", "MATERIAL"),
                                          ("Reported by", "BUYER"), ("Alleged party", "SELLER"),
                                          ("Scope", "Milestone 4")])]),
                ("card", "Evidence", [("table", ["File", "By", ""], [["photo_lot4.jpg", "BUYER", "<Download>"],
                                                                     ["l15_report.pdf", "INSPECTOR", "<Download>"]])])],
               [("card", "Outcome", [("kv", [("Final breaching party", "SELLER"),
                                             ("Decision source", "INSPECTOR_L1_5"),
                                             ("Inspection status", "[CONFIRMED]"),
                                             ("Contest status", "[NOT CONTESTED]"),
                                             ("Contest deadline", "01/12/2026")])]),
                ("card", "Level 2 report", [("buttons", ["Acknowledge receipt"])])],
           ]),
       ])

BREACH_Q = [("title", "Breach case queue"),
            ("filters", ["Status: not RESOLVED  v", "Severity  v", "Reason  v", "Contract ID"], "Search"),
            ("table", ["Case", "Contract", "Reason", "Severity", "Requester", "Reported", "Status", ""],
             [["BC-0091", "c0ffee00", "QUALITY", "MATERIAL", "BUYER", "27/11", "[REPORTED]", "<Open>"],
              ["BC-0092", "c0ffee38", "QUANTITY", "MINOR", "BUYER", "26/11", "[UNDER_REVIEW]", "<Open>"],
              ["BC-0093", "c0ffee00", "LATE", "MATERIAL", "SELLER", "24/11", "[UNDER_REVIEW]", "<Open>"]])]
screen(45, "breach_case_queue", shell="app", role="ARBITRATOR", active="Breach Cases", crumb="Disputes / Queue",
       blocks=BREACH_Q)

BREACH_ARB = [
    ("title", "Breach case BC-0092", "UNDER_REVIEW", ["~Start review", "Request inspection", "~Terminate", "*Resolve"]),
    ("cols", [
        [("card", "Case", [("kv", [("Contract", "c0ffee38"), ("Reason", "QUANTITY"), ("Severity", "MINOR"),
                                   ("Requester", "BUYER"), ("Milestone", "1")])]),
         ("card", "Evidence & records", [("table", ["Item", ""], [["Signed terms (PDF)", "<Open>"],
                                                                  ["Weighing records", "<Open>"],
                                                                  ["buyer_scale.jpg", "<Open>"]])])],
        [("card", "Quality / inspection result", [("kv", [("Level 1.5 result", "[CONFIRMED]"),
                                                          ("Accepted qty", "9,580 kg"),
                                                          ("System disposition", "Shortfall 420 kg")])]),
         ("banner", "Resolve is disabled while the result is INCONCLUSIVE. Decisions with a penalty take "
                    "effect after the contest window.")],
    ]),
]
screen(46, "breach_case_detail_arbitrator", shell="app", role="ARBITRATOR", active="Breach Cases",
       crumb="Disputes / Breach case", blocks=BREACH_ARB)

screen(47, "request_inspection_dialog", shell="app", role="ARBITRATOR", active="Breach Cases",
       crumb="Disputes / Breach case", base=BREACH_ARB, dialog=dict(title="Request inspection", blocks=[
           ("radio", ["Level 1.5 inspection", "Level 2 commission"], 0),
           "Inspector: assigned by the system",
           "Level 2 organization: Vinacontrol (from signed terms)",
           ("fields", [("!Note to inspector", "ta:")], 1),
       ], buttons=["Cancel", "*Request"]))

screen(48, "resolve_breach_case_dialog", shell="app", role="ARBITRATOR", active="Breach Cases",
       crumb="Disputes / Breach case", base=BREACH_ARB, dialog=dict(title="Resolve breach case", blocks=[
           ("text", "Final breaching party *", "s"),
           ("radio", ["BUYER", "SELLER", "No fault"], 1),
           ("fields", [("Decision source *", "v:ARBITRATOR"), ("Evidence relied on *", "v:2 files, 1 report"),
                       ("!Decision note *", "ta:")]),
           ("text", "Recorded in the audit chain; both parties are notified.", "s"),
       ], buttons=["Cancel", "*Resolve case"]))

screen(49, "terminate_contract_dialog", shell="app", role="BUYER", active="My Contracts",
       crumb="Contracts / Contract detail", base=CONTRACT_DETAIL, dialog=dict(title="Terminate contract", blocks=[
           "Basis: BC-0091 resolved - MATERIAL breach by SELLER",
           ("kv", [("Settled milestones", "unchanged (2)"), ("Unsettled milestones", "end (9)"),
                   ("Seller deposit forfeited", "380,000,000"), ("Penalty", "5 % of unsettled value")]),
           ("banner", "Not allowed when the case is unresolved, MINOR, or you are the breaching party."),
       ], buttons=["Cancel", "*Terminate contract"]))

FM_Q = [("title", "Force majeure review queue"),
        ("table", ["Contract", "Milestone", "Seller", "Event", "Filed", "Window", ""],
         [["c0ffee00", "7", "Ea Kar Coffee Coop", "Flood", "25/11/2026", "[IN WINDOW]", "<Open>"],
          ["c0ffee38", "3", "Tay Nguyen Coffee", "Drought", "10/11/2026", "[LATE]", "<Open>"]])]
screen(50, "force_majeure_review_queue", shell="app", role="ARBITRATOR", active="Force Majeure",
       crumb="Force majeure / Queue", blocks=FM_Q)

screen(51, "force_majeure_claim_detail", shell="app", role="ARBITRATOR", active="Force Majeure",
       crumb="Force majeure / Claim", blocks=[
           ("title", "Force majeure claim - c0ffee00 / M7", "PENDING", ["Reject", "*Approve"]),
           ("cols", [
               [("card", "Claim", [("kv", [("Event", "Flood"), ("Event date", "25/11/2026"),
                                           ("Affected qty", "4,000 kg"), ("Filed", "26/11/2026"),
                                           ("Report window", "[IN WINDOW]")])])],
               [("card", "Evidence", [("table", ["File", ""], [["flood_photo.jpg", "<Open>"],
                                                               ["commune_confirm.pdf", "<Open>"]])])],
           ]),
           ("banner", "The losing party may contest; the claim then goes to a Level 1.5 inspection."),
       ])

screen(52, "activation_failures", shell="app", role="OPERATOR", active="Activation Failures",
       crumb="Operations / Activation failures", blocks=[
           ("title", "Activation failures"),
           ("table", ["Contract", "Missing leg", "Last error", "Cancel request", "Status", ""],
            [["c0ffee90", "Seller deposit", "BANK_TIMEOUT", "Buyer since 26/11", "[SIGNED]", "<Retry lock>"],
             ["c0ffee77", "Buyer deposit", "INSUFFICIENT_FUNDS", "-", "[SIGNED]", "<Retry lock>"],
             ["c0ffee55", "-", "Refund failed", "Both", "[REFUND_PENDING]", ""]]),
           ("text", "The Operator cannot cancel the contract or change amounts (MSG92).", "s"),
       ])

screen(53, "milestone_funding_failures", shell="app", role="OPERATOR", active="Funding Failures",
       crumb="Operations / Funding failures", blocks=[
           ("title", "Milestone funding failures"),
           ("table", ["Contract", "Milestone", "Amount", "Last error", "Cure deadline", ""],
            [["c0ffee00", "8", "950,000,000", "INSUFFICIENT_FUNDS", "02/12/2026", "<Retry funding>"],
             ["c0ffee38", "4", "950,000,000", "BANK_TIMEOUT", "30/11/2026", "<Retry funding>"]]),
           ("text", "After the cure deadline the system records a Buyer funding breach.", "s"),
       ])

ASSIGNED = [("title", "Assigned cases"),
            ("table", ["Case", "Contract", "Commodity", "Milestone", "Report due", "Status", ""],
             [["BC-0092", "c0ffee38", "COFFEE", "1", "02/12/2026", "[ASSIGNED]", "<Open>"],
              ["BC-0088", "c0ffee12", "COFFEE", "3", "29/11/2026", "[IN_PROGRESS]", "<Open>"]]),
            ("card", "What to measure - BC-0092", ["Moisture max: 12.5 %", "Defects max: 5 %", "Screen size: 16",
                                                   "Deviation policy: price discount"])]
screen(54, "assigned_cases", shell="app", role="INSPECTOR", active="Assigned Cases", crumb="Inspection / Assigned",
       blocks=ASSIGNED)

screen(55, "submit_inspection_result", shell="app", role="INSPECTOR", active="Assigned Cases",
       crumb="Inspection / BC-0092 / Submit result", blocks=[
           ("title", "Submit inspection result", "IN_PROGRESS"),
           ("cols", [
               [("card", "Measurements", [("fields", [("Measured qty (kg) *", "9600"), ("Accepted qty (kg) *", "9580"),
                                                      ("Moisture (%) *", "12.9"), ("Defects (%) *", "4.2"),
                                                      ("Screen size", "16")])]),
                ("card", "Inconclusive", [("check", "Mark as inconclusive"),
                                          ("fields", [("!Reason (at least one)", "")], 1)])],
               [("card", "Report file", [("upload", "Signed inspection report *", "PDF, up to 10 MB")]),
                ("card", "Identity", ["Last login: 27/11/2026 22:40",
                                      ("buttons", ["Re-authenticate"]),
                                      ("text", "Required if last login > 30 minutes (MSG05).", "s")])],
           ]),
           ("buttons", ["Cancel", "*Submit result"]),
       ])

screen(56, "level2_commissions", shell="app", role="ARBITRATOR", active="Level 2 Commissions",
       crumb="Inspection / Level 2 commissions", blocks=[
           ("title", "Level 2 commissions"),
           ("table", ["Commission", "Case", "Organization", "Sent", "Org case ID", "Suggestion", ""],
            [["L2-014", "BC-0093", "Vinacontrol", "25/11", "-", "VNC-26-8812", "<Confirm>"],
             ["L2-011", "BC-0071", "Vinacontrol", "18/11", "VNC-26-8590", "-", "[CONFIRMED]"]]),
           ("text", "Suggestions come from acknowledgement emails from the organization's domain.", "s"),
       ])

screen(57, "pending_external_report_review", shell="app", role="OPERATOR", active="Level 2 Reports",
       crumb="Inspection / Level 2 reports", blocks=[
           ("title", "Pending external report review"),
           ("cols", [
               [("table", ["Received", "Sender", "SPF", "Match", ""],
                 [["27/11 08:12", "lab@vinacontrol.vn", "[PASS]", "L2-014", "<Open>"],
                  ["26/11 15:40", "lab@vinacontrol.vn", "[PASS]", "[UNMATCHED]", "<Open>"]])],
               [("card", "Report L2-014", ["File hash: 9a7c...e1f0", ("buttons", ["Open file"]),
                                           ("fields", [("Commission", "v:L2-014"), ("Accepted qty (kg)", "9500"),
                                                       ("Moisture (%)", "13.1"), ("Defects (%)", "4.8")]),
                                           ("buttons", ["Reject", "*Approve"])])],
           ], [1, 1]),
       ])

screen(58, "manual_external_report_upload", shell="app", role="OPERATOR", active="Level 2 Reports",
       crumb="Inspection / Manual upload", blocks=[
           ("title", "Manual external report upload"),
           ("card", "Report", [("fields", [("Commission *", "v:L2-014"), ("Received via *", "v:Courier")]),
                               ("upload", "Level 2 report file *", "PDF, up to 20 MB - hash fixed at upload")]),
           ("card", "Normalised result", [("fields", [("Accepted qty (kg)", ""), ("Moisture (%)", ""),
                                                      ("Defects (%)", ""), ("Screen size", "")])]),
           ("buttons", ["Cancel", "*Upload and confirm"]),
           ("text", "Confirmed under your Operator identity (MSG101).", "s"),
       ])

screen(59, "email_intake_failures", shell="app", role="OPERATOR", active="Email Intake",
       crumb="Inspection / Email intake failures", blocks=[
           ("title", "Email intake failures"),
           ("table", ["Received", "Sender", "Subject", "Failure", "Status", ""],
            [["27/11 07:55", "lab@vinacontrol.vn", "Report VNC-26-8812", "PARSE_ERROR", "[OPEN]", "<Mark reviewed>"],
             ["25/11 10:20", "lab@vinacontrol.vn", "Report VNC-26-8770", "SIZE > 20 MB", "[REVIEWED]", ""]]),
       ])

# ======================================================== 8. Audit & Security
screen(60, "audit_chain_verification_status", shell="app", role="ADMIN", active="Audit Chain",
       crumb="Audit / Chain verification", blocks=[
           ("title", "Audit chain verification status"),
           ("cols", [
               [("card", "Latest weekly run", [("kv", [("Run at", "23/11/2026 02:00"), ("Result", "[PASSED]"),
                                                       ("Records checked", "18,240")])])],
               [("card", "Latest anchor", [("kv", [("Anchored hash", "a1b2...ff09"), ("Anchored at", "27/11/2026 00:00")])])],
           ]),
           ("card", "Past runs", [("table", ["Run", "Result", "Detail"],
                                   [["16/11/2026", "[PASSED]", "-"],
                                    ["09/11/2026", "[FAILED]", "First mismatch seq 10422 (MSG103)"]])]),
           ("card", "Reconciliation alerts", [("table", ["Contract", "Milestone", "Ledger", "Audit chain"],
                                               [["c0ffee12", "3", "950,000,000", "948,100,000"]])]),
       ])

screen(61, "contract_evidence_export", shell="app", role="ADMIN", active="Evidence Export",
       crumb="Audit / Evidence export", blocks=[
           ("title", "Contract evidence export"),
           ("filters", ["Contract ID: c0ffee00-2026-4142-8000-000000000142"], "Verify chain"),
           ("card", "Chain check", [("kv", [("Records", "11"), ("Result", "[VALID]"),
                                            ("Anchor proof", "27/11/2026")])]),
           ("card", "Export", [("buttons", ["*Export evidence package", "Generate EUDR package"]),
                               ("text", "EUDR package is available for coffee contracts.", "s")]),
           ("card", "Audit records", [("table", ["Seq", "Time", "Event", "Hash"],
                                       [["11", "22:50", "ADMIN viewed audit log", "4f1e...a2c1"],
                                        ["10", "12:40", "Contract settled", "ffff...9999"],
                                        ["7", "12:00", "Milestone settled 948,100,000", "7f3a...7777"]])]),
       ])

screen(62, "security_lock_status", shell="app", role="ADMIN", active="Security Lock",
       crumb="Security / Lock status", blocks=[
           ("title", "Security lock status", "INACTIVE"),
           ("banner", "Only the External Verifier can unlock, by a signed request. The Admin has no unlock action."),
           ("card", "Lock history", [("table", ["Time", "Action", "By", "Reason"],
                                      [["20/11/2026 03:10", "UNLOCK", "External Verifier", "Investigation closed"],
                                       ["19/11/2026 23:55", "LOCK", "External Verifier", "Ledger mismatch"]])]),
           ("card", "Nonce log", [("table", ["Time", "Nonce", "Request", "Result"],
                                   [["20/11 03:10", "9c1e...77ab", "UNLOCK", "[ACCEPTED]"],
                                    ["19/11 23:55", "1a0f...c3d2", "LOCK", "[ACCEPTED]"]])]),
       ])

# ======================================================== 9. Analytics
screen(63, "termination_report_by_commodity", shell="app", role="ADMIN", active="Analytics",
       crumb="Analytics / Terminations", blocks=[
           ("title", "Termination report by commodity"),
           ("filters", ["Year: 2026  v", "", ""]),
           ("bars", 220, ["Buyer fault", "Seller fault", "Mutual", "No fault"], [4, 7, 3, 2], "COFFEE - terminations"),
           ("table", ["Commodity", "Buyer fault", "Seller fault", "Mutual", "No fault"],
            [["COFFEE", "4", "7", "3", "2"]]),
       ])

screen(64, "escrow_effectiveness_report", shell="app", role="ADMIN", active="Analytics",
       crumb="Analytics / Escrow effectiveness", blocks=[
           ("title", "Escrow effectiveness report"),
           ("filters", ["Month: 11/2026  v", "", ""]),
           ("cols", [[("card", "Locked", [("text", "28.5 bn VND", "t", "#222222")])],
                     [("card", "Disbursed", [("text", "24.1 bn VND", "t", "#222222")])],
                     [("card", "Refunded", [("text", "3.2 bn VND", "t", "#222222")])]]),
           ("bars", 200, ["W1", "W2", "W3", "W4"], [6, 8, 5, 9], "Locked vs disbursed by week"),
       ])

screen(65, "force_majeure_trends", shell="app", role="ADMIN", active="Analytics",
       crumb="Analytics / Force majeure trends", blocks=[
           ("title", "Force majeure trends"),
           ("filters", ["Year: 2026  v", "", ""]),
           ("bars", 220, ["J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"],
            [0.2, 0.2, 1, 2, 1, 1, 3, 5, 6, 4, 2, 1], "COFFEE - incidents per month"),
           ("text", "Peak months: August - October (rainy season).", "b", "#222222"),
       ])


def main(argv):
    want = {int(a) for a in argv} or set(SCREENS)
    for no in sorted(want):
        slug, spec = SCREENS[no]
        path = OUT / f"s{no:02d}_{slug}.png"
        render(spec, path)
        print("wrote", path.name)


if __name__ == "__main__":
    main(sys.argv[1:])
