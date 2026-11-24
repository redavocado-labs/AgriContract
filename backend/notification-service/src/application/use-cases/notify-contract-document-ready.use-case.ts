import { createHash } from 'node:crypto';
import type { ContractDocumentReadyRequestedCommand } from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { DocumentFileMismatchError } from '../notification-errors';
import type { NotificationSender } from '../notification-sender';
import type { DocumentFiles, StoredFile } from '../ports/document-files.port';
import { vietnamTime } from '../vietnam-time';

/*
 * `notification.contract_document_ready_requested` -> buyer and seller: the
 * final signed contract PDF (design §3.3, §4). The canonical Phase 2
 * final-contract email.
 *
 * The PDF is not in the command. It is read from file-service by the trusted
 * `fileId` once per delivery, before anyone is mailed, and must hash to the
 * command's `documentFileHash`: file-service already refuses to hand over
 * bytes that differ from what it stored, and this check ties those bytes to
 * the hash the body prints, so no recipient gets a PDF the email contradicts.
 * - not READY (404) or file-service unavailable: retried, nobody mailed yet
 *   on this delivery;
 * - a different hash, or a read no retry can change (401, size cap):
 *   dead-lettered at once by the route, unsent.
 *
 * Dedup is by `documentId` (design §5, the contract's x-deduplication-key):
 * it is the row's event key, so another command for the same document never
 * sends the final PDF twice to the same address.
 */

const KIND = COMMAND_KINDS.contractFinalPdf;

export class NotifyContractDocumentReadyUseCase {
  constructor(
    private readonly sender: NotificationSender,
    private readonly files: DocumentFiles,
  ) {}

  /**
   * A redelivery after every recipient is SENT (the ack was lost) returns
   * before reading the PDF: file-service being down or the file gone by then
   * must not retry and dead-letter a command nobody is missing a mail of.
   * Otherwise the PDF is read once and mailed to whoever is not SENT yet;
   * send() claims each, so a concurrent delivery still sends nothing twice.
   */
  async execute(command: ContractDocumentReadyRequestedCommand): Promise<void> {
    const pending: typeof command.recipients = [];
    for (const recipient of command.recipients) {
      if (
        !(await this.sender.alreadySent(
          command.documentId,
          KIND,
          recipient.email,
        ))
      ) {
        pending.push(recipient);
      }
    }
    if (pending.length === 0) return;

    const file = await this.files.readReady(command.fileId);
    const sha256 = createHash('sha256').update(file.content).digest('hex');
    if (sha256 !== command.documentFileHash) {
      throw new DocumentFileMismatchError(command.eventId, command.fileId);
    }

    const subject = `[AgriContract] Final signed contract ${command.contractId}`;
    const body = contractDocumentReadyBody(command, file);
    const attachment = {
      filename: attachmentName(command.contractId),
      contentType: file.contentType,
      content: file.content,
    };

    for (const recipient of pending) {
      await this.sender.send(
        command.documentId,
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

/** The name the PDF is attached under. */
export const attachmentName = (contractId: string): string =>
  `contract-${contractId}.pdf`;

/**
 * The email text of `contract-final-pdf/v1`. Changing it is a new version of
 * the template: rows already written name the text they sent. The row keeps
 * this text, not the PDF (design §3.3), so the text names everything that
 * identifies the attachment: file id, name, size and SHA-256. Every fixed
 * line stays within 76 characters; a long templateVersion may make its own
 * line longer.
 */
export function contractDocumentReadyBody(
  command: ContractDocumentReadyRequestedCommand,
  file: StoredFile,
): string {
  const generated = new Date(command.generatedAt);
  return [
    'The final signed contract is ready. Its PDF is attached to this email.',
    '',
    `Contract:     ${command.contractId}`,
    `Document:     ${command.documentId}`,
    `Template:     ${command.templateCode}`,
    `Version:      ${command.templateVersion}`,
    'Generated at:',
    `${vietnamTime(generated)} Vietnam time (${generated.toISOString()} UTC)`,
    '',
    'Attached PDF:',
    `File:         ${command.fileId}`,
    `Name:         ${attachmentName(command.contractId)}`,
    `Size:         ${file.content.length} bytes`,
    'SHA-256 of the PDF (documentFileHash):',
    command.documentFileHash,
    '',
    'SHA-256 of the signed contract terms it was rendered from',
    '(sourceContentHash):',
    command.sourceContentHash,
    '',
    'The SHA-256 of the attached file was checked against documentFileHash',
    'before this email was sent. To check it yourself, compute the SHA-256 of',
    'the attachment: it must equal the value above. The two hashes answer',
    'different questions: documentFileHash identifies this PDF file,',
    'sourceContentHash the signed contract content it presents.',
    '',
  ].join('\n');
}
