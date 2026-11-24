import type { NotificationRecipient } from './notification-recipient';

/**
 * `notification.audit_failure_requested` (notification-commands.yaml
 * `auditFailureRequested`), published by audit-service when the weekly
 * VerifyChainJob set WEEKLY_VERIFY_FAILED (hash-chain design §5.1). The
 * integrity alert to the Admin and several Software Buyer contacts at once,
 * with no approval step in between (hash-chain design §5.2, design §8).
 *
 * `failureSummary` is a free object (`type: object`): where the check found
 * the first mismatch, or that the anchored hash is missing. The email shows
 * it whole and reads no field from it. `eventId` is the envelope's: the
 * dedup key's event (design §5).
 */
export interface AuditFailureRequestedCommand {
  eventId: string;
  recipients: NotificationRecipient[];
  failureSummary: Record<string, unknown>;
}
