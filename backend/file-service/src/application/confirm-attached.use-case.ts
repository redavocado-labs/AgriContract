import type { StoredFile } from '../domain/stored-file';
import {
  FileFailedError,
  FileMetadataUnavailableError,
  FileNotFoundError,
  failureCode,
} from './file-errors';
import type { FileRepository } from './ports/file.repository';

/**
 * ConfirmAttached (file-service-phase2-design.md §7, supplementary-services-
 * api.yaml confirmAttached): the owning service saved the fileId in its own
 * entity, so the file is no orphan and §7's one-week cleanup leaves it alone.
 *
 * Idempotent by nature: it only ever sets `attached`, so a repeat (inspection
 * and contract-service retry until it succeeds) changes nothing and succeeds
 * again. No key needs to be kept for that. A file still PROCESSING can be
 * confirmed: the owner keeps the fileId from the upload on and waits for
 * file.ready on its own side. Unknown or deleted: FileNotFoundError.
 *
 * A FAILED file cannot be attached (#131): FileFailedError, 409. The
 * repository checks it again in the UPDATE, so a scan that fails the file
 * between the read and the write still wins.
 */
export class ConfirmAttachedUseCase {
  constructor(private readonly files: FileRepository) {}

  async execute(fileId: string): Promise<void> {
    const file = await this.find(fileId);
    if (!file || file.deletedAt !== null) throw new FileNotFoundError();
    if (file.status === 'FAILED') throw new FileFailedError();
    if (file.attached) return;
    let attached: boolean;
    try {
      attached = await this.files.markAttached(fileId);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
    }
    if (!attached) {
      // Changed in between: failed by the scan, deleted, or attached by a
      // concurrent confirm (MySQL may count that row as unchanged).
      const now = await this.find(fileId);
      if (now && now.deletedAt === null) {
        if (now.status === 'FAILED') throw new FileFailedError();
        if (now.attached) return;
      }
      throw new FileNotFoundError();
    }
  }

  private async find(fileId: string): Promise<StoredFile | null> {
    try {
      return await this.files.findById(fileId);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
    }
  }
}
