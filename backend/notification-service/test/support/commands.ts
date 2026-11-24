import type {
  ActivationCancelRequestedCommand,
  ContractActivationFailedRequestedCommand,
  ContractAnchorRequestedCommand,
  ContractDocumentReadyRequestedCommand,
  ContractTerminatedRequestedCommand,
  EscrowLegAlertRequestedCommand,
  EscrowRemedyLegAlertRequestedCommand,
  EscrowRemedyRejectedRequestedCommand,
  Level15AssignmentWaitingRequestedCommand,
  Level15InspectorAssignmentRequestedCommand,
  MilestoneFundingStatusRequestedCommand,
  NotificationRecipient,
  UserKycResultRequestedCommand,
  UserLockChangedRequestedCommand,
} from '../../src/application/commands';

/*
 * Fixtures for the Phase 2 notification commands
 * (contracts/events/notification-commands.yaml). The envelope is the one
 * contract-service writes to its outbox for a signed contract (#360), so the
 * contract test can hand the same object to Ajv and to the parser.
 */

const CONTRACT_ID = '3f1b9c7e-5d2a-4c8b-9e1f-7a6b5c4d3e2f';

/** The terms snapshot, trimmed to a readable subset of the signed canonical JSON. */
export const TERMS_SNAPSHOT: Record<string, unknown> = {
  agreedPrice: '92000.00',
  currency: 'VND',
  contractTemplateCode: 'VN_COFFEE_B2B',
  contractTemplateVersion: '1.0.0',
  milestoneSchedule: [
    {
      milestoneIndex: 1,
      committedQuantity: 1200,
      expectedDeliveryDate: '2027-01-15',
      graceDays: 3,
    },
  ],
  totalContractValue: '110400000.00',
};

/**
 * `signedContentHash` of `TERMS_SNAPSHOT`, computed the way contract-service
 * does (`JcsTermsCanonicalizer`): `org.erdtman.jcs.JsonCanonicalizer` 1.1, then
 * SHA-256 of the UTF-8 bytes. Written out rather than computed here, so a test
 * comparing against it does not reuse the code it checks.
 */
export const TERMS_SNAPSHOT_HASH =
  '67e2e1076c96f3df33106f897bdac5e0bdd60c2c013a63bd5f336d2fa63b493f';

export const ANCHOR_RECIPIENTS: NotificationRecipient[] = [
  {
    userId: 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b',
    email: 'buyer@anchor.test',
    role: 'BUYER',
  },
  {
    userId: '9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b',
    email: 'seller@anchor.test',
    role: 'SELLER',
  },
];

/** A valid `notification.contract_anchor_requested` message, as published. */
export function anchorEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: '1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d',
    eventType: 'notification.contract_anchor_requested',
    eventVersion: 1,
    occurredAt: '2026-11-08T09:30:00Z',
    producer: 'contract-service',
    aggregateId: CONTRACT_ID,
    correlationId: CONTRACT_ID,
    causationId: null,
    payload: anchorPayload(),
    ...overrides,
  };
}

export function anchorPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: CONTRACT_ID,
    recipients: ANCHOR_RECIPIENTS.map((r) => ({ ...r })),
    contractTermsSnapshot: { ...TERMS_SNAPSHOT },
    signedContentHash: TERMS_SNAPSHOT_HASH,
    signedAt: '2026-11-08T09:30:00Z',
    ...overrides,
  };
}

export const anchorBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(anchorEnvelope(overrides)));

/** The command the parser is expected to produce from `anchorEnvelope()`. */
export function anchorCommand(): ContractAnchorRequestedCommand {
  const envelope = anchorEnvelope();
  return {
    eventId: envelope.eventId as string,
    contractId: CONTRACT_ID,
    recipients: ANCHOR_RECIPIENTS.map((r) => ({ ...r })),
    contractTermsSnapshot: { ...TERMS_SNAPSHOT },
    signedContentHash: TERMS_SNAPSHOT_HASH,
    signedAt: '2026-11-08T09:30:00Z',
  };
}

/*
 * `notification.activation_cancel_requested`, as contract-service writes it
 * (OutboxContractEventPublisher.activationCancelRequested): the buyer asked,
 * so the one recipient is the seller.
 */
