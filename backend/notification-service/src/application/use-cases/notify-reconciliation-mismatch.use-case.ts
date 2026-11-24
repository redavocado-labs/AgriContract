import type { ReconciliationMismatchRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { requireRecipientRoles, sendToEach } from './staff-recipients';

/*
 * `notification.reconciliation_mismatch_requested` -> "the ledger of this
 * contract does not match its anchored audit record" (design §4; bank
 * design §5b.1). The reconciliation job has no write access: nothing is
 * corrected automatically, and the only correction is a reversal entry
 * with its attribution.
 *
 * Admin only: any other role refuses the command before anyone is mailed.
 * `mismatchSummary` is a free object: shown whole, no field read from it.
 */

const KIND = COMMAND_KINDS.reconciliationMismatch;

const ROLES = ['ADMIN'] as const;

export class NotifyReconciliationMismatchUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own; every
   * recipient is tried even if the email to one fails, and the first
   * failure is rethrown so the redelivery retries only the unsent ones.
   */
  async execute(
    command: ReconciliationMismatchRequestedCommand,
  ): Promise<void> {
    requireRecipientRoles(
      command.eventId,
      KIND.eventType,
      command.recipients,
      ROLES,
    );
    const subject = `[AgriContract] Ledger does not match the audit record: contract ${command.contractId}`;
    const body = reconciliationMismatchBody(command);

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
 * The email text of `reconciliation-mismatch/v1`. Changing it is a new
 * template version: rows already written name the text they sent.
 */
export function reconciliationMismatchBody(
  command: ReconciliationMismatchRequestedCommand,
): string {
  return [
    'The ledger reconciliation found that the bank ledger of contract',
    `${command.contractId} does not match the amounts in its`,
    'anchored audit record.',
    '',
    'Nothing was corrected automatically. The only correction is a',
    'reversal entry with its attribution. Buyers and sellers are not told',
    'by this email.',
    '',
    'What the reconciliation reported, as bank-service sent it:',
    JSON.stringify(command.mismatchSummary, null, 2),
    '',
  ].join('\n');
}
