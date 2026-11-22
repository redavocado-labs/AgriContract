import { get as httpGet } from 'node:http';
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
import { givenStoredFile } from '../../../test/support/stored';
import { APP_OPTIONS } from '../../app.options';
import { ConfirmAttachedUseCase } from '../../application/confirm-attached.use-case';
import { DeleteFileUseCase } from '../../application/delete-file.use-case';
import { ReadFileUseCase } from '../../application/read-file.use-case';
import { validate } from '../../config/env.validation';
import type { ErrorEnvelope } from './error-envelope.filter';
import { FileByIdController } from './file-by-id.controller';
import { InternalSecretGuard } from './internal-secret.guard';

const SECRET = 'test-internal-secret';
const UNKNOWN = '8c1d1a52-3f5e-4b8e-9d1a-0b0c0d0e0f10';

describe('/internal/v1/files/{fileId} (getFile, confirmAttached, deleteFile)', () => {
  let app: INestApplication;
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let logged: string[];
  let port: number;
  const pdf = Buffer.concat([
    Buffer.from('%PDF-1.7\n'),
    Buffer.alloc(200 * 1024, 7),
    Buffer.from('%%EOF\n'),
  ]);

  const server = () => app.getHttpServer() as App;
  const path = (fileId: string, suffix = '') =>
    `/internal/v1/files/${fileId}${suffix}`;

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
      controllers: [FileByIdController],
      providers: [
        InternalSecretGuard,
        {
          provide: ReadFileUseCase,
          useFactory: () =>
            new ReadFileUseCase(storage, files, new Logger('test')),
        },
        {
          provide: ConfirmAttachedUseCase,
          useFactory: () => new ConfirmAttachedUseCase(files),
        },
        {
          provide: DeleteFileUseCase,
          useFactory: () => new DeleteFileUseCase(storage, files),
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

  describe('every route needs X-Internal-Secret', () => {
    it.each([
      ['GET', (id: string) => request(server()).get(path(id))],
      [
        'POST confirm-attached',
        (id: string) =>
          request(server())
            .post(path(id, '/confirm-attached'))
            .set('Idempotency-Key', 'k'),
      ],
      [
        'DELETE',
        (id: string) =>
          request(server()).delete(path(id)).set('Idempotency-Key', 'k'),
      ],
    ])('%s: 401 UNAUTHORIZED, nothing done', async (_, send) => {
      const file = givenStoredFile(files, storage, pdf);

      const res = await send(file.fileId).set('X-Internal-Secret', 'wrong');

      expect(res.status).toBe(401);
      expect((res.body as ErrorEnvelope).code).toBe('UNAUTHORIZED');
      expect(files.rows.get(file.fileId)).toMatchObject({
        attached: false,
        deletedAt: null,
      });
      expect(storage.objects.has(file.fileId)).toBe(true);
    });
  });

  describe('GET', () => {
    const get = (fileId: string) =>
      request(server())
        .get(path(fileId))
        .set('X-Internal-Secret', SECRET)
        .buffer(true)
        .parse((res, done) => {
          const chunks: Buffer[] = [];
          res.on('data', (c: Buffer) => chunks.push(c));
          res.on('end', () => done(null, Buffer.concat(chunks)));
        });

    it('200: the exact bytes with the stored Content-Type and Content-Length', async () => {
      const file = givenStoredFile(files, storage, pdf);

      const res = await get(file.fileId);

      expect(res.status).toBe(200);
      expect(res.headers['content-type']).toBe('application/pdf');
      expect(res.headers['content-length']).toBe(String(pdf.length));
      expect(res.headers['cache-control']).toBe('no-store');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
      expect(res.body).toEqual(pdf);
    });

    it('accepts the fileId in upper case', async () => {
      const file = givenStoredFile(files, storage, pdf);

      const res = await get(file.fileId.toUpperCase());

      expect(res.status).toBe(200);
    });

    it.each([
      ['PROCESSING', { status: 'PROCESSING' as const }],
      ['FAILED', { status: 'FAILED' as const }],
      ['deleted', { deletedAt: new Date() }],
    ])('404 NOT_FOUND for a %s file', async (_, state) => {
      const file = givenStoredFile(files, storage, pdf, state);

      const res = await get(file.fileId);

      expect(res.status).toBe(404);
      expect(JSON.parse((res.body as Buffer).toString())).toMatchObject({
        status: 404,
        code: 'NOT_FOUND',
        message: 'File not found',
        path: path(file.fileId),
      });
    });

    it.each([UNKNOWN, 'not-a-uuid', `${UNKNOWN}x`])(
      '404 NOT_FOUND for %s',
      async (fileId) => {
        const res = await get(fileId);

        expect(res.status).toBe(404);
      },
    );

    it('503 FILE_STORAGE_UNAVAILABLE when storage does not answer', async () => {
      const file = givenStoredFile(files, storage, pdf);
      storage.getError = Object.assign(new Error('x'), {
        code: 'ECONNREFUSED',
      });

      const res = await get(file.fileId);

      expect(res.status).toBe(503);
      const body = JSON.parse((res.body as Buffer).toString()) as ErrorEnvelope;
      expect(body.code).toBe('FILE_STORAGE_UNAVAILABLE');
    });

    it('stored bytes that do not match the row: the connection is cut short of Content-Length', async () => {
      const file = givenStoredFile(files, storage, pdf);
      const tampered = Buffer.from(pdf);
      tampered[tampered.length - 1] ^= 0xff;
      storage.objects.get(file.fileId)!.bytes = tampered;

      const outcome = await new Promise<{
        status?: number;
        received: number;
        clean: boolean;
      }>((resolve) => {
        const req = httpGet(
          {
            host: '127.0.0.1',
            port,
            path: path(file.fileId),
            headers: { 'X-Internal-Secret': SECRET },
          },
          (res) => {
            let received = 0;
            res.on('data', (c: Buffer) => (received += c.length));
            res.on('end', () =>
              resolve({
                status: res.statusCode,
                received,
                clean: res.complete,
              }),
            );
            res.on('aborted', () =>
              resolve({ status: res.statusCode, received, clean: false }),
            );
            res.on('error', () =>
              resolve({ status: res.statusCode, received, clean: false }),
            );
          },
        );
        req.on('error', () => resolve({ received: 0, clean: false }));
      });

      expect(outcome.clean).toBe(false);
      expect(outcome.received).toBeLessThan(pdf.length);
      expect(
        logged.some((l) =>
          l.includes(
            `File ${file.fileId}: Stored object ${file.fileId} does not match`,
          ),
        ),
      ).toBe(true);
    });
  });

  describe('POST confirm-attached', () => {
    const confirm = (fileId: string, key: string | null = 'confirm-1') => {
      const req = request(server())
        .post(path(fileId, '/confirm-attached'))
        .set('X-Internal-Secret', SECRET);
      return key === null ? req : req.set('Idempotency-Key', key);
    };

    it('204 and attached; a repeat (any key) is 204 again', async () => {
      const file = givenStoredFile(files, storage, pdf, {
        status: 'PROCESSING',
      });

      const first = await confirm(file.fileId);
      const again = await confirm(file.fileId, 'another-key');

      expect(first.status).toBe(204);
      expect(first.text).toBe('');
      expect(again.status).toBe(204);
      expect(files.rows.get(file.fileId)?.attached).toBe(true);
    });

    it('400 VALIDATION_ERROR without an Idempotency-Key; nothing changed', async () => {
      const file = givenStoredFile(files, storage, pdf);

      const res = await confirm(file.fileId, null);

      expect(res.status).toBe(400);
      expect(res.body as ErrorEnvelope).toMatchObject({
        code: 'VALIDATION_ERROR',
        message: 'Idempotency-Key must be 1 to 255 printable ASCII characters',
      });
      expect(files.rows.get(file.fileId)?.attached).toBe(false);
    });

    it('409 FILE_FAILED for a file the virus scan rejected', async () => {
      const file = givenStoredFile(files, storage, pdf, { status: 'FAILED' });

      const res = await confirm(file.fileId);

      expect(res.status).toBe(409);
      expect(res.body as ErrorEnvelope).toMatchObject({
        status: 409,
        code: 'FILE_FAILED',
      });
      expect(files.rows.get(file.fileId)?.attached).toBe(false);
    });

    it('404 NOT_FOUND for an unknown or deleted file', async () => {
      const deleted = givenStoredFile(files, storage, pdf, {
        deletedAt: new Date(),
      });

      expect((await confirm(UNKNOWN)).status).toBe(404);
      expect((await confirm(deleted.fileId)).status).toBe(404);
    });
  });

  describe('DELETE', () => {
    const remove = (fileId: string, key: string | null = 'delete-1') => {
      const req = request(server())
        .delete(path(fileId))
        .set('X-Internal-Secret', SECRET);
      return key === null ? req : req.set('Idempotency-Key', key);
    };

    it('204: tombstoned and the blob removed; a repeat is 204 again; GET is 404 after', async () => {
      const file = givenStoredFile(files, storage, pdf, { attached: true });

      const first = await remove(file.fileId);
      const again = await remove(file.fileId, 'delete-2');

      expect(first.status).toBe(204);
      expect(again.status).toBe(204);
      expect(files.rows.get(file.fileId)?.deletedAt).toBeInstanceOf(Date);
      expect(storage.objects.has(file.fileId)).toBe(false);
      const read = await request(server())
        .get(path(file.fileId))
        .set('X-Internal-Secret', SECRET);
      expect(read.status).toBe(404);
    });

    it('409 LEGAL_HOLD_ACTIVE: the file stays', async () => {
      const file = givenStoredFile(files, storage, pdf, { legalHold: true });

      const res = await remove(file.fileId);

      expect(res.status).toBe(409);
      expect(res.body as ErrorEnvelope).toMatchObject({
        status: 409,
        error: 'Conflict',
        code: 'LEGAL_HOLD_ACTIVE',
      });
      expect(storage.objects.has(file.fileId)).toBe(true);
    });

    it('503 when the blob was not removed; the repeat finishes the deletion', async () => {
      const file = givenStoredFile(files, storage, pdf);
      storage.deleteError = Object.assign(new Error('x'), {
        code: 'ECONNREFUSED',
      });

      const failed = await remove(file.fileId);
      storage.deleteError = null;
      const retried = await remove(file.fileId);

      expect(failed.status).toBe(503);
      expect((failed.body as ErrorEnvelope).code).toBe(
        'FILE_STORAGE_UNAVAILABLE',
      );
      expect(retried.status).toBe(204);
      expect(storage.objects.has(file.fileId)).toBe(false);
    });

    it('400 without an Idempotency-Key, 404 for an unknown file', async () => {
      const file = givenStoredFile(files, storage, pdf);

      expect((await remove(file.fileId, null)).status).toBe(400);
      expect(storage.objects.has(file.fileId)).toBe(true);
      expect((await remove(UNKNOWN)).status).toBe(404);
    });
  });
});
