/**
 * The email intake bridge (#275) on the real things: GreenMail (IMAP),
 * SeaweedFS, MySQL (V4) and clamd, without a broker (scan jobs are read back
 * from event_outbox, as the poller would publish them). Run with
 *   npm run test:int
 * after, in backend/:
 *   docker compose up -d --wait file-db object-storage greenmail clamav
 *   docker compose up file-migrate
 * INTAKE_IMAP_HOST / _PORT default to localhost:3143, and the login to the
 * compose GreenMail user unless INTAKE_IMAP_USERNAME / _PASSWORD are set
 * (the app has no default for them; this test sets its own). Each run uses
 * an IMAP folder, an intake address and a bucket of its own.
 */
import { randomUUID } from 'node:crypto';
import {
  DeleteBucketCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
  type S3Client,
} from '@aws-sdk/client-s3';
import type { EventEnvelope } from '@agricontract/eventing';
import { ImapFlow } from 'imapflow';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { EICAR } from '../../test/support/eicar';
import { PDF, buildMail } from '../../test/support/mail';
import { validate, type AppConfig } from '../config/env.validation';
import { ImapIntakeMailbox } from '../infrastructure/intake/imap-intake-mailbox';
import { readMail } from '../infrastructure/intake/mime-reader';
import { FileMysqlRepository } from '../infrastructure/persistence/file.mysql-repository';
import { IntakeMysqlRepository } from '../infrastructure/persistence/intake.mysql-repository';
import { ClamdScanner } from '../infrastructure/scan/clamd-scanner';
import {
  S3ObjectStorage,
  createS3Client,
} from '../infrastructure/storage/s3-object-storage';
import { EmailIntakeUseCase } from './email-intake.use-case';
import { ScanFileUseCase } from './scan-file.use-case';
import { StoreFileUseCase } from './store-file.use-case';

const run = randomUUID().slice(0, 8);
const bucket = `file-intake-int-${run}`;
const folder = `int-${run}`;
const address = `intake-int-${run}@agricontract.local`;
/** INTAKE_AUTHSERV_ID of this test: GreenMail writes no header, the test mails do. */
const OUR_SERVER = 'mx.agricontract.local';

