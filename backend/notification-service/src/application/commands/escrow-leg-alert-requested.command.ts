import type { PartyRole } from './contract-terminated-requested.command';

/** The kind of leg: the contract's `legKind` enum. Milestone legs come later (§3.1b). */
export const ESCROW_LEG_KINDS = ['DEPOSIT'] as const;
export type EscrowLegKind = (typeof ESCROW_LEG_KINDS)[number];

/** The leg's state when the command was written: the contract's `legStatus` enum. */
export const ESCROW_LEG_STATUSES = [
  'REQUESTED',
  'LOCKED',
  'FAILED',
  'CANCEL_PENDING',
  'CANCELLED',
  'RETURN_REQUESTED',
  'RETURN_FAILED',
  'RETURNED',
] as const;
export type EscrowLegStatus = (typeof ESCROW_LEG_STATUSES)[number];

/** Which alert this is: the contract's `stage` enum. */
export const ESCROW_LEG_ALERT_STAGES = [
  'STUCK',
  'RESOLVED',
  'RETURN_EXHAUSTED',
] as const;
export type EscrowLegAlertStage = (typeof ESCROW_LEG_ALERT_STAGES)[number];

/**
 * `notification.escrow_leg_alert_requested` (notification-commands.yaml
 * `escrowLegAlertRequested`, D68), published by escrow-service's leg monitor
 * (milestone-escrow design §3.1b). A staff-only alert: the command names no
 * recipient, and the email goes to this service's `notification.operatorEmail`.
 *
 * STUCK once per waiting episode, when a leg waits on the bank (REQUESTED,
 * CANCEL_PENDING, RETURN_REQUESTED) longer than escrow's threshold; RESOLVED
 * once when a leg alerted STUCK gets its bank result; RETURN_EXHAUSTED once
 * when a return failed with no resend left. `eventId` is the envelope's: the
 * dedup key's event (design §5), so each alert is its own email.
 */
export interface EscrowLegAlertRequestedCommand {
  eventId: string;
  contractId: string;
  legId: string;
  legKind: EscrowLegKind;
  party: PartyRole;
  legStatus: EscrowLegStatus;
  /** Non-negative VND with two decimals, as the contract's `money`. */
  amount: string;
  /** The sourceEventId escrow sent to the bank for this state: what the operator greps for. */
  bankCommandEventId: string;
  /** When the problem began: entry into the waiting state, or the last failed return. */
  since: string;
  stage: EscrowLegAlertStage;
}
