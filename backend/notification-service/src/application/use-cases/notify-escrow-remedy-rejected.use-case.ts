import type { EscrowRemedyRejectedRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';

/*
 * `notification.escrow_remedy_rejected_requested` -> the Operator (design §4,
 * D93; milestone-escrow design §6.8). escrow-service refused a whole finalized
 * remedy decision because its legs add up to more than the money still
 * locked: no bank command was sent, and resending cannot help.
 *
 * The command names no recipient: the email goes to `notification.operatorEmail`.
 * There is no platform action for it (Known Limitation, §8c): the email says
 * so, and that it needs a developer.
 */

const SUBJECT = '[AgriContract] Escrow rejected a remedy decision';

/** Width the reason is wrapped to, within the 76 characters of a 7bit line. */
const REASON_WIDTH = 72;

export class NotifyEscrowRemedyRejectedUseCase {
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
  async execute(command: EscrowRemedyRejectedRequestedCommand): Promise<void> {
    if (this.operatorEmail === null) {
      throw new OperatorEmailNotConfiguredError(command.eventId);
    }
    await this.sender.send(
      command.eventId,
      COMMAND_KINDS.escrowRemedyRejected,
      this.operatorEmail,
      SUBJECT,
      escrowRemedyRejectedBody(command),
    );
  }
}

/**
 * The email text of `escrow-remedy-rejected/v1`. Changing it is a new version
 * of the template: rows already written name the text they sent. The fixed
 * lines stay within 76 characters and the reason is wrapped to 72, so an
 * ASCII reason goes out as 7bit too.
 */
export function escrowRemedyRejectedBody(
  command: EscrowRemedyRejectedRequestedCommand,
): string {
  const rejectedAt = new Date(command.rejectedAt);
  return [
    'escrow-service rejected a whole remedy decision: its transfers add up',
    'to more than the money still locked for the contract, so no bank',
    'command was sent for any of them. Normal operation never gets here: it',
    'points to a calculation or data error.',
    '',
    `Contract:     ${command.contractId}`,
    `Decision:     ${command.remedyDecisionId}`,
    'Rejected at:',
    `${vietnamTime(rejectedAt)} Vietnam time (${rejectedAt.toISOString()} UTC)`,
    'Reason given by escrow-service:',
    ...wrap(command.reason, REASON_WIDTH),
    '',
    'There is no platform action for this: resending does not help, and the',
    'contract stays ACTIVE. It needs a developer to find the error.',
    '',
  ].join('\n');
}

/**
 * Greedy word wrap that keeps the reason's own line breaks; a word longer
 * than the width stays whole on its own line rather than being cut.
 */
export function wrap(text: string, width: number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split(/\r?\n/)) {
    let line = '';
    for (const word of paragraph.split(/\s+/).filter((w) => w !== '')) {
      if (line === '') {
        line = word;
      } else if (line.length + 1 + word.length <= width) {
        line = `${line} ${word}`;
      } else {
        lines.push(line);
        line = word;
      }
    }
    lines.push(line);
  }
  return lines;
}