export const CANCEL_RECIPIENT: NotificationRecipient = {
  userId: '9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b',
  email: 'seller@cancel.test',
  role: 'SELLER',
};

export function cancelEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: '6c5d4e3f-2a1b-4c0d-9e8f-7a6b5c4d3e2f',
    eventType: 'notification.activation_cancel_requested',
    eventVersion: 1,
    occurredAt: '2026-11-09T03:00:00Z',
    producer: 'contract-service',
    aggregateId: CONTRACT_ID,
    correlationId: CONTRACT_ID,
    causationId: null,
    payload: cancelPayload(),
    ...overrides,
  };
}

export function cancelPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: CONTRACT_ID,
    recipients: [{ ...CANCEL_RECIPIENT }],
    requestedByRole: 'BUYER',
    oneSidedCancelAllowedFrom: '2026-11-14T03:00:00Z',
    ...overrides,
  };
}

export const cancelBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(cancelEnvelope(overrides)));

/** The command the parser is expected to produce from `cancelEnvelope()`. */
export function cancelCommand(): ActivationCancelRequestedCommand {
  return {
    eventId: cancelEnvelope().eventId as string,
    contractId: CONTRACT_ID,
    recipients: [{ ...CANCEL_RECIPIENT }],
    requestedByRole: 'BUYER',
    oneSidedCancelAllowedFrom: '2026-11-14T03:00:00Z',
  };
}

/*
 * `notification.contract_activation_failed_requested`, as contract-service
 * writes it (OutboxContractEventPublisher.contractActivationFailedRequested)
 * from escrow-service's `escrow.deposit_lock_failed`: the buyer's lock failed,
 * the seller's is held, and both parties are addressed.
 */
export const ACTIVATION_FAILED_RECIPIENTS: NotificationRecipient[] = [
  {
    userId: 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b',
    email: 'buyer@activation.test',
    role: 'BUYER',
  },
  {
    userId: '9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b',
    email: 'seller@activation.test',
    role: 'SELLER',
  },
];

export function activationFailedEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: '4b3a2c1d-0e9f-4a8b-8c7d-6e5f4a3b2c1d',
    eventType: 'notification.contract_activation_failed_requested',
    eventVersion: 1,
    occurredAt: '2026-11-10T02:00:00.123456Z',
    producer: 'contract-service',
    aggregateId: CONTRACT_ID,
    correlationId: CONTRACT_ID,
    causationId: '7d6c5b4a-3f2e-4d1c-8b0a-9f8e7d6c5b4a',
    payload: activationFailedPayload(),
    ...overrides,
  };
}

export function activationFailedPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: CONTRACT_ID,
    recipients: ACTIVATION_FAILED_RECIPIENTS.map((r) => ({ ...r })),
    failedLeg: 'BUYER',
    buyerDepositState: 'LOCK_FAILED',
    sellerDepositState: 'DEPOSIT_LOCKED',
    nextActions: ['RETRY_DEPOSIT_LOCK', 'REQUEST_ACTIVATION_CANCEL'],
    oneSidedCancelAllowedFrom: '2026-11-13T17:00:00Z',
    ...overrides,
  };
}

export const activationFailedBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(activationFailedEnvelope(overrides)));

/** The command the parser is expected to produce from `activationFailedEnvelope()`. */
export function activationFailedCommand(): ContractActivationFailedRequestedCommand {
  return {
    eventId: activationFailedEnvelope().eventId as string,
    contractId: CONTRACT_ID,
    recipients: ACTIVATION_FAILED_RECIPIENTS.map((r) => ({ ...r })),
    failedLeg: 'BUYER',
    buyerDepositState: 'LOCK_FAILED',
    sellerDepositState: 'DEPOSIT_LOCKED',
    nextActions: ['RETRY_DEPOSIT_LOCK', 'REQUEST_ACTIVATION_CANCEL'],
    oneSidedCancelAllowedFrom: '2026-11-13T17:00:00Z',
  };
}

