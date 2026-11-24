import {
  InvalidEnvelopeError,
  NonRetryableError,
  parseEnvelope,
  type EventEnvelope,
} from '@agricontract/eventing';
import {
  ACTIVATION_NEXT_ACTIONS,
  BREACH_REASON_CODES,
  BREACH_SEVERITIES,
  DECISION_SOURCES,
  CANCEL_REQUESTER_ROLES,
  ESCROW_LEG_ALERT_STAGES,
  ESCROW_LEG_KINDS,
  ESCROW_LEG_STATUSES,
  ESCROW_REMEDY_REJECTED_REASON_MAX_LENGTH,
  FAILED_LEGS,
  FUND_TYPES,
  KYC_RESULTS,
  LEVEL15_WAITING_REASONS,
  LEVEL15_INSPECTOR_STAGES,
  LEVEL15_WAITING_STAGES,
  LOCK_ACTIONS,
  MILESTONE_FUNDING_STATUS_TYPES,
  MILESTONE_STATUS_TYPES,
  QUALITY_DISPOSITIONS,
  PARTY_ROLES,
  RECIPIENT_ROLES,
  REMEDY_TYPES,
  TERMINATION_REQUESTERS,
  TERMINATION_TYPES,
  type ActivationCancelRequestedCommand,
  type ContractActivationFailedRequestedCommand,
  type ContractTerminatedRequestedCommand,
  type RemedyLeg,
  type ContractAnchorRequestedCommand,
  type ContractDocumentReadyRequestedCommand,
  type MilestoneAnchorRequestedCommand,
  type EscrowLegAlertRequestedCommand,
  type EscrowRemedyLegAlertRequestedCommand,
  type EscrowRemedyRejectedRequestedCommand,
  type Level15AssignmentWaitingRequestedCommand,
  type Level15InspectorAssignmentRequestedCommand,
  type MilestoneFundingStatusRequestedCommand,
  type MilestoneStatusRequestedCommand,
  type BreachNoticeRequestedCommand,
  type RemedyFinalizedRequestedCommand,
  type NotificationRecipient,
  type RecipientRole,
  type UserKycResultRequestedCommand,
  type UserLockChangedRequestedCommand,
} from '../../application/commands';

/*
 * Parsers for the Phase 2 notification commands of
 * contracts/events/notification-commands.yaml. Unlike the flat Phase 1 events
 * (legacy-payload.parsers.ts), a command is a full event envelope and its
 * recipients live in `payload.recipients`, so parseEnvelope runs first and the
 * payload is read after it.
 *
 * Every failure is a NonRetryableError: the message goes to the DLQ at once
 * instead of being retried three times, because no redelivery of the same
 * bytes can become valid. parseEnvelope's own InvalidEnvelopeError is
 * converted, since the queue consumer only passes a NonRetryableError through
 * unchanged (anything else it replaces with a retryable code).
 *
 * What the messages may say: a field name, a JSON type, the index of a bad
 * recipient and the event id when it is a plain id. Never a value, because
 * these messages are logged on every dead-letter and `payload.recipients` holds
 * email addresses (review #172).
 *
 * The envelope checks are the command contract's, which is stricter than
 * event-envelope.yaml on two points: `correlationId` is required, and
 * `subject` is not among the allowed properties.
 */

/** UUIDs and ids like evt-anchor-1729: safe to log, unlike free text or emails. */
const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/;

const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

const SHA256_HEX = /^[a-f0-9]{64}$/;

/** The contract's `money`: non-negative VND with two decimals, at most 19 characters. */
const MONEY = /^[0-9]+\.[0-9]{2}$/;
const MONEY_MAX_LENGTH = 19;
const MONEY_RULE = `must be a decimal string with two decimals, at most ${MONEY_MAX_LENGTH} characters`;

const isMoney = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length <= MONEY_MAX_LENGTH &&
  MONEY.test(value);

const REMEDY_LEG_FIELDS = [
  'remedyLegId',
  'remedyType',
  'fundType',
  'role',
  'amount',
] as const;

/** RFC 3339, the shape every producer's Instant serialises to. */
const DATE_TIME =
  /^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})$/;

/**
 * An address the contract's `format: email` can accept. Deliberately not
 * stricter than that format: a recipient the publisher considers valid must
 * reach SMTP, not the DLQ. The value is never quoted in the error.
 */
const EMAIL = /^[^\s@]+@[^\s@]+$/;

/** Properties the command envelope allows; `subject` of event-envelope.yaml is not one. */
const ENVELOPE_PROPERTIES = [
  'eventId',
  'eventType',
  'eventVersion',
  'occurredAt',
  'producer',
  'aggregateId',
  'correlationId',
  'causationId',
  'payload',
] as const;

class CommandReader {
  constructor(
    private readonly eventType: string,
    private readonly eventId: string,
    private readonly payload: Record<string, unknown>,
  ) {}

  /** Rejects any payload property the command does not declare (additionalProperties: false). */
  only(fields: readonly string[]): this {
    for (const key of Object.keys(this.payload)) {
      if (!fields.includes(key)) {
        throw this.error(`unknown payload property "${key}"`);
      }
    }
    return this;
  }

  uuid(field: string): string {
    const value = this.string(field);
    if (!UUID.test(value)) throw this.error(`field "${field}" must be a UUID`);
    return value;
  }

  hash(field: string): string {
    const value = this.string(field);
    if (!SHA256_HEX.test(value)) {
      throw this.error(`field "${field}" must be 64 lowercase hex characters`);
    }
    return value;
  }

