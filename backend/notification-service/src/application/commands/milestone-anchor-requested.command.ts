import type { NotificationRecipient } from './notification-recipient';

/**
 * `notification.milestone_anchor_requested` (notification-commands.yaml
 * `milestoneAnchorRequested`), published by audit-service once a
 * `milestone.settled` is written to the audit chain and its record hash has
 * an OpenTimestamps proof (hash-chain design §4.3). The settlement email to
 * buyer and seller, with the proof attached (design §3.3).
 *
 * `otsProof` is the `.ots` file, base64 as design §3.3 says; the contract
 * types it a plain string, so it is decoded and checked by the use case, not
 * the parser. `settlementSummary` is a free object (`type: object`): the
 * email reads only the values it knows, each checked for its shape.
 * `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface MilestoneAnchorRequestedCommand {
  eventId: string;
  contractId: string;
  milestoneId: string;
  recipients: NotificationRecipient[];
  /** SHA-256 of the audit record: the digest the OpenTimestamps proof commits to. */
  recordHash: string;
  /** The `.ots` proof, base64. */
  otsProof: string;
  settlementSummary: Record<string, unknown>;
}
