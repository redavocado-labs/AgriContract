/** The contract's `reason`: 1 to 500 characters. */
export const ESCROW_REMEDY_REJECTED_REASON_MAX_LENGTH = 500;

/**
 * `notification.escrow_remedy_rejected_requested` (notification-commands.yaml
 * `escrowRemedyRejectedRequested`, D93), published by escrow-service once per
 * `remedyDecisionId` when it refused a whole `remedy.finalized` decision
 * because its legs exceed the money still locked (milestone-escrow design
 * §6.8). No bank command was sent; normal operation never gets here, so it
 * means a calculation or data error. The contract stays ACTIVE and there is no
 * platform action to fix it.
 *
 * A staff-only alert: the command names no recipient, and the email goes to
 * this service's `notification.operatorEmail`. `eventId` is the envelope's:
 * the dedup key's event (design §5).
 */
export interface EscrowRemedyRejectedRequestedCommand {
  eventId: string;
  contractId: string;
  remedyDecisionId: string;
  /** escrow-service's own words: free text, never logged. */
  reason: string;
  rejectedAt: string;
}
