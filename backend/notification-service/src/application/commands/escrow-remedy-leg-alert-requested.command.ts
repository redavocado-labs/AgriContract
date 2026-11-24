import type { PartyRole } from './contract-terminated-requested.command';

/**
 * `notification.escrow_remedy_leg_alert_requested` (notification-commands.yaml
 * `escrowRemedyLegAlertRequested`, D93), published by escrow-service once when
 * a money leg of a `remedy.finalized` decision was refused by the bank with no
 * resend left (milestone-escrow design §6.8). A staff-only alert: the command
 * names no recipient, and the email goes to this service's
 * `notification.operatorEmail`.
 *
 * The contract stays ACTIVE until that leg completes. The Operator fixes the
 * cause (kill switch, bank error) and asks contract-service to resend the
 * failed legs (RetryRemedyLegs); escrow resends with the same bank command id.
 * `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface EscrowRemedyLegAlertRequestedCommand {
  eventId: string;
  contractId: string;
  remedyDecisionId: string;
  remedyLegId: string;
  /** The payee of the leg. */
  party: PartyRole;
  /** Non-negative VND with two decimals, as the contract's `money`. */
  amount: string;
  /** The sourceEventId escrow sent to the bank for the leg: what the operator greps for. */
  bankCommandEventId: string;
  /** When the leg failed for good. */
  since: string;
}
