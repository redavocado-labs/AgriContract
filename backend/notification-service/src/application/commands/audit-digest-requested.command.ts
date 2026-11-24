import type { NotificationRecipient } from './notification-recipient';

/**
 * `notification.audit_digest_requested` (notification-commands.yaml
 * `auditDigestRequested`), published by audit-service's weekly DigestJob only
 * after VerifyChainJob set WEEKLY_VERIFY_OK (hash-chain design §5.1). The
 * weekly digest to the Software Buyer contacts (design §4): the hash of the
 * chain's head, a copy kept outside the platform with a timestamp the
 * platform does not control.
 *
 * `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface AuditDigestRequestedCommand {
  eventId: string;
  recipients: NotificationRecipient[];
  /** SHA-256 of the chain's head record, lowercase hex. */
  recordHash: string;
}
