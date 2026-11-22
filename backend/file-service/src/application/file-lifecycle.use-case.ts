import type { DeleteFileUseCase } from './delete-file.use-case';
import { failureCode } from './file-errors';
import type { FileRepository, TombstoneGuard } from './ports/file.repository';
import type { ObjectStoragePort } from './ports/object-storage.port';

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

/** §7: a file nobody confirmed within one week is an orphan. */
export const ORPHAN_AFTER_MS = 7 * DAY_MS;
/**
 * A blob with no row is only swept once it is this old: a store in progress
 * writes its blob a moment before its row (StoreFileUseCase).
 */
export const STRAY_BLOB_GRACE_MS = DAY_MS;

/** deletion_reason of the job's tombstones (V1's CHECK wants one). */
export const LIFECYCLE_REASONS = {
  ORPHAN:
    'ORPHAN: not attached within 7 days (file-service-phase2-design.md §7)',
  EXPIRED: 'RETENTION_EXPIRED: retention_until has passed',
} as const satisfies Record<TombstoneGuard['kind'], string>;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export interface LifecycleLogger {
  log(message: string): void;
  warn(message: string): void;
}

export interface LifecycleOptions {
  /** Rows per kind per run; the rest waits for the next run. */
  batchSize: number;
  orphanAfterMs: number;
  strayBlobGraceMs: number;
  now: () => Date;
}

export interface LifecycleReport {
  /** Orphans tombstoned this run. */
  orphans: number;
  /** Files past retention_until tombstoned this run. */
  expired: number;
  /** Blobs removed for tombstoned rows (this run's and earlier failed ones). */
  blobsRemoved: number;
  /** Blobs with no row at all, removed by the sweep. */
  strayBlobs: number;
  /** Blob removals that failed; their rows stay pending for the next run. */
  failures: number;
}

/**
 * The lifecycle job of file-service-phase2-design.md §7 (#276), one run:
 *
 * 1. **Orphans**: not attached a week after their upload (§7's
 *    ConfirmAttached guard) → tombstone, then blob.
 * 2. **Expired**: `retention_until` has passed → tombstone, then blob.
 *    file-service does not decide retention; it only applies the date the
 *    owning side set (governance §8).
 * 3. **Pending blobs**: every tombstoned row whose blob is not known to be
 *    gone (`blob_deleted_at` NULL), including those from a DELETE or an
 *    earlier run whose blob step failed → blob removed and marked. A failed
 *    step 2 therefore heals on the next run (matrix 21e).
 * 4. **Stray blobs**: objects whose key is a fileId with no row at all,
 *    older than a day (a store that failed after its PUT, and whose own
 *    clean-up failed too) → removed. With 3, no blob outlives its file.
 *
 * Never under legal hold (matrix 21d): candidates exclude held rows, and the
 * tombstone checks the hold again in its own statement, together with the
 * candidate's condition (still unattached / still expired), so a hold set or
 * a confirm-attached arriving meanwhile wins. Every step is idempotent, so
 * several instances, or a run cut short, are safe.
 */
export class FileLifecycleUseCase {
  constructor(
    private readonly files: FileRepository,
    private readonly storage: ObjectStoragePort,
    private readonly deleteFile: DeleteFileUseCase,
    private readonly logger: LifecycleLogger,
    private readonly options: LifecycleOptions,
  ) {}

  async runOnce(): Promise<LifecycleReport> {
    const now = this.options.now();
    const report: LifecycleReport = {
      orphans: 0,
      expired: 0,
      blobsRemoved: 0,
      strayBlobs: 0,
      failures: 0,
    };
    report.orphans = await this.tombstoneAll({
      kind: 'ORPHAN',
      before: new Date(now.getTime() - this.options.orphanAfterMs),
    });
    report.expired = await this.tombstoneAll({ kind: 'EXPIRED', before: now });
    await this.removePendingBlobs(report);
    report.strayBlobs = await this.sweepStrayBlobs(now);

    if (Object.values(report).some((n) => n > 0)) {
      this.logger.log(
        `Lifecycle run: ${report.orphans} orphan(s) and ${report.expired} expired file(s) tombstoned, ` +
          `${report.blobsRemoved} blob(s) removed, ${report.strayBlobs} stray blob(s) swept, ` +
          `${report.failures} blob removal(s) left for the next run`,
      );
    }
    return report;
  }

  private async tombstoneAll(guard: TombstoneGuard): Promise<number> {
    const ids = await this.files.findLifecycleCandidates(
      guard.kind,
      guard.before,
      this.options.batchSize,
    );
    let tombstoned = 0;
    for (const fileId of ids) {
      // False: it stopped qualifying (attached, held, retention extended, or
      // deleted by someone else) since it was selected.
      if (
        await this.files.tombstone(fileId, LIFECYCLE_REASONS[guard.kind], guard)
      ) {
        tombstoned += 1;
      }
    }
    return tombstoned;
  }

  private async removePendingBlobs(report: LifecycleReport): Promise<void> {
    const ids = await this.files.findBlobPending(this.options.batchSize);
    for (const fileId of ids) {
      try {
        await this.deleteFile.removeBlob(fileId);
        report.blobsRemoved += 1;
      } catch (err) {
        report.failures += 1;
        this.logger.warn(
          `File ${fileId}: blob removal failed (${failureCode(err)}); the next run tries again`,
        );
      }
    }
  }

  private async sweepStrayBlobs(now: Date): Promise<number> {
    const cutoff = now.getTime() - this.options.strayBlobGraceMs;
    let swept = 0;
    let batch: string[] = [];
    const flush = async () => {
      const known = await this.files.existingIds(batch);
      for (const key of batch) {
        if (known.has(key)) continue;
        await this.storage.delete(key);
        swept += 1;
        this.logger.warn(`Object ${key} had no row; removed as a stray blob`);
      }
      batch = [];
    };
    for await (const object of this.storage.list()) {
      // Only keys file-service makes (a fileId) and old enough to be settled.
      if (!UUID.test(object.key) || object.lastModified.getTime() >= cutoff) {
        continue;
      }
      batch.push(object.key);
      if (batch.length >= this.options.batchSize) await flush();
    }
    if (batch.length > 0) await flush();
    return swept;
  }
}