/*
 * `notification.contract_terminated_requested` for WITHDRAW_OFFER, as
 * contract-service writes it (OutboxContractEventPublisher
 * .offerWithdrawnNoticeRequested): the buyer withdrew, so the one recipient is
 * the seller; every breach, remedy and supersede field is null or empty.
 */
export const WITHDRAW_RECIPIENT: NotificationRecipient = {
  userId: '9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b',
  email: 'seller@withdraw.test',
  role: 'SELLER',
};

export function terminatedEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: '5e4d3c2b-1a0f-4e9d-8c7b-6a5f4e3d2c1b',
    eventType: 'notification.contract_terminated_requested',
    eventVersion: 1,
    occurredAt: '2026-11-10T04:30:00.123456Z',
    producer: 'contract-service',
    aggregateId: CONTRACT_ID,
    correlationId: CONTRACT_ID,
    causationId: null,
    payload: terminatedPayload(),
    ...overrides,
  };
}

export function terminatedPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: CONTRACT_ID,
    recipients: [{ ...WITHDRAW_RECIPIENT }],
    terminationType: 'WITHDRAW_OFFER',
    requestedBy: 'BUYER',
    finalBreachingRole: null,
    breachReasonCode: null,
    remedyDecisionId: null,
    breachCaseId: null,
    affectedMilestoneIds: [],
    supersededByContractId: null,
    replacesContractId: null,
    remedyLegs: [],
    ...overrides,
  };
}

export const terminatedBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(terminatedEnvelope(overrides)));

/** The command the parser is expected to produce from `terminatedEnvelope()`. */
export function terminatedCommand(): ContractTerminatedRequestedCommand {
  return {
    eventId: terminatedEnvelope().eventId as string,
    contractId: CONTRACT_ID,
    recipients: [{ ...WITHDRAW_RECIPIENT }],
    terminationType: 'WITHDRAW_OFFER',
    requestedBy: 'BUYER',
    finalBreachingRole: null,
    breachReasonCode: null,
    remedyDecisionId: null,
    breachCaseId: null,
    affectedMilestoneIds: [],
    supersededByContractId: null,
    replacesContractId: null,
    remedyLegs: [],
  };
}

/** A breach termination with every field filled, to show the parser reads them all. */
export const BREACH_TERMINATION: Record<string, unknown> = {
  terminationType: 'TERMINATION_FOR_BREACH',
  requestedBy: 'SYSTEM',
  finalBreachingRole: 'SELLER',
  breachReasonCode: 'NON_DELIVERY',
  remedyDecisionId: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
  breachCaseId: '1b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e',
  affectedMilestoneIds: [
    '2c3d4e5f-6a7b-4c8d-8e9f-1a2b3c4d5e6f',
    '3d4e5f6a-7b8c-4d9e-9f0a-2b3c4d5e6f7a',
  ],
  supersededByContractId: null,
  replacesContractId: null,
  remedyLegs: [
    {
      remedyLegId: '4e5f6a7b-8c9d-4e0f-8a1b-3c4d5e6f7a8b',
      remedyType: 'DEPOSIT_FORFEITURE',
      fundType: 'SELLER_DEPOSIT',
      role: 'SELLER',
      amount: '7126425.00',
    },
  ],
};

/*
 * `notification.user_kyc_result_requested`, as user-service writes it
 * (OutboxKycResultPublisher): the decision starts its own chain, so
 * correlationId is the command's eventId and causationId is null;
 * aggregateId is the user, and occurredAt and decidedAt are both the stored
 * decision time. This one is a rejection.
 */
export const KYC_USER_ID = '5f4e3d2c-1b0a-4f9e-8d7c-6b5a4f3e2d1c';
export const KYC_EMAIL = 'owner@kyc.test';
const KYC_EVENT_ID = '2c1b0a9f-8e7d-4c6b-9a5f-4e3d2c1b0a9f';

export function kycEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: KYC_EVENT_ID,
    eventType: 'notification.user_kyc_result_requested',
    eventVersion: 1,
    occurredAt: '2026-11-11T02:30:00.123456Z',
    producer: 'user-service',
    aggregateId: KYC_USER_ID,
    correlationId: KYC_EVENT_ID,
    causationId: null,
    payload: kycPayload(),
    ...overrides,
  };
}

