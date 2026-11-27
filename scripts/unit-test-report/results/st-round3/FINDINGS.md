# Findings

## BF03-070 Sign offer as seller

- Request `POST http://localhost:8080/api/v1/contracts/4d9529dc-0a25-49a0-b157-8b4a617c5bd5/sign/verify`
- Expected behavior in [070-sign-seller.bru](<repo>/backend/bruno/e2e/bf03-signing-activation/happy/070-sign-seller.bru) and OpenAPI verifyOtpAndSign. The OTP is scoped by test contract ID and recipient.
- Actual HTTP response `409` with `{"code": "DELIVERY_SCHEDULE_TOO_SOON", "message": "Milestone 1 is due on 2026-12-02, fewer than 3 business days from today; move its delivery date and sign again.", "status": 409}`
- Assertion or runner error `expected 409 to equal 204`
- Severity business flow
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-bf03.json](bruno-bf03.json) and the corresponding service logs in `logs/`

## BF04-010 Wait for a locked milestone

- Request `GET {{base_url}}/api/v1/contracts/{{contract_id}}/milestones/{{milestone_id}}`
- Expected behavior in [010-funded.bru](<repo>/backend/bruno/e2e/bf04-milestone-delivery-settlement/clean/010-funded.bru) and OpenAPI getMilestoneDetail and milestone escrow §4, D63/D64.
- Actual HTTP response `error` with `null`
- Assertion or runner error `Fixture 070-sign-seller returned 409 DELIVERY_SCHEDULE_TOO_SOON`
- Severity business flow
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-bf04.json](bruno-bf04.json) and the corresponding service logs in `logs/`

## BF04-110 Wait for a locked milestone

- Request `GET {{base_url}}/api/v1/contracts/{{contract_id}}/milestones/{{milestone_id}}`
- Expected behavior in [010-funded.bru](<repo>/backend/bruno/e2e/bf04-milestone-delivery-settlement/dispute/010-funded.bru) and OpenAPI getMilestoneDetail and milestone escrow §4, D63/D64.
- Actual HTTP response `error` with `null`
- Assertion or runner error `Fixture 070-sign-seller returned 409 DELIVERY_SCHEDULE_TOO_SOON`
- Severity business flow
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-bf04.json](bruno-bf04.json) and the corresponding service logs in `logs/`

## BF04-310 Wait for final contract settlement

- Request `GET {{base_url}}/api/v1/contracts/{{contract_id}}`
- Expected behavior in [010-settled-contract.bru](<repo>/backend/bruno/e2e/bf04-milestone-delivery-settlement/completion/010-settled-contract.bru) and OpenAPI getContract and D94. Reuses the clean contract or creates and settles a separate fixture.
- Actual HTTP response `error` with `null`
- Assertion or runner error `Fixture 010-funded returned error `
- Severity business flow
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-bf04.json](bruno-bf04.json) and the corresponding service logs in `logs/`

## SEC-020 A protected profile requires an authenticated session

- Request `GET http://localhost:8080/api/v1/users/me`
- Expected behavior in [020-anonymous-profile.bru](<repo>/backend/bruno/security/authn/020-anonymous-profile.bru) and OpenAPI getMyProfile 401; ErrorEnvelope. A normal unauthenticated request exercises the authentication rule. and contracts/openapi/golden-flow-api.yaml components.responses.Unauthorized
- Actual HTTP response `401` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-040 BUYER reads kyc queue

