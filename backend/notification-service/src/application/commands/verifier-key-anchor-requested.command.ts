/**
 * `notification.verifier_key_anchor_requested` (notification-commands.yaml
 * `verifierKeyAnchorRequested`), published by bank-service once a rotation of
 * the External Verifier's public key is recorded (bank design §3.5.6, layer
 * 2). The fingerprint goes straight to the External Verifier's mailbox: a copy
 * outside the platform, so a key swapped in the platform's config no longer
 * matches what the verifier was mailed (hash-chain design §4.1).
 *
 * One address, `recipientEmail`; there is no `payload.recipients`. `eventId`
 * is the envelope's: the dedup key's event (design §5).
 */
export interface VerifierKeyAnchorRequestedCommand {
  eventId: string;
  recipientEmail: string;
  /** Any non-empty string, shown as sent. */
  publicKeyFingerprint: string;
}