export function kycPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    userId: KYC_USER_ID,
    recipientEmail: KYC_EMAIL,
    organizationName: 'Dak Lak Coffee Cooperative',
    result: 'REJECTED',
    reason: 'The business licence is not legible.',
    decidedAt: '2026-11-11T02:30:00.123456Z',
    ...overrides,
  };
}

/** A VERIFIED payload: user-service leaves `reason` out entirely. */
export function kycVerifiedPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const payload = kycPayload({ result: 'VERIFIED', ...overrides });
  if (!('reason' in overrides)) delete payload.reason;
  return payload;
}

export const kycBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(kycEnvelope(overrides)));

/** The command the parser is expected to produce from `kycEnvelope()`. */
export function kycCommand(): UserKycResultRequestedCommand {
  return {
    eventId: KYC_EVENT_ID,
    userId: KYC_USER_ID,
    recipientEmail: KYC_EMAIL,
    organizationName: 'Dak Lak Coffee Cooperative',
    result: 'REJECTED',
    reason: 'The business licence is not legible.',
    decidedAt: '2026-11-11T02:30:00.123456Z',
  };
}

/*
 * `notification.user_lock_changed_requested`, as user-service writes it
 * (OutboxLockChangePublisher) after applying a `reputation.locked`: the
 * command continues the reputation event's chain, so causationId is that
 * event's eventId and correlationId is inherited. effectiveUntil is always
 * written, null when no lock is left.
 */
export const LOCK_USER_ID = '3e2d1c0b-9a8f-4e7d-8c6b-5a4f3e2d1c0b';
export const LOCK_EMAIL = 'trader@lock.test';
const LOCK_EVENT_ID = '6d5c4b3a-2f1e-4d0c-9b8a-7f6e5d4c3b2a';

export function lockEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: LOCK_EVENT_ID,
    eventType: 'notification.user_lock_changed_requested',
    eventVersion: 1,
    occurredAt: '2026-11-11T04:00:00.654321Z',
    producer: 'user-service',
    aggregateId: LOCK_USER_ID,
    correlationId: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
    causationId: '9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a',
    payload: lockPayload(),
    ...overrides,
  };
}

export function lockPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    userId: LOCK_USER_ID,
    recipientEmail: LOCK_EMAIL,
    action: 'LOCKED',
    effectiveUntil: '2026-12-11T04:00:00Z',
    reasonCode: 'STRATEGIC_ABANDONMENT',
    ...overrides,
  };
}

export const lockBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(lockEnvelope(overrides)));

/** The command the parser is expected to produce from `lockEnvelope()`. */
export function lockCommand(): UserLockChangedRequestedCommand {
  return {
    eventId: LOCK_EVENT_ID,
    userId: LOCK_USER_ID,
    recipientEmail: LOCK_EMAIL,
    action: 'LOCKED',
    effectiveUntil: '2026-12-11T04:00:00Z',
    reasonCode: 'STRATEGIC_ABANDONMENT',
  };
}

/*
 * `notification.level1_5_assignment_waiting_requested`, as inspection-service
 * is to write it (#439, D49) when a Level 1.5 request finds no inspector: the
 * first command of a wait, WAITING_STARTED. No recipient: it goes to the
 * configured operator address.
 */
export const WAITING_CONTRACT_ID = '5b4a3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d';
export const WAITING_MILESTONE_ID = '8c7d6e5f-4a3b-4c2d-9e1f-0a9b8c7d6e5f';
export const OPERATOR_EMAIL = 'operator@agricontract.test';
const WAITING_EVENT_ID = '2c1d0e9f-8a7b-4c6d-9e5f-4a3b2c1d0e9f';

export function waitingEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: WAITING_EVENT_ID,
    eventType: 'notification.level1_5_assignment_waiting_requested',
    eventVersion: 1,
    occurredAt: '2026-11-16T03:00:00.250Z',
    producer: 'inspection-service',
    aggregateId: WAITING_MILESTONE_ID,
    correlationId: '7e6d5c4b-3a2f-4e1d-8c0b-9a8f7e6d5c4b',
    causationId: '4f3e2d1c-0b9a-4876-9543-210fedcba987',
    payload: waitingPayload(),
    ...overrides,
  };
}