  dateTime(field: string): string {
    const value = this.string(field);
    if (!DATE_TIME.test(value)) {
      throw this.error(`field "${field}" must be an RFC 3339 date-time`);
    }
    return value;
  }

  /** The contract's `money`; the bad value is not quoted. */
  money(field: string): string {
    const value = this.payload[field];
    if (!isMoney(value)) throw this.error(`field "${field}" ${MONEY_RULE}`);
    return value;
  }

  /** One of the contract's enum values; the bad value is not quoted. */
  enumValue<T extends string>(field: string, values: readonly T[]): T {
    const value = this.payload[field];
    if (!(values as readonly unknown[]).includes(value)) {
      throw this.error(`field "${field}" must be one of ${values.join(', ')}`);
    }
    return value as T;
  }

  /**
   * Any JSON string, the empty one included, as a bare `{type: string}` is
   * declared: rejecting more would dead-letter a command the contract allows.
   */
  text(field: string): string {
    const value = this.payload[field];
    if (typeof value !== 'string') {
      throw this.error(
        `field "${field}" must be a string, got ${describe(value)}`,
      );
    }
    return value;
  }

  /**
   * A `{type: string, minLength: 1, maxLength}`: lengths in code points, as
   * JSON Schema counts them. The bad value is not quoted: it is free text.
   */
  boundedText(field: string, maxLength: number): string {
    const value = this.text(field);
    const length = [...value].length;
    if (length < 1 || length > maxLength) {
      throw this.error(
        `field "${field}" must be 1 to ${maxLength} characters, got ${length}`,
      );
    }
    return value;
  }

  /**
   * An optional bare `{type: string}`: absent is null, but a property that is
   * present must be a string, since the contract does not allow null.
   */
  optionalText(field: string): string | null {
    return field in this.payload ? this.text(field) : null;
  }

  /**
   * An optional date-time that may also be null, as `{type: [string, "null"]}`
   * outside `required` is declared: absent and null both read as null.
   */
  optionalNullableDateTime(field: string): string | null {
    const value = this.payload[field];
    return value === undefined || value === null ? null : this.dateTime(field);
  }

  /**
   * An optional string that may also be null, as `{type: [string, "null"]}`
   * outside `required` is declared: absent and null both read as null, and
   * any string, the empty one included, is kept.
   */
  optionalNullableText(field: string): string | null {
    const value = this.payload[field];
    return value === undefined || value === null ? null : this.text(field);
  }

  /** A single address outside `payload.recipients`, checked as a recipient's is; never quoted. */
  email(field: string): string {
    const value = this.payload[field];
    if (typeof value !== 'string' || !EMAIL.test(value)) {
      throw this.error(`field "${field}" must be an email address`);
    }
    return value;
  }

  /**
   * An array of the contract's enum values, possibly empty; duplicates are
   * allowed because the contract does not declare uniqueItems. The bad value
   * is not quoted, only its index.
   */
  enumArray<T extends string>(field: string, values: readonly T[]): T[] {
    const value = this.payload[field];
    if (!Array.isArray(value)) {
      throw this.error(
        `field "${field}" must be an array, got ${describe(value)}`,
      );
    }
    value.forEach((item, i) => {
      if (!(values as readonly unknown[]).includes(item)) {
        throw this.error(`${field}[${i}] must be one of ${values.join(', ')}`);
      }
    });
    return (value as unknown[]).slice() as T[];
  }

  /** A UUID or null; the property itself is required (absent is not null). */
  nullableUuid(field: string): string | null {
    this.present(field);
    return this.payload[field] === null ? null : this.uuid(field);
  }

  /** One of the enum values or null; the property itself is required. */
  nullableEnum<T extends string>(
    field: string,
    values: readonly T[],
  ): T | null {
    this.present(field);
    return this.payload[field] === null ? null : this.enumValue(field, values);
  }

  /** An array of distinct UUIDs (uniqueItems), possibly empty; bad items by index only. */
  uuidSet(field: string): string[] {
    const value = this.payload[field];
    if (!Array.isArray(value)) {
      throw this.error(
        `field "${field}" must be an array, got ${describe(value)}`,
      );
    }
    value.forEach((item, i) => {
      if (typeof item !== 'string' || !UUID.test(item)) {
        throw this.error(`${field}[${i}] must be a UUID`);
      }
      if (value.indexOf(item) !== i) {
        throw this.error(`${field}[${i}] repeats an earlier item`);
      }
    });
    return (value as string[]).slice();
  }

  /** `remedyLegs`: possibly empty, each exactly the contract's `remedyLeg`. */
  remedyLegs(): RemedyLeg[] {
    const value = this.payload.remedyLegs;
    if (!Array.isArray(value)) {
      throw this.error(
        `field "remedyLegs" must be an array, got ${describe(value)}`,
      );
    }
    return value.map((item, i) => this.remedyLeg(item, i));
  }

