# Findings

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
