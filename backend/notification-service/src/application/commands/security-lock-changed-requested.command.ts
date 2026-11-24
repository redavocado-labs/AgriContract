import type { NotificationRecipient } from './notification-recipient';

/** The system lock's change: the contract's `action` enum. */
export const SECURITY_LOCK_ACTIONS = ['LOCKED', 'UNLOCKED'] as const;
export type SecurityLockAction = (typeof SECURITY_LOCK_ACTIONS)[number];

/**
 * `notification.security_lock_changed_requested` (notification-commands.yaml
 * `securityLockChangedRequested`), published by bank-service once the
 * External Verifier's signed emergency lock or unlock is committed (bank
 * design §3.5.4). While the system lock is on, bank-service refuses every
 * lock, release, seize and refund.
 *
 * To the Admin and the External Verifier only, never the buyers and sellers
 * (design §4). `reason` is the verifier's own text, any string: shown as
 * sent, never logged. `eventId` is the envelope's: the dedup key's event
 * (design §5).
 */
export interface SecurityLockChangedRequestedCommand {
  eventId: string;
  recipients: NotificationRecipient[];
  action: SecurityLockAction;
  reason: string;
  /** When the lock or unlock was committed. */
  timestamp: string;
}
