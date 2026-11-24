import type { AuditFailureRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import {
  requireRecipientRoles,
  roleCounts,
  sendToEach,
} from './staff-recipients';

/*
 * `notification.audit_failure_requested` -> the integrity alert (design §4;
 * hash-chain design §5.2). VerifyChainJob found a record that no longer
 * hashes to its stored value, or the anchored hash missing from the chain.
 *
 * It goes to the Admin and to several Software Buyer contacts at once, with
 * no approval step: an Admin who changed the data must not be able to hold
 * the alert back (design §8, "no single gatekeeper"). So every recipient is
 * tried even if the email to one fails, and the text tells each recipient
 * how many others it went to. Buyers and sellers are not told: they are
 * told once the Admin has identified the affected contracts, by a separate,
 * reviewed message (hash-chain design §5.2). Any role but ADMIN and
 * SOFTWARE_BUYER refuses the command before anyone is mailed.
 *
 * `failureSummary` is a free object: shown whole, no field read from it.
 */

const KIND = COMMAND_KINDS.auditFailure;

const ROLES = ['ADMIN', 'SOFTWARE_BUYER'] as const;

const SUBJECT = '[AgriContract] ALERT: audit chain check failed';

export class NotifyAuditFailureUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own; every
   * recipient is tried even if the email to one fails, and the first
   * failure is rethrown so the redelivery retries only the unsent ones.
   */
  async execute(command: AuditFailureRequestedCommand): Promise<void> {
    requireRecipientRoles(
      command.eventId,
      KIND.eventType,
      command.recipients,
      ROLES,
    );
    const body = auditFailureBody(command);

    await sendToEach(command.recipients, (recipient) =>
      this.sender.send(
        command.eventId,
        KIND,
        recipient.email,
        SUBJECT,
        body,
        recipient.userId,
      ),
    );
  }
}

/**
 * The email text of `audit-failure/v1`. Changing it is a new template
 * version: rows already written name the text they sent.
 */
export function auditFailureBody(
  command: AuditFailureRequestedCommand,
): string {
  return [
    'The weekly check of the AgriContract audit chain FAILED. Until the',
    'failure is investigated, the audit records cannot be relied on.',
    '',
    'This alert was sent automatically, with no approval step, to every',
    `contact at once: ${roleCounts(command.recipients)}.`,
    '',
    'What the check reported, as audit-service sent it:',
    JSON.stringify(command.failureSummary, null, 2),
    '',
    'Buyers and sellers are not told by this alert. They are told once the',
    'affected contracts have been identified.',
    '',
  ].join('\n');
}