export function waitingPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: WAITING_CONTRACT_ID,
    milestoneId: WAITING_MILESTONE_ID,
    reason: 'NO_ELIGIBLE',
    waitingSince: '2026-11-16T03:00:00Z',
    stage: 'WAITING_STARTED',
    ...overrides,
  };
}

export const waitingBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(waitingEnvelope(overrides)));

/** The command the parser is expected to produce from `waitingEnvelope()`. */
export function waitingCommand(): Level15AssignmentWaitingRequestedCommand {
  return {
    eventId: WAITING_EVENT_ID,
    contractId: WAITING_CONTRACT_ID,
    milestoneId: WAITING_MILESTONE_ID,
    reason: 'NO_ELIGIBLE',
    waitingSince: '2026-11-16T03:00:00Z',
    stage: 'WAITING_STARTED',
  };
}

/*
 * `notification.level1_5_inspector_assignment_requested`, as inspection-service
 * is to write it (D57) when it assigns a Level 1.5 inspector: stage ASSIGNED,
 * one recipient of role INSPECTOR.
 */
export const INSPECTOR_CONTRACT_ID = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
export const INSPECTOR_MILESTONE_ID = '2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e';
export const INSPECTOR_COMMISSION_ID = '3c4d5e6f-7a8b-4c9d-8e1f-2a3b4c5d6e7f';
export const INSPECTOR_RECIPIENT = {
  userId: '4d5e6f7a-8b9c-4d0e-9f1a-2b3c4d5e6f7a',
  email: 'inspector1@inspect.test',
  role: 'INSPECTOR',
} as const satisfies NotificationRecipient;
const INSPECTOR_EVENT_ID = '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b';

export function inspectorEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: INSPECTOR_EVENT_ID,
    eventType: 'notification.level1_5_inspector_assignment_requested',
    eventVersion: 1,
    occurredAt: '2026-11-20T02:00:00.125Z',
    producer: 'inspection-service',
    aggregateId: INSPECTOR_MILESTONE_ID,
    correlationId: '6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c',
    causationId: '7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d',
    payload: inspectorPayload(),
    ...overrides,
  };
}

export function inspectorPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: INSPECTOR_CONTRACT_ID,
    milestoneId: INSPECTOR_MILESTONE_ID,
    commissionId: INSPECTOR_COMMISSION_ID,
    recipients: [{ ...INSPECTOR_RECIPIENT }],
    reportDueAt: '2026-11-25T10:00:00Z',
    freeCancelUntil: '2026-11-21T02:00:00Z',
    stage: 'ASSIGNED',
    ...overrides,
  };
}

export const inspectorBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(inspectorEnvelope(overrides)));

/** The command the parser is expected to produce from `inspectorEnvelope()`. */
export function inspectorCommand(): Level15InspectorAssignmentRequestedCommand {
  return {
    eventId: INSPECTOR_EVENT_ID,
    contractId: INSPECTOR_CONTRACT_ID,
    milestoneId: INSPECTOR_MILESTONE_ID,
    commissionId: INSPECTOR_COMMISSION_ID,
    recipient: { ...INSPECTOR_RECIPIENT },
    reportDueAt: '2026-11-25T10:00:00Z',
    freeCancelUntil: '2026-11-21T02:00:00Z',
    stage: 'ASSIGNED',
  };
}

/*
 * `notification.escrow_leg_alert_requested`, as escrow-service's leg monitor
 * is to write it (#528, D68): the buyer's deposit leg has waited on the bank
 * for its lock past the threshold, stage STUCK. No recipient: it goes to the
 * configured operator address.
 */
export const LEG_ALERT_CONTRACT_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
export const LEG_ALERT_LEG_ID = '0b1c2d3e-4f5a-4b6c-9d7e-8f9a0b1c2d3e';
export const LEG_ALERT_BANK_COMMAND_ID = '1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f';
const LEG_ALERT_EVENT_ID = '2d3e4f5a-6b7c-4d8e-9f0a-1b2c3d4e5f6a';

