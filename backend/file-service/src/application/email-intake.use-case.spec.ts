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
  authservIdOf,
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
  MailLimits,
} from './ports/intake-mailbox.port';
import { StoreFileUseCase } from './store-file.use-case';

const RECEIVED = new Date('2026-11-22T02:00:05Z');
/** INTAKE_AUTHSERV_ID of these tests: the id our receiving server writes. */
const OUR_SERVER = 'mx.agricontract.local';
const KiB = 1024;
const MiB = 1024 * KiB;

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
  /** Rows written by recordFailure (mails given up). */
  readonly givenUp: { sourceMailbox: string; reason: string }[] = [];
  recordError: Error | null = null;
  failureError: Error | null = null;

  recordFailure(sourceMailbox: string, reason: string): Promise<void> {
    if (this.failureError) return Promise.reject(this.failureError);
    this.givenUp.push({ sourceMailbox, reason });
    return Promise.resolve();
  }

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
  let logs: string[];
  let warnings: string[];
  const auth = `${OUR_SERVER}; spf=pass smtp.mailfrom=sgs.example.com; dkim=pass header.d=sgs.example.com; dmarc=pass`;

  beforeEach(() => {
    mailbox = new FakeMailbox();
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    intake = new InMemoryIntake();
    logs = [];
    warnings = [];
    useCase = new EmailIntakeUseCase(
      mailbox,
      readMail,
      new StoreFileUseCase(storage, files, { warn() {} }),
      intake,
      { log: (m) => void logs.push(m), warn: (m) => void warnings.push(m) },
      { authservId: OUR_SERVER },
    );
  });

  const reportMail = (overrides: Partial<TestMail> = {}): TestMail => ({
    attachments: [
      { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
    ],
    ...overrides,
  });

  const stored = () => [...files.rows.values()];

  /**
   * The use case with a reader that records the limits it is asked for and
   * reads with them divided by 1024, so a test of a limit moves kilobytes,
   * not megabytes (the reader's own spec holds it to the limit it is given).
   */
  const withScaledReader = (asked: MailLimits[]) =>
    new EmailIntakeUseCase(
      mailbox,
      (source, limits) => {
        asked.push(limits);
        return readMail(source, {
          attachmentBytes: limits.attachmentBytes / KiB,
          mailBytes: limits.mailBytes / KiB,
        });
      },
      new StoreFileUseCase(storage, files, { warn() {} }),
      intake,
      { log() {}, warn() {} },
      { authservId: OUR_SERVER },
    );
  const LIMITS = { attachmentBytes: 20 * MiB, mailBytes: 40 * MiB };

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
          spfDkimResult: 'PASS',
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
        spfDkimResult: 'PASS',
        subject: 'Re: commission request - our case ID SGS-VN-7781',
        snippet: 'Dear team, we confirm the case ID SGS-VN-7781. Regards',
        receivedAt: '2026-11-22T02:00:05.000Z',
      },
    });
  });

  it('what cannot be taken becomes a failure for the Operator; the rest of the mail still goes', async () => {
    const asked: MailLimits[] = [];
    useCase = withScaledReader(asked);
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
          bytes: Buffer.alloc(20 * KiB + 1, 1),
        },
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });

    await useCase.poll(10);

    expect(asked).toEqual([LIMITS]);
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
    // Skipped, but traceable: uid and the first 12 characters of the key.
    const key = intake.all[0].messageKey;
    expect(logs).toContain(
      `Intake mail uid 2 (${key.slice(0, 12)}): already processed, skipped`,
    );
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

  describe('a mail that fails technically every time (design §4, D88)', () => {
    const failInsert = () => {
      files.insertError = Object.assign(new Error('x'), {
        code: 'ER_LOCK_DEADLOCK',
      });
    };

    it('holds back the mails behind it for 2 polls, then is given up: one failure row, flagged read, and the poll goes on', async () => {
      const poison = mailbox.add(reportMail({ subject: 'poison' }));
      const next = mailbox.add({ subject: 'Case ID SGS-VN-1' });
      failInsert();

      await expect(useCase.poll(10)).rejects.toThrow();
      await expect(useCase.poll(10)).rejects.toThrow();
      expect(mailbox.seen.size).toBe(0);
      expect(intake.givenUp).toEqual([]);
      expect(warnings).toEqual([
        'Intake mail uid 1: attempt 1 of 3 failed (ER_LOCK_DEADLOCK); the next poll tries it again',
        'Intake mail uid 1: attempt 2 of 3 failed (ER_LOCK_DEADLOCK); the next poll tries it again',
      ]);

      await expect(useCase.poll(10)).resolves.toBe(2);

      expect(intake.givenUp).toEqual([
        {
          sourceMailbox: 'intake@agricontract.local',
          reason: expect.stringMatching(
            /^mail uid 1: could not be processed: \S+ \(3 attempts\)$/,
          ) as string,
        },
      ]);
      expect([...mailbox.seen]).toEqual([poison, next]);
      // The mail behind it went through; the one given up is not recorded as processed.
      expect(intake.all).toHaveLength(1);
      expect(intake.all[0].notice).not.toBeNull();
    });

    it('the failure code, never a message, goes into the row', async () => {
      mailbox.add(reportMail());
      storage.putError = Object.assign(
        new Error('connect ECONNREFUSED 10.0.0.5:8333'),
        { code: 'ECONNREFUSED' },
      );
      storage.failBeforeRead = true;

      for (let i = 0; i < 2; i += 1) {
        await expect(useCase.poll(10)).rejects.toThrow();
      }
      await useCase.poll(10);

      expect(intake.givenUp[0].reason).toBe(
        'mail uid 1: could not be processed: ECONNREFUSED (3 attempts)',
      );
      expect(JSON.stringify(intake.givenUp)).not.toContain('10.0.0.5');
    });

    it('attempts count per mail, whatever fails: here the record after the stores', async () => {
      mailbox.add(reportMail());
      failInsert();
      await expect(useCase.poll(10)).rejects.toThrow();
      await expect(useCase.poll(10)).rejects.toThrow();
      files.insertError = null;
      intake.recordError = Object.assign(new Error('x'), {
        code: 'ECONNRESET',
      });
      // Third failure, but of the record after the stores: still given up.
      await useCase.poll(10);
      expect(intake.givenUp).toHaveLength(1);

      // Flagged unread again by an Operator: taken in anew, and it now works.
      intake.recordError = null;
      mailbox.seen.clear();
      await useCase.poll(10);
      expect(intake.all).toHaveLength(1);
      expect(stored()).toHaveLength(1);
    });

    it('a failure row that cannot be written either leaves the mail unseen', async () => {
      mailbox.add(reportMail());
      failInsert();
      intake.failureError = Object.assign(new Error('x'), {
        code: 'ECONNREFUSED',
      });
      for (let i = 0; i < 3; i += 1) {
        await expect(useCase.poll(10)).rejects.toThrow();
      }
      expect(mailbox.seen.size).toBe(0);

      // Once the database is back, the next failure gives it up at once.
      intake.failureError = null;
      await useCase.poll(10);
      expect(intake.givenUp).toHaveLength(1);
      expect(mailbox.seen.size).toBe(1);
    });
  });

  it('the attachments of one mail are taken up to 40 MiB in all; the rest become failures', async () => {
    const asked: MailLimits[] = [];
    useCase = withScaledReader(asked);
    const big = (name: string) => ({
      filename: name,
      contentType: 'application/pdf',
      bytes: Buffer.alloc(15 * KiB, 1),
    });
    mailbox.add({
      attachments: [big('a.pdf'), big('b.pdf'), big('c.pdf')],
    });

    await useCase.poll(10);

    expect(asked).toEqual([LIMITS]);
    expect(stored()).toHaveLength(2);
    expect(intake.all[0]).toMatchObject({
      attachments: 2,
      failures: [
        'attachment 3 "c.pdf": not taken, the attachments of one mail are limited to 40 MiB in all',
      ],
    });
  });

  it('spfDkimResult reads only our server: a header the sender wrote is ignored', async () => {
    mailbox.add(
      reportMail({
        // The sender's own claim; our server wrote nothing (GreenMail).
        authenticationResults: ['mx.sender.example; dmarc=pass'],
      }),
    );

    await useCase.poll(10);

    expect(files.outbox[0].payload).toMatchObject({
      emailMeta: { spfDkimResult: 'NONE' },
    });
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
    [[], 'NONE'],
    [['mx.ours; none'], 'NONE'],
    [['mx.ours; spf=pass; dkim=pass; dmarc=pass'], 'PASS'],
    // DMARC passes with DKIM aligned, so a forwarded mail with spf=fail is PASS.
    [['mx.ours; spf=fail; dkim=pass; dmarc=pass'], 'PASS'],
    [['mx.ours; dkim=fail header.d=x; spf=softfail'], 'FAIL'],
    [['mx.ours; spf=pass; dkim=pass; dmarc=fail'], 'FAIL'],
    [['mx.ours; spf=pass; dkim=pass'], 'NONE'],
    [['mx.ours; spf=softfail; dkim=neutral; dmarc=temperror'], 'NONE'],
    [['MX.OURS 1; dkim/1 = fail'], 'FAIL'],
    // The first verdict of a method counts.
    [['mx.ours; dmarc=pass; dmarc=fail'], 'PASS'],
    // A comment says nothing.
    [['mx.ours (dmarc=pass); spf=none'], 'NONE'],
    // The sender wrote one without our id: never read.
    [['mx.theirs; spf=pass; dkim=pass; dmarc=pass'], 'NONE'],
    // Ours is the topmost header carrying our id; others are skipped.
    [
      ['mx.relay; dmarc=pass', 'mx.ours; dmarc=fail', 'mx.ours; dmarc=pass'],
      'FAIL',
    ],
    // An id that only starts with ours is not ours.
    [['mx.ours.evil.example; dmarc=pass'], 'NONE'],
  ])('spfDkimResult(%j) = %j', (headers, result) => {
    expect(spfDkimResult(headers, 'mx.ours')).toBe(result);
  });

  it('spfDkimResult is NONE when no authserv-id is set: no header is trusted', () => {
    expect(spfDkimResult(['mx.ours; dmarc=pass'], null)).toBe('NONE');
  });

  it.each([
    ['mx.ours; spf=pass', 'mx.ours'],
    ['  MX.Ours 1; dkim=pass', 'mx.ours'],
    ['(comment) mx.ours; none', 'mx.ours'],
    ['', null],
  ])('authservIdOf(%j) = %j', (header, id) => {
    expect(authservIdOf(header)).toBe(id);
  });

  it('snippet collapses whitespace and keeps 200 characters', () => {
    expect(snippet('  a\n\n b\tc  ')).toBe('a b c');
    expect(snippet('x'.repeat(500))).toHaveLength(200);
  });
});
