import type { NotificationRecipient } from './notification-recipient';

/** Which funding email this is: the contract's `statusType` enum. */
export const MILESTONE_FUNDING_STATUS_TYPES = [
  'FUNDING_REMINDER',
  'FUNDING_FAILED',
  'FUNDING_CURE_DEADLINE',
] as const;
export type MilestoneFundingStatusType =
  (typeof MILESTONE_FUNDING_STATUS_TYPES)[number];

/**
 * `notification.milestone_funding_status_requested` (notification-commands.yaml
 * `milestoneFundingStatusRequested`), published by contract-service about the
 * buyer's funding of one milestone (milestone-escrow design §6b):
 * FUNDING_REMINDER to have the money ready, FUNDING_FAILED once escrow ran out
 * of lock retries (no penalty yet, the seller's delivery clock pauses), and
 * FUNDING_CURE_DEADLINE for the end of the cure window.
 *
 * `recipients` are chosen by the publisher (buyer, seller, operator), one
 * email each. `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface MilestoneFundingStatusRequestedCommand {
  eventId: string;
  contractId: string;
  milestoneId: string;
  recipients: NotificationRecipient[];
  statusType: MilestoneFundingStatusType;
  /** UTC instant computed by contract-service in Vietnam time; absent or null = none. */
  deadline: string | null;
  /** Free text from contract-service; absent or null = none. */
  reason: string | null;
}