describe('email intake on GreenMail, SeaweedFS, MySQL and clamd', () => {
  // IMAP sessions and clamd take seconds each; three polls in a row pass Jest's 5 s default.
  jest.setTimeout(60_000);

  let config: AppConfig;
  let intakeConfig: NonNullable<AppConfig['intake']>;
  let pool: Pool;
  let client: S3Client;
  let storage: S3ObjectStorage;
  let imap: ImapFlow;
  let intake: EmailIntakeUseCase;
  let scan: ScanFileUseCase;
  const quiet = { log() {}, warn() {}, error() {} };

  const append = (raw: Buffer) => imap.append(folder, raw);

  async function rows<T extends RowDataPacket>(sql: string, values: unknown[]) {
    const [r] = await pool.query<T[]>(sql, values);
    return r;
  }
  const messages = () =>
    rows('SELECT * FROM email_intake_message WHERE source_mailbox = ?', [
      address,
    ]);
  const files = async () => {
    const keys = (await messages()).map((m) => `${m.message_key as string}:%`);
    if (keys.length === 0) return [];
    return rows(
      `SELECT * FROM file WHERE ${keys.map(() => 'idempotency_key LIKE ?').join(' OR ')} ORDER BY content_type`,
      keys,
    );
  };
  const outbox = async (aggregateIds: string[]) =>
    aggregateIds.length === 0
      ? []
      : (
          await rows(
            'SELECT envelope FROM event_outbox WHERE aggregate_id IN (?) ORDER BY id',
            [aggregateIds],
          )
        ).map((r) => r.envelope as EventEnvelope);

  beforeAll(async () => {
    config = validate({
      ...process.env,
      INTAKE_IMAP_HOST: process.env.INTAKE_IMAP_HOST ?? 'localhost',
      INTAKE_IMAP_USERNAME: process.env.INTAKE_IMAP_USERNAME ?? 'intake',
      INTAKE_IMAP_PASSWORD:
        process.env.INTAKE_IMAP_PASSWORD ?? 'intake-dev-secret',
    });
    intakeConfig = { ...config.intake!, mailbox: folder, address };
    pool = createPool({
      host: config.db.host,
      port: config.db.port,
      database: config.db.name,
      user: config.db.username,
      password: config.db.password,
      timezone: 'Z',
    });
    client = createS3Client({ ...config.storage, bucket });
    storage = new S3ObjectStorage(client, bucket);
    await storage.ensureBucket();
    imap = new ImapFlow({
      host: intakeConfig.host,
      port: intakeConfig.port,
      secure: intakeConfig.secure,
      auth: { user: intakeConfig.username, pass: intakeConfig.password },
      logger: false,
    });
    imap.on('error', () => undefined);
    await imap.connect();
    await imap.mailboxCreate(folder);

    const fileRepo = new FileMysqlRepository(pool);
    intake = new EmailIntakeUseCase(
      new ImapIntakeMailbox(intakeConfig),
      readMail,
      new StoreFileUseCase(storage, fileRepo, quiet),
      new IntakeMysqlRepository(pool),
      quiet,
      { authservId: OUR_SERVER },
    );
    scan = new ScanFileUseCase(
      storage,
      fileRepo,
      new ClamdScanner({ ...config.clamav, timeoutMs: 30_000 }),
      quiet,
    );
  });

  afterAll(async () => {
    try {
      const keys = (await messages()).map((m) => m.message_key as string);
      const ids = (await files()).map((f) => f.file_id as string);
      if (ids.length + keys.length > 0) {
        await pool.query('DELETE FROM event_outbox WHERE aggregate_id IN (?)', [
          [...ids, ...keys],
        ]);
      }
      if (ids.length > 0) {
        await pool.query('DELETE FROM file WHERE file_id IN (?)', [ids]);
      }
      await pool.query(
        'DELETE FROM email_intake_failure WHERE source_mailbox = ?',
        [address],
      );
      await pool.query(
        'DELETE FROM email_intake_message WHERE source_mailbox = ?',
        [address],
      );
      const listed = await client.send(
        new ListObjectsV2Command({ Bucket: bucket }),
      );
      for (const o of listed.Contents ?? []) {
        await client.send(
          new DeleteObjectCommand({ Bucket: bucket, Key: o.Key! }),
        );
      }
      await client.send(new DeleteBucketCommand({ Bucket: bucket }));
      await imap.mailboxDelete(folder).catch(() => undefined);
    } finally {
      await imap.logout().catch(() => imap.close());
      storage.close();
      await pool.end();
    }
  });

  it('one poll: attachments stored and scanned, a notice published, a bad type recorded; all once', async () => {
    const auth = [
      `${OUR_SERVER}; spf=pass smtp.mailfrom=sgs.example.com; dkim=pass header.d=sgs.example.com; dmarc=pass`,
    ];
    const reportMail = buildMail({
      subject: 'Inspection report CASE-2026-0042',
      authenticationResults: auth,
      attachments: [
        { filename: 'report.pdf', contentType: 'application/pdf', bytes: PDF },
      ],
    });
    await append(reportMail);
    await append(
      buildMail({
        subject: 'Report (second copy)',
        attachments: [
          {
            filename: 'report.pdf',
            contentType: 'application/pdf',
            bytes: EICAR,
          },
        ],
      }),
    );
    await append(
      buildMail({
        subject: 'Re: our case ID SGS-VN-7781',
        text: 'Case ID SGS-VN-7781 confirmed.',
      }),
    );
    await append(
      buildMail({
        subject: 'Report as Word',
        attachments: [
          {
            filename: 'report.docx',
            contentType: 'application/msword',
            bytes: Buffer.from('PK'),
          },
        ],
      }),
    );

    await expect(intake.poll(50)).resolves.toBe(4);

    // Every mail recorded once and flagged \Seen.
    expect(await messages()).toHaveLength(4);
    await imap.mailboxOpen(folder);
    expect(await imap.search({ seen: false }, { uid: true })).toEqual([]);

    // Two EMAIL_INTAKE files, hash computed when stored, each with its scan job and emailMeta.
    const stored = await files();
    expect(stored).toHaveLength(2);
    for (const f of stored) {
      expect(f).toMatchObject({
        ingest_channel: 'EMAIL_INTAKE',
        status: 'PROCESSING',
        uploaded_by: 'file-service:email-intake',
      });
      expect(f.storage_hash).toMatch(/^[0-9a-f]{64}$/);
    }
    const ids = stored.map((f) => f.file_id as string);
    const jobs = await outbox(ids);
    expect(jobs.map((j) => j.eventType)).toEqual([
      'file.email.parsed',
      'file.email.parsed',
    ]);

    // The scan decides: the report READY with file.ready + emailMeta, EICAR FAILED.
    for (const job of jobs) await scan.execute(job.aggregateId, job);
    const after = await outbox(ids);
    const ready = after.filter((e) => e.eventType === 'file.ready');
    const failed = after.filter((e) => e.eventType === 'file.failed');
    expect(ready).toHaveLength(1);
    expect(failed).toHaveLength(1);
    expect(ready[0].payload).toMatchObject({
      ingestChannel: 'EMAIL_INTAKE',
      uploadedBy: 'file-service:email-intake',
      emailMeta: {
        senderDomain: 'sgs.example.com',
        spfDkimResult: 'PASS',
        subject: 'Inspection report CASE-2026-0042',
      },
    });
    expect(failed[0].payload).toMatchObject({
      failureReason: 'virus detected',
    });

    // The mail without attachment: one file.email_notice, keyed on its message.
    const notices = await outbox(
      (await messages()).map((m) => m.message_key as string),
    );
    expect(notices).toHaveLength(1);
    expect(notices[0]).toMatchObject({
      eventType: 'file.email_notice',
      payload: {
        subject: 'Re: our case ID SGS-VN-7781',
        snippet: 'Case ID SGS-VN-7781 confirmed.',
      },
    });

    // The Word file: one failure for the Operator.
    const failures = await rows(
      'SELECT * FROM email_intake_failure WHERE source_mailbox = ?',
      [address],
    );
    expect(failures).toHaveLength(1);
    expect(failures[0]).toMatchObject({ file_id: null, reviewed_by_admin: 0 });
    expect(failures[0].failure_reason).toContain(
      'application/msword not accepted',
    );

    // Polled again, and the same report delivered a second time: nothing new.
    await append(reportMail);
    await intake.poll(50);
    expect(await messages()).toHaveLength(4);
    expect(await files()).toHaveLength(2);
    expect(await outbox(ids)).toHaveLength(after.length);
  });

  it('a mail that fails every time is given up on its 3rd poll: flagged \\Seen, one failure row, and the mail behind it goes', async () => {
    // Storage that refuses every PUT: a technical failure, the same each time.
    const broken = new StoreFileUseCase(
      {
        put: () =>
          Promise.reject(
            Object.assign(new Error('refused'), { code: 'ECONNREFUSED' }),
          ),
        delete: () => Promise.resolve(),
      } as unknown as S3ObjectStorage,
      new FileMysqlRepository(pool),
      quiet,
    );
    const failing = new EmailIntakeUseCase(
      new ImapIntakeMailbox(intakeConfig),
      readMail,
      broken,
      new IntakeMysqlRepository(pool),
      quiet,
      { authservId: OUR_SERVER },
    );
    const before = (await messages()).length;
    const appended = await append(
      buildMail({
        subject: 'Report that cannot be stored',
        attachments: [
          { filename: 'r.pdf', contentType: 'application/pdf', bytes: PDF },
        ],
      }),
    );
    await append(buildMail({ subject: 'Case ID SGS-VN-9001' }));
    const poison = appended ? appended.uid : undefined;
    expect(poison).toBeDefined();
    await imap.mailboxOpen(folder);
    const unseen = async () =>
      (await imap.search({ seen: false }, { uid: true })) || [];

    await expect(failing.poll(50)).rejects.toThrow();
    await expect(failing.poll(50)).rejects.toThrow();
    expect(await unseen()).toHaveLength(2);

    await expect(failing.poll(50)).resolves.toBe(2);

    expect(await unseen()).toEqual([]);
    const failures = await rows(
      'SELECT failure_reason FROM email_intake_failure WHERE source_mailbox = ? AND failure_reason LIKE ?',
      [address, '%could not be processed%'],
    );
    expect(failures.map((f) => f.failure_reason as string)).toEqual([
      `mail uid ${poison}: could not be processed: ECONNREFUSED (3 attempts)`,
    ]);
    // Only the mail behind it is recorded as processed.
    expect(await messages()).toHaveLength(before + 1);
  });
});