export function legAlertEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: LEG_ALERT_EVENT_ID,
    eventType: 'notification.escrow_leg_alert_requested',
    eventVersion: 1,
    occurredAt: '2026-11-21T03:05:00.500Z',
    producer: 'escrow-service',
    aggregateId: LEG_ALERT_LEG_ID,
    correlationId: '3e4f5a6b-7c8d-4e9f-8a0b-2c3d4e5f6a7b',
    causationId: '4f5a6b7c-8d9e-4f0a-9b1c-3d4e5f6a7b8c',
    payload: legAlertPayload(),
    ...overrides,
  };
}

export function legAlertPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: LEG_ALERT_CONTRACT_ID,
    legId: LEG_ALERT_LEG_ID,
    legKind: 'DEPOSIT',
    party: 'BUYER',
    legStatus: 'REQUESTED',
    amount: '150000000.00',
    bankCommandEventId: LEG_ALERT_BANK_COMMAND_ID,
    since: '2026-11-21T03:00:00Z',
    stage: 'STUCK',
    ...overrides,
  };
}

export const legAlertBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(legAlertEnvelope(overrides)));

/** The command the parser is expected to produce from `legAlertEnvelope()`. */
export function legAlertCommand(): EscrowLegAlertRequestedCommand {
  return {
    eventId: LEG_ALERT_EVENT_ID,
    contractId: LEG_ALERT_CONTRACT_ID,
    legId: LEG_ALERT_LEG_ID,
    legKind: 'DEPOSIT',
    party: 'BUYER',
    legStatus: 'REQUESTED',
    amount: '150000000.00',
    bankCommandEventId: LEG_ALERT_BANK_COMMAND_ID,
    since: '2026-11-21T03:00:00Z',
    stage: 'STUCK',
  };
}

/*
 * `notification.escrow_remedy_leg_alert_requested`, as escrow-service is to
 * write it (#609, D93): the seller's payout of a finalized remedy decision
 * was refused by the bank with no resend left. No recipient: it goes to the
 * configured operator address.
 */
export const REMEDY_CONTRACT_ID = '7e6d5c4b-3a29-4817-a6f5-e4d3c2b1a098';
export const REMEDY_DECISION_ID = '8f7e6d5c-4b3a-4928-b7a6-f5e4d3c2b1a0';
export const REMEDY_LEG_ID = '9a8f7e6d-5c4b-4a39-88b7-a6f5e4d3c2b1';
export const REMEDY_BANK_COMMAND_ID = 'a09f8e7d-6c5b-4a4b-99c8-b7a6f5e4d3c2';
const REMEDY_LEG_ALERT_EVENT_ID = 'b1a09f8e-7d6c-4b5a-8ad9-c8b7a6f5e4d3';
const REMEDY_REJECTED_EVENT_ID = 'c2b1a09f-8e7d-4c6b-9bea-d9c8b7a6f5e4';

export function remedyLegAlertEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: REMEDY_LEG_ALERT_EVENT_ID,
    eventType: 'notification.escrow_remedy_leg_alert_requested',
    eventVersion: 1,
    occurredAt: '2026-11-24T08:15:00.250Z',
    producer: 'escrow-service',
    aggregateId: REMEDY_LEG_ID,
    correlationId: 'd3c2b1a0-9f8e-4d7c-8cfb-e0d9c8b7a6f5',
    causationId: 'e4d3c2b1-a09f-4e8d-9d0c-f1e0d9c8b7a6',
    payload: remedyLegAlertPayload(),
    ...overrides,
  };
}

export function remedyLegAlertPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: REMEDY_CONTRACT_ID,
    remedyDecisionId: REMEDY_DECISION_ID,
    remedyLegId: REMEDY_LEG_ID,
    party: 'SELLER',
    amount: '45000000.00',
    bankCommandEventId: REMEDY_BANK_COMMAND_ID,
    since: '2026-11-24T08:14:59Z',
    ...overrides,
  };
}

export const remedyLegAlertBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(remedyLegAlertEnvelope(overrides)));

