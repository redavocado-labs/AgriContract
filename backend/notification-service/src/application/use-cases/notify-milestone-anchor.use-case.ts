import { createHash } from 'node:crypto';
import type { MilestoneAnchorRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OtsProofInvalidError } from '../notification-errors';
import type { NotificationSender } from '../notification-sender';
import type { EmailAttachment } from '../ports/email.port';
import { vnd } from '../vnd';

/*
 * `notification.milestone_anchor_requested` -> buyer and seller (design §3.3,
 * §4; hash-chain design §4.3). A milestone settled, audit-service wrote it to
 * the audit chain and obtained an OpenTimestamps proof for the record hash:
 * the email gives the hash and attaches the proof as
 * `{contractId}-{milestoneId}.ots`, so either party can check it without
 * AgriContract.
 *
 * The proof is decoded once per delivery, before anyone is mailed. One that
 * is empty, not strict base64 or over the cap is dead-lettered unsent
 * (OtsProofInvalidError): a settlement email without its proof is not the
 * evidence email promised. The row keeps the text, not the file (§3.3), so
 * the text names the proof's size and SHA-256.
 */

const KIND = COMMAND_KINDS.milestoneAnchor;

/** An OpenTimestamps proof is a few kilobytes; anything near this is not one. */
export const MAX_OTS_PROOF_BYTES = 1024 * 1024;

const BASE64 =
  /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** The proof's bytes, or OtsProofInvalidError with a reason that never quotes it. */
export function decodeOtsProof(eventId: string, otsProof: string): Buffer {
  if (otsProof.length === 0) throw new OtsProofInvalidError(eventId, 'empty');
  // Four characters per three bytes: refuse before decoding a huge string.
  if (otsProof.length > Math.ceil(MAX_OTS_PROOF_BYTES / 3) * 4) {
    throw new OtsProofInvalidError(eventId, 'over 1 MiB');
  }
  if (!BASE64.test(otsProof)) {
    throw new OtsProofInvalidError(eventId, 'not strict base64');
  }
  const bytes = Buffer.from(otsProof, 'base64');
  if (bytes.length > MAX_OTS_PROOF_BYTES) {
    throw new OtsProofInvalidError(eventId, 'over 1 MiB');
  }
  return bytes;
}

/** The name the proof is attached under (design §3.3). */
export const proofName = (contractId: string, milestoneId: string): string =>
  `${contractId}-${milestoneId}.ots`;

export class NotifyMilestoneAnchorUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own, in the
   * command's order; a failure rethrows and the redelivery skips whoever is
   * already SENT (as NotifyContractAnchorUseCase).
   */
  async execute(command: MilestoneAnchorRequestedCommand): Promise<void> {
    const proof = decodeOtsProof(command.eventId, command.otsProof);
    const attachment: EmailAttachment = {
      filename: proofName(command.contractId, command.milestoneId),
      contentType: 'application/octet-stream',
      content: proof,
    };
    const subject = `[AgriContract] Milestone settled on contract ${command.contractId}: timestamp proof`;
    const body = milestoneAnchorText(command, proof);

    for (const recipient of command.recipients) {
      await this.sender.send(
        command.eventId,
        KIND,
        recipient.email,
        subject,
        body,
        recipient.userId,
        [attachment],
      );
    }
  }
}

/** The contract's money ("45000000.00") as grouped VND, or null. */
function amount(summary: Record<string, unknown>, key: string): string | null {
  const value = summary[key];
  return typeof value === 'string' &&
    value.length <= 19 &&
    /^\d+\.\d{2}$/.test(value)
    ? vnd(value)
    : null;
}

/**
 * The email text of `milestone-anchor/v1`. Changing it is a new version of
 * the template: rows already written name the text they sent. Every line
 * stays within 76 characters, so the text goes out as 7bit: the proof's
 * file name (77 characters with two UUIDs) is the attachment's name and is
 * not repeated in the text.
 */
export function milestoneAnchorText(
  command: MilestoneAnchorRequestedCommand,
  proof: Buffer,
): string {
  const summary = [
    ['Amount locked:', amount(command.settlementSummary, 'lockedAmount')],
    ['Amount settled:', amount(command.settlementSummary, 'actualAmount')],
  ]
    .filter((row): row is [string, string] => row[1] !== null)
    .map(([label, value]) => `${label.padEnd(16)}${value}`);

  return [
    'A milestone of your AgriContract contract is settled. Its record is',
    "written to AgriContract's audit chain, and the OpenTimestamps proof of",
    'that record is attached to this email.',
    '',
    `Contract:       ${command.contractId}`,
    `Milestone:      ${command.milestoneId}`,
    ...summary,
    '',
    'Audit record hash (SHA-256), the digest the proof commits to:',
    command.recordHash,
    '',
    'Attached proof (.ots):',
    `Size:           ${proof.length} bytes`,
    'SHA-256 of the proof file:',
    createHash('sha256').update(proof).digest('hex'),
    '',
    'Keep the proof with this email. It lets anyone check, without',
    'AgriContract, that this record hash existed no later than the Bitcoin',
    'block the proof is anchored in, using the OpenTimestamps client:',
    '  ots verify -d <audit record hash> <attached .ots file>',
    'A fresh proof may still be pending in the OpenTimestamps calendars;',
    '"ots upgrade" completes it once the Bitcoin transaction is confirmed.',
    '',
  ].join('\n');
}
