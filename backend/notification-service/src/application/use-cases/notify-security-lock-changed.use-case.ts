import type { SecurityLockChangedRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { requireRecipientRoles, sendToEach } from './staff-recipients';

/*
 * `notification.security_lock_changed_requested` -> "the system lock is on /
 * off" (design §4; bank design §3.5.3-§3.5.5). The External Verifier signs an
 * emergency lock or unlock; once bank-service commits it, this email goes to
 * the Admin (to investigate) and the External Verifier (to confirm the
 * command was applied). Buyers and sellers are never mailed: they are told
 * only once the affected contracts are known (bank design §3.5.4).
 *
 * The External Verifier is the Software Buyer organisation that holds the
 * signing key (hash-chain design §4.4); the contract has no role of its own
 * for it, so ADMIN and SOFTWARE_BUYER are the roles this command may reach.
 * Any other refuses it before anyone is mailed.
 *
 * `reason` is the verifier's own text, shown as sent.
 */

const KIND = COMMAND_KINDS.securityLockChanged;

const ROLES = ['ADMIN', 'SOFTWARE_BUYER'] as const;

const SUBJECTS = {
  LOCKED: '[AgriContract] Security lock applied: all money movements stopped',
  UNLOCKED: '[AgriContract] Security lock lifted: money movements resumed',
} as const;

export class NotifySecurityLockChangedUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own; every
   * recipient is tried even if the email to one fails, and the first
   * failure is rethrown so the redelivery retries only the unsent ones.
   */
  async execute(command: SecurityLockChangedRequestedCommand): Promise<void> {
    requireRecipientRoles(
      command.eventId,
      KIND.eventType,
      command.recipients,
      ROLES,
    );
    const subject = SUBJECTS[command.action];
    const body = securityLockChangedBody(command);

    await sendToEach(command.recipients, (recipient) =>
      this.sender.send(
        command.eventId,
        KIND,
        recipient.email,
        subject,
        body,
        recipient.userId,
      ),
    );
  }
}

/**
 * The email text of `security-lock-changed/v1`. Changing it is a new
 * template version: rows already written name the text they sent.
 */
export function securityLockChangedBody(
  command: SecurityLockChangedRequestedCommand,
): string {
  const at = new Date(command.timestamp);
  const when = `${vietnamTime(at)} Vietnam time (${at.toISOString()} UTC)`;
  const reason = command.reason.trim() === '' ? '(none given)' : command.reason;

  const lines =
    command.action === 'LOCKED'
      ? [
          "The External Verifier's signed emergency lock was applied at:",
          when,
          '',
          'While the lock is on, the bank refuses every deposit lock, release,',
          'seizure and refund, on every contract: no money moves on the',
          'platform. Only an unlock signed by the External Verifier lifts it;',
          'the platform administrator cannot.',
          '',
          `Reason given with the lock: ${reason}`,
        ]
      : [
          "The External Verifier's signed unlock was applied at:",
          when,
          '',
          'The bank processes deposit locks, releases, seizures and refunds',
          'again. What it refused while the lock was on stays refused: lifting',
          'the lock does not run those requests again.',
          '',
          `Reason given with the unlock: ${reason}`,
        ];

  lines.push(
    '',
    'This email goes to the platform administrator and the External',
    'Verifier only. Buyers and sellers are not told by it.',
    '',
  );
  return lines.join('\n');
}
