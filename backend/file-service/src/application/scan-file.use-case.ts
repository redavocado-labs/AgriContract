import type { EventEnvelope } from '@agricontract/eventing';
import { pipeline, type Readable } from 'node:stream';
import type { StoredFile } from '../domain/stored-file';
import { fileFailed, fileReady } from './file-events';
import { StoredBytesMismatchError } from './file-errors';
import type { FileRepository, ScanOutcome } from './ports/file.repository';
import {
  ObjectNotFoundError,
  type ObjectStoragePort,
} from './ports/object-storage.port';
import type { VirusScannerPort } from './ports/virus-scanner.port';
import { VerifiedRead } from './verified-read';

/** failureReason values of file.failed: short, fixed, safe to show an end-user. */
export const FAILURE_REASONS = {
  virus: 'virus detected',
  integrity: 'stored bytes do not match the upload',
  missing: 'stored object missing',
} as const;

export interface ScanLogger {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

export type ScanResult = 'READY' | 'FAILED' | 'SKIPPED';

/**
 * VirusScanConsumer's work (file-service-phase2-design.md §4): the one place
 * that decides PROCESSING → READY or → FAILED, and so the one place that
 * publishes file.ready / file.failed (§5), through the outbox in the same
 * transaction as the status change.
 *
 * The stored object is read back from storage and checked on the way
 * (VerifiedRead, same check as GET) while the scanner reads it, so READY
 * means "these exact bytes are clean". Outcomes:
 *
 * - clean, bytes intact → READY + file.ready;
 * - a signature found → FAILED "virus detected" + file.failed;
 * - bytes that are not the ones uploaded, or no object at all → FAILED as
 *   well: they will never become the upload, so waiting cannot help;
 * - anything else (scanner, storage or database down) throws: the consumer
 *   retries, then dead-letters to file-service.virus-scan.dlq and the file
 *   stays PROCESSING until the job is replayed.
 *
 * Idempotent on the file's status: a job for a file that is not PROCESSING
 * any more (a redelivery, a replay) or deleted is SKIPPED and publishes
 * nothing. recordScanOutcome checks PROCESSING again in its UPDATE, so two
 * scans of one file still produce one outcome and one event.
 */
export class ScanFileUseCase {
  constructor(
    private readonly storage: ObjectStoragePort,
    private readonly files: FileRepository,
    private readonly scanner: VirusScannerPort,
    private readonly logger: ScanLogger,
  ) {}

  async execute(fileId: string, job: EventEnvelope): Promise<ScanResult> {
    const file = await this.files.findById(fileId);
    if (!file) {
      this.logger.warn(`Scan job ${job.eventId}: file ${fileId} has no row`);
      return 'SKIPPED';
    }
    if (file.status !== 'PROCESSING' || file.deletedAt !== null) {
      return 'SKIPPED';
    }

    let stored: Readable;
    try {
      stored = await this.storage.get(fileId);
    } catch (err) {
      if (!(err instanceof ObjectNotFoundError)) throw err;
      this.logger.error(
        `File ${fileId} is PROCESSING but object ${fileId} is missing from storage`,
      );
      return this.decide(file, job, failed(FAILURE_REASONS.missing));
    }

    const body = new VerifiedRead(file);
    pipeline(stored, body, () => undefined);
    let outcome: ScanOutcome;
    try {
      const verdict = await this.scanner.scan(body);
      if (verdict.clean) {
        outcome = { status: 'READY' };
      } else {
        this.logger.warn(
          `File ${fileId}: virus scan found ${verdict.signature}`,
        );
        outcome = failed(FAILURE_REASONS.virus);
      }
    } catch (err) {
      if (!(err instanceof StoredBytesMismatchError)) throw err;
      this.logger.error(
        `File ${fileId}: ${err.message} (tampering or corruption in object storage)`,
      );
      outcome = failed(FAILURE_REASONS.integrity);
    } finally {
      body.destroy();
    }
    return this.decide(file, job, outcome);
  }

  private async decide(
    file: StoredFile,
    job: EventEnvelope,
    outcome: ScanOutcome,
  ): Promise<ScanResult> {
    const event =
      outcome.status === 'READY'
        ? fileReady(job, file)
        : fileFailed(job, file, outcome.failureReason);
    const changed = await this.files.recordScanOutcome(
      file.fileId,
      outcome,
      event,
    );
    if (!changed) return 'SKIPPED';
    this.logger.log(`File ${file.fileId} is ${outcome.status}`);
    return outcome.status;
  }
}

function failed(failureReason: string): ScanOutcome {
  return { status: 'FAILED', failureReason };
}
