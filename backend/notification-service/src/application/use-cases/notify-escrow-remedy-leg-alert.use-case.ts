import type { EscrowRemedyLegAlertRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { vnd } from '../vnd';

/*
 * `notification.escrow_remedy_leg_alert_requested` -> the Operator (design §4,
 * D93; milestone-escrow design §6.8). A money leg of a finalized remedy
 * decision was refused by the bank and escrow-service has no resend left, so
 * the contract cannot reach SETTLED or TERMINATED until someone acts.
 *
 * The command names no recipient: the email goes to `notification.operatorEmail`.
 * It gives the bank command id to grep the bank log for, and the one action
 * there is: fix the cause, then resend the contract's failed legs.
 */

const SUBJECT =
  '[AgriContract] A remedy payment was refused by the bank, no retry left';

export class NotifyEscrowRemedyLegAlertUseCase {
  /**
   * `operatorEmail` is null when OPERATOR_EMAIL is not set: every command
   * then fails with OperatorEmailNotConfiguredError instead of being acked
   * unsent.
   */
  constructor(
    private readonly sender: NotificationSender,
    private readonly operatorEmail: string | null,
  ) {}

  /**
   * One email, claimed and recorded as every other; a failure rethrows and
   * the redelivery sends it unless it is already SENT. There is no platform
   * user behind the address, so no recipient id.
   */
  async execute(command: EscrowRemedyLegAlertRequestedCommand): Promise<void> {
    if (this.operatorEmail === null) {
      throw new OperatorEmailNotConfiguredError(command.eventId);
    }
    await this.sender.send(
      command.eventId,
      COMMAND_KINDS.escrowRemedyLegFailed,
      this.operatorEmail,
      SUBJECT,
      escrowRemedyLegAlertBody(command),
    );
  }
}

/**
 * The email text of `escrow-remedy-leg-failed/v1`. Changing it is a new
 * version of the template: rows already written name the text they sent.
 * Every line stays within 76 characters, so the email goes out as 7bit and
 * the ids read unbroken.
 */
export function escrowRemedyLegAlertBody(
  command: EscrowRemedyLegAlertRequestedCommand,
): string {
  const since = new Date(command.since);
  return [
    'The bank refused a money transfer of a finalized remedy decision, and',
    'escrow-service has no resend left for it. The contract stays ACTIVE:',
    'it cannot settle or terminate until this transfer completes.',
    '',
    `Contract:     ${command.contractId}`,
    `Decision:     ${command.remedyDecisionId}`,
    `Remedy leg:   ${command.remedyLegId}`,
    `Payee:        ${command.party}`,
    `Amount:       ${vnd(command.amount)}`,
    `Bank command: ${command.bankCommandEventId}`,
    'Failed since:',
    `${vietnamTime(since)} Vietnam time (${since.toISOString()} UTC)`,
    '',
    'Search the bank-service log for the bank command id to see why the bank',
    'refused it (kill switch on, bank error).',
    '',
    'Once the cause is fixed, press resend for this contract:',
    'POST /api/v1/contracts/{contractId}/remedy/retry-legs',
    'escrow-service then resends every failed transfer of the contract with',
    'its original bank command id, so the bank never books one twice.',
    'Transfers already completed are not sent again. The amount and the payee',
    'cannot be changed here: a different payment needs a new decision.',
    '',
  ].join('\n');
}