/** The command the parser is expected to produce from `remedyLegAlertEnvelope()`. */
export function remedyLegAlertCommand(): EscrowRemedyLegAlertRequestedCommand {
  return {
    eventId: REMEDY_LEG_ALERT_EVENT_ID,
    contractId: REMEDY_CONTRACT_ID,
    remedyDecisionId: REMEDY_DECISION_ID,
    remedyLegId: REMEDY_LEG_ID,
    party: 'SELLER',
    amount: '45000000.00',
    bankCommandEventId: REMEDY_BANK_COMMAND_ID,
    since: '2026-11-24T08:14:59Z',
  };
}

/*
 * `notification.escrow_remedy_rejected_requested`, as escrow-service is to
 * write it (#609, D93): it refused the same decision as a whole, because its
 * legs exceed the money still locked.
 */
export const REMEDY_REJECTED_REASON =
  'Remedy legs total 160000000.00 but only 150000000.00 is still locked for the contract';

export function remedyRejectedEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: REMEDY_REJECTED_EVENT_ID,
    eventType: 'notification.escrow_remedy_rejected_requested',
    eventVersion: 1,
    occurredAt: '2026-11-24T08:00:01.125Z',
    producer: 'escrow-service',
    aggregateId: REMEDY_DECISION_ID,
    correlationId: 'f5e4d3c2-b1a0-4f9e-8e1d-a2f1e0d9c8b7',
    payload: remedyRejectedPayload(),
    ...overrides,
  };
}

export function remedyRejectedPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: REMEDY_CONTRACT_ID,
    remedyDecisionId: REMEDY_DECISION_ID,
    reason: REMEDY_REJECTED_REASON,
    rejectedAt: '2026-11-24T08:00:00Z',
    ...overrides,
  };
}

export const remedyRejectedBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(remedyRejectedEnvelope(overrides)));

/** The command the parser is expected to produce from `remedyRejectedEnvelope()`. */
export function remedyRejectedCommand(): EscrowRemedyRejectedRequestedCommand {
  return {
    eventId: REMEDY_REJECTED_EVENT_ID,
    contractId: REMEDY_CONTRACT_ID,
    remedyDecisionId: REMEDY_DECISION_ID,
    reason: REMEDY_REJECTED_REASON,
    rejectedAt: '2026-11-24T08:00:00Z',
  };
}

/*
 * `notification.milestone_funding_status_requested`, as contract-service is
 * to write it (milestone-escrow §6b): escrow ran out of lock retries for the
 * second milestone, statusType FUNDING_FAILED, to the buyer, the seller and
 * an operator, with the end of the cure window and escrow's reason.
 */
export const FUNDING_CONTRACT_ID = '4d3c2b1a-0f9e-4d8c-8b7a-6f5e4d3c2b1a';
export const FUNDING_MILESTONE_ID = '5e4d3c2b-1a0f-4e9d-9c8b-7a6f5e4d3c2b';
const FUNDING_EVENT_ID = '6f5e4d3c-2b1a-4f0e-8d9c-8b7a6f5e4d3c';

export const FUNDING_RECIPIENTS: NotificationRecipient[] = [
  {
    userId: '7a6f5e4d-3c2b-4a1f-9e0d-9c8b7a6f5e4d',
    email: 'buyer@funding.test',
    role: 'BUYER',
  },
  {
    userId: '8b7a6f5e-4d3c-4b2a-8f1e-0d9c8b7a6f5e',
    email: 'seller@funding.test',
    role: 'SELLER',
  },
  { userId: null, email: 'ops@funding.test', role: 'OPERATOR' },
];

export const FUNDING_REASON = 'Insufficient funds in the buyer account';

export function fundingEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: FUNDING_EVENT_ID,
    eventType: 'notification.milestone_funding_status_requested',
    eventVersion: 1,
    occurredAt: '2026-12-01T02:00:00.125Z',
    producer: 'contract-service',
    aggregateId: FUNDING_CONTRACT_ID,
    correlationId: '9c8b7a6f-5e4d-4c3b-9a2f-1e0d9c8b7a6f',
    causationId: 'a0d9c8b7-a6f5-4e4d-8c3b-2a1f0e9d8c7b',
    payload: fundingPayload(),
    ...overrides,
  };
}

