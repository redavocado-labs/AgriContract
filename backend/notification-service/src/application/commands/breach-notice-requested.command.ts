import type { BreachReasonCode } from './contract-terminated-requested.command';
import type { NotificationRecipient } from './notification-recipient';

/** The contract's `severity` enum. */
export const BREACH_SEVERITIES = ['MINOR', 'MATERIAL'] as const;
export type BreachSeverity = (typeof BREACH_SEVERITIES)[number];

/**
 * `notification.breach_notice_requested` (notification-commands.yaml
 * `breachNoticeRequested`), published by contract-service when a BreachCase
 * opens (milestone-escrow design §6.4; D96). The formal, timestamped notice
 * at the start of the evidence chain: an allegation, not a decision.
 *
 * `reportedAt` is the envelope's `occurredAt`, which contract-service sets to
 * the case's reportedAt. `eventId` is the envelope's: the dedup key's event.
 */
export interface BreachNoticeRequestedCommand {
  eventId: string;
  reportedAt: string;
  breachCaseId: string;
  contractId: string;
  recipients: NotificationRecipient[];
  breachReasonCode: BreachReasonCode;
  severity: BreachSeverity;
  /** File references of the case's evidence, as sent: plain strings in the contract. */
  evidenceRefs: string[];
}
