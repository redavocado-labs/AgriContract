import type { NotificationRecipient } from './notification-recipient';

/** Which milestone email this is: the contract's `statusType` enum. */
export const MILESTONE_STATUS_TYPES = [
  'WEIGHED',
  'CONFIRMED',
  'FLAGGED',
  'FORCE_MAJEURE',
  'LEVEL2_OPTOUT_REQUESTED',
  'BUYER_RECEIVE_REMINDER',
  'PENALTY',
  'PROVISIONAL_LEVEL2',
] as const;
export type MilestoneStatusType = (typeof MILESTONE_STATUS_TYPES)[number];

/**
 * `notification.milestone_status_requested` (notification-commands.yaml
 * `milestoneStatusRequested`), published by contract-service as a milestone
 * moves (milestone-escrow design §3.2; D96): the seller weighed, the buyer
 * has not received yet, the milestone settled, the buyer flagged it.
 *
 * `statusData` is a free object in the contract (`type: object`), so the
 * email reads from it only the values it knows, each checked for its shape
 * when the body is rendered; an absent or odd value is left out of the
 * email, never a reason to dead-letter the command. `eventId` is the
 * envelope's: the dedup key's event (design §5).
 */
export interface MilestoneStatusRequestedCommand {
  eventId: string;
  contractId: string;
  milestoneId: string;
  recipients: NotificationRecipient[];
  statusType: MilestoneStatusType;
  /** UTC instant computed by contract-service in Vietnam time; absent or null = none. */
  deadline: string | null;
  /** Absent = null. */
  statusData: Record<string, unknown> | null;
}
