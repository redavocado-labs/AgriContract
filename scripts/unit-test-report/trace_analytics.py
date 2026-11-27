# Report 5.1 (#521): the Jest tests behind each row of the analytics-service sheets, in sheet order (see
# trace_notification.py for the notation). The real-MySQL tests (ANALYTICS_TEST_MYSQL_URL) are skipped in a unit
# run; no row points at them.
# AnalyticsTopology UTC004 carries the history of defect DEF-01 (Report 5.5, #449): Round 1 is the CI run that found
# it, Round 2 the CI re-run after the fix, both on main; Round 3 is the Jest run of this part.
SERVICE = "analytics-service"

CUONG = "Nguyen Minh Cuong"
RUN = "https://github.com/redavocado-labs/AgriContract/actions/runs/"
OUTCOMES = "contract outcome facts through the production wiring › "
RULE = "terminationOutcome (the default-rate rule) › "
DLQ = "DLQ monitor › "
INGEST = "analytics canonical event ingestion › "
DEDUP = INGEST + "consumer dedupes events › "
BOUNDARY = INGEST + "the ingestion boundary rejects invalid canonical input (review finding) › "
WIRING = "projections through the production wiring › "
RUNTIME = "production wiring: stop, publish, restart, replay › "
ACTIVE = "shutdown with an active handler (real wiring) › "
MATRIX23 = "analytics is not on the transaction critical path (Matrix 23) › "

TESTS = {
    "AnalyticsArchitecture": [
        ["canonical layout › *", "dependency rule › *"],
    ],
    "ContractOutcomes": [
        [RULE + "only TERMINATION_FOR_BREACH with a final role*"],
        [RULE + "mutual outcomes are never a default*", RULE + "force majeure and activation failure are no-fault*"],
        [RULE + "a breach termination without a final role blames nobody", RULE + "does not read requestedBy at all"],
        [OUTCOMES + "requestedBy and finalBreachingRole are separate*", OUTCOMES + "the mirror case*"],
        [OUTCOMES + "every termination branch is stored*", OUTCOMES + "a mutual exit*"],
        [OUTCOMES + "replay is ignored*", OUTCOMES + "contract.settled writes one settlement fact*"],
        [OUTCOMES + "restart catch-up*"],
        [OUTCOMES + "an invalid termination*"],
    ],
    "AnalyticsDlqMonitor": [
        [DLQ + "checks the DLQ of every subscribed event", DLQ + "a non-empty DLQ warns with queue and depth",
         DLQ + "empty DLQs do not warn"],
        [DLQ + "a failing check is logged and the remaining queues are still checked"],
        [DLQ + "start() checks right away and repeats; stop() ends it; one pass at a time"],
    ],
    "AnalyticsEventContracts": [
        ["production rules match golden-flow-events.yaml › *"],
        ["validator and YAML schema agree › contract.*", "validator and YAML schema agree › milestone.*"],
        ["validator and YAML schema agree › recipients*"],
        ["what the production validator rejects (review finding) › *"],
    ],
    "AnalyticsHealth": [
        ["GET /health (Nest) › *"],
    ],
    "AnalyticsIngestion": [
        [INGEST + "a production consumer exists for every subscribed event, with no projection registered › *"],
        [DEDUP + "a redelivered event*", DEDUP + "different events for the same contract*"],
        [DEDUP + "a failing*"],
        [DEDUP + "after retries are exhausted*"],
        [BOUNDARY + "a payload with*", BOUNDARY + "an event that arrives on the wrong queue*",
         BOUNDARY + "an off-contract envelope", BOUNDARY + "an unsupported eventVersion",
         BOUNDARY + "contract.signed from an unrelated producer*"],
        [BOUNDARY + "a valid event after a rejected one is still ingested"],
        [INGEST + "contact PII is not stored › *"],
        [INGEST + "outage does not block producers › *"],
        [INGEST + "queue backlog can catch up › *"],
        [INGEST + "guards › *"],
    ],
    "AnalyticsInvalidEvents": [
        ["invalid events are dead-lettered without side effects › contract.*"],
        ["invalid events are dead-lettered without side effects › the corrected event*"],
    ],
    "AnalyticsLifecycle": [
        ["gracefulShutdown › runs the steps in order*"],
        ["gracefulShutdown › a failing step*", "gracefulShutdown › a stuck close step*"],
        [ACTIVE + "waits for the running handler*", ACTIVE + "timeout path*"],
        [ACTIVE + "messages still waiting in the queue*", "deliveries that arrive after shutdown began › *"],
    ],
    "AnalyticsMilestoneLock": [
        ["per-milestone lock order › *"],
    ],
    "AnalyticsPersistence": [
        ["toIngestCommand › carries*", "toIngestCommand › does not change*", "toIngestCommand › removes*"],
        ["toIngestCommand › leaves payloads without recipients untouched"],
        {"tests": ["MysqlIngestedEventStore › *"],
         "note": "Dòng ghi DATETIME(6); test hiện tại kiểm giờ UTC DATETIME(3)"},
        ["test/nest-projections.spec.ts::ProjectionsModule (Nest DI) › *"],
    ],
    "AnalyticsProjections": [
        ["money arithmetic › *"],
        [WIRING + "contract.signed upserts dim_contract*"],
        [WIRING + "milestone.settled writes one fact*", WIRING + "milestone.cancelled_with_penalty*"],
        [WIRING + "force majeure*", WIRING + "a REJECTED force majeure*", WIRING + "the flag belongs*"],
        [WIRING + "restart catch-up*", WIRING + "events for the same contract*"],
        [WIRING + "a projection failure*", WIRING + "a malformed event*"],
    ],
    "AnalyticsRuntime": [
        [RUNTIME + "events published while analytics is stopped*"],
        [RUNTIME + "replaying the same eventIds*"],
        [RUNTIME + "startup declares the topology*", RUNTIME + "a rejected event shows up*"],
    ],
    "AnalyticsTopology": [
        ["RabbitMQ topology › *"],
        ["frozen event contract › *"],
        [MATRIX23 + "analytics-service makes no outbound HTTP calls*", MATRIX23 + "escrow-service has no*"],
        {"tests": [MATRIX23 + "contract-service has no synchronous*", MATRIX23 + "the reference check ignores comments*"],
         "round1": ("Failed", "15/11/2026", CUONG),
         "round2": ("Passed", "15/11/2026", CUONG),
         "defect": "DEF-01 (#449)",
         "note": "Round 1: CI " + RUN + "43699113645 (main 36bf2fcd), test bắt chữ 'analytics' trong comment của "
                 "contract-service; sửa ở #449, PR #468 (e62bc34c): test bỏ qua comment. Round 2: chạy lại "
                 "CI " + RUN + "43725489719 (main e62bc34c), Passed. Report 5.5 DEF-01"},
        ["persistence and config › *", "NestJS wiring › *"],
    ],
}
