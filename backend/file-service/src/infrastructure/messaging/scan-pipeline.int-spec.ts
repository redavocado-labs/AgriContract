/**
 * The whole asynchronous path through the whole app (AppModule) on the
 * compose services, RabbitMQ included: upload → outbox → agricontract.files
 * → file-service.virus-scan → clamd → file.ready / file.failed on
 * agricontract.files, as contract-service receives them. Run with
 *   npm run test:int
 * after, in backend/:
 *   docker compose up -d --wait file-db object-storage rabbitmq
 *   docker compose up file-migrate
 *   docker compose --profile scan up -d --wait clamav
 * No other file-service may consume file-service.virus-scan meanwhile (stop
 * the compose one), or it takes the jobs.
 */
import { randomUUID } from 'node:crypto';
import {
  DeleteBucketCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
  S3Client,
} from '@aws-sdk/client-s3';
import type { EventEnvelope } from '@agricontract/eventing';
import type { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { connect, type Channel, type ChannelModel } from 'amqplib';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import type { App } from 'supertest/types';
import { EICAR } from '../../../test/support/eicar';
import { APP_OPTIONS } from '../../app.options';
import { FILES_EXCHANGE } from '../../application/file-events';
import { validate } from '../../config/env.validation';
import { createS3Client } from '../storage/s3-object-storage';

const SECRET = `int-secret-${randomUUID()}`;
const bucket = `file-pipeline-int-${randomUUID().slice(0, 8)}`;
const user = randomUUID();
const WAIT_MS = 60_000;

jest.setTimeout(WAIT_MS + 30_000);

describe('upload → virus scan → file.ready / file.failed on RabbitMQ', () => {
  let app: INestApplication | undefined;
  let pool: Pool;
  let client: S3Client;
  let broker: ChannelModel;
  let channel: Channel;
  const received: EventEnvelope[] = [];
  const fileIds: string[] = [];

  const upload = async (bytes: Buffer, contentType: string) => {
    const res = await request(app!.getHttpServer() as App)
      .post('/internal/v1/files')
      .set('X-Internal-Secret', SECRET)
      .set('X-Acting-User-Id', user)
      .set('Idempotency-Key', randomUUID())
      .set('Content-Type', contentType)
      .send(bytes);
    expect(res.status).toBe(202);
    const { fileId } = res.body as { fileId: string };
    fileIds.push(fileId);
    return fileId;
  };

  const eventsFor = (fileId: string) =>
    received.filter((e) => e.aggregateId === fileId);

  async function until(done: () => boolean): Promise<void> {
    const deadline = Date.now() + WAIT_MS;
    while (!done()) {
      if (Date.now() > deadline) throw new Error('timed out waiting');
      await new Promise((r) => setTimeout(r, 200));
    }
  }

  beforeAll(async () => {
    process.env.S3_BUCKET = bucket;
    process.env.SERVICE_INTERNAL_SECRET = SECRET;
    // Off in tests: no periodic DLQ check behind the test's back.
    process.env.FILE_DLQ_CHECK_INTERVAL_MS = '0';
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

    // A listener of our own on file-service's exchange, as contract-service binds.
    broker = await connect({
      hostname: config.rabbitmq.host,
      port: config.rabbitmq.port,
      username: config.rabbitmq.username,
      password: config.rabbitmq.password,
    });
    channel = await broker.createChannel();
    await channel.assertExchange(FILES_EXCHANGE, 'topic', {
      durable: true,
      autoDelete: false,
    });
    const { queue } = await channel.assertQueue('', {
      exclusive: true,
      autoDelete: true,
    });
    await channel.bindQueue(queue, FILES_EXCHANGE, 'file.ready');
    await channel.bindQueue(queue, FILES_EXCHANGE, 'file.failed');
    await channel.consume(
      queue,
      (msg) => {
        if (msg) {
          received.push(JSON.parse(msg.content.toString()) as EventEnvelope);
        }
      },
      { noAck: true },
    );

    const { AppModule } = await import('../../app.module.js');
    const moduleRef = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication({ ...APP_OPTIONS, logger: false });
    await app.init();
  });

  afterAll(async () => {
    try {
      await app?.close();
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
      await broker?.close();
      await pool.end();
      client.destroy();
    }
  });

  it('a clean file is published once as file.ready, EICAR once as file.failed; then GET and confirm-attached follow', async () => {
    const clean = await upload(
      Buffer.concat([Buffer.from('%PDF-1.7\n'), Buffer.alloc(512 * 1024, 1)]),
      'application/pdf',
    );
    const infected = await upload(EICAR, 'application/pdf');

    await until(
      () => eventsFor(clean).length > 0 && eventsFor(infected).length > 0,
    );
    // Room for a duplicate to show up, if there were one.
    await new Promise((r) => setTimeout(r, 3_000));

    expect(eventsFor(clean)).toHaveLength(1);
    expect(eventsFor(clean)[0]).toMatchObject({
      eventType: 'file.ready',
      producer: 'file-service',
      payload: {
        fileId: clean,
        uploadedBy: user,
        ingestChannel: 'DIRECT_UPLOAD',
      },
    });
    expect(eventsFor(infected)).toHaveLength(1);
    expect(eventsFor(infected)[0]).toMatchObject({
      eventType: 'file.failed',
      payload: { fileId: infected, failureReason: 'virus detected' },
    });

    const [rows] = await pool.query<RowDataPacket[]>(
      'SELECT file_id, status FROM file WHERE uploaded_by = ?',
      [user],
    );
    expect(
      Object.fromEntries(rows.map((r) => [r.file_id as string, r.status])),
    ).toEqual({ [clean]: 'READY', [infected]: 'FAILED' });

    const server = app!.getHttpServer() as App;
    const get = (id: string) =>
      request(server)
        .get(`/internal/v1/files/${id}`)
        .set('X-Internal-Secret', SECRET);
    const confirm = (id: string) =>
      request(server)
        .post(`/internal/v1/files/${id}/confirm-attached`)
        .set('X-Internal-Secret', SECRET)
        .set('Idempotency-Key', `confirm-attached:${id}`);
    expect((await get(clean)).status).toBe(200);
    expect((await get(infected)).status).toBe(404);
    expect((await confirm(clean)).status).toBe(204);
    const refused = await confirm(infected);
    expect(refused.status).toBe(409);
    expect((refused.body as { code: string }).code).toBe('FILE_FAILED');
  });
});
