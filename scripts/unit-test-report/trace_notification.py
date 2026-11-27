# Report 5.1 (#521): the Jest tests behind each row of the notification-service sheets, in sheet order (see
# trace_contract.py). A test is its full Jest name ("describe › ... › test"); "prefix*" stands for every test of
# that describe block or it.each table; "spec.ts::name" points into a spec file the sheet folds in
# (data_r2_node.FOLDED). Matched by reading the test titles and, where needed, the specs.
SERVICE = "notification-service"

SEND = "NotificationSender.send (plan §4.3) › "
HEART = SEND + "claim heartbeat while sending (fake timers) › "
RACE = SEND + "concurrent deliveries of the same event (review #172) › "
DEDUP = SEND + "dedup by (event, recipient, notification type) (#115) › "
SAFE = SEND + "error messages are safe to log (review #172) › "
MSGID = SEND + "provider message id › "
WAIT = SEND + "waiting for a live claim of another delivery (injected clock and sleep) › "
OTPREQ = "parseOtpEmailRequest › "
RENDER = "renderOtpEmail › "
SENDOTP = "SendOtpEmailUseCase › "
ANCHOR = "NotifyContractAnchorUseCase › "
CANCEL = "NotifyActivationCancelRequestedUseCase › "
CANCELBODY = "activationCancelBody (activation-cancel-requested/v1) › "
LEGACY = "Notify*UseCase (golden fixtures frozen from the Java service) › "
LOG = "NotificationLog › "
KINDS = "COMMAND_KINDS › "
CANON = "canonicalJson (RFC 8785) › "
ENV = "validate (env) › "
MAILER = "NodemailerEmailAdapter › "
HTTP = "POST /internal/v1/notifications/otp-email › "
AMQP = "AmqpService › "
DLQ = "DlqMonitor › "
PHASE1 = "src/application/phase1-notification-kinds.spec.ts::"
LOGGER = "src/infrastructure/logging/eventing-logger.spec.ts::"
SMOKE = "src/infrastructure/eventing.smoke.spec.ts::"
OTPMODULE = "src/otp-email.module.spec.ts::"
CA = "category.approved parser › "
CR = "category.rejected parser › "
READER = "the shared PayloadReader rules, on one field of one parser › "
TOPO = "topology (must match the Java RabbitMQConfig) › "
CLAIM = "MysqlNotificationLogRepository.claim › "
MARK = "MysqlNotificationLogRepository.markSent / markFailed › "
PII = "src/infrastructure/messaging/pii-logging.spec.ts::no personal data in the logs › "
MYSQL = "src/infrastructure/persistence/mysql.provider.spec.ts::MysqlService › "
NOTIFMODULE = "src/notification.module.spec.ts::NotificationModule wiring › "
AN = "parseContractAnchorRequested › "
ANREJ = AN + "rejects, without retrying, a message that › "
AC = "parseActivationCancelRequested › "
NEC = "NotificationEventConsumer › "
SHUT = "NotificationEventConsumer at shutdown: no new attempt starts (fake timers) › "
DELIVERY = "delivery handling of a QueueConsumer (eventing handleDelivery) › "
ANC = "contractAnchorRequested contract (notification-commands.yaml) › "
ACC = ("test/contract/activation-cancel-requested.contract.spec.ts::"
       "activationCancelRequested contract (notification-commands.yaml) › ")
OTPC = "test/contract/otp-email-request.contract.spec.ts::OtpEmailRequest contract (golden-flow-api.yaml) › "
LEGACY_GONE = ("Handler Java của event Phase 1 này đã bị gỡ; use-cases.spec.ts chỉ còn golden fixture của "
               "category approved/rejected, không còn test nào ứng với dòng này")