  private remedyLeg(value: unknown, index: number): RemedyLeg {
    const at = `remedyLegs[${index}]`;
    if (!isPlainObject(value)) {
      throw this.error(`${at} must be a JSON object, got ${describe(value)}`);
    }
    for (const key of Object.keys(value)) {
      if (!(REMEDY_LEG_FIELDS as readonly string[]).includes(key)) {
        throw this.error(`unknown property "${key}" in ${at}`);
      }
    }
    const oneOf = <T extends string>(
      field: string,
      values: readonly T[],
    ): T => {
      if (!(values as readonly unknown[]).includes(value[field])) {
        throw this.error(`${at}.${field} must be one of ${values.join(', ')}`);
      }
      return value[field] as T;
    };

    const id = value.remedyLegId;
    if (typeof id !== 'string' || !UUID.test(id)) {
      throw this.error(`${at}.remedyLegId must be a UUID`);
    }
    const amount = value.amount;
    if (!isMoney(amount)) {
      throw this.error(`${at}.amount ${MONEY_RULE}`);
    }
    return {
      remedyLegId: id,
      remedyType: oneOf('remedyType', REMEDY_TYPES),
      fundType: oneOf('fundType', FUND_TYPES),
      role: oneOf('role', PARTY_ROLES),
      amount,
    };
  }

  /** A required property that may hold null. */
  private present(field: string): void {
    if (!(field in this.payload))
      throw this.error(`field "${field}" is missing`);
  }

  /** A JSON boolean; the bad value is not quoted. */
  boolean(field: string): boolean {
    const value = this.payload[field];
    if (typeof value !== 'boolean') {
      throw this.error(
        `field "${field}" must be a boolean, got ${describe(value)}`,
      );
    }
    return value;
  }

  /** An array of strings, possibly empty; items are not quoted, only their index. */
  stringArray(field: string): string[] {
    const value = this.payload[field];
    if (!Array.isArray(value)) {
      throw this.error(
        `field "${field}" must be an array, got ${describe(value)}`,
      );
    }
    value.forEach((item, i) => {
      if (typeof item !== 'string') {
        throw this.error(
          `${field}[${i}] must be a string, got ${describe(item)}`,
        );
      }
    });
    return (value as string[]).slice();
  }

  /** An optional `{type: object}` outside `required`: absent is null, null is not allowed. */
  optionalObject(field: string): Record<string, unknown> | null {
    return field in this.payload ? this.object(field) : null;
  }

  /** A JSON object of any shape, as `contractTermsSnapshot` is declared. */
  object(field: string): Record<string, unknown> {
    const value = this.payload[field];
    if (!isPlainObject(value)) {
      throw this.error(
        `field "${field}" must be a JSON object, got ${describe(value)}`,
      );
    }
    return value;
  }

  /** `payload.recipients`: at least one, each exactly {userId, email, role}. */
  recipients(): NotificationRecipient[] {
    const value = this.payload.recipients;
    if (!Array.isArray(value) || value.length === 0) {
      throw this.error(
        `field "recipients" must be a non-empty array, got ${describe(value)}`,
      );
    }
    return value.map((item, i) => this.recipient(item, i));
  }

  private recipient(value: unknown, index: number): NotificationRecipient {
    const at = `recipients[${index}]`;
    if (!isPlainObject(value)) {
      throw this.error(`${at} must be a JSON object, got ${describe(value)}`);
    }
    for (const key of Object.keys(value)) {
      if (!['userId', 'email', 'role'].includes(key)) {
        throw this.error(`unknown property "${key}" in ${at}`);
      }
    }

    const userId = value.userId;
    // Nullable by contract, but absent is not null: the property is required.
    if (!('userId' in value)) throw this.error(`${at}.userId is missing`);
    if (userId !== null && !(typeof userId === 'string' && UUID.test(userId))) {
      throw this.error(`${at}.userId must be a UUID or null`);
    }

    const email = value.email;
    if (typeof email !== 'string' || !EMAIL.test(email)) {
      throw this.error(`${at}.email must be an email address`);
    }

    const role = value.role;
    if (
      typeof role !== 'string' ||
      !(RECIPIENT_ROLES as readonly string[]).includes(role)
    ) {
      throw this.error(
        `${at}.role must be one of ${RECIPIENT_ROLES.join(', ')}`,
      );
    }

    return { userId, email, role: role as RecipientRole };
  }

  private string(field: string): string {
    const value = this.payload[field];
    if (typeof value !== 'string' || value === '') {
      throw this.error(
        `field "${field}" must be a non-empty string, got ${describe(value)}`,
      );
    }
    return value;
  }

  error(reason: string): NonRetryableError {
    return malformed(this.eventType, this.eventId, reason);
  }
}

/** The one error shape of this module: never quotes a payload value. */
function malformed(
  eventType: string,
  eventId: string,
  reason: string,
): NonRetryableError {
  const event = SAFE_ID.test(eventId) ? ` (event ${eventId})` : '';
  return new NonRetryableError(
    `Malformed ${eventType} command: ${reason}${event}`,
  );
}

/**
 * A well-formed command this service cannot mail yet (a termination type
 * without a template): dead-lettered at once, like a malformed one, so it
 * waits in the DLQ for the template instead of being acked unsent. `reason`
 * must quote enum values only.
 */
export function unsupportedCommand(
  eventType: string,
  eventId: string,
  reason: string,
): NonRetryableError {
  const event = SAFE_ID.test(eventId) ? ` (event ${eventId})` : '';
  return new NonRetryableError(
    `Unsupported ${eventType} command: ${reason}${event}`,
  );
}

/**
 * Validates the envelope of a command and returns a reader over its payload.
 * `producer` is checked against the contract's `const`: a command from another
 * service is not the one this template renders.
 */
