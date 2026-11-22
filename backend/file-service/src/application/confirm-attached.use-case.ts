import type { StoredFile } from '../domain/stored-file';
import {
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
 */
export class ConfirmAttachedUseCase {
  constructor(private readonly files: FileRepository) {}

  async execute(fileId: string): Promise<void> {
    const file = await this.find(fileId);
    if (!file || file.deletedAt !== null) throw new FileNotFoundError();
    if (file.attached) return;
    try {
      await this.files.markAttached(fileId);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
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
