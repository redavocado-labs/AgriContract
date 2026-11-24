import type { AuditDigestRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { requireRecipientRoles, sendToEach } from './staff-recipients';

/*
 * `notification.audit_digest_requested` -> the weekly digest (design §4;
 * hash-chain design §5.1). audit-service publishes it only after
 * VerifyChainJob set WEEKLY_VERIFY_OK, so the email can say the check
 * passed. Its value is the head hash kept outside the platform, with a
 * timestamp the platform does not control (hash-chain design §6): each
 * record's hash covers the one before it, so if this hash ever disappears
 * from the chain, records written before the email were changed.
 *
 * Software Buyer contacts only: any other role refuses the command before
 * anyone is mailed.
 */

const KIND = COMMAND_KINDS.auditDigest;

const ROLES = ['SOFTWARE_BUYER'] as const;

const SUBJECT = '[AgriContract] Weekly audit chain digest: check passed';

export class NotifyAuditDigestUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own. Every
   * recipient is tried even if the email to one fails (sendToEach); the
   * redelivery skips whoever is already SENT. Throws
   * RecipientRoleRefusedError, mailing nobody, if a recipient is not a
   * Software Buyer contact.
   */
  async execute(command: AuditDigestRequestedCommand): Promise<void> {
    requireRecipientRoles(
      command.eventId,
      KIND.eventType,
      command.recipients,
      ROLES,
    );
    const body = auditDigestBody(command);

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
 * The email text of `audit-digest/v1`. Changing it is a new template
 * version: rows already written name the text they sent.
 */
export function auditDigestBody(command: AuditDigestRequestedCommand): string {
  return [
    'The weekly check of the AgriContract audit chain passed: every audit',
    'record still hashes to its stored value, and the hash last anchored with',
    'OpenTimestamps is still in the chain.',
    '',
    "Hash of the chain's newest record (SHA-256):",
    command.recordHash,
    '',
    'Keep this email. Each audit record is hashed together with the one',
    'before it, so this hash covers every record up to now. If it is ever',
    "missing from the platform's audit chain, records written before this",
    'email was sent have been changed since.',
    '',
  ].join('\n');
}
