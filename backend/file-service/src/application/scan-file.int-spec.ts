/**
 * The virus scan on the real things: clamd (compose `clamav`), SeaweedFS and
 * MySQL with the outbox of V2, without a broker (the job is read back from
 * event_outbox, as the poller would publish it). Run with
 *   npm run test:int
 * after, in backend/:
 *   docker compose up -d --wait file-db object-storage
 *   docker compose up file-migrate
 *   docker compose --profile scan up -d --wait clamav
 * CLAMAV_HOST / CLAMAV_PORT default to localhost:3410. The run uses a bucket
 * of its own, deleted at the end.
 */
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import {
  DeleteBucketCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
} from '@aws-sdk/client-s3';
import type { EventEnvelope } from '@agricontract/eventing';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { EICAR } from '../../test/support/eicar';
import { validate } from '../config/env.validation';
import { FileMysqlRepository } from '../infrastructure/persistence/file.mysql-repository';
import { ClamdScanner } from '../infrastructure/scan/clamd-scanner';
import {
  S3ObjectStorage,
  createS3Client,
} from '../infrastructure/storage/s3-object-storage';
import { DIRECT_UPLOAD_POLICY } from './ingest-policy';
import { ScanFileUseCase } from './scan-file.use-case';
import { StoreFileUseCase } from './store-file.use-case';

const bucket = `file-scan-int-${randomUUID().slice(0, 8)}`;
const user = randomUUID();

describe('virus scan on clamd, SeaweedFS and MySQL', () => {
  let pool: Pool;
  let storage: S3ObjectStorage;
  let store: StoreFileUseCase;
  let scan: ScanFileUseCase;
  const fileIds: string[] = [];
  const quiet = { log() {}, warn() {}, error() {} };

  async function upload(bytes: Buffer, contentType: string): Promise<string> {
    const file = await store.execute(
      {
        body: Readable.from([bytes]),
        contentType,
        contentLength: bytes.length,
        uploadedBy: user,
        idempotencyKey: randomUUID(),
      },
      DIRECT_UPLOAD_POLICY,
    );
    fileIds.push(file.fileId);
    return file.fileId;
  }

  async function outbox(fileId: string): Promise<EventEnvelope[]> {
    const [rows] = await pool.query<RowDataPacket[]>(
      'SELECT envelope FROM event_outbox WHERE aggregate_id = ? ORDER BY id',
      [fileId],
    );
    return rows.map((r) => r.envelope as EventEnvelope);
  }

  async function row(fileId: string): Promise<RowDataPacket> {
    const [rows] = await pool.query<RowDataPacket[]>(
      'SELECT status, failure_reason FROM file WHERE file_id = ?',
      [fileId],
    );
    return rows[0];
  }

  beforeAll(async () => {
    const config = validate(process.env);
    pool = createPool({
      host: config.db.host,
      port: config.db.port,
      database: config.db.name,
      user: config.db.username,
      password: config.db.password,
      timezone: 'Z',
    });
    storage = new S3ObjectStorage(
      createS3Client({ ...config.storage, bucket }),
      bucket,
    );
    await storage.ensureBucket();
    const files = new FileMysqlRepository(pool);
    store = new StoreFileUseCase(storage, files, quiet);
    scan = new ScanFileUseCase(
      storage,
      files,
      new ClamdScanner({ ...config.clamav, timeoutMs: 30_000 }),
      quiet,
    );
  });

  afterAll(async () => {
    try {
      if (fileIds.length > 0) {
        await pool.query('DELETE FROM event_outbox WHERE aggregate_id IN (?)', [
          fileIds,
        ]);
      }
      await pool.query('DELETE FROM file WHERE uploaded_by = ?', [user]);
      const client = createS3Client({ ...validate(process.env).storage });
      const res = await client.send(
        new ListObjectsV2Command({ Bucket: bucket }),
      );
      for (const o of res.Contents ?? []) {
        await client.send(
          new DeleteObjectCommand({ Bucket: bucket, Key: o.Key! }),
        );
      }
      await client.send(new DeleteBucketCommand({ Bucket: bucket }));
      client.destroy();
    } finally {
      storage.close();
      await pool.end();
    }
  });

  it('the upload writes its scan job to the outbox with the row', async () => {
    const fileId = await upload(
      Buffer.from('%PDF-1.7\n%%EOF\n'),
      'application/pdf',
    );

    const events = await outbox(fileId);

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      eventType: 'file.uploaded.direct',
      producer: 'file-service',
      aggregateId: fileId,
      payload: { fileId },
    });
    expect((await row(fileId)).status).toBe('PROCESSING');
  });

  it('EICAR ends FAILED "virus detected", with one file.failed', async () => {
    const fileId = await upload(EICAR, 'application/pdf');
    const [job] = await outbox(fileId);

    await expect(scan.execute(fileId, job)).resolves.toBe('FAILED');

    expect(await row(fileId)).toEqual({
      status: 'FAILED',
      failure_reason: 'virus detected',
    });
    const events = await outbox(fileId);
    expect(events.map((e) => e.eventType)).toEqual([
      'file.uploaded.direct',
      'file.failed',
    ]);
    expect(events[1]).toMatchObject({
      causationId: job.eventId,
      payload: {
        fileId,
        uploadedBy: user,
        ingestChannel: 'DIRECT_UPLOAD',
        failureReason: 'virus detected',
      },
    });
  });

  it('a clean 3 MiB PNG ends READY with one file.ready; the job again publishes nothing', async () => {
    const png = Buffer.concat([
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
      Buffer.alloc(3 * 1024 * 1024, 0x2a),
    ]);
    const fileId = await upload(png, 'image/png');
    const [job] = await outbox(fileId);

    await expect(scan.execute(fileId, job)).resolves.toBe('READY');
    await expect(scan.execute(fileId, job)).resolves.toBe('SKIPPED');

    expect(await row(fileId)).toEqual({
      status: 'READY',
      failure_reason: null,
    });
    const events = await outbox(fileId);
    expect(events.map((e) => e.eventType)).toEqual([
      'file.uploaded.direct',
      'file.ready',
    ]);
    expect(events[1].payload).toEqual({
      fileId,
      uploadedBy: user,
      ingestChannel: 'DIRECT_UPLOAD',
      contentType: 'image/png',
      fileSize: png.length,
    });
  });
});
