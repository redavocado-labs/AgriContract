# SRS Part III — screen wireframes

One PNG per screen of Report 3 › I.5.1 Screen List (64 screens), redrawn as simple grayscale wireframes.

- `sNN_<name>.png` — wireframe for screen #NN (the number matches the Screen List table).
- `real/sNN_real.png` — screenshot of the running frontend (27/11/2026), used as the reference for the wireframe.
- Re-render: `python screens.py` (all) or `python screens.py 30 36` (only some screens). Needs Pillow.

Where the real UI comes from: the local `backend` docker stack + `npm run dev` (:5173) for auth, account and public pages;
`VITE_MOCK_API=bf04` (:5174) for the contract, milestone, dispute, remedy and audit pages, because the local DB has no contracts.

| # | Screen | SRS | Wireframe drawn from |
|---|---|---|---|
| 1 | Register Account | 1.1.1 | real UI (`real/s01`) |
| 2 | Login | 1.1.2 | real UI (`real/s02`) |
| 3 | Forgot Password | 1.1.3 | real UI (`real/s03`) |
| 4 | Update Password | 1.1.4 | scope (Keycloak page, not reachable without a reset link) |
| 5 | Organization Onboarding | 1.1.5 | real UI (`real/s05`) |
| 6 | My Profile | 1.2.1 | real UI (`real/s06`) |
| 7 | Public User Profile | 1.2.2 | real UI (`real/s07`, needs login) + scope |
| 8–13 | KYC Review Queue … Propose Reputation Action | 2.1.1–2.2.2 | scope (no UI yet) |
| 14–22 | My Plots … Listing Form | 3.1.1–3.4.2 | scope (no UI yet) |
| 23 | Browse Listings | 3.5.1 | real UI (`real/s23`) |
| 24 | Listing Detail | 3.5.2 | real UI (`real/s24`, seed listing not found) + scope |
| 25 | Category & Listing Moderation | 3.6.1 | scope |
| 26 | Market Price Board | 4.1.1 | real UI (`real/s26`) |
| 27 | Manual Price Entry | 4.1.2 | scope |
| 28 | Create Offer | 5.1.1 | real UI (`real/s28`, step bar) + scope |
| 29 | My Contracts | 5.2.1 | real UI (`real/s29`) |
| 30 | Contract Detail | 5.2.2 | real UI (`real/s30`) |
| 31–35 | Edit Terms … Export Escrow Statement | 5.2.3–5.5.1 | scope, same layout as #30 |
| 36 | Milestone Detail | 6.1.1 | real UI (`real/s36`) |
| 37 | Record Weighing | 6.2.1 | real UI (`real/s37`) |
| 38 | Record Receipt | 6.2.2 | real UI (`real/s38`, `real/s38b`) |
| 39 | Respond to Flag | 6.3.1 | real UI (`real/s39`) |
| 40–41 | Claim Force Majeure, Price Adjustment | 6.3.2, 6.4.1 | scope, same layout as #36 |
| 42–43 | Report Breach, Breach Case (Party) | 7.1.1–7.1.2 | scope |
| 44 | Breach Case Queue | 7.2.1 | real UI (`real/s44`) |
| 45 | Breach Case Detail (Arbitrator) | 7.2.2 | real UI (`real/s45`) + scope |
| 46–51 | Request Inspection … Activation Failures | 7.2.3–7.5.1 | scope |
| 52 | Milestone Funding Failures | 7.5.2 | real UI (`real/s52`, remedy retry) + scope |
| 53–58 | Assigned Cases … Email Intake Failures | 7.6.1–7.7.4 | scope |
| 59 | Audit Chain Verification Status | 8.1.1 | real UI (`real/s59`, audit log) + scope |
| 60–64 | Evidence Export … Force Majeure Trends | 8.1.2–9.1.3 | scope |
