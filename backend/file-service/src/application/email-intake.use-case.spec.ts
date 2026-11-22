import { Readable } from 'node:stream';
import { EICAR } from '../../test/support/eicar';
import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { PDF, buildMail, type TestMail } from '../../test/support/mail';
import { readMail } from '../infrastructure/intake/mime-reader';
import {
  EmailIntakeUseCase,
  INTAKE_UPLOADER,
  domainOf,
  snippet,
  spfDkimResult,
} from './email-intake.use-case';
import type {
  IntakeRepository,
  ProcessedMail,
} from './ports/intake.repository';
import type {
  IntakeMail,
  IntakeMailboxPort,
} from './ports/intake-mailbox.port';
import { StoreFileUseCase } from './store-file.use-case';

const RECEIVED = new Date('2026-11-22T02:00:05Z');

class FakeMailbox implements IntakeMailboxPort {
  readonly address = 'intake@agricontract.local';
  /** uid → raw mail; seen ones are flagged. */
  readonly mails = new Map<number, Buffer>();
  readonly seen = new Set<number>();
  private next = 1;

  add(mail: TestMail | Buffer): number {
    const uid = this.next++;
    this.mails.set(uid, Buffer.isBuffer(mail) ? mail : buildMail(mail));
    return uid;
  }

  async processUnseen(
    handle: (mail: IntakeMail) => Promise<void>,
    limit: number,
  ): Promise<number> {
    let handled = 0;
    for (const [uid, raw] of this.mails) {
      if (this.seen.has(uid) || handled >= limit) continue;
      await handle({
        uid,
        uidValidity: '7',
        internalDate: RECEIVED,
        source: Readable.from([raw]),
      });
      this.seen.add(uid);
      handled += 1;
    }
    return handled;
  }
}

class InMemoryIntake implements IntakeRepository {
  readonly processed = new Map<string, ProcessedMail>();
  recordError: Error | null = null;

  isProcessed(key: string): Promise<boolean> {
    return Promise.resolve(this.processed.has(key));
  }

  recordProcessed(mail: ProcessedMail): Promise<boolean> {
    if (this.recordError) return Promise.reject(this.recordError);
    if (this.processed.has(mail.messageKey)) return Promise.resolve(false);
    this.processed.set(mail.messageKey, mail);
    return Promise.resolve(true);
  }

  get all(): ProcessedMail[] {
    return [...this.processed.values()];
  }
}