function readCommand(
  body: Buffer,
  eventType: string,
  producer: string,
): { envelope: EventEnvelope; payload: CommandReader } {
  let envelope: EventEnvelope;
  try {
    envelope = parseEnvelope(body);
  } catch (err) {
    if (err instanceof InvalidEnvelopeError) {
      throw new NonRetryableError(
        `Malformed ${eventType} command: ${err.message}`,
      );
    }
    throw err;
  }

  const fail = (reason: string) =>
    malformed(eventType, envelope.eventId, reason);

  for (const key of Object.keys(envelope as unknown as object)) {
    if (!(ENVELOPE_PROPERTIES as readonly string[]).includes(key)) {
      throw fail(`unknown envelope property "${key}"`);
    }
  }
  // eventType is pattern-checked by the envelope, so quoting it leaks nothing.
  if (envelope.eventType !== eventType) {
    throw fail(`eventType "${envelope.eventType}" is not this queue's command`);
  }
  if (envelope.producer !== producer) {
    throw fail(`producer must be "${producer}"`);
  }
  if (
    typeof envelope.correlationId !== 'string' ||
    !UUID.test(envelope.correlationId)
  ) {
    throw fail('correlationId must be a UUID');
  }
  if (!isPlainObject(envelope.payload)) {
    throw fail('payload must be a JSON object');
  }

  return {
    envelope,
    payload: new CommandReader(eventType, envelope.eventId, envelope.payload),
  };
}

export const CONTRACT_ANCHOR_REQUESTED =
  'notification.contract_anchor_requested';

/** Payload properties of `contractAnchorRequested`, all required. */
export const CONTRACT_ANCHOR_FIELDS = [
  'contractId',
  'recipients',
  'contractTermsSnapshot',
  'signedContentHash',
  'signedAt',
] as const;

export function parseContractAnchorRequested(
  body: Buffer,
): ContractAnchorRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    CONTRACT_ANCHOR_REQUESTED,
    'contract-service',
  );
  payload.only(CONTRACT_ANCHOR_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    contractTermsSnapshot: payload.object('contractTermsSnapshot'),
    signedContentHash: payload.hash('signedContentHash'),
    signedAt: payload.dateTime('signedAt'),
  };
}

export const ACTIVATION_CANCEL_REQUESTED =
  'notification.activation_cancel_requested';

/** Payload properties of `activationCancelRequested`, all required. */
export const ACTIVATION_CANCEL_FIELDS = [
  'contractId',
  'recipients',
  'requestedByRole',
  'oneSidedCancelAllowedFrom',
] as const;

export function parseActivationCancelRequested(
  body: Buffer,
): ActivationCancelRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    ACTIVATION_CANCEL_REQUESTED,
    'contract-service',
  );
  payload.only(ACTIVATION_CANCEL_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    requestedByRole: payload.enumValue(
      'requestedByRole',
      CANCEL_REQUESTER_ROLES,
    ),
    oneSidedCancelAllowedFrom: payload.dateTime('oneSidedCancelAllowedFrom'),
  };
}

export const MILESTONE_STATUS_REQUESTED =
  'notification.milestone_status_requested';

/** Payload properties of `milestoneStatusRequested`; `deadline` and `statusData` are optional. */
export const MILESTONE_STATUS_FIELDS = [
  'contractId',
  'milestoneId',
  'recipients',
  'statusType',
  'deadline',
  'statusData',
] as const;

/** The required ones among MILESTONE_STATUS_FIELDS. */
export const MILESTONE_STATUS_REQUIRED = [
  'contractId',
  'milestoneId',
  'recipients',
  'statusType',
] as const;

/**
 * contract-service's milestone status (D96). `statusData` is any JSON object
 * when present (the contract types it `object` and nothing more): its values
 * are checked when the email reads them, not here, so a publisher adding a
 * field never dead-letters the command.
 */
export function parseMilestoneStatusRequested(
  body: Buffer,
): MilestoneStatusRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    MILESTONE_STATUS_REQUESTED,
    'contract-service',
  );
  payload.only(MILESTONE_STATUS_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    milestoneId: payload.uuid('milestoneId'),
    recipients: payload.recipients(),
    statusType: payload.enumValue('statusType', MILESTONE_STATUS_TYPES),
    deadline: payload.optionalNullableDateTime('deadline'),
    statusData: payload.optionalObject('statusData'),
  };
}

export const BREACH_NOTICE_REQUESTED = 'notification.breach_notice_requested';

/** Payload properties of `breachNoticeRequested`, all required. */
export const BREACH_NOTICE_FIELDS = [
  'breachCaseId',
  'contractId',
  'recipients',
  'breachReasonCode',
  'severity',
  'evidenceRefs',
] as const;

/**
 * contract-service's BreachCase notice (D96). `evidenceRefs` are any strings
 * by contract; the email shows only those that look like a file reference.
 */
export function parseBreachNoticeRequested(
  body: Buffer,
): BreachNoticeRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    BREACH_NOTICE_REQUESTED,
    'contract-service',
  );
  payload.only(BREACH_NOTICE_FIELDS);

  return {
    eventId: envelope.eventId,
    reportedAt: envelope.occurredAt,
    breachCaseId: payload.uuid('breachCaseId'),
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    breachReasonCode: payload.enumValue(
      'breachReasonCode',
      BREACH_REASON_CODES,
    ),
    severity: payload.enumValue('severity', BREACH_SEVERITIES),
    evidenceRefs: payload.stringArray('evidenceRefs'),
  };
}

export const REMEDY_FINALIZED_REQUESTED =
  'notification.remedy_finalized_requested';

