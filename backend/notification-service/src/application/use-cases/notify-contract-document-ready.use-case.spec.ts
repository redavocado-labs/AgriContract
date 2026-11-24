import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  ANCHOR_RECIPIENTS,
  DOCUMENT_FILE_ID,
  DOCUMENT_ID,
  DOCUMENT_PDF,
  DOCUMENT_PDF_SHA256,
  TERMS_SNAPSHOT_HASH,
  documentReadyCommand,
} from '../../../test/support/commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import {
  DocumentFileMismatchError,
  FileNotReadyError,
} from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import type { DocumentFiles, StoredFile } from '../ports/document-files.port';
import {
  NotifyContractDocumentReadyUseCase,
  attachmentName,
  contractDocumentReadyBody,
} from './notify-contract-document-ready.use-case';

const CONTRACT_ID = documentReadyCommand().contractId;
const PDF: StoredFile = {
  content: DOCUMENT_PDF,
  contentType: 'application/pdf',
};

/** file-service as the use case sees it: one answer per read, in order. */
class FakeDocumentFiles implements DocumentFiles {
  readonly reads: string[] = [];
  answers: Array<StoredFile | Error> = [];

  readReady(fileId: string): Promise<StoredFile> {
    this.reads.push(fileId);
    const answer = this.answers.shift() ?? PDF;
    return answer instanceof Error
      ? Promise.reject(answer)
      : Promise.resolve(answer);
  }
}

function setup() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const files = new FakeDocumentFiles();
  const useCase = new NotifyContractDocumentReadyUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
    files,
  );
  return { logs, email, files, useCase };
}

describe('NotifyContractDocumentReadyUseCase', () => {
  it('reads the PDF once and mails it to every recipient, keyed by documentId', async () => {
    const { logs, email, files, useCase } = setup();
    const command = documentReadyCommand();

    await useCase.execute(command);

    expect(files.reads).toEqual([DOCUMENT_FILE_ID]);
    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
    for (const [i, recipient] of ANCHOR_RECIPIENTS.entries()) {
      const mail = email.sent[i];
      expect(mail.subject).toBe(
        `[AgriContract] Final signed contract ${CONTRACT_ID}`,
      );
      expect(mail.body).toBe(contractDocumentReadyBody(command, PDF));
      expect(mail.attachments).toEqual([
        {
          filename: `contract-${CONTRACT_ID}.pdf`,
          contentType: 'application/pdf',
          content: DOCUMENT_PDF,
        },
      ]);
      // design §5: the row's event key is the document, not the envelope.
      expect(
        logs.get({
          eventId: DOCUMENT_ID,
          recipientEmail: recipient.email,
          notificationType: 'CONTRACT_FINAL_PDF',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        eventType: 'notification.contract_document_ready_requested',
        templateVersion: COMMAND_KINDS.contractFinalPdf.templateVersion,
        recipientUserId: recipient.userId,
      });
    }
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: ANCHOR_RECIPIENTS[0].email,
        notificationType: 'CONTRACT_FINAL_PDF',
      }),
    ).toBeUndefined();
  });

  it('a second command for the same document sends no second PDF (dedup by documentId)', async () => {
    const { email, useCase } = setup();

    await useCase.execute(documentReadyCommand());
    await useCase.execute({
      ...documentReadyCommand(),
      eventId: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
    });

    expect(email.sent).toHaveLength(2);
  });

  it('a file that is not READY yet rethrows, mails nobody, and the redelivery sends', async () => {
    const { email, files, useCase } = setup();
    files.answers = [new FileNotReadyError(DOCUMENT_FILE_ID)];

    await expect(
      useCase.execute(documentReadyCommand()),
    ).rejects.toBeInstanceOf(FileNotReadyError);
    expect(email.sent).toEqual([]);

    await useCase.execute(documentReadyCommand());
    expect(email.sent).toHaveLength(2);
  });

  it('bytes that do not hash to documentFileHash are never mailed', async () => {
    const { logs, email, files, useCase } = setup();
    files.answers = [
      {
        content: Buffer.from('%PDF-1.7 another file'),
        contentType: 'application/pdf',
      },
    ];

    const failure = useCase.execute(documentReadyCommand());

    await expect(failure).rejects.toBeInstanceOf(DocumentFileMismatchError);
    await expect(failure).rejects.toThrow(
      `file ${DOCUMENT_FILE_ID} does not hash to the documentFileHash of the command`,
    );
    expect(email.sent).toEqual([]);
    expect(logs.size).toBe(0);
  });

  it('a send failure for one recipient rethrows; the redelivery reads again and sends only what is missing', async () => {
    const { email, files, useCase } = setup();
    email.failFor.add(ANCHOR_RECIPIENTS[1].email);

    await expect(useCase.execute(documentReadyCommand())).rejects.toThrow();
    expect(email.sent.map((m) => m.to)).toEqual([ANCHOR_RECIPIENTS[0].email]);

    email.failFor.clear();
    await useCase.execute(documentReadyCommand());
    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
    expect(files.reads).toHaveLength(2);
  });

  it('attaches with the content type file-service recorded', async () => {
    const { email, files, useCase } = setup();
    files.answers = [{ ...PDF, contentType: 'application/x-pdf' }];

    await useCase.execute(documentReadyCommand());

    expect(email.sent[0].attachments?.[0].contentType).toBe(
      'application/x-pdf',
    );
  });
});

describe('contractDocumentReadyBody', () => {
  it('contract-final-pdf/v1: the document, the attachment and both hashes', () => {
    expect(contractDocumentReadyBody(documentReadyCommand(), PDF)).toBe(
      [
        'The final signed contract is ready. Its PDF is attached to this email.',
        '',
        `Contract:     ${CONTRACT_ID}`,
        `Document:     ${DOCUMENT_ID}`,
        'Template:     VN_COFFEE_B2B',
        'Version:      1.0.0',
        'Generated at:',
        '2026-11-25 11:00:00 Vietnam time (2026-11-25T04:00:00.000Z UTC)',
        '',
        'Attached PDF:',
        `File:         ${DOCUMENT_FILE_ID}`,
        `Name:         contract-${CONTRACT_ID}.pdf`,
        `Size:         ${DOCUMENT_PDF.length} bytes`,
        'SHA-256 of the PDF (documentFileHash):',
        DOCUMENT_PDF_SHA256,
        '',
        'SHA-256 of the signed contract terms it was rendered from',
        '(sourceContentHash):',
        TERMS_SNAPSHOT_HASH,
        '',
        'The SHA-256 of the attached file was checked against documentFileHash',
        'before this email was sent. To check it yourself, compute the SHA-256 of',
        'the attachment: it must equal the value above. The two hashes answer',
        'different questions: documentFileHash identifies this PDF file,',
        'sourceContentHash the signed contract content it presents.',
        '',
      ].join('\n'),
    );
  });

  it('keeps every line within 76 characters', () => {
    for (const line of contractDocumentReadyBody(
      documentReadyCommand(),
      PDF,
    ).split('\n')) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
  });

  it('names the attachment after the contract', () => {
    expect(attachmentName(CONTRACT_ID)).toBe(`contract-${CONTRACT_ID}.pdf`);
  });
});
