import { createEnvelope, type EventEnvelope } from '@agricontract/eventing';
import {
  FakeObjectStorage,
  FakeScanner,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { givenStoredFile } from '../../test/support/stored';
import type { StoredFile } from '../domain/stored-file';
import { PRODUCER, SCAN_REQUESTED } from './file-events';
import { FAILURE_REASONS, ScanFileUseCase } from './scan-file.use-case';

describe('ScanFileUseCase', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let scanner: FakeScanner;
  let logs: { level: string; message: string }[];
  let scan: ScanFileUseCase;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    Buffer.alloc(70 * 1024, 3),
  ]);

  const jobFor = (file: StoredFile): EventEnvelope =>
    createEnvelope({
      eventType: SCAN_REQUESTED,
      producer: PRODUCER,
      aggregateId: file.fileId,
      payload: { fileId: file.fileId },
    });
  const uploaded = (overrides: Partial<StoredFile> = {}) =>
    givenStoredFile(files, storage, png, {
      status: 'PROCESSING',
      contentType: 'image/png',
      ...overrides,
    });

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    scanner = new FakeScanner();
    logs = [];
    const logger = (level: string) => (message: string) =>
      logs.push({ level, message });
    scan = new ScanFileUseCase(storage, files, scanner, {
      log: logger('log'),
      warn: logger('warn'),
      error: logger('error'),
    });
  });

  it('clean: READY and one file.ready, caused by the job, with the five contract fields', async () => {
    const file = uploaded();
    const job = jobFor(file);

    await expect(scan.execute(file.fileId, job)).resolves.toBe('READY');

    expect(scanner.scanned).toEqual([png]);
    expect(files.rows.get(file.fileId)?.status).toBe('READY');
    expect(files.outbox).toHaveLength(1);
    expect(files.outbox[0]).toMatchObject({
      eventType: 'file.ready',
      producer: 'file-service',
      aggregateId: file.fileId,
      causationId: job.eventId,
      payload: {
        fileId: file.fileId,
        uploadedBy: file.uploadedBy,
        ingestChannel: 'DIRECT_UPLOAD',
        contentType: 'image/png',
        fileSize: png.length,
      },
    });
  });

  it('a signature found: FAILED "virus detected" and one file.failed; the signature only in the log', async () => {
    const file = uploaded();
    scanner.verdict = { clean: false, signature: 'Eicar-Test-Signature' };

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'FAILED',
    );

    expect(files.rows.get(file.fileId)?.status).toBe('FAILED');
    expect(files.failureReasons.get(file.fileId)).toBe('virus detected');
    expect(files.outbox).toHaveLength(1);
    expect(files.outbox[0]).toMatchObject({
      eventType: 'file.failed',
      payload: {
        fileId: file.fileId,
        uploadedBy: file.uploadedBy,
        ingestChannel: 'DIRECT_UPLOAD',
        failureReason: FAILURE_REASONS.virus,
      },
    });
    expect(logs).toContainEqual({
      level: 'warn',
      message: `File ${file.fileId}: virus scan found Eicar-Test-Signature`,
    });
  });

  it('publishes once: a second job for the same file (redelivery, replay) is SKIPPED', async () => {
    const file = uploaded();
    await scan.execute(file.fileId, jobFor(file));

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'SKIPPED',
    );

    expect(files.outbox).toHaveLength(1);
    expect(scanner.scanned).toHaveLength(1);
  });

  it('two scans racing on one file: the second outcome is not recorded, one event', async () => {
    const file = uploaded();
    const first = scan.execute(file.fileId, jobFor(file));
    const second = scan.execute(file.fileId, jobFor(file));

    const results = await Promise.all([first, second]);

    expect(results.sort()).toEqual(['READY', 'SKIPPED']);
    expect(files.outbox).toHaveLength(1);
  });

  it.each([
    ['READY', { status: 'READY' as const }],
    ['FAILED', { status: 'FAILED' as const }],
    ['deleted', { deletedAt: new Date() }],
  ])('a %s file is not scanned again', async (_, state) => {
    const file = uploaded(state);

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'SKIPPED',
    );
    expect(scanner.scanned).toEqual([]);
    expect(files.outbox).toEqual([]);
  });

  it('no row: SKIPPED with a warning', async () => {
    const file = uploaded();
    files.rows.delete(file.fileId);

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'SKIPPED',
    );
    expect(logs.map((l) => l.level)).toEqual(['warn']);
  });

  it('stored bytes that are not the upload: FAILED, never READY, even if the scanner says clean', async () => {
    const file = uploaded();
    const tampered = Buffer.from(png);
    tampered[20] ^= 0xff;
    storage.objects.get(file.fileId)!.bytes = tampered;

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'FAILED',
    );
    expect(files.failureReasons.get(file.fileId)).toBe(
      FAILURE_REASONS.integrity,
    );
    expect(files.outbox[0].eventType).toBe('file.failed');
    expect(logs.some((l) => l.level === 'error')).toBe(true);
  });

  it('no object for a PROCESSING row: FAILED "stored object missing"', async () => {
    const file = uploaded();
    storage.objects.delete(file.fileId);

    await expect(scan.execute(file.fileId, jobFor(file))).resolves.toBe(
      'FAILED',
    );
    expect(files.failureReasons.get(file.fileId)).toBe(FAILURE_REASONS.missing);
  });

  describe('a failure without a verdict throws (retry, then DLQ) and changes nothing', () => {
    it.each([
      [
        'scanner down',
        () => {
          scanner.error = Object.assign(new Error('connect'), {
            code: 'ECONNREFUSED',
          });
        },
      ],
      [
        'storage down',
        () => {
          storage.getError = Object.assign(new Error('x'), {
            code: 'ECONNREFUSED',
          });
        },
      ],
      [
        'storage stream broken part way',
        () => {
          storage.getStreamError = Object.assign(new Error('x'), {
            code: 'ECONNRESET',
          });
        },
      ],
      [
        'database down on the outcome',
        () => {
          files.recordError = Object.assign(new Error('x'), {
            code: 'ECONNRESET',
          });
        },
      ],
    ])('%s', async (_, breakIt) => {
      const file = uploaded();
      breakIt();

      await expect(scan.execute(file.fileId, jobFor(file))).rejects.toThrow();
      expect(files.rows.get(file.fileId)?.status).toBe('PROCESSING');
      expect(files.outbox).toEqual([]);
    });
  });
});