/** Payload properties of `remedyFinalizedRequested`, all required. */
export const REMEDY_FINALIZED_FIELDS = [
  'remedyDecisionId',
  'attributionDecisionId',
  'breachCaseId',
  'contractId',
  'buyerId',
  'sellerId',
  'affectedMilestoneIds',
  'recipients',
  'finalBreachingRole',
  'breachReasonCode',
  'qualityDisposition',
  'decisionSource',
  'penaltyEligible',
  'reputationEligible',
  'remedyLegs',
] as const;

/**
 * contract-service's finalized remedy decision (D96), with the contract's
 * four consistency rules (its `allOf` of if/then):
 * - no breaching role: no penalty, no reputation, legs only NONE,
 *   PAYMENT_SETTLEMENT or PAYMENT_REFUND;
 * - penaltyEligible false: no CONTRACTUAL_PENALTY leg;
 * - CONFORMING or PARTIALLY_CONFORMING: no role, no reason, no penalty, no
 *   reputation;
 * - NON_CONFORMING: SELLER, QUALITY_BELOW_COMMITTED, penalty and reputation.
 * A command breaking one would make the email contradict the decision.
 */
export function parseRemedyFinalizedRequested(
  body: Buffer,
): RemedyFinalizedRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    REMEDY_FINALIZED_REQUESTED,
    'contract-service',
  );
  payload.only(REMEDY_FINALIZED_FIELDS);

  const command: RemedyFinalizedRequestedCommand = {
    eventId: envelope.eventId,
    remedyDecisionId: payload.uuid('remedyDecisionId'),
    attributionDecisionId: payload.uuid('attributionDecisionId'),
    breachCaseId: payload.nullableUuid('breachCaseId'),
    contractId: payload.uuid('contractId'),
    buyerId: payload.uuid('buyerId'),
    sellerId: payload.uuid('sellerId'),
    affectedMilestoneIds: payload.uuidSet('affectedMilestoneIds'),
    recipients: payload.recipients(),
    finalBreachingRole: payload.nullableEnum('finalBreachingRole', PARTY_ROLES),
    breachReasonCode: payload.nullableEnum(
      'breachReasonCode',
      BREACH_REASON_CODES,
    ),
    qualityDisposition: payload.nullableEnum(
      'qualityDisposition',
      QUALITY_DISPOSITIONS,
    ),
    decisionSource: payload.enumValue('decisionSource', DECISION_SOURCES),
    penaltyEligible: payload.boolean('penaltyEligible'),
    reputationEligible: payload.boolean('reputationEligible'),
    remedyLegs: payload.remedyLegs(),
  };

  const legTypes = command.remedyLegs.map((leg) => leg.remedyType);
  if (command.finalBreachingRole === null) {
    if (command.penaltyEligible || command.reputationEligible) {
      throw payload.error(
        'a decision with no finalBreachingRole cannot be penaltyEligible or reputationEligible',
      );
    }
    if (
      legTypes.some(
        (t) => !['NONE', 'PAYMENT_SETTLEMENT', 'PAYMENT_REFUND'].includes(t),
      )
    ) {
      throw payload.error(
        'a decision with no finalBreachingRole can only have NONE, PAYMENT_SETTLEMENT or PAYMENT_REFUND legs',
      );
    }
  }
  if (!command.penaltyEligible && legTypes.includes('CONTRACTUAL_PENALTY')) {
    throw payload.error(
      'a CONTRACTUAL_PENALTY leg needs penaltyEligible = true',
    );
  }
  const quality = command.qualityDisposition;
  if (
    (quality === 'CONFORMING' || quality === 'PARTIALLY_CONFORMING') &&
    (command.finalBreachingRole !== null ||
      command.breachReasonCode !== null ||
      command.penaltyEligible ||
      command.reputationEligible)
  ) {
    throw payload.error(
      `qualityDisposition ${quality} needs no finalBreachingRole, no breachReasonCode, no penalty and no reputation`,
    );
  }
  if (
    quality === 'NON_CONFORMING' &&
    (command.finalBreachingRole !== 'SELLER' ||
      command.breachReasonCode !== 'QUALITY_BELOW_COMMITTED' ||
      !command.penaltyEligible ||
      !command.reputationEligible)
  ) {
    throw payload.error(
      'qualityDisposition NON_CONFORMING needs SELLER, QUALITY_BELOW_COMMITTED, penalty and reputation',
    );
  }
  return command;
}

export const MILESTONE_ANCHOR_REQUESTED =
  'notification.milestone_anchor_requested';

/** Payload properties of `milestoneAnchorRequested`, all required. */
export const MILESTONE_ANCHOR_FIELDS = [
  'contractId',
  'milestoneId',
  'recipients',
  'recordHash',
  'otsProof',
  'settlementSummary',
] as const;

/**
 * audit-service's settlement email with the OpenTimestamps proof (hash-chain
 * design §4.3). `otsProof` is any string here, as the contract declares it:
 * whether it is a usable base64 proof is the use case's check, which
 * dead-letters a bad one with its own message. `settlementSummary` is any
 * JSON object.
 */
export function parseMilestoneAnchorRequested(
  body: Buffer,
): MilestoneAnchorRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    MILESTONE_ANCHOR_REQUESTED,
    'audit-service',
  );
  payload.only(MILESTONE_ANCHOR_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    milestoneId: payload.uuid('milestoneId'),
    recipients: payload.recipients(),
    recordHash: payload.hash('recordHash'),
    otsProof: payload.text('otsProof'),
    settlementSummary: payload.object('settlementSummary'),
  };
}

