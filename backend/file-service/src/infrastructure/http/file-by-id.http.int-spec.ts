/**
 * GET, confirm-attached and DELETE on /internal/v1/files/{fileId} through the
 * whole app (AppModule) on the compose services. Run with
 *   npm run test:int
 * after `docker compose up -d --wait file-db object-storage` and
 * `docker compose up file-migrate` in backend/. The run uses a bucket of its
 * own, deleted at the end. Nothing sets READY before the virus scan (#131),
 * so the tests set it in SQL, as the scan would.
 */
import { createHash, randomUUID } from 'node:crypto';
import { get as httpGet } from 'node:http';
import type { AddressInfo } from 'node:net';
import {
  DeleteBucketCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import type { App } from 'supertest/types';
import { APP_OPTIONS } from '../../app.options';
import { OWNER_DELETION_REASON } from '../../application/delete-file.use-case';
import { validate } from '../../config/env.validation';
import { createS3Client } from '../storage/s3-object-storage';
import { withoutBroker } from '../../../test/support/without-broker';

const SECRET = `int-secret-${randomUUID()}`;
const bucket = `file-byid-int-${randomUUID().slice(0, 8)}`;
const user = randomUUID();
const sha256 = (bytes: Buffer) =>
  createHash('sha256').update(bytes).digest('hex');

describe('/internal/v1/files/{fileId} on SeaweedFS and MySQL', () => {
  let app: INestApplication | undefined;
  let pool: Pool;
  let client: S3Client;
  let port: number;
  const pdf = Buffer.concat([
    Buffer.from('%PDF-1.7\n'),
    Buffer.alloc(3 * 1024 * 1024 + 17, 5),
    Buffer.from('%%EOF\n'),
  ]);

  const server = () => app!.getHttpServer() as App;
  const path = (fileId: string, suffix = '') =>
    `/internal/v1/files/${fileId}${suffix}`;

  /** Stores `pdf` through storeOnBehalfOf and marks it READY, as the scan would. */
  async function readyFile(key: string): Promise<string> {
    const res = await request(server())
      .post('/internal/v1/files')
      .set('X-Internal-Secret', SECRET)
      .set('X-Acting-User-Id', user)
      .set('Idempotency-Key', key)
      .set('Content-Type', 'application/pdf')
      .send(pdf);
    expect(res.status).toBe(202);
    const { fileId } = res.body as { fileId: string };
    await pool.query("UPDATE file SET status = 'READY' WHERE file_id = ?", [
      fileId,
    ]);
    return fileId;
  }

  async function row(fileId: string): Promise<RowDataPacket> {
    const [rows] = await pool.query<RowDataPacket[]>(
      'SELECT * FROM file WHERE file_id = ?',
      [fileId],
    );
    return rows[0];
  }

  async function objectExists(fileId: string): Promise<boolean> {
    try {
      await client.send(new HeadObjectCommand({ Bucket: bucket, Key: fileId }));
      return true;
    } catch {
      return false;
    }
  }

  const getBytes = (fileId: string) =>
    request(server())
      .get(path(fileId))
      .set('X-Internal-Secret', SECRET)
      .buffer(true)
      .parse((res, done) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => done(null, Buffer.concat(chunks)));
      });

  beforeAll(async () => {
    process.env.S3_BUCKET = bucket;
    process.env.SERVICE_INTERNAL_SECRET = SECRET;
    const config = validate(process.env);
    pool = createPool({
      host: config.db.host,
      port: config.db.port,
      database: config.db.name,
      user: config.db.username,
      password: config.db.password,
      timezone: 'Z',
    });
    client = createS3Client(config.storage);

    const { AppModule } = await import('../../app.module.js');
    const moduleRef = await withoutBroker(
      Test.createTestingModule({ imports: [AppModule] }),
    ).compile();
    app = moduleRef.createNestApplication({ ...APP_OPTIONS, logger: false });
    await app.listen(0, '127.0.0.1');
    port = (app.getHttpServer() as { address(): AddressInfo }).address().port;
  });

  afterAll(async () => {
    try {
      await pool.query('DELETE FROM file WHERE uploaded_by = ?', [user]);
      if (app) {
        const res = await client.send(
          new ListObjectsV2Command({ Bucket: bucket }),
        );
        for (const o of res.Contents ?? []) {
          await client.send(
            new DeleteObjectCommand({ Bucket: bucket, Key: o.Key! }),
          );
        }
        await client.send(new DeleteBucketCommand({ Bucket: bucket }));
      }
    } finally {
      await app?.close();
      await pool.end();
      client.destroy();
    }
  });

  it('GET is 404 while PROCESSING, then streams the exact bytes once READY', async () => {
    const res = await request(server())
      .post('/internal/v1/files')
      .set('X-Internal-Secret', SECRET)
      .set('X-Acting-User-Id', user)
      .set('Idempotency-Key', 'byid-int-processing')
      .set('Content-Type', 'application/pdf')
      .send(pdf);
    const { fileId } = res.body as { fileId: string };

    expect((await getBytes(fileId)).status).toBe(404);

    await pool.query("UPDATE file SET status = 'READY' WHERE file_id = ?", [
      fileId,
    ]);
    const read = await getBytes(fileId);

    expect(read.status).toBe(200);
    expect(read.headers['content-type']).toBe('application/pdf');
    expect(read.headers['content-length']).toBe(String(pdf.length));
    expect(sha256(read.body as Buffer)).toBe(sha256(pdf));
  });

  it('GET of an object changed in storage behind the row is cut short', async () => {
    const fileId = await readyFile('byid-int-tampered');
    const tampered = Buffer.from(pdf);
    tampered[100] ^= 0xff;
    await client.send(
      new PutObjectCommand({
        Bucket: bucket,
        Key: fileId,
        Body: tampered,
        ContentType: 'application/pdf',
      }),
    );

    const outcome = await new Promise<{ received: number; clean: boolean }>(
      (resolve) => {
        const req = httpGet(
          {
            host: '127.0.0.1',
            port,
            path: path(fileId),
            headers: { 'X-Internal-Secret': SECRET },
          },
          (res) => {
            let received = 0;
            res.on('data', (c: Buffer) => (received += c.length));
            res.on('end', () => resolve({ received, clean: res.complete }));
            res.on('aborted', () => resolve({ received, clean: false }));
            res.on('error', () => resolve({ received, clean: false }));
          },
        );
        req.on('error', () => resolve({ received: -1, clean: false }));
      },
    );

    expect(outcome.clean).toBe(false);
    expect(outcome.received).toBeLessThan(pdf.length);
  });

  it('confirm-attached sets attached = 1 and is idempotent', async () => {
    const fileId = await readyFile('byid-int-confirm');
    const confirm = () =>
      request(server())
        .post(path(fileId, '/confirm-attached'))
        .set('X-Internal-Secret', SECRET)
        .set('Idempotency-Key', `confirm-attached:${fileId}`);

    expect((await confirm()).status).toBe(204);
    expect((await confirm()).status).toBe(204);
    expect((await row(fileId)).attached).toBe(1);
  });

  it('DELETE: 409 under legal hold; once released, tombstone then blob; GET 404 after', async () => {
    const fileId = await readyFile('byid-int-delete');
    const remove = () =>
      request(server())
        .delete(path(fileId))
        .set('X-Internal-Secret', SECRET)
        .set('Idempotency-Key', `delete:${fileId}`);

    await pool.query('UPDATE file SET legal_hold = TRUE WHERE file_id = ?', [
      fileId,
    ]);
    const held = await remove();
    expect(held.status).toBe(409);
    expect((held.body as { code: string }).code).toBe('LEGAL_HOLD_ACTIVE');
    expect((await row(fileId)).deleted_at).toBeNull();
    expect(await objectExists(fileId)).toBe(true);

    await pool.query('UPDATE file SET legal_hold = FALSE WHERE file_id = ?', [
      fileId,
    ]);
    expect((await remove()).status).toBe(204);
    expect((await remove()).status).toBe(204);

    const deleted = await row(fileId);
    expect(deleted.deleted_at).toBeInstanceOf(Date);
    expect(deleted.deletion_reason).toBe(OWNER_DELETION_REASON);
    expect(await objectExists(fileId)).toBe(false);
    expect((await getBytes(fileId)).status).toBe(404);
  });

  it('a tombstoned row whose blob is still there (blob step failed earlier): DELETE again removes it', async () => {
    const fileId = await readyFile('byid-int-heal');
    // Step 1 done, step 2 never ran: what a storage failure during DELETE leaves.
    await pool.query(
      'UPDATE file SET deleted_at = CURRENT_TIMESTAMP, deletion_reason = ? WHERE file_id = ?',
      [OWNER_DELETION_REASON, fileId],
    );
    expect(await objectExists(fileId)).toBe(true);

    const res = await request(server())
      .delete(path(fileId))
      .set('X-Internal-Secret', SECRET)
      .set('Idempotency-Key', `delete:${fileId}`);

    expect(res.status).toBe(204);
    expect(await objectExists(fileId)).toBe(false);
  });
});
