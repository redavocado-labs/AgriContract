import type { VerifierKeyAnchorRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';

/*
 * `notification.verifier_key_anchor_requested` -> the External Verifier's
 * public key fingerprint, mailed to the verifier itself (design §4; bank
 * design §3.5.6, layer 2). A rotation is only valid when signed with the
 * key in force, and each valid one sends this email, so the verifier holds
 * a copy outside the platform of every key it registered: a key swapped
 * into the platform's config would match neither the anchored audit record
 * nor this email (hash-chain design §4.1).
 *
 * One address, `recipientEmail`, no platform id. The fingerprint is shown
 * as sent: notification-service does not compute it again (design §8).
 */

const KIND = COMMAND_KINDS.verifierKeyAnchor;

const SUBJECT = '[AgriContract] External Verifier public key registered';

export class NotifyVerifierKeyAnchorUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /** One email, claimed and recorded as every other; a failure rethrows. */
  async execute(command: VerifierKeyAnchorRequestedCommand): Promise<void> {
    await this.sender.send(
      command.eventId,
      KIND,
      command.recipientEmail,
      SUBJECT,
      verifierKeyAnchorBody(command),
    );
  }
}

/**
 * The email text of `verifier-key-anchor/v1`. Changing it is a new template
 * version: rows already written name the text they sent.
 */
export function verifierKeyAnchorBody(
  command: VerifierKeyAnchorRequestedCommand,
): string {
  return [
    'A public key was registered as the External Verifier key of',
    'AgriContract. Its fingerprint:',
    '',
    command.publicKeyFingerprint,
    '',
    'Keep this email: it is your copy, outside the platform, of the key the',
    'platform now trusts for emergency lock and unlock requests. Every valid',
    'change of that key sends you an email like this one. If this is not',
    'the fingerprint of a key you hold, the key was registered without you:',
    'report it at once.',
    '',
  ].join('\n');
}