export const CONTRACT_DOCUMENT_READY_REQUESTED =
  'notification.contract_document_ready_requested';

/** Payload properties of `contractDocumentReadyRequested`, all required. */
export const CONTRACT_DOCUMENT_READY_FIELDS = [
  'documentId',
  'contractId',
  'recipients',
  'fileId',
  'sourceContentHash',
  'documentFileHash',
  'templateCode',
  'templateVersion',
  'generatedAt',
] as const;

/** The contract's `templateCode` pattern. */
const TEMPLATE_CODE = /^VN_COFFEE_[A-Z0-9_]+$/;

/**
 * contract-service's final-document email (design §3.3). The hashes are the
 * contract's lowercase SHA-256 hex; `templateVersion` is any non-empty
 * string. The PDF itself is not in the command: the use case reads it from
 * file-service by `fileId`.
 */
export function parseContractDocumentReadyRequested(
  body: Buffer,
): ContractDocumentReadyRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    CONTRACT_DOCUMENT_READY_REQUESTED,
    'contract-service',
  );
  payload.only(CONTRACT_DOCUMENT_READY_FIELDS);

  const templateCode = payload.text('templateCode');
  if (!TEMPLATE_CODE.test(templateCode)) {
    throw payload.error(
      'field "templateCode" must match ^VN_COFFEE_[A-Z0-9_]+$',
    );
  }
  const templateVersion = payload.text('templateVersion');
  if (templateVersion === '') {
    throw payload.error('field "templateVersion" must not be empty');
  }

  return {
    eventId: envelope.eventId,
    documentId: payload.uuid('documentId'),
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    fileId: payload.uuid('fileId'),
    sourceContentHash: payload.hash('sourceContentHash'),
    documentFileHash: payload.hash('documentFileHash'),
    templateCode,
    templateVersion,
    generatedAt: payload.dateTime('generatedAt'),
  };
}

export const MILESTONE_FUNDING_STATUS_REQUESTED =
  'notification.milestone_funding_status_requested';

/** Payload properties of `milestoneFundingStatusRequested`; `deadline` and `reason` are optional. */
export const MILESTONE_FUNDING_STATUS_FIELDS = [
  'contractId',
  'milestoneId',
  'recipients',
  'statusType',
  'deadline',
  'reason',
] as const;

/** The required ones among MILESTONE_FUNDING_STATUS_FIELDS. */
export const MILESTONE_FUNDING_STATUS_REQUIRED = [
  'contractId',
  'milestoneId',
  'recipients',
  'statusType',
] as const;

/**
 * contract-service's funding status of one milestone (milestone-escrow
 * design §6b). `deadline` and `reason` may be absent or null with any
 * statusType: which status carries them is the publisher's rule, and the
 * email says when none was given.
 */
export function parseMilestoneFundingStatusRequested(
  body: Buffer,
): MilestoneFundingStatusRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    MILESTONE_FUNDING_STATUS_REQUESTED,
    'contract-service',
  );
  payload.only(MILESTONE_FUNDING_STATUS_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    milestoneId: payload.uuid('milestoneId'),
    recipients: payload.recipients(),
    statusType: payload.enumValue('statusType', MILESTONE_FUNDING_STATUS_TYPES),
    deadline: payload.optionalNullableDateTime('deadline'),
    reason: payload.optionalNullableText('reason'),
  };
}

export const CONTRACT_ACTIVATION_FAILED_REQUESTED =
  'notification.contract_activation_failed_requested';

/** Payload properties of `contractActivationFailedRequested`, all required. */
export const CONTRACT_ACTIVATION_FAILED_FIELDS = [
  'contractId',
  'recipients',
  'failedLeg',
  'buyerDepositState',
  'sellerDepositState',
  'nextActions',
  'oneSidedCancelAllowedFrom',
] as const;

export function parseContractActivationFailedRequested(
  body: Buffer,
): ContractActivationFailedRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    CONTRACT_ACTIVATION_FAILED_REQUESTED,
    'contract-service',
  );
  payload.only(CONTRACT_ACTIVATION_FAILED_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    failedLeg: payload.enumValue('failedLeg', FAILED_LEGS),
    buyerDepositState: payload.text('buyerDepositState'),
    sellerDepositState: payload.text('sellerDepositState'),
    nextActions: payload.enumArray('nextActions', ACTIVATION_NEXT_ACTIONS),
    oneSidedCancelAllowedFrom: payload.dateTime('oneSidedCancelAllowedFrom'),
  };
}

export const CONTRACT_TERMINATED_REQUESTED =
  'notification.contract_terminated_requested';

/** Payload properties of `contractTerminatedRequested`, all required. */
export const CONTRACT_TERMINATED_FIELDS = [
  'contractId',
  'recipients',
  'terminationType',
  'requestedBy',
  'finalBreachingRole',
  'breachReasonCode',
  'remedyDecisionId',
  'breachCaseId',
  'affectedMilestoneIds',
  'supersededByContractId',
  'replacesContractId',
  'remedyLegs',
] as const;

/**
 * Every termination type parses, so the whole contract is checked here; which
 * types have an email is decided after parsing (command-routes.ts).
 */