- Request `GET http://localhost:8080/api/v1/admin/users?verificationStatus=PENDING`
- Expected behavior in [040-kyc-queue-buyer.bru](<repo>/backend/bruno/security/authz-role-matrix/040-kyc-queue-buyer.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-041 SELLER reads kyc queue

- Request `GET http://localhost:8080/api/v1/admin/users?verificationStatus=PENDING`
- Expected behavior in [041-kyc-queue-seller.bru](<repo>/backend/bruno/security/authz-role-matrix/041-kyc-queue-seller.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-042 INSPECTOR reads kyc queue

- Request `GET http://localhost:8080/api/v1/admin/users?verificationStatus=PENDING`
- Expected behavior in [042-kyc-queue-inspector.bru](<repo>/backend/bruno/security/authz-role-matrix/042-kyc-queue-inspector.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-044 ARBITRATOR reads kyc queue

- Request `GET http://localhost:8080/api/v1/admin/users?verificationStatus=PENDING`
- Expected behavior in [044-kyc-queue-arbitrator.bru](<repo>/backend/bruno/security/authz-role-matrix/044-kyc-queue-arbitrator.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-045 ADMIN reads kyc queue

- Request `GET http://localhost:8080/api/v1/admin/users?verificationStatus=PENDING`
- Expected behavior in [045-kyc-queue-admin.bru](<repo>/backend/bruno/security/authz-role-matrix/045-kyc-queue-admin.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-060 BUYER reads arbitration queue

- Request `GET http://localhost:8080/api/v1/arbitration/breach-cases?size=10`
- Expected behavior in [060-arbitration-queue-buyer.bru](<repo>/backend/bruno/security/authz-role-matrix/060-arbitration-queue-buyer.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-061 SELLER reads arbitration queue

- Request `GET http://localhost:8080/api/v1/arbitration/breach-cases?size=10`
- Expected behavior in [061-arbitration-queue-seller.bru](<repo>/backend/bruno/security/authz-role-matrix/061-arbitration-queue-seller.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-062 INSPECTOR reads arbitration queue

- Request `GET http://localhost:8080/api/v1/arbitration/breach-cases?size=10`
- Expected behavior in [062-arbitration-queue-inspector.bru](<repo>/backend/bruno/security/authz-role-matrix/062-arbitration-queue-inspector.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-063 OPERATOR reads arbitration queue

- Request `GET http://localhost:8080/api/v1/arbitration/breach-cases?size=10`
- Expected behavior in [063-arbitration-queue-operator.bru](<repo>/backend/bruno/security/authz-role-matrix/063-arbitration-queue-operator.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-065 ADMIN reads arbitration queue

- Request `GET http://localhost:8080/api/v1/arbitration/breach-cases?size=10`
- Expected behavior in [065-arbitration-queue-admin.bru](<repo>/backend/bruno/security/authz-role-matrix/065-arbitration-queue-admin.bru) and Data-governance design §5.4 and the corresponding OpenAPI x-roles and ErrorEnvelope. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-110 Operator role is denied global verification

- Request `GET http://localhost:8080/api/v1/admin/audit/verification`
- Expected behavior in [110-verify-global-as-operator.bru](<repo>/backend/bruno/security/audit-evidence-log/110-verify-global-as-operator.bru) and OpenAPI verifyGlobalAuditChain and data-governance design §5.4. and contracts/openapi/golden-flow-api.yaml components.responses.Forbidden
- Actual HTTP response `403` with `""`
- Assertion or runner error `expected '' to be an object`
- Severity security
- Suspected source api-gateway authentication or authorization filter
- Tracked in #663
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-130 Party reads the minimized trail

- Request `GET {{audit_service_url}}/api/v1/audit/contracts/{{contract_id}}/records`
- Expected behavior in [120-party-view.bru](<repo>/backend/bruno/security/audit-evidence-log/120-party-view.bru) and OpenAPI getContractAuditTrail; hash-chain design §4.6.
- Actual HTTP response `error` with `null`
- Assertion or runner error `Fixture 010-settled-contract returned error `
- Severity security
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-140 Admin reads the same contract trail

- Request `GET http://localhost:8080/api/v1/admin/audit/contracts/38f9cae6-6599-4493-91ae-f7eaa93de22d/records`
- Expected behavior in [130-admin-view.bru](<repo>/backend/bruno/security/audit-evidence-log/130-admin-view.bru) and OpenAPI getContractAuditTrailForAdmin; hash-chain design §4.6.
- Actual HTTP response `404` with `{"code": "AUDIT_TRAIL_NOT_FOUND", "message": "No evidence log for this contract", "status": 404}`
- Assertion or runner error `expected 404 to equal 200`
- Severity security
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-150 Admin read logging records one system event per read

- Request `GET http://localhost:8080/api/v1/admin/audit/records?subjectType=SYSTEM&size=2`
- Expected behavior in [140-global-view.bru](<repo>/backend/bruno/security/audit-evidence-log/140-global-view.bru) and OpenAPI listAuditRecordsForAdmin; hash-chain design §4.6.
- Actual HTTP response `200` with `{}`
- Assertion or runner error `Target cannot be null or undefined.`
- Severity security
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-160 Admin reads preserve the party trail

- Request `GET http://localhost:8092/api/v1/audit/contracts/38f9cae6-6599-4493-91ae-f7eaa93de22d/records`
- Expected behavior in [150-party-view-unchanged.bru](<repo>/backend/bruno/security/audit-evidence-log/150-party-view-unchanged.bru) and OpenAPI getContractAuditTrail and hash-chain design §4.6.
- Actual HTTP response `404` with `{"code": "AUDIT_TRAIL_NOT_FOUND", "message": "No evidence log for this contract", "status": 404}`
- Assertion or runner error `expected 404 to equal 200`
- Severity security
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`

## SEC-170 Verify exactly two audit records were appended

- Request `GET http://localhost:8080/api/v1/admin/audit/verification`
- Expected behavior in [160-global-view-count.bru](<repo>/backend/bruno/security/audit-evidence-log/160-global-view-count.bru) and OpenAPI verifyGlobalAuditChain and hash-chain design §4.6. Verification itself is read only.
- Actual HTTP response `200` with `{"status": "INTACT"}`
- Assertion or runner error `expected 2 to equal NaN`
- Severity security
- Suspected source See service logs for the endpoint owner
- Evidence [bruno-security.json](bruno-security.json) and the corresponding service logs in `logs/`
