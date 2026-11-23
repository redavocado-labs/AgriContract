import { createHash } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import type { AddressInfo } from 'node:net';
import { Logger, type INestApplication } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import type { App } from 'supertest/types';
import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../../test/support/fakes';
import { APP_OPTIONS } from '../../app.options';
import { StoreFileUseCase } from '../../application/store-file.use-case';
import { validate } from '../../config/env.validation';
import type { ErrorEnvelope } from './error-envelope.filter';
import { InternalSecretGuard } from './internal-secret.guard';
import { SystemGeneratedController } from './system-generated.controller';

const PATH = '/internal/v1/files/system-generated';
const SECRET = 'test-internal-secret';
const KEY = 'final-contract-pdf-1';
const MiB = 1024 * 1024;
const sha256 = (bytes: Buffer) =>
  createHash('sha256').update(bytes).digest('hex');

describe('POST /internal/v1/files/system-generated (storeSystemGenerated)', () => {
  let app: INestApplication;
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let logged: string[];
  let port: number;
  const pdf = Buffer.from('%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n');

  beforeEach(async () => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    logged = [];
    for (const level of ['log', 'warn', 'error', 'debug'] as const) {
      jest
        .spyOn(Logger.prototype, level)
        .mockImplementation((...args: unknown[]) => {
          logged.push(args.map(String).join(' '));
        });
    }
    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({
          isGlobal: true,
          ignoreEnvFile: true,
          validate: () => validate({ SERVICE_INTERNAL_SECRET: SECRET }),
        }),
      ],
      controllers: [SystemGeneratedController],
      providers: [
        InternalSecretGuard,
        {
          provide: StoreFileUseCase,
          useFactory: () =>
            new StoreFileUseCase(storage, files, new Logger('test')),
        },
      ],
    }).compile();
    app = moduleRef.createNestApplication(APP_OPTIONS);
    await app.listen(0, '127.0.0.1');
    port = (app.getHttpServer() as { address(): AddressInfo }).address().port;
  });

  afterEach(async () => {
    await app.close();
    jest.restoreAllMocks();
  });

  const post = (
    body: Buffer = pdf,
    {
      serviceId = 'contract-service',
      contentType = 'application/pdf',
    }: { serviceId?: string; contentType?: string } = {},
  ) =>
    request(app.getHttpServer() as App)
      .post(PATH)
      .set('X-Internal-Secret', SECRET)
      .set('X-Service-Id', serviceId)
      .set('Idempotency-Key', KEY)
      .set('Content-Type', contentType)
      .send(body);

  const expectEnvelope = (
    status: number,
    code: string,
    got: { status: number; body: unknown },
  ) => {
    expect(got.status).toBe(status);
    const envelope = got.body as ErrorEnvelope;
    expect(envelope).toMatchObject({ status, code, path: PATH });
    return envelope;
  };

  const nothingKept = () => {
    expect(storage.objects.size).toBe(0);
    expect(files.rows.size).toBe(0);
  };

  describe('201', () => {
    it('stores the PDF READY under the service id and answers StoredSystemFile', async () => {
      const res = await post();

      expect(res.status).toBe(201);
      const body = res.body as Record<string, unknown>;
      expect(Object.keys(body).sort()).toEqual(
        ['contentType', 'fileId', 'fileSize', 'status', 'storageHash'].sort(),
      );
      const fileId = body.fileId as string;
      expect(body).toEqual({
        fileId,
        status: 'READY',
        storageHash: sha256(pdf),
        fileSize: pdf.length,
        contentType: 'application/pdf',
      });
      expect(storage.objects.get(fileId)?.bytes).toEqual(pdf);
      expect(files.rows.get(fileId)).toEqual({
        fileId,
        storageHash: sha256(pdf),
        uploadedBy: 'contract-service',
        ingestChannel: 'SYSTEM_GENERATED',
        contentType: 'application/pdf',
        fileSize: pdf.length,
        status: 'READY',
        attached: false,
        legalHold: false,
        deletedAt: null,
        idempotencyKey: KEY,
      });
    });

    it('takes audit-service too, and a content type with parameters', async () => {
      const res = await post(pdf, {
        serviceId: 'audit-service',
        contentType: 'Application/PDF; charset=binary',
      });

      expect(res.status).toBe(201);
      const { fileId } = res.body as { fileId: string };
      expect(files.rows.get(fileId)?.uploadedBy).toBe('audit-service');
    });

    it('takes a file of exactly 10 MiB', async () => {
      const res = await post(Buffer.alloc(10 * MiB, 7));

      expect(res.status).toBe(201);
      expect(storage.objects.size).toBe(1);
    });

    it('a repeat of (X-Service-Id, Idempotency-Key) answers the earlier file, storing nothing', async () => {
      const first = await post();

      const repeat = await post(Buffer.from('%PDF other bytes'));

      expect(repeat.status).toBe(201);
      expect(repeat.body).toEqual(first.body);
      expect(storage.objects.size).toBe(1);
      expect(files.rows.size).toBe(1);
    });

    it('the same key from another service is another file', async () => {
      const first = await post();
      const other = await post(pdf, { serviceId: 'audit-service' });

      expect(other.status).toBe(201);
      expect((other.body as { fileId: string }).fileId).not.toBe(
        (first.body as { fileId: string }).fileId,
      );
      expect(files.rows.size).toBe(2);
    });
  });

  it('401 without the internal secret, nothing kept', async () => {
    const res = await request(app.getHttpServer() as App)
      .post(PATH)
      .set('X-Service-Id', 'contract-service')
      .set('Idempotency-Key', KEY)
      .set('Content-Type', 'application/pdf')
      .send(pdf);

    expectEnvelope(401, 'UNAUTHORIZED', res);
    nothingKept();
  });

  describe('400 VALIDATION_ERROR, nothing kept', () => {
    it.each([
      ['X-Service-Id missing', 'X-Service-Id', null],
      ['an X-Service-Id not on the list', 'X-Service-Id', 'product-service'],
      ['X-Service-Id in another case', 'X-Service-Id', 'Contract-Service'],
      [
        'a user id as X-Service-Id',
        'X-Service-Id',
        '0b6f3c1e-7d2a-4c5b-9e8f-1a2b3c4d5e6f',
      ],
      ['Idempotency-Key missing', 'Idempotency-Key', null],
      ['Idempotency-Key of 256 characters', 'Idempotency-Key', 'k'.repeat(256)],
    ])('%s', async (_case, header, value) => {
      const headers: Record<string, string> = {
        'X-Service-Id': 'contract-service',
        'Idempotency-Key': KEY,
      };
      if (value === null) delete headers[header];
      else headers[header] = value;
      const req = request(app.getHttpServer() as App)
        .post(PATH)
        .set('X-Internal-Secret', SECRET)
        .set('Content-Type', 'application/pdf');
      for (const [name, v] of Object.entries(headers)) req.set(name, v);

      const envelope = expectEnvelope(
        400,
        'VALIDATION_ERROR',
        await req.send(pdf),
      );

      expect(envelope.message).toContain(header);
      if (value !== null) expect(envelope.message).not.toContain(value);
      nothingKept();
    });

    it.each(['image/png', 'image/jpeg', 'text/plain'])(
      'Content-Type %s: application/pdf only',
      async (contentType) => {
        expectEnvelope(
          400,
          'VALIDATION_ERROR',
          await post(pdf, { contentType }),
        );
        nothingKept();
      },
    );

    it('an empty body', async () => {
      expectEnvelope(400, 'VALIDATION_ERROR', await post(Buffer.alloc(0)));
      nothingKept();
    });
  });

  it('413 FILE_TOO_LARGE over 10 MiB: answered while the caller is still sending, nothing kept', async () => {
    // Declares 10 MiB + 1 and sends only the first 64 KiB, never ending.
    const res = await new Promise<{ status: number; body: unknown }>(
      (resolve, reject) => {
        const req = httpRequest({
          host: '127.0.0.1',
          port,
          method: 'POST',
          path: PATH,
          headers: {
            'X-Internal-Secret': SECRET,
            'X-Service-Id': 'contract-service',
            'Idempotency-Key': KEY,
            'Content-Type': 'application/pdf',
            'Content-Length': 10 * MiB + 1,
          },
        });
        req.on('response', (r) => {
          const parts: Buffer[] = [];
          r.on('data', (c: Buffer) => parts.push(c));
          r.on('end', () =>
            resolve({
              status: r.statusCode ?? 0,
              body: JSON.parse(Buffer.concat(parts).toString('utf8')),
            }),
          );
          r.on('error', reject);
        });
        // The socket may be reset after the answer: the body never ended.
        req.on('error', () => undefined);
        req.write(Buffer.alloc(64 * 1024));
      },
    );

    expectEnvelope(413, 'FILE_TOO_LARGE', res);
    nothingKept();
  });

  describe('503, nothing kept', () => {
    it('object storage down: FILE_STORAGE_UNAVAILABLE', async () => {
      storage.putError = Object.assign(new Error('connect ECONNREFUSED'), {
        code: 'ECONNREFUSED',
      });
      storage.failBeforeRead = true;

      expectEnvelope(503, 'FILE_STORAGE_UNAVAILABLE', await post());
      nothingKept();
    });

    it('the file database down: FILE_METADATA_UNAVAILABLE, the blob is removed', async () => {
      files.insertError = Object.assign(new Error('Too many connections'), {
        code: 'ER_CON_COUNT_ERROR',
      });

      expectEnvelope(503, 'FILE_METADATA_UNAVAILABLE', await post());
      expect(storage.objects.size).toBe(0);
    });
  });

  it('never logs the idempotency key', async () => {
    files.insertError = Object.assign(new Error('boom'), { code: 'ER_X' });

    await post();

    expect(logged.join('\n')).not.toContain(KEY);
  });
});
