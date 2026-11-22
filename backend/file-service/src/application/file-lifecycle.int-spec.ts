/**
 * The lifecycle job (#276) on SeaweedFS and MySQL (V3), on a bucket of its
 * own: real SQL guards, real deletes. Run with
 *   npm run test:int
 * after `docker compose up -d --wait file-db object-storage` and
 * `docker compose up file-migrate` in backend/. States that need time
 * (a week-old upload, a passed retention, a hold) are set in SQL.
 */
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import {
  DeleteBucketCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
  type S3Client,
} from '@aws-sdk/client-s3';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { validate } from '../config/env.validation';
import { FileMysqlRepository } from '../infrastructure/persistence/file.mysql-repository';
import {
  S3ObjectStorage,
  createS3Client,
} from '../infrastructure/storage/s3-object-storage';
import { DeleteFileUseCase } from './delete-file.use-case';
import {
  FileLifecycleUseCase,
  LIFECYCLE_REASONS,
  ORPHAN_AFTER_MS,
} from './file-lifecycle.use-case';
import { DIRECT_UPLOAD_POLICY } from './ingest-policy';
import { StoreFileUseCase } from './store-file.use-case';

const bucket = `file-lifecycle-int-${randomUUID().slice(0, 8)}`;
const user = randomUUID();

describe('lifecycle job on SeaweedFS and MySQL', () => {
  let pool: Pool;
  let client: S3Client;
  let storage: S3ObjectStorage;
  let files: FileMysqlRepository;
  let store: StoreFileUseCase;
  let lifecycle: FileLifecycleUseCase;
  const fileIds: string[] = [];
  const quiet = { log() {}, warn() {} };

  async function upload(): Promise<string> {
    const bytes = Buffer.from(`%PDF-1.7\n${randomUUID()}\n%%EOF\n`);
    const file = await store.execute(
      {
        body: Readable.from([bytes]),
        contentType: 'application/pdf',
        contentLength: bytes.length,
        uploadedBy: user,
        idempotencyKey: randomUUID(),
      },
      DIRECT_UPLOAD_POLICY,
    );
    fileIds.push(file.fileId);
    return file.fileId;
  }

  const set = (fileId: string, sql: string, values: unknown[] = []) =>
    pool.query(`UPDATE file SET ${sql} WHERE file_id = ?`, [...values, fileId]);

  async function row(fileId: string): Promise<RowDataPacket> {
    const [rows] = await pool.query<RowDataPacket[]>(
      'SELECT deleted_at, deletion_reason, blob_deleted_at FROM file WHERE file_id = ?',
      [fileId],
    );
    return rows[0];
  }

  async function hasObject(key: string): Promise<boolean> {
    try {
      await client.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
      return true;
    } catch {
      return false;
    }
  }

  const deletedBy = async (fileId: string, reason: string) => {
    const r = await row(fileId);
    expect(r.deleted_at).toBeInstanceOf(Date);
    expect(r.deletion_reason).toBe(reason);
    expect(r.blob_deleted_at).toBeInstanceOf(Date);
    expect(await hasObject(fileId)).toBe(false);
  };
  const kept = async (fileId: string) => {
    expect((await row(fileId)).deleted_at).toBeNull();
    expect(await hasObject(fileId)).toBe(true);
  };

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
    client = createS3Client({ ...config.storage, bucket });
    storage = new S3ObjectStorage(client, bucket);
    await storage.ensureBucket();
    files = new FileMysqlRepository(pool);
    store = new StoreFileUseCase(storage, files, { warn() {} });
    lifecycle = new FileLifecycleUseCase(
      files,
      storage,
      new DeleteFileUseCase(storage, files),
      quiet,
      {
        batchSize: 1000,
        orphanAfterMs: ORPHAN_AFTER_MS,
        // Every object of this bucket is the test's: sweep without waiting.
        strayBlobGraceMs: 0,
        now: () => new Date(),
      },
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
      const res = await client.send(
        new ListObjectsV2Command({ Bucket: bucket }),
      );
      for (const o of res.Contents ?? []) {
        await client.send(
          new DeleteObjectCommand({ Bucket: bucket, Key: o.Key! }),
        );
      }
      await client.send(new DeleteBucketCommand({ Bucket: bucket }));
    } finally {
      storage.close();
      await pool.end();
    }
  });

  it('one run: orphans and expired files go; attached, fresh and held files stay', async () => {
    const orphan = await upload();
    await set(orphan, 'created_at = NOW() - INTERVAL 8 DAY');
    const fresh = await upload();
    await set(fresh, 'created_at = NOW() - INTERVAL 6 DAY');
    const attachedOld = await upload();
    await set(
      attachedOld,
      'attached = TRUE, created_at = NOW() - INTERVAL 30 DAY',
    );
    const expired = await upload();
    await set(
      expired,
      'attached = TRUE, retention_until = NOW() - INTERVAL 1 MINUTE',
    );
    const heldOrphan = await upload();
    await set(
      heldOrphan,
      'legal_hold = TRUE, created_at = NOW() - INTERVAL 30 DAY',
    );
    // Matrix 21d: retention long over, but under legal hold.
    const heldExpired = await upload();
    await set(
      heldExpired,
      'attached = TRUE, legal_hold = TRUE, retention_until = NOW() - INTERVAL 1 YEAR',
    );
    const future = await upload();
    await set(
      future,
      'attached = TRUE, retention_until = NOW() + INTERVAL 1 DAY',
    );

    await lifecycle.runOnce();

    await deletedBy(orphan, LIFECYCLE_REASONS.ORPHAN);
    await deletedBy(expired, LIFECYCLE_REASONS.EXPIRED);
    for (const id of [fresh, attachedOld, heldOrphan, heldExpired, future]) {
      await kept(id);
    }
  });

  it('matrix 21e: a tombstoned row whose blob is still there is finished by the next run', async () => {
    const half = await upload();
    // What a failed blob step leaves: step 1 done, the blob and no mark.
    await set(half, 'deleted_at = NOW(), deletion_reason = ?', [
      'test: step 2 failed',
    ]);
    expect(await hasObject(half)).toBe(true);

    await lifecycle.runOnce();

    await deletedBy(half, 'test: step 2 failed');
  });

  it('a stray blob (a fileId key with no row) is swept; objects with a row stay', async () => {
    const strayKey = randomUUID();
    await client.send(
      new PutObjectCommand({
        Bucket: bucket,
        Key: strayKey,
        Body: 'left by a failed store',
      }),
    );
    const withRow = await upload();
    await set(withRow, 'attached = TRUE');

    const report = await lifecycle.runOnce();

    expect(report.strayBlobs).toBe(1);
    expect(await hasObject(strayKey)).toBe(false);
    await kept(withRow);
  });

  it('a second run finds nothing to do', async () => {
    await lifecycle.runOnce();

    expect(await lifecycle.runOnce()).toEqual({
      orphans: 0,
      expired: 0,
      blobsRemoved: 0,
      strayBlobs: 0,
      failures: 0,
    });
  });
});
