import type { BreachNoticeRequestedCommand, BreachSeverity } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { reasonLines } from './breach-reasons';

/*
 * `notification.breach_notice_requested` -> both parties (design §4;
 * milestone-escrow design §6.4; D96). The formal notice that a BreachCase is
 * open, with the time it was reported: the start of the evidence chain. It
 * is an allegation, not a decision, and the email says so. The command does
 * not say which party is alleged to have breached, so neither does the email.
 */

const KIND = COMMAND_KINDS.breachNotice;

const SEVERITIES: Record<BreachSeverity, string> = {
  MINOR: 'MINOR',
  MATERIAL: 'MATERIAL',
};

/** A file id or a plain reference: anything else is counted, not shown. */
const SHOWN_REFERENCE = /^[A-Za-z0-9._:/-]{1,64}$/;

export class NotifyBreachNoticeUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own, in the
   * command's order; a failure rethrows and the redelivery skips whoever is
   * already SENT (as NotifyContractAnchorUseCase).
   */
  async execute(command: BreachNoticeRequestedCommand): Promise<void> {
    const subject = `[AgriContract] Breach case opened on contract ${command.contractId}`;
    const body = breachNoticeText(command);

    for (const recipient of command.recipients) {
      await this.sender.send(
        command.eventId,
        KIND,
        recipient.email,
        subject,
        body,
        recipient.userId,
      );
    }
  }
}

/**
 * The email text of `breach-notice/v1`. Changing it is a new version of the
 * template: rows already written name the text they sent. Every line stays
 * within 76 characters, so the email goes out as 7bit.
 */
export function breachNoticeText(
  command: BreachNoticeRequestedCommand,
): string {
  const reported = new Date(command.reportedAt);
  const shown = command.evidenceRefs.filter((ref) => SHOWN_REFERENCE.test(ref));
  const hidden = command.evidenceRefs.length - shown.length;

  const evidence =
    command.evidenceRefs.length === 0
      ? ['Evidence: none attached to the case yet.']
      : [
          `Evidence: ${command.evidenceRefs.length} file reference(s)`,
          ...shown.map((ref) => `- ${ref}`),
          ...(hidden > 0 ? [`- ${hidden} more not shown here`] : []),
        ];

  return [
    'This is a formal notice that a breach case was opened on your',
    'AgriContract contract.',
    '',
    `Contract:    ${command.contractId}`,
    `Breach case: ${command.breachCaseId}`,
    ...reasonLines('Reason:      ', command.breachReasonCode),
    `Severity:    ${SEVERITIES[command.severity]}`,
    'Reported at:',
    `${vietnamTime(reported)} Vietnam time (${reported.toISOString()} UTC)`,
    '',
    ...evidence,
    '',
    'This is an allegation, not a decision: nobody is penalised and no money',
    'moves because of it until the case is decided. You will get an email',
    'with the decision.',
    '',
  ].join('\n');
}