export function fundingPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    contractId: FUNDING_CONTRACT_ID,
    milestoneId: FUNDING_MILESTONE_ID,
    recipients: FUNDING_RECIPIENTS.map((r) => ({ ...r })),
    statusType: 'FUNDING_FAILED',
    deadline: '2026-12-04T16:59:59.999Z',
    reason: FUNDING_REASON,
    ...overrides,
  };
}

export const fundingBody = (overrides: Record<string, unknown> = {}): Buffer =>
  Buffer.from(JSON.stringify(fundingEnvelope(overrides)));

/** The command the parser is expected to produce from `fundingEnvelope()`. */
export function fundingCommand(): MilestoneFundingStatusRequestedCommand {
  return {
    eventId: FUNDING_EVENT_ID,
    contractId: FUNDING_CONTRACT_ID,
    milestoneId: FUNDING_MILESTONE_ID,
    recipients: FUNDING_RECIPIENTS.map((r) => ({ ...r })),
    statusType: 'FUNDING_FAILED',
    deadline: '2026-12-04T16:59:59.999Z',
    reason: FUNDING_REASON,
  };
}

/*
 * `notification.contract_document_ready_requested`, as contract-service is to
 * write it after `contract.document_generated` (design §3.3): the final
 * signed contract PDF of the anchor fixture's contract, to its buyer and
 * seller. DOCUMENT_PDF stands for the stored bytes; DOCUMENT_PDF_SHA256 is
 * their SHA-256, from sha256sum rather than from the code under test.
 */
export const DOCUMENT_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e';
export const DOCUMENT_FILE_ID = 'c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f';
const DOCUMENT_EVENT_ID = 'd4e5f6a7-b8c9-4d0e-9f1a-3b4c5d6e7f8a';

export const DOCUMENT_PDF = Buffer.from(
  '%PDF-1.7\n% AgriContract final signed contract (test bytes)\n%%EOF\n',
  'latin1',
);
export const DOCUMENT_PDF_SHA256 =
  'b0c1599c63930fdb7a8ef24e770abedf61595981ce0fdea9277605cf6064d137';

export function documentReadyEnvelope(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    eventId: DOCUMENT_EVENT_ID,
    eventType: 'notification.contract_document_ready_requested',
    eventVersion: 1,
    occurredAt: '2026-11-25T04:00:01.500Z',
    producer: 'contract-service',
    aggregateId: CONTRACT_ID,
    correlationId: 'e5f6a7b8-c9d0-4e1f-8a2b-4c5d6e7f8a9b',
    causationId: 'f6a7b8c9-d0e1-4f2a-9b3c-5d6e7f8a9b0c',
    payload: documentReadyPayload(),
    ...overrides,
  };
}

export function documentReadyPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    documentId: DOCUMENT_ID,
    contractId: CONTRACT_ID,
    recipients: ANCHOR_RECIPIENTS.map((r) => ({ ...r })),
    fileId: DOCUMENT_FILE_ID,
    sourceContentHash: TERMS_SNAPSHOT_HASH,
    documentFileHash: DOCUMENT_PDF_SHA256,
    templateCode: 'VN_COFFEE_B2B',
    templateVersion: '1.0.0',
    generatedAt: '2026-11-25T04:00:00Z',
    ...overrides,
  };
}

export const documentReadyBody = (
  overrides: Record<string, unknown> = {},
): Buffer => Buffer.from(JSON.stringify(documentReadyEnvelope(overrides)));

/** The command the parser is expected to produce from `documentReadyEnvelope()`. */
export function documentReadyCommand(): ContractDocumentReadyRequestedCommand {
  return {
    eventId: DOCUMENT_EVENT_ID,
    documentId: DOCUMENT_ID,
    contractId: CONTRACT_ID,
    recipients: ANCHOR_RECIPIENTS.map((r) => ({ ...r })),
    fileId: DOCUMENT_FILE_ID,
    sourceContentHash: TERMS_SNAPSHOT_HASH,
    documentFileHash: DOCUMENT_PDF_SHA256,
    templateCode: 'VN_COFFEE_B2B',
    templateVersion: '1.0.0',
    generatedAt: '2026-11-25T04:00:00Z',
  };
}
