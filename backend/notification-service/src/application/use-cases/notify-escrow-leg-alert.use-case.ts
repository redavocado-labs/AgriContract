import type {
  EscrowLegAlertRequestedCommand,
  EscrowLegAlertStage,
  EscrowLegStatus,
} from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { vnd } from '../vnd';

/*
 * `notification.escrow_leg_alert_requested` -> the Operator (design §4, D68;
 * milestone-escrow design §3.1b). escrow-service's leg monitor reports a
 * deposit leg that has waited on the bank too long (STUCK), the same leg
 * getting its bank result after all (RESOLVED), and a return that failed
 * with no resend left (RETURN_EXHAUSTED).
 *
 * The command names no recipient: the email goes to `notification.operatorEmail`.
 * Its point is the bank command id: the Operator greps the bank log for it to
 * tell a replay mismatch, a lost result and a command that never arrived
 * apart (§3.1b "Truy vết").
 */

type Kind = (typeof COMMAND_KINDS)[keyof typeof COMMAND_KINDS];

const KINDS: Record<EscrowLegAlertStage, Kind> = {
  STUCK: COMMAND_KINDS.escrowLegStuck,
  RESOLVED: COMMAND_KINDS.escrowLegResolved,
  RETURN_EXHAUSTED: COMMAND_KINDS.escrowLegReturnExhausted,
};

const SUBJECTS: Record<EscrowLegAlertStage, string> = {
  STUCK: '[AgriContract] An escrow deposit is waiting on the bank too long',
  RESOLVED: '[AgriContract] A stuck escrow deposit has its bank result',
  RETURN_EXHAUSTED:
    '[AgriContract] An escrow deposit return failed with no retry left',
};

const STATUSES: Record<EscrowLegStatus, string> = {
  REQUESTED: 'lock sent to the bank, no result yet',
  LOCKED: 'locked by the bank',
  FAILED: 'lock failed',
  CANCEL_PENDING: 'cancel sent to the bank, no result yet',
  CANCELLED: 'cancelled',
  RETURN_REQUESTED: 'return sent to the bank, no result yet',
  RETURN_FAILED: 'return failed',
  RETURNED: 'returned to its owner',
};

export class NotifyEscrowLegAlertUseCase {
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
   * One email of the stage's kind, claimed and recorded as every other; a
   * failure rethrows and the redelivery sends it unless it is already SENT.
   * There is no platform user behind the address, so no recipient id.
   */
  async execute(command: EscrowLegAlertRequestedCommand): Promise<void> {
    if (this.operatorEmail === null) {
      throw new OperatorEmailNotConfiguredError(command.eventId);
    }
    await this.sender.send(
      command.eventId,
      KINDS[command.stage],
      this.operatorEmail,
      SUBJECTS[command.stage],
      escrowLegAlertBody(command),
    );
  }
}

const GREP = [
  'Search the bank-service log for the bank command id:',
  '- a bank_command_replay_mismatch line: the bank got another command',
  '  with this id and different content, and will not answer this one;',
  '- a ledger line for it: the bank applied it, and its result was lost',
  '  on the way back to escrow-service;',
  '- nothing: the command never reached the bank; check the escrow-service',
  '  outbox and the dead-letter queues.',
];

/**
 * The email text of the three templates: `escrow-leg-stuck/v1`,
 * `escrow-leg-resolved/v1` and `escrow-leg-return-exhausted/v1`. Changing one
 * is a new version of that template: rows already written name the text they
 * sent. Every line stays within 76 characters, so the email goes out as 7bit
 * and the ids read unbroken.
 */
export function escrowLegAlertBody(
  command: EscrowLegAlertRequestedCommand,
): string {
  const details = [
    `Contract:     ${command.contractId}`,
    `Leg:          ${command.legId}`,
    `Leg kind:     ${command.legKind}`,
    `Party:        ${command.party}`,
    `Status:       ${command.legStatus} (${STATUSES[command.legStatus]})`,
    `Amount:       ${vnd(command.amount)}`,
    `Bank command: ${command.bankCommandEventId}`,
    command.stage === 'RETURN_EXHAUSTED'
      ? 'Last failed return:'
      : 'Waiting since:',
    instant(command.since),
  ];
  let lines: string[];

  switch (command.stage) {
    case 'STUCK':
      lines = [
        'An escrow deposit leg has waited on the bank longer than expected.',
        'escrow-service sent the bank a command for it and has no result yet.',
        'The leg stays as it is until the result arrives; you will get one',
        'more email when it does.',
        '',
        ...details,
        '',
        ...GREP,
      ];
      break;
    case 'RESOLVED':
      lines = [
        'An escrow deposit leg that was reported stuck has now received its',
        'bank result. Nothing more is needed from you for this leg.',
        '',
        ...details,
      ];
      break;
    case 'RETURN_EXHAUSTED':
      lines = [
        'Returning an escrow deposit to its owner failed, and no resend is',
        'left: escrow-service will not try this return again on its own.',
        'The deposit has not been returned.',
        '',
        ...details,
        '',
        'Search the bank-service log for the bank command id to see why the',
        'return failed.',
      ];
      break;
  }

  lines.push('');
  return lines.join('\n');
}

function instant(value: string): string {
  const date = new Date(value);
  return `${vietnamTime(date)} Vietnam time (${date.toISOString()} UTC)`;
}
