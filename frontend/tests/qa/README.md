# Independent frontend QA

Run from `frontend/` on Node 22 after `npm ci`.

```sh
npm test -- tests/qa/gateway.integration.test.ts
npx playwright install chromium --only-shell
npm run test:e2e
```

Playwright starts a loopback-only Vite server. Each test gets an isolated browser context, a synthetic subject or guest session, and a fixed starting clock. The suite intercepts Axios traffic using browser routes, fails on unexpected API requests, and aborts requests to external origins. The Keycloak adapter is replaced at the module boundary. MSW exercises the real Axios request pipeline without connecting to a gateway.

Set `PLAYWRIGHT_BROWSERS_PATH` when Chromium was installed in a custom directory. To rerun a browser test against an earlier source snapshot, point `QA_FRONTEND_ROOT` at that snapshot's frontend directory with compatible dependencies available. Tests run with the current QA toolchain in both cases.

| Finding | Specification | Independent cases |
| --- | --- | --- |
| FE-003 | Golden-flow OpenAPI `IdempotencyKey`, `withdrawOffer` and `verifyOtpAndSign`. Error catalog replay and conflict rules. | Capture UUID, reconcile a committed withdrawal with a lost response, verify no replay on reload or second tab, preserve UUID after an explicit retry following an authoritative read, and prevent writes when a reconciliation read fails or the recovery window expires. MSW covers HTTP 401, 403, 409, 422, 429, 503 and a disconnected response, with no implicit retry. |
| FE-004 | Golden-flow OpenAPI `getContract` and Contract state machine `SIGNED → ACTIVE` on `escrow.deposit_locked`. Hardening brief sets 5-second cadence and 120-second budget. | Late transition into SIGNED, initially SIGNED, timeout and manual refresh, transient read failure, resource switch, stop after ACTIVE. |
| FE-007 | Supplementary API `ListingResponse` and `reserveListingQuantity`. Product design defining reservable states and quantity bounds. | Verified BUYER sees AVAILABLE and PARTIALLY_COMMITTED coffee listings, including stock exactly at the minimum. CLOSED, below-minimum and legacy null-stock rows are absent. The selected ID reaches the URL and detail GET, and the offer form shows the latest server price. |
| FE-010 / FE-011 | Golden-flow OpenAPI `getMilestoneDetail` and `recordSellerWeighed` define participant access, seller-only weighing and the server correction deadline. | Buyer cannot weigh as seller, forbidden responses hide milestone detail, a prior actor's milestone is hidden after an in-app identity switch, and the seller correction control is unavailable after the server deadline. |
| FE-011 | Golden-flow OpenAPI `verifyOtpAndSign`, signing design and canonical error catalog. | OTP expiry prevents transport, active requests contain only the challenge ID and code, and a step-up rejection remains visible to the caller. |
| FE-015 | Golden-flow OpenAPI `uploadMilestoneEvidence` and `recordSellerWeighed`. File-service scan states govern eligibility. Hardening brief sets 60-second scan budget. | Upload bytes and key on the wire, missing projection, stop polling past the budget, manual recheck to READY, failed scan with a replacement control and no dead retry, independent second file, safe same-key recovery following an explicit upload 503. |
| FE-016 | WCAG 2.2 A and AA criteria covered by axe plus keyboard behavior. | Dialog focus behavior, guest pricing reads and keyboard-only province selection, 320 CSS pixel reflow on home and prices, and axe scans of contract detail and public prices. Axe findings are attached to Playwright results. |

The following need additional independent coverage or a test environment.

- Full onboarding, role assignment and Keycloak redirect/step-up require a test realm. Gateway and browser assertions here cover selected role gates and transport behavior.
- Real server idempotency, cross-tab commits across independent browser profiles, recovery from already-issued writes and evidence-event latency require a disposable backend. The HTTP fixtures encode permitted responses and never create real financial, signing or file-scan effects.
- Browser command recovery covers body-less withdrawal. Changed-payload, session-expiry recovery across other commands, and the full evidence replacement and resource-switch sequences remain untested here.
- Contract creation and negotiation, successful OTP signing, milestone money movement, inspection, dispute and arbitration decisions, and ledger reconciliation need their own signed-off end-to-end fixtures. These tests do not verify those transitions.
- Mutation coverage and the application-wide coverage baseline belong to the broader frontend verification. Manual screen-reader coverage remains pending.

The fixtures follow the frozen API and lifecycle references. [Golden-flow OpenAPI](../../../contracts/openapi/golden-flow-api.yaml) · [Supplementary API](../../../contracts/events/supplementary-services-api.yaml) · [State machines](../../../docs/contracts/state-machines.md) · [Error catalog](../../../docs/contracts/error-catalog.md) · [File-service design](../../../docs/design/file-service-phase2-design.md)
