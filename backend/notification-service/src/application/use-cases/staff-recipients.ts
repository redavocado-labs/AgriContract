import type { NotificationRecipient, RecipientRole } from '../commands';
import {
  NotificationDeliveryError,
  RecipientRoleRefusedError,
} from '../notification-errors';

/*
 * The integrity and security commands of audit-service and bank-service
 * (design §4, #278) go to staff contacts only: the Admin, and the Software
 * Buyer contacts, one of which acts as the External Verifier (hash-chain
 * design §4.4). The contract lets `recipients[].role` be any role, so which
 * roles each command may reach is checked here, before anything is sent.
 */

/** Refuses the whole command if one recipient's role is not in `allowed`. */
export function requireRecipientRoles(
  eventId: string,
  eventType: string,
  recipients: readonly NotificationRecipient[],
  allowed: readonly RecipientRole[],
): void {
  recipients.forEach((recipient, index) => {
    if (!allowed.includes(recipient.role)) {
      throw new RecipientRoleRefusedError(
        eventId,
        eventType,
        index,
        recipient.role,
        allowed,
      );
    }
  });
}

/**
 * Sends to every recipient even when the email to one of them fails, then
 * rethrows the first such failure, so the message is retried and the
 * redelivery skips whoever is already SENT. One address the mail server
 * refuses must not hold back the alert to the others: no single recipient
 * is a gatekeeper (design §8, hash-chain design §5.2).
 *
 * Only an email failure moves on to the next recipient. A log write failure,
 * a claim still held by another delivery or a shutdown rethrows at once, as
 * NotificationSender's other callers do: those are not about one address.
 */
export async function sendToEach<T>(
  recipients: readonly T[],
  send: (recipient: T) => Promise<void>,
): Promise<void> {
  let firstFailure: NotificationDeliveryError | null = null;
  for (const recipient of recipients) {
    try {
      await send(recipient);
    } catch (err) {
      if (!(err instanceof NotificationDeliveryError && err.step === 'email')) {
        throw err;
      }
      firstFailure ??= err;
    }
  }
  if (firstFailure !== null) throw firstFailure;
}

/** "1 ADMIN, 2 SOFTWARE_BUYER": how many recipients of each role, in order of first appearance. */
export function roleCounts(
  recipients: readonly NotificationRecipient[],
): string {
  const counts = new Map<RecipientRole, number>();
  for (const { role } of recipients) {
    counts.set(role, (counts.get(role) ?? 0) + 1);
  }
  return [...counts].map(([role, n]) => `${n} ${role}`).join(', ');
}
