import type { EventEnvelope } from '@agricontract/eventing';
import type { StoredFile } from '../../domain/stored-file';

export const FILE_REPOSITORY = Symbol('FILE_REPOSITORY');

/**
 * insert() found the (uploadedBy, idempotencyKey) pair already taken: another
 * store with the same key won the race (D60). Nothing was written.
 */
export class IdempotencyKeyTakenError extends Error {
  constructor() {
    super('Idempotency key already used by this uploader');
    this.name = IdempotencyKeyTakenError.name;
  }
}

/** What the virus scan decided (§2.2): PROCESSING → READY or → FAILED. */
export type ScanOutcome =
  { status: 'READY' } | { status: 'FAILED'; failureReason: string };

/**
 * The `file` table (file-service-phase2-design.md §8) and the outbox its
 * events go through: an event is written in the same transaction as the row
 * change it reports, never published directly (@agricontract/eventing).
 */
export interface FileRepository {
  /**
   * Inserts a new row and, in the same transaction, `events` into the outbox
   * (the scan job of an upload). Rejects with IdempotencyKeyTakenError when
   * the row's (uploadedBy, idempotencyKey) pair is already used, and then
   * writes no event either; the fileId is new, so any other duplicate is a
   * bug and rejects as it is.
   */
  insert(file: StoredFile, events?: readonly EventEnvelope[]): Promise<void>;
  /**
   * Moves a PROCESSING, not deleted row to the outcome and writes `event`
   * (file.ready / file.failed) in the same transaction. Returns false and
   * writes nothing when the row is not PROCESSING any more (an earlier scan
   * of the same file already decided) or was deleted: one outcome, one event.
   */
  recordScanOutcome(
    fileId: string,
    outcome: ScanOutcome,
    event: EventEnvelope,
  ): Promise<boolean>;
  findById(fileId: string): Promise<StoredFile | null>;
  findByIdempotencyKey(
    uploadedBy: string,
    idempotencyKey: string,
  ): Promise<StoredFile | null>;
  /**
   * Sets attached on a row that is neither deleted nor FAILED (§7, #131).
   * Returns false when no row was changed: the caller reads it again to learn
   * why (it may also have been attached already).
   */
  markAttached(fileId: string): Promise<boolean>;
  /**
   * Step 1 of a deletion (§7): stamps deleted_at and deletion_reason, only on
   * a row that is neither deleted nor under legal hold, in one statement so a
   * hold set meanwhile still wins. Returns false when no row was changed: the
   * caller reads the row again to learn why.
   *
   * `guard` adds the lifecycle job's own condition to the same statement, so
   * a row that stopped qualifying since it was selected is left alone:
   * `ORPHAN` (still not attached, created before `before`), `EXPIRED`
   * (retention_until still before `before`).
   */
  tombstone(
    fileId: string,
    reason: string,
    guard?: TombstoneGuard,
  ): Promise<boolean>;
  /** Step 2 is done (§7): the blob of a tombstoned row is gone. Idempotent. */
  markBlobDeleted(fileId: string): Promise<void>;
  /**
   * Lifecycle candidates (§7, #276), at most `limit`, oldest first; never a
   * row under legal hold or already deleted:
   * - ORPHAN: not attached and created before `before` (a week ago);
   * - EXPIRED: retention_until before `before` (now).
   */
  findLifecycleCandidates(
    kind: TombstoneGuard['kind'],
    before: Date,
    limit: number,
  ): Promise<string[]>;
  /** Tombstoned rows whose blob is not known to be gone (step 2 pending), at most `limit`. */
  findBlobPending(limit: number): Promise<string[]>;
  /** Which of `fileIds` have a row, deleted or not. */
  existingIds(fileIds: readonly string[]): Promise<Set<string>>;
}

export interface TombstoneGuard {
  kind: 'ORPHAN' | 'EXPIRED';
  before: Date;
}
