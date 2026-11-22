import { randomUUID } from 'node:crypto';
import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { givenStoredFile } from '../../test/support/stored';
import type { StoredFile } from '../domain/stored-file';
import { DeleteFileUseCase } from './delete-file.use-case';
import {
  FileLifecycleUseCase,
  LIFECYCLE_REASONS,
  ORPHAN_AFTER_MS,
  STRAY_BLOB_GRACE_MS,
} from './file-lifecycle.use-case';

const DAY = 24 * 60 * 60 * 1000;
const NOW = new Date('2026-11-22T10:00:00Z');
const ago = (ms: number) => new Date(NOW.getTime() - ms);

describe('FileLifecycleUseCase (one run of the §7 lifecycle job)', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let logs: string[];
  let lifecycle: FileLifecycleUseCase;
  const bytes = Buffer.from('%PDF-1.7\n%%EOF\n');

  const file = (
    overrides: Partial<StoredFile> = {},
    meta: { createdAt?: Date; retentionUntil?: Date | null } = {},
  ) => {
    const stored = givenStoredFile(files, storage, bytes, overrides);
    Object.assign(files.metaOf(stored.fileId), {
      createdAt: meta.createdAt ?? ago(DAY),
      retentionUntil: meta.retentionUntil ?? null,
    });
    return stored;
  };
  const alive = (f: StoredFile) =>
    files.rows.get(f.fileId)?.deletedAt === null &&
    storage.objects.has(f.fileId);
  const gone = (f: StoredFile) =>
    files.rows.get(f.fileId)?.deletedAt instanceof Date &&
    files.metaOf(f.fileId).blobDeletedAt instanceof Date &&
    !storage.objects.has(f.fileId);

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    logs = [];
    const logger = {
      log: (m: string) => logs.push(m),
      warn: (m: string) => logs.push(m),
    };
    lifecycle = new FileLifecycleUseCase(
      files,
      storage,
      new DeleteFileUseCase(storage, files),
      logger,
      {
        batchSize: 100,
        orphanAfterMs: ORPHAN_AFTER_MS,
        strayBlobGraceMs: STRAY_BLOB_GRACE_MS,
        now: () => NOW,
      },
    );
  });

  describe('orphans: not attached a week after the upload', () => {
    it('are tombstoned with the ORPHAN reason, then their blob goes', async () => {
      const orphan = file({}, { createdAt: ago(8 * DAY) });

      const report = await lifecycle.runOnce();

      expect(report).toMatchObject({
        orphans: 1,
        blobsRemoved: 1,
        failures: 0,
      });
      expect(gone(orphan)).toBe(true);
      expect(files.tombstones).toEqual([
        { fileId: orphan.fileId, reason: LIFECYCLE_REASONS.ORPHAN },
      ]);
    });

    it.each([
      ['younger than a week', {}, { createdAt: ago(6 * DAY) }],
      ['attached', { attached: true }, { createdAt: ago(30 * DAY) }],
      ['under legal hold', { legalHold: true }, { createdAt: ago(30 * DAY) }],
    ])('a file %s stays', async (_, overrides, meta) => {
      const kept = file(overrides, meta);

      await lifecycle.runOnce();

      expect(alive(kept)).toBe(true);
    });

    it('a confirm-attached landing between the select and the tombstone wins', async () => {
      const orphan = file({}, { createdAt: ago(8 * DAY) });
      files.beforeTombstone = (id) => {
        files.rows.get(id)!.attached = true;
      };

      const report = await lifecycle.runOnce();

      expect(report.orphans).toBe(0);
      expect(alive(orphan)).toBe(true);
    });
  });

  describe('retention: retention_until has passed', () => {
    it('an expired, attached file is tombstoned with the EXPIRED reason, then its blob goes', async () => {
      const expired = file({ attached: true }, { retentionUntil: ago(1) });

      const report = await lifecycle.runOnce();

      expect(report).toMatchObject({ expired: 1, blobsRemoved: 1 });
      expect(gone(expired)).toBe(true);
      expect(files.tombstones[0].reason).toBe(LIFECYCLE_REASONS.EXPIRED);
    });

    it('matrix 21d: legal hold + expired retention: the file stays whole', async () => {
      const held = file(
        { attached: true, legalHold: true },
        { retentionUntil: ago(365 * DAY) },
      );

      const report = await lifecycle.runOnce();

      expect(report.expired).toBe(0);
      expect(alive(held)).toBe(true);
      expect(storage.deleted).toEqual([]);
    });

    it('a hold set between the select and the tombstone still wins', async () => {
      const expired = file({ attached: true }, { retentionUntil: ago(1) });
      files.beforeTombstone = (id) => {
        files.rows.get(id)!.legalHold = true;
      };

      await lifecycle.runOnce();

      expect(alive(expired)).toBe(true);
    });

    it.each([
      ['in the future', new Date(NOW.getTime() + DAY)],
      ['not set (keep indefinitely)', null],
    ])('retention %s: the file stays', async (_, retentionUntil) => {
      const kept = file({ attached: true }, { retentionUntil });

      await lifecycle.runOnce();

      expect(alive(kept)).toBe(true);
    });
  });

  describe('matrix 21e: the blob step heals on the next run', () => {
    it('storage fails after the tombstone: the row waits, the next run removes the blob; no garbage', async () => {
      const orphan = file({}, { createdAt: ago(8 * DAY) });
      storage.deleteError = Object.assign(new Error('x'), {
        code: 'ECONNREFUSED',
      });

      const first = await lifecycle.runOnce();

      expect(first).toMatchObject({ orphans: 1, blobsRemoved: 0, failures: 1 });
      expect(files.rows.get(orphan.fileId)?.deletedAt).toBeInstanceOf(Date);
      expect(storage.objects.has(orphan.fileId)).toBe(true);
      expect(logs.some((l) => l.includes('the next run tries again'))).toBe(
        true,
      );

      storage.deleteError = null;
      const second = await lifecycle.runOnce();

      expect(second).toMatchObject({
        orphans: 0,
        blobsRemoved: 1,
        failures: 0,
      });
      expect(gone(orphan)).toBe(true);
      expect(files.tombstones).toHaveLength(1);
    });

    it('a DELETE whose blob step failed earlier is finished by the job', async () => {
      const deleted = file({ deletedAt: new Date() });

      await lifecycle.runOnce();

      expect(gone(deleted)).toBe(true);
    });

    it('the blob went but its mark was not written: the next run deletes again (a no-op) and marks', async () => {
      const orphan = file({}, { createdAt: ago(8 * DAY) });
      files.markBlobDeletedError = Object.assign(new Error('x'), {
        code: 'ECONNRESET',
      });

      expect((await lifecycle.runOnce()).failures).toBe(1);
      expect(storage.objects.has(orphan.fileId)).toBe(false);

      files.markBlobDeletedError = null;
      await lifecycle.runOnce();

      expect(gone(orphan)).toBe(true);
    });

    it('a finished deletion is never touched again', async () => {
      file({}, { createdAt: ago(8 * DAY) });
      await lifecycle.runOnce();
      const deletes = storage.deleted.length;

      const again = await lifecycle.runOnce();

      expect(again).toEqual({
        orphans: 0,
        expired: 0,
        blobsRemoved: 0,
        strayBlobs: 0,
        failures: 0,
      });
      expect(storage.deleted).toHaveLength(deletes);
    });
  });

  describe('stray blobs: an object with no row at all', () => {
    const stray = (lastModified: Date, key: string = randomUUID()) => {
      storage.objects.set(key, {
        bytes,
        contentType: 'application/pdf',
        contentLength: bytes.length,
      });
      storage.modified.set(key, lastModified);
      return key;
    };

    it('older than a day: removed, and logged by key', async () => {
      const key = stray(ago(2 * DAY));

      const report = await lifecycle.runOnce();

      expect(report.strayBlobs).toBe(1);
      expect(storage.objects.has(key)).toBe(false);
      expect(logs).toContain(
        `Object ${key} had no row; removed as a stray blob`,
      );
    });

    it('younger than a day (a store still writing its row): kept', async () => {
      const key = stray(ago(60_000));

      await lifecycle.runOnce();

      expect(storage.objects.has(key)).toBe(true);
    });

    it("a key that is not a fileId is not file-service's: kept", async () => {
      const key = stray(ago(30 * DAY), 'backups/readme.txt');

      await lifecycle.runOnce();

      expect(storage.objects.has(key)).toBe(true);
    });

    it('an object with a row is never a stray, even an old one', async () => {
      const kept = file({ attached: true }, { createdAt: ago(400 * DAY) });
      storage.modified.set(kept.fileId, ago(400 * DAY));

      const report = await lifecycle.runOnce();

      expect(report.strayBlobs).toBe(0);
      expect(alive(kept)).toBe(true);
    });
  });

  it('takes at most batchSize rows per kind per run; the rest waits', async () => {
    lifecycle = new FileLifecycleUseCase(
      files,
      storage,
      new DeleteFileUseCase(storage, files),
      { log() {}, warn() {} },
      {
        batchSize: 2,
        orphanAfterMs: ORPHAN_AFTER_MS,
        strayBlobGraceMs: STRAY_BLOB_GRACE_MS,
        now: () => NOW,
      },
    );
    for (let i = 0; i < 3; i += 1) file({}, { createdAt: ago(8 * DAY) });

    expect((await lifecycle.runOnce()).orphans).toBe(2);
    expect((await lifecycle.runOnce()).orphans).toBe(1);
  });
});