TESTS = {
    "NotificationSender": [
        [SEND + "sends and records SENT with the recipient email as user"],
        [SEND + "skips a recipient already SENT for this event"],
        [SEND + "records FAILED with retry_count + 1 and throws when the mail fails"],
        [SEND + "sends again on redelivery after a failure, then records SENT"],
        [SEND + "a different event to the same recipient is a separate email"],
        [MSGID + "records the id the provider gave the accepted email",
         MSGID + "records SENT with no id when the provider named none"],
        [MSGID + "a failed attempt records no id, and keeps none after the retake",
         MSGID + "each recipient of one event keeps its own id"],
        [DEDUP + "records the kind of the email and, once SENT, its sent time",
         DEDUP + "records the recipient platform id when the caller passes one"],
        [DEDUP + "two recipients of one event: two emails, two records",
         DEDUP + "one event, one recipient, two notification types: two emails, two records"],
        [DEDUP + "retries after failures reuse the one record: failure code kept, then SENT",
         DEDUP + "a retake records what this attempt rendered, not the failed one"],
        [DEDUP + "an email SENT before migration V2 is not sent again when its event is redelivered",
         DEDUP + "an email left FAILED before migration V2 is sent on redelivery, in a new record",
         DEDUP + "an email left PENDING before migration V2 is sent on redelivery, in a new record",
         DEDUP + "a legacy row of another recipient does not stop this one"],
        [RACE + "first delivery: only the claim holder sends; the other waits, finds SENT and sends nothing"],
        [RACE + "a PENDING row older than the lease (sender died mid-send) is retaken and sent at once"],
        [RACE + "a FAILED row is retaken by exactly one of two deliveries; the other then finds SENT"],
        [WAIT + "waits for 50s at most, polling every 1s"],
        [WAIT + "SENT meanwhile: skips the recipient without sending",
         WAIT + "FAILED meanwhile: takes the claim over and sends",
         WAIT + "holder died (no heartbeat): takes the claim over once it is older than the 45s lease, and sends"],
        [WAIT + "holder still alive after 50s (heartbeat): throws a retryable SendInProgressError without sending"],
        [WAIT + "shutdown during the wait (review of #206) › *"],
        [HEART + "uses a 45s lease refreshed every 15s",
         HEART + "refreshes the claim every 15s while the send is in progress",
         HEART + "keeps a long send claimed past the lease: another delivery cannot take it over"],
        [HEART + "stops beating once the send succeeded", HEART + "stops beating once the send failed"],
        [HEART + "a failed beat is logged as an error with the event id and code only; the send goes on",
         HEART + "does not start a beat while the previous one still waits for the database"],
        [SAFE + "*"],
    ],
    "OtpEmailRequest": [
        [OTPREQ + "accepts a valid request and returns exactly the contract fields",
         OTPREQ + "accepts an expiry with an offset and fractional seconds"],
        [OTPREQ + "rejects a body that is*"],
        [OTPREQ + "reports each missing field"],
        [OTPREQ + "rejects requestId*", OTPREQ + "rejects recipientUserId*", OTPREQ + "rejects contractId*"],
        [OTPREQ + "rejects recipientEmail*"],
        [OTPREQ + "rejects otpCode*"],
        [OTPREQ + "rejects signedContentHash*"],
        [OTPREQ + "rejects expiresAt*"],
        [OTPREQ + "rejects unknown fields without naming them"],
        [OTPREQ + "never quotes a value in its message"],
        [OTPREQ + "purpose (D42) › is SIGN_CONTRACT when absent, as contract-service sends a signing code",
         OTPREQ + "purpose (D42) › is not reported missing"],
        [OTPREQ + "purpose (D42) › accepts SIGN_CONTRACT", OTPREQ + "purpose (D42) › accepts CANCEL_ACTIVATION"],
        [OTPREQ + 'purpose (D42) › rejects null', OTPREQ + 'purpose (D42) › rejects "cancel_activation"',
         OTPREQ + 'purpose (D42) › rejects "TERMINATE_CONTRACT"', OTPREQ + "purpose (D42) › rejects 1",
         OTPREQ + 'purpose (D42) › rejects ""'],
    ],
    "OtpEmailTemplate": [
        [RENDER + "keeps the code out of the subject",
         RENDER + "puts the code on a line of its own, then the contract and the full hash"],
        [RENDER + "converts an expiry sent with an offset", RENDER + "shows the expiry in Vietnam time and in UTC"],
        [RENDER + "renders the signing email exactly as otp-email/v1 always has"],
        [RENDER + "purpose CANCEL_ACTIVATION › has its own subject, without the code",
         RENDER + "purpose CANCEL_ACTIVATION › says the code confirms a cancel request, not a signature",
         RENDER + "purpose CANCEL_ACTIVATION › carries the code, the contract, the expiry and the full hash"],
        [RENDER + "keeps every SIGN_CONTRACT line within 76 characters for a 66-character contract id",
         RENDER + "keeps every CANCEL_ACTIVATION line within 76 characters for a 66-character contract id",
         RENDER + "sends the SIGN_CONTRACT email as 7bit, so the raw message holds the code and hash unbroken",
         RENDER + "sends the CANCEL_ACTIVATION email as 7bit, so the raw message holds the code and hash unbroken"],
        [RENDER + "stores a placeholder body that holds no code",
         RENDER + "purpose CANCEL_ACTIVATION › stores its own placeholder body, which holds no code"],
    ],
    "SendOtpEmail": [
        [SENDOTP + "sends the rendered email and records it SENT without the code",
         SENDOTP + "records the provider id of the OTP email",
         SENDOTP + "records the OTP email SENT with no id when the provider named none"],
        [SENDOTP + "a signing code and a cancel code to the same address are two emails and two rows",
         SENDOTP + "sends a CANCEL_ACTIVATION code with its own template and records it under its own type"],
        [SENDOTP + "does not send again for a requestId already SENT"],
        [SENDOTP + "fails with a code-only error and records FAILED when the provider rejects"],
        [SENDOTP + "sends again when the caller repeats a requestId that FAILED"],
        [SENDOTP + "returns at once with OtpEmailInProgressError while another call holds a live claim"],
        [SENDOTP + "takes over a claim older than the lease (the caller died mid-send)"],
        [SENDOTP + "sends nothing when the log cannot be claimed"],
        [SENDOTP + "still succeeds when SENT cannot be recorded, and logs only a code",
         SENDOTP + "still reports the provider failure when FAILED cannot be recorded"],
    ],
    "NotifyContractAnchor": [
        [ANCHOR + "emails every recipient of the command, in its order",
         ANCHOR + "carries the signed hash and the whole terms snapshot in the body"],
        [ANCHOR + "the printed canonical line hashes to signedContentHash › *"],
        [ANCHOR + "records one row per recipient with its platform id and the template version",
         ANCHOR + "keeps a recipient without a platform id as null, not as a string"],
        [ANCHOR + "a redelivery of the same command sends nothing twice"],
        [ANCHOR + "a failure on the second recipient leaves the first sent and resends only the second"],
        [ANCHOR + "two recipients at the same address are one email, not two"],
    ],
    "NotifyActivationCancel": [
        [CANCEL + "emails the other party, with the contract in the subject",
         CANCEL + "records the row with its type, template version and platform id"],
        [CANCEL + "a redelivery of the same command sends nothing twice"],
        [CANCEL + "a failure rethrows and the redelivery sends it"],
        [CANCELBODY + "tells the other party who asked, what they can do, and from when silence is enough",
         CANCELBODY + "names the seller when the seller asked"],
        [CANCELBODY + 'gives an OPERATOR the facts without "you can" lines',
         CANCELBODY + 'gives the requesting party itself the facts without "you can" lines'],
        [CANCELBODY + "converts an instant sent with an offset", CANCELBODY + "keeps every line within 76 characters*"],
    ],
    "NotifyLegacyUseCases": [
        [LEGACY + "covers the 2 remaining Java handlers with one use case each",
         LEGACY + "handleCategoryApproved sends the Java subject and body to the Java recipients, in order",
         LEGACY + "handleCategoryRejected sends the Java subject and body to the Java recipients, in order"],
        LEGACY_GONE,
        LEGACY_GONE,
        LEGACY_GONE,
        [LEGACY + "a redelivered event sends nothing once its recipient is SENT",
         LEGACY + "an event redelivered across the V2 deploy does not mail a recipient SENT before V2"],
    ],
    "NotificationLog": [
        [LOG + "starts PENDING with no retries and a UUID notification id",
         LOG + "is keyed by event, recipient and notification type",
         LOG + "takes the kind's template version, the evidence of what was sent"],
        [LOG + "carries the recipient platform id when the sender has one, null otherwise"],
        [LOG + "markSent sets SENT without touching the retry count", LOG + "markFailed sets FAILED and counts each failure"],
        [LOG + "reconstitute keeps the stored state", LOG + "keeps the enum values of the Java service"],
    ],
    "NotificationKinds": [
        [KINDS + "*"],
        [PHASE1 + "PHASE1_KINDS › *"],
    ],
    "CanonicalJson": [
        [CANON + "writes the RFC 8785 §3.2.4 sample as the RFC does",
         CANON + "writes the terms fixture on one line with no whitespace"],
        [CANON + "sorts keys by UTF-16 code units, as RFC 8785 §3.2.3 does",
         CANON + "sorts nested objects and keeps array order and null members"],
        [CANON + "refuses*"],
    ],
    "NotificationEnv": [
        [ENV + "uses the Java defaults when nothing is set"],
        [ENV + "reads values and converts ports to numbers"],
        [ENV + "reads SERVICE_INTERNAL_SECRET and rejects an empty one"],
        [ENV + "rejects port value*"],
        [ENV + "reports every invalid variable at once without echoing secrets"],
    ],
    "NotifInfraHelpers": [
        ["describeError › *"],
        [LOGGER + "eventingLogger › *"],
        [SMOKE + "@agricontract/eventing under Jest › *"],
    ],
    "NodemailerEmailAdapter": [
        [MAILER + "uses plain SMTP without auth or STARTTLS, like the Java config",
         MAILER + "uses the given timeouts (OTP: well below the caller read timeout of 5s)"],
        [MAILER + "sends a plain text mail from MAIL_FROM", MAILER + "defaults from to noreply@agricontract.com"],
        [MAILER + "provider message id › *"],
        [MAILER + "errors carry a code only, never an address (review #172) › *",
         "smtpFailureCode › ignores codes that do not look like codes"],
    ],
    "NotificationHealth": [
        ["GET /actuator/health › answers 200 with status UP when everything is up"],
        ["GET /actuator/health › answers 503 with status DOWN when RabbitMQ is down",
         "GET /actuator/health › answers 503 when the database is down",
         "RabbitHealthIndicator › is down without a channel", "DatabaseHealthIndicator › is down with the ping error"],
    ],
    "OtpEmailHttp": [
        {"tests": [HTTP + "returns 200 with an empty body once the provider accepted the email"],
         "note": "Dòng ghi 204; test hiện tại assert 200 với body rỗng"},
        {"tests": [HTTP + "returns 200 again for the same requestId without sending a second email"],
         "note": "Dòng ghi 204; test hiện tại assert 200"},
        [HTTP + "returns 401 with no secret and sends nothing", HTTP + "returns 401 with a wrong secret and sends nothing",
         HTTP + "returns 401 with the secret plus another value and sends nothing",
         HTTP + "does not accept the gateway secret header"],
        {"tests": [HTTP + "returns 400 for malformed JSON without quoting the body",
                   HTTP + "returns 400 naming the invalid fields only", HTTP + "returns 400 for a body over the size limit"],
         "note": "Dòng ghi 413 cho body quá giới hạn; test hiện tại assert 400"},
        [HTTP + "returns 502 when the provider rejects, logging the requestId and code only"],
        {"tests": [HTTP + "returns 503 at once while another call sends the same requestId"],
         "note": "Dòng ghi 409; test hiện tại assert 503"},
        [HTTP + "returns 404 as an envelope for an unknown internal route"],
        [OTPMODULE + "OtpEmailModule wiring › *"],
    ],
    "AmqpProvider": [
        [AMQP + "starts without waiting for the broker, then retries with 1s, 2s backoff until connected",
         AMQP + "caps the backoff at 30s"],
        [AMQP + "sets prefetch, declares the topology and runs callbacks on every (re)connect",
         AMQP + "runs a callback registered after the channel is ready right away"],
        [AMQP + "logs an error and retries when the topology declaration fails"],
        [AMQP + "stops retrying once destroyed"],
        [AMQP + "keeps the channel open on module destroy so consumers can drain",
         AMQP + 'closes channel then connection on application shutdown and swallows "already closed"'],
    ],
    "LegacyPayloadParsers": [
        [CA + "maps a flat payload to the command and ignores extra fields", CA + "accepts the body as a string too",
         CA + "does not trim or convert values", CR + "maps a flat payload to the command and ignores extra fields",
         CR + "accepts the body as a string too", CR + "does not trim or convert values"],
        [READER + "a required field*", CA + "dead-letters a payload without*", CR + "dead-letters a payload without*"],
        [READER + "a body that*"],
        {"tests": ["optional fields › a missing or null categoryId becomes null; an empty one is kept"],
         "note": "Dòng ghi thiếu lý do vẫn được chấp nhận; test hiện tại dead-letter category.rejected không có "
                 "rejectionReason (dead-letters a payload without rejectionReason)"},
        {"tests": [READER + "a required field that is a number dead-letters the message",
                   READER + "a required field that is an object dead-letters the message"],
         "note": "Quy tắc sai kiểu của PayloadReader dùng chung, kiểm trên 1 field đại diện"},
        "Parser escrow.penalized (penalizedParty) đã bị gỡ cùng event Phase 1; spec chỉ còn parser category, "
        "không còn test nào ứng với dòng này",
        ["error messages name the field type, never the value",
         "error messages never carry payload text (review #172) › *"],
    ],
    "NotifShutdownAndPii": [
        ["shutdown order (Nest lifecycle) › acks the in-flight message, then closes the channel, then ends the pool"],
        ["shutdown order (Nest lifecycle) › after the drain timeout closes the channel without ack or nack, and only "
         "then ends the pool",
         "shutdown order (Nest lifecycle) › a retry due during shutdown is parked: the channel closes after its "
         "backoff, without ack or nack"],
        [PII + "*"],
    ],
    "NotificationTopology": [
        [TOPO + "names the 24 queues and 24 DLQs"],
        [TOPO + "declares exactly 14 durable, non auto-delete exchanges",
         TOPO + "declares 48 queues: main queues with only x-dead-letter-exchange, DLQs without arguments",
         TOPO + "binds each queue to its exchange and each DLQ to its DLX with the same routing key"],
    ],
    "NotificationPersistence": [
        [CLAIM + "first looks for the email SENT before V2, on its legacy key",
         CLAIM + "reports ALREADY_SENT, and writes nothing, when the legacy row is SENT"],
        [CLAIM + "claims a first delivery by inserting a PENDING row with its kind and DB-clock UTC timestamps",
         CLAIM + "writes recipient_user_id as NULL when the sender has only the address"],
        [CLAIM + "on an existing row, retakes it by its key only if FAILED or PENDING older than the lease",
         CLAIM + "the retake overwrites what the failed attempt rendered, so the row describes the email sent now",
         CLAIM + "reports ALREADY_SENT when the row could not be retaken and is SENT",
         CLAIM + "reports IN_PROGRESS when another delivery holds a fresh PENDING claim"],
        [CLAIM + "rethrows insert errors other than a duplicate key"],
        ["MysqlNotificationLogRepository.heartbeat › moves updated_at of the PENDING row to the DB clock, and touches "
         "nothing else"],
        [MARK + "markSent sets SENT, the provider message id and sent_at by the key",
         MARK + "markSent stores no provider id when the provider named none",
         MARK + "markSent drops a provider id wider than the column instead of failing"],
        [MARK + "markFailed sets FAILED, increments retry_count in SQL and stores the failure code"],
        [MYSQL + "*"],
        [NOTIFMODULE + "*"],
    ],
    "NotificationCommandParsers": [
        [AN + "reads the envelope eventId and every payload field",
         AN + "takes the dedup event from the envelope, not from the payload",
         AN + "keeps a null userId null: the recipient has no account"],
        [ANREJ + "is not JSON", ANREJ + "has no envelope (a flat Phase 1 style payload)", ANREJ + "has a bad eventId",
         ANREJ + "has a bad eventType", ANREJ + "has a bad eventVersion", ANREJ + "has a bad occurredAt",
         ANREJ + "has a bad producer", ANREJ + "has a bad aggregateId", ANREJ + "has a bad correlationId",
         ANREJ + "has a bad payload", ANREJ + "carries a `subject`, which the command envelope does not allow"],
        [ANREJ + "has a bad contractId", ANREJ + "has a bad contractTermsSnapshot", ANREJ + "has a bad signedContentHash",
         ANREJ + "has a bad signedContentHash length", ANREJ + "has a bad signedAt",
         ANREJ + "adds a payload property the command does not declare"],
        [ANREJ + "has no recipients at all", ANREJ + "has a recipient that is not an object",
         ANREJ + "has a recipient without userId", ANREJ + "has a userId that is not a UUID",
         ANREJ + "has an address that is not one", ANREJ + "has a role outside the contract",
         ANREJ + "has a property the recipient does not declare"],
        [AN + "error messages › *"],
        [AC + "reads the envelope eventId and every payload field", AC + "reads a seller request"],
        [AC + "rejects a missing requestedByRole", AC + "rejects requestedByRole*", AC + "rejects oneSidedCancelAllowedFrom*",
         AC + "rejects contractId*", AC + "rejects trigger*"],
        [AC + "rejects the anchor command*"],
    ],
    "NotificationEventConsumer": [
        [NEC + "registers through AmqpService.onChannelReady at startup",
         NEC + "consumes every queue on every channel, again after a reconnect"],
        {"tests": [NEC + "notification-svc.category.*",
                   NEC + "runs the anchor use case with the parsed command, recipients included, and acks",
                   NEC + "runs the activation-cancel use case on its own queue, and acks"],
         "note": "Dòng ghi 9 queue Phase 1; nay chỉ còn 2 queue category, 7 queue Phase 1 đã bỏ (topology retired queues)"},
        [NEC + "dead-letters a command whose eventType is not this queue*",
         NEC + "dead-letters a malformed payload at once, without calling the handler"],
        [DELIVERY + "retries a transient error 3 times with 1s/2s backoff, then nacks without requeue",
         DELIVERY + "acks after a transient error that succeeds on retry"],
        [DELIVERY + "nacks a NonRetryableError at once, without retrying"],
        [NEC + "on shutdown cancels every consumer, then waits for in-flight messages",
         NEC + "drains for 40s: above the 30s SMTP socket timeout, below the 60s compose stop_grace_period"],
        [NEC + "drain timing (fake timers) › *"],
        [NEC + "does not attach to a channel that opens during shutdown"],
        [SHUT + "an attempt running when shutdown begins finishes and is acked",
         SHUT + "an attempt running when shutdown begins that fails for good is nacked to the DLQ as usual"],
        [SHUT + "the retry after a failed attempt is parked*", SHUT + "a message already in its backoff*",
         SHUT + "a delivery reaching the consumer after shutdown began*",
         SHUT + "a message waiting for another delivery*"],
    ],
    "NotificationContracts": [
        [ANC + "is a command of the contract, consumed by this service",
         ANC + "names the eventType and producer the parser and the kind use",
         ANC + "declares exactly the payload fields the parser reads, all required",
         ANC + "declares exactly the recipient roles the code accepts"],
        [ANC + "accepts*"],
        [ANC + "rejects*"],
        [ACC + "is a command of the contract, consumed by this service",
         ACC + "names the eventType and producer the parser and the kind use",
         ACC + "declares exactly the payload fields the parser reads, all required",
         ACC + "declares exactly the requester roles the code accepts"],
        [ACC + "accepts*"],
        [ACC + "rejects*"],
        [OTPC + "has the operation, path and service auth the controller implements",
         OTPC + "declares exactly the fields the code reads, and requires those of every purpose",
         OTPC + "declares the purposes the code has a template for, SIGN_CONTRACT by default"],
        {"tests": [OTPC + "agrees with the schema on*"],
         "note": "Dòng ghi 20 mẫu body; bảng mẫu của test nay lớn hơn (thêm VERIFY_EMAIL, CONCEDE/SETTLE_DISPUTE)"},
        [OTPC + "is stricter than the schema*"],
    ],
    "NotifDlqMonitor": [
        [DLQ + "runs every 5 minutes, like the Java DlqMonitor"],
        [DLQ + "checks every DLQ and stays quiet when they are empty", DLQ + "warns for each DLQ that holds messages"],
        [DLQ + "skips the check without crashing while the channel is not ready",
         DLQ + "logs a failed check and stops this round instead of throwing"],
    ],
}
