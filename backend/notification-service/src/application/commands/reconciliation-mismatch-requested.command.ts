import type { NotificationRecipient } from './notification-recipient';

/**
 * `notification.reconciliation_mismatch_requested` (notification-commands.yaml
 * `reconciliationMismatchRequested`), published by bank-service's
 * LedgerAuditReconciliationJob when the ledger of a contract does not match
 * the amounts of its anchored audit record (bank design §5b.1). Nothing is
 * fixed automatically.
 *
 * Admin only (design §4). `mismatchSummary` is a free object
 * (`type: object`): the email shows it whole and reads no field from it.
 * `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface ReconciliationMismatchRequestedCommand {
  eventId: string;
  contractId: string;
  recipients: NotificationRecipient[];
  mismatchSummary: Record<string, unknown>;
}