describe('EmailIntakeUseCase', () => {
  let mailbox: FakeMailbox;
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let intake: InMemoryIntake;
  let useCase: EmailIntakeUseCase;
  const auth =
    'mx.agricontract.local; spf=pass smtp.mailfrom=sgs.example.com; dkim=pass header.d=sgs.example.com; dmarc=pass';

  beforeEach(() => {
    mailbox = new FakeMailbox();
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    intake = new InMemoryIntake();
    useCase = new EmailIntakeUseCase(
      mailbox,
      readMail,
      new StoreFileUseCase(storage, files, { warn() {} }),
      intake,
      { log() {}, warn() {} },
    );
  });

  const stored = () => [...files.rows.values()];

  it('a report attachment becomes an EMAIL_INTAKE file, PROCESSING, its hash computed as it was stored', async () => {
    mailbox.add({
      from: 'reports@SGS.example.com',
      subject: 'Report CASE-2026-0042',
      authenticationResults: [auth],
      attachments: [
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });

    await expect(useCase.poll(10)).resolves.toBe(1);

    expect(stored()).toHaveLength(1);
    const [file] = stored();
    expect(file).toMatchObject({
      ingestChannel: 'EMAIL_INTAKE',
      status: 'PROCESSING',
      uploadedBy: INTAKE_UPLOADER,
      contentType: 'application/pdf',
      fileSize: PDF.length,
    });
    expect(storage.objects.get(file.fileId)?.bytes).toEqual(PDF);
    expect(file.storageHash).toMatch(/^[0-9a-f]{64}$/);
    // Its scan job carries the mail's emailMeta on to file.ready.
    expect(files.outbox).toHaveLength(1);
    expect(files.outbox[0]).toMatchObject({
      eventType: 'file.email.parsed',
      payload: {
        fileId: file.fileId,
        emailMeta: {
          senderDomain: 'sgs.example.com',
          spfDkimResult: 'spf=pass; dkim=pass; dmarc=pass',
          subject: 'Report CASE-2026-0042',
          receivedAt: '2026-11-22T02:00:05.000Z',
        },
      },
    });
    expect(intake.all).toEqual([
      expect.objectContaining({ attachments: 1, failures: [], notice: null }),
    ]);
  });

  it('EICAR is taken in like any attachment: the scan, not the intake, fails it', async () => {
    mailbox.add({
      attachments: [
        {
          filename: 'report.pdf',
          contentType: 'application/pdf',
          bytes: EICAR,
        },
      ],
    });

    await useCase.poll(10);

    expect(stored()).toHaveLength(1);
    expect(files.outbox[0].eventType).toBe('file.email.parsed');
  });

  it('a mail without attachment publishes one file.email_notice and creates no file', async () => {
    mailbox.add({
      subject: 'Re: commission request - our case ID SGS-VN-7781',
      text: 'Dear team,\n\n   we confirm the case ID SGS-VN-7781.\n\nRegards',
      authenticationResults: [auth],
    });

    await useCase.poll(10);

    expect(stored()).toEqual([]);
    const [record] = intake.all;
    expect(record.notice).toMatchObject({
      eventType: 'file.email_notice',
      producer: 'file-service',
      aggregateId: record.messageKey,
      payload: {
        senderDomain: 'sgs.example.com',
        spfDkimResult: 'spf=pass; dkim=pass; dmarc=pass',
        subject: 'Re: commission request - our case ID SGS-VN-7781',
        snippet: 'Dear team, we confirm the case ID SGS-VN-7781. Regards',
        receivedAt: '2026-11-22T02:00:05.000Z',
      },
    });
  });

  it('what cannot be taken becomes a failure for the Operator; the rest of the mail still goes', async () => {
    mailbox.add({
      attachments: [
        {
          filename: 'report.docx',
          contentType:
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          bytes: Buffer.from('PK'),
        },
        {
          filename: 'huge.pdf',
          contentType: 'application/pdf',
          bytes: Buffer.alloc(20 * 1024 * 1024 + 1, 1),
        },
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });

    await useCase.poll(10);

    expect(stored()).toHaveLength(1);
    expect(intake.all[0]).toMatchObject({
      attachments: 1,
      notice: null,
      failures: [
        'attachment 1 "report.docx": type application/vnd.openxmlformats-officedocument.wordprocessingml.document not accepted (PDF, JPEG or PNG)',
        'attachment 2 "huge.pdf": larger than 20 MiB',
      ],
      sourceMailbox: 'intake@agricontract.local',
    });
  });

  it('once per mail: the same mail polled again (not flagged before a crash) does nothing', async () => {
    const raw = buildMail({
      messageId: '<same@sgs.example.com>',
      attachments: [
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });
    mailbox.add(raw);
    await useCase.poll(10);
    // The same message delivered again (or the flag lost): another uid, same Message-ID.
    mailbox.add(raw);

    await useCase.poll(10);

    expect(stored()).toHaveLength(1);
    expect(files.outbox).toHaveLength(1);
    expect(intake.all).toHaveLength(1);
  });

  it('a crash before the record: the next poll stores nothing twice (the store is idempotent per attachment)', async () => {
    mailbox.add({
      attachments: [
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });
    intake.recordError = Object.assign(new Error('x'), { code: 'ECONNRESET' });

    await expect(useCase.poll(10)).rejects.toThrow();
    expect(mailbox.seen.size).toBe(0); // left unseen

    intake.recordError = null;
    await useCase.poll(10);

    expect(stored()).toHaveLength(1);
    expect(files.outbox).toHaveLength(1);
    expect(intake.all).toHaveLength(1);
  });

  it('storage down: the poll fails and the mail stays unseen, nothing recorded', async () => {
    mailbox.add({
      attachments: [
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });
    storage.putError = Object.assign(new Error('x'), { code: 'ECONNREFUSED' });
    storage.failBeforeRead = true;

    await expect(useCase.poll(10)).rejects.toThrow();

    expect(mailbox.seen.size).toBe(0);
    expect(intake.all).toEqual([]);
  });

  it('a mail without Message-ID is keyed on UIDVALIDITY:UID', async () => {
    mailbox.add({ messageId: null });
    mailbox.add({ messageId: null });

    await useCase.poll(10);

    expect(intake.all).toHaveLength(2);
  });

  it('takes at most `limit` mails per poll', async () => {
    for (let i = 0; i < 3; i += 1) mailbox.add({});

    expect(await useCase.poll(2)).toBe(2);
    expect(await useCase.poll(2)).toBe(1);
  });
});

describe('emailMeta helpers', () => {
  it.each([
    ['reports@SGS.Example.com', 'sgs.example.com'],
    ['"a@b" <x@y.vn>', 'y.vn'],
    [null, ''],
    ['no-at-sign', ''],
  ])('domainOf(%j) = %j', (address, domain) => {
    expect(domainOf(address)).toBe(domain);
  });

  it.each([
    [[], 'none'],
    [['mx.example; none'], 'none'],
    [
      ['mx.example; dkim=fail header.d=x; spf=softfail'],
      'spf=softfail; dkim=fail',
    ],
    [['mx.example; spf=pass; spf=fail'], 'spf=pass'],
    // Only the topmost header, the one our side added, counts.
    [['mx.ours; spf=fail', 'mx.theirs; spf=pass; dkim=pass'], 'spf=fail'],
  ])('spfDkimResult(%j) = %j', (headers, result) => {
    expect(spfDkimResult(headers)).toBe(result);
  });

  it('snippet collapses whitespace and keeps 200 characters', () => {
    expect(snippet('  a\n\n b\tc  ')).toBe('a b c');
    expect(snippet('x'.repeat(500))).toHaveLength(200);
  });
});