export function parseContractTerminatedRequested(
  body: Buffer,
): ContractTerminatedRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    CONTRACT_TERMINATED_REQUESTED,
    'contract-service',
  );
  payload.only(CONTRACT_TERMINATED_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    recipients: payload.recipients(),
    terminationType: payload.enumValue('terminationType', TERMINATION_TYPES),
    requestedBy: payload.enumValue('requestedBy', TERMINATION_REQUESTERS),
    finalBreachingRole: payload.nullableEnum('finalBreachingRole', PARTY_ROLES),
    breachReasonCode: payload.nullableEnum(
      'breachReasonCode',
      BREACH_REASON_CODES,
    ),
    remedyDecisionId: payload.nullableUuid('remedyDecisionId'),
    breachCaseId: payload.nullableUuid('breachCaseId'),
    affectedMilestoneIds: payload.uuidSet('affectedMilestoneIds'),
    supersededByContractId: payload.nullableUuid('supersededByContractId'),
    replacesContractId: payload.nullableUuid('replacesContractId'),
    remedyLegs: payload.remedyLegs(),
  };
}

export const USER_KYC_RESULT_REQUESTED =
  'notification.user_kyc_result_requested';

/** Payload properties of `userKycResultRequested`. */
export const USER_KYC_RESULT_FIELDS = [
  'userId',
  'recipientEmail',
  'organizationName',
  'result',
  'reason',
  'decidedAt',
] as const;

/** All of them but `reason`, which user-service leaves out for VERIFIED. */
export const USER_KYC_RESULT_REQUIRED = USER_KYC_RESULT_FIELDS.filter(
  (field) => field !== 'reason',
);

/**
 * user-service's command: one user, one address, no `payload.recipients`.
 * A REJECTED without a reason, or a VERIFIED with one, is what the contract
 * allows, so both parse; the template decides what to show.
 */
export function parseUserKycResultRequested(
  body: Buffer,
): UserKycResultRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    USER_KYC_RESULT_REQUESTED,
    'user-service',
  );
  payload.only(USER_KYC_RESULT_FIELDS);

  return {
    eventId: envelope.eventId,
    userId: payload.uuid('userId'),
    recipientEmail: payload.email('recipientEmail'),
    organizationName: payload.text('organizationName'),
    result: payload.enumValue('result', KYC_RESULTS),
    reason: payload.optionalText('reason'),
    decidedAt: payload.dateTime('decidedAt'),
  };
}

export const USER_LOCK_CHANGED_REQUESTED =
  'notification.user_lock_changed_requested';

/** Payload properties of `userLockChangedRequested`. */
export const USER_LOCK_CHANGED_FIELDS = [
  'userId',
  'recipientEmail',
  'action',
  'effectiveUntil',
  'reasonCode',
] as const;

/** All of them but `effectiveUntil`, which the contract leaves optional. */
export const USER_LOCK_CHANGED_REQUIRED = USER_LOCK_CHANGED_FIELDS.filter(
  (field) => field !== 'effectiveUntil',
);

/**
 * user-service's lock command: one user, one address. A LOCKED without an
 * end is what the contract allows (user-service never sends one), so it
 * parses; the template words it.
 */
export function parseUserLockChangedRequested(
  body: Buffer,
): UserLockChangedRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    USER_LOCK_CHANGED_REQUESTED,
    'user-service',
  );
  payload.only(USER_LOCK_CHANGED_FIELDS);

  return {
    eventId: envelope.eventId,
    userId: payload.uuid('userId'),
    recipientEmail: payload.email('recipientEmail'),
    action: payload.enumValue('action', LOCK_ACTIONS),
    effectiveUntil: payload.optionalNullableDateTime('effectiveUntil'),
    reasonCode: payload.text('reasonCode'),
  };
}

export const LEVEL15_ASSIGNMENT_WAITING_REQUESTED =
  'notification.level1_5_assignment_waiting_requested';

/** Payload properties of `level15AssignmentWaitingRequested`, all required. */
export const LEVEL15_ASSIGNMENT_WAITING_FIELDS = [
  'contractId',
  'milestoneId',
  'reason',
  'waitingSince',
  'stage',
] as const;

/**
 * inspection-service's staff alert (D49): no recipient in the payload, the
 * address is this service's own `notification.operatorEmail`. Nothing in it
 * is personal data, but the errors still quote field names and enum values
 * only, as everywhere else.
 */
export function parseLevel15AssignmentWaitingRequested(
  body: Buffer,
): Level15AssignmentWaitingRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    LEVEL15_ASSIGNMENT_WAITING_REQUESTED,
    'inspection-service',
  );
  payload.only(LEVEL15_ASSIGNMENT_WAITING_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    milestoneId: payload.uuid('milestoneId'),
    reason: payload.enumValue('reason', LEVEL15_WAITING_REASONS),
    waitingSince: payload.dateTime('waitingSince'),
    stage: payload.enumValue('stage', LEVEL15_WAITING_STAGES),
  };
}

export const LEVEL15_INSPECTOR_ASSIGNMENT_REQUESTED =
  'notification.level1_5_inspector_assignment_requested';

/** Payload properties of `level15InspectorAssignmentRequested`, all required. */
export const LEVEL15_INSPECTOR_ASSIGNMENT_FIELDS = [
  'contractId',
  'milestoneId',
  'commissionId',
  'recipients',
  'reportDueAt',
  'freeCancelUntil',
  'stage',
] as const;

/**
 * The mail to the assigned Level 1.5 inspector (D57). The contract narrows
 * `recipients` to one entry of role INSPECTOR (`maxItems: 1`, role
 * `const: INSPECTOR`): a second recipient or another role is not a mail this
 * template may send, so it is dead-lettered like any malformed command.
 */
