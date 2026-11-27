# Report 5.1 (#521): the Jest tests behind each row of the pricing-service sheets, in sheet order (see
# trace_notification.py for the notation). The real-MySQL tests of test/replay.spec.ts are skipped without
# PRICING_TEST_MYSQL_URL; no row points at them.
SERVICE = "pricing-service"

HTTP = "POST /api/v1/prices/manual › "
AUTH = HTTP + "operator authorization › "
STORED = HTTP + "source and timestamp stored › "
VALID = HTTP + "validation › "
IDEM = HTTP + "Idempotency-Key › "
REDIS = "Redis cache never hangs (rules §4B) › "
REPLAY = "idempotent replay returns the original response after the MySQL round trip › "
REPO = "MysqlPriceHistoryRepository › "

TESTS = {
    "PricingArchitecture": [
        ["canonical layout › *"],
        ["dependency rule › application/*", "dependency rule › domain/*", "dependency rule › infrastructure/*",
         "dependency rule › main.ts*"],
        ["dependency rule › domain and application use no framework or driver packages",
         "dependency rule › HTTP statuses appear only in the web layer"],
        ["NestJS (decision 19/10/2026) › *"],
    ],
    "PricingContract": [
        ["supplementary-services-api.yaml: recordManualPrice › *"],
        ["golden-flow ErrorEnvelope › *"],
        ["pricing is not a synchronous dependency of the contract flow (design §4.1) › *"],
    ],
    "PricingHttp": [
        [AUTH + "OPERATOR can record a manual price (201)", AUTH + "accepts OPERATOR among several*"],
        [AUTH + "rejects ADMIN*", AUTH + "rejects BUYER*", AUTH + "rejects INSPECTOR*", AUTH + "rejects SELLER*"],
        [AUTH + "rejects a forged*", AUTH + "rejects a gateway request without user identity",
         AUTH + "rejects a request that did not come through the gateway*"],
        [AUTH + "checks authorization before reading/validating the body"],
        [STORED + "stores source=OPERATOR_MANUAL*", STORED + "trims text fields"],
        [STORED + "is append-only*"],
        [STORED + "updates the cache only after the row is written", STORED + "a Redis failure does not fail the request*"],
        [VALID + "rejects non-coffee*", VALID + "rejects missing*", VALID + "rejects unknown field*",
         VALID + "rejects itemName*"],
        [VALID + "rejects numeric price*", VALID + "rejects price*", VALID + "rejects zero price*",
         VALID + "rejects negative price*"],
        [VALID + "rejects impossible date*", VALID + "rejects date-time*", VALID + "rejects future date*"],
        [VALID + "accepts*"],
        [VALID + "rejects malformed JSON", VALID + "rejects a non-JSON content type with 415"],
        [IDEM + "is required (400)"],
        [IDEM + "a retry with the same key*", IDEM + "concurrent requests with the same key*"],
        [IDEM + "reusing a key with a different body*"],
        [IDEM + "keys are scoped per actor"],
        [HTTP + "routing › *"],
    ],
    "PricingRedis": [
        [REDIS + "Redis down (connection refused)*", REDIS + "Redis accepts TCP*", REDIS + "Redis stops answering*"],
        [REDIS + "POST /api/v1/prices/manual with Redis unavailable › *"],
        [REDIS + "startup does not wait for Redis › *"],
    ],
    "PricingReplay": [
        [REPLAY + "price*"],
        [REPLAY + '"00100.00" is rejected*'],
        ["a replay heals a cache update that failed on the first attempt › *"],
    ],
    "PricingUnits": [
        ["slugify / cache key (design §2) › *", "validateManualPrice › *", "businessToday (Asia/Ho_Chi_Minh) › *"],
        ["RedisPriceCache › *"],
        [REPO + "INSERTs every attribution column*", REPO + "maps a duplicate key error*", REPO + "reads a stored row*",
         REPO + "is append-only*"],
        ["migration (additive) › *", "config › *"],
    ],
    "RecordManualPrice": [
        ["recordManualPrice throws application errors, not HTTP errors › *"],
        ["web layer maps application errors (infrastructure/http/errors.ts) › *"],
    ],
}