export function parseLevel15InspectorAssignmentRequested(
  body: Buffer,
): Level15InspectorAssignmentRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    LEVEL15_INSPECTOR_ASSIGNMENT_REQUESTED,
    'inspection-service',
  );
  payload.only(LEVEL15_INSPECTOR_ASSIGNMENT_FIELDS);

  const contractId = payload.uuid('contractId');
  const milestoneId = payload.uuid('milestoneId');
  const commissionId = payload.uuid('commissionId');
  const recipients = payload.recipients();
  if (recipients.length !== 1) {
    throw payload.error(
      `field "recipients" must hold exactly one inspector, got ${recipients.length}`,
    );
  }
  const [recipient] = recipients;
  if (recipient.role !== 'INSPECTOR') {
    throw payload.error('recipients[0].role must be INSPECTOR');
  }

  return {
    eventId: envelope.eventId,
    contractId,
    milestoneId,
    commissionId,
    recipient: { ...recipient, role: 'INSPECTOR' },
    reportDueAt: payload.dateTime('reportDueAt'),
    freeCancelUntil: payload.dateTime('freeCancelUntil'),
    stage: payload.enumValue('stage', LEVEL15_INSPECTOR_STAGES),
  };
}

export const ESCROW_LEG_ALERT_REQUESTED =
  'notification.escrow_leg_alert_requested';

/** Payload properties of `escrowLegAlertRequested`, all required. */
export const ESCROW_LEG_ALERT_FIELDS = [
  'contractId',
  'legId',
  'legKind',
  'party',
  'legStatus',
  'amount',
  'bankCommandEventId',
  'since',
  'stage',
] as const;

/**
 * escrow-service's staff alert (D68): no recipient in the payload, the
 * address is this service's own `notification.operatorEmail`. Any legStatus
 * of the contract parses with any stage: which status a stage is sent in is
 * escrow's rule, and the email shows the status as it came.
 */
export function parseEscrowLegAlertRequested(
  body: Buffer,
): EscrowLegAlertRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    ESCROW_LEG_ALERT_REQUESTED,
    'escrow-service',
  );
  payload.only(ESCROW_LEG_ALERT_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    legId: payload.uuid('legId'),
    legKind: payload.enumValue('legKind', ESCROW_LEG_KINDS),
    party: payload.enumValue('party', PARTY_ROLES),
    legStatus: payload.enumValue('legStatus', ESCROW_LEG_STATUSES),
    amount: payload.money('amount'),
    bankCommandEventId: payload.uuid('bankCommandEventId'),
    since: payload.dateTime('since'),
    stage: payload.enumValue('stage', ESCROW_LEG_ALERT_STAGES),
  };
}

export const ESCROW_REMEDY_LEG_ALERT_REQUESTED =
  'notification.escrow_remedy_leg_alert_requested';

/** Payload properties of `escrowRemedyLegAlertRequested`, all required. */
export const ESCROW_REMEDY_LEG_ALERT_FIELDS = [
  'contractId',
  'remedyDecisionId',
  'remedyLegId',
  'party',
  'amount',
  'bankCommandEventId',
  'since',
] as const;

/**
 * escrow-service's staff alert for a remedy leg out of resends (D93): no
 * recipient in the payload, the address is this service's own
 * `notification.operatorEmail`. `party` is the payee.
 */
export function parseEscrowRemedyLegAlertRequested(
  body: Buffer,
): EscrowRemedyLegAlertRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    ESCROW_REMEDY_LEG_ALERT_REQUESTED,
    'escrow-service',
  );
  payload.only(ESCROW_REMEDY_LEG_ALERT_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    remedyDecisionId: payload.uuid('remedyDecisionId'),
    remedyLegId: payload.uuid('remedyLegId'),
    party: payload.enumValue('party', PARTY_ROLES),
    amount: payload.money('amount'),
    bankCommandEventId: payload.uuid('bankCommandEventId'),
    since: payload.dateTime('since'),
  };
}

export const ESCROW_REMEDY_REJECTED_REQUESTED =
  'notification.escrow_remedy_rejected_requested';

/** Payload properties of `escrowRemedyRejectedRequested`, all required. */
export const ESCROW_REMEDY_REJECTED_FIELDS = [
  'contractId',
  'remedyDecisionId',
  'reason',
  'rejectedAt',
] as const;

/**
 * escrow-service's staff alert for a whole remedy decision it refused (D93):
 * no recipient in the payload, the address is this service's own
 * `notification.operatorEmail`. `reason` is escrow's free text, 1 to 500
 * characters; an error names its length, never its words.
 */
export function parseEscrowRemedyRejectedRequested(
  body: Buffer,
): EscrowRemedyRejectedRequestedCommand {
  const { envelope, payload } = readCommand(
    body,
    ESCROW_REMEDY_REJECTED_REQUESTED,
    'escrow-service',
  );
  payload.only(ESCROW_REMEDY_REJECTED_FIELDS);

  return {
    eventId: envelope.eventId,
    contractId: payload.uuid('contractId'),
    remedyDecisionId: payload.uuid('remedyDecisionId'),
    reason: payload.boundedText(
      'reason',
      ESCROW_REMEDY_REJECTED_REASON_MAX_LENGTH,
    ),
    rejectedAt: payload.dateTime('rejectedAt'),
  };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The JSON type of a bad value, never the value itself. */
function describe(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  return `a ${typeof value}`;
}
