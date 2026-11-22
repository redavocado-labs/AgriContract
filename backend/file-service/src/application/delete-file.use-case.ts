import type { StoredFile } from '../domain/stored-file';
import {
  FileMetadataUnavailableError,
  FileNotFoundError,
  LegalHoldActiveError,
  ObjectStorageUnavailableError,
  failureCode,
} from './file-errors';
import type { FileRepository } from './ports/file.repository';
import type { ObjectStoragePort } from './ports/object-storage.port';

/** deletion_reason of a DeleteFile call; the CHECK of V1 wants one. */
export const OWNER_DELETION_REASON =
  'DELETE_FILE requested by the owning service';

/**
 * DeleteFile (file-service-phase2-design.md §7, supplementary-services-
 * api.yaml deleteFile): the owning service, which knows its retention rules,
 * says the file may go. file-service never decides that itself.
 *
 * Two steps, each idempotent: (1) the row gets its tombstone (deleted_at,
 * deletion_reason), from then on GET and confirm-attached answer 404;
 * (2) the blob is removed. If step 2 fails the call fails (503) and the
 * caller sends it again: step 1 is then already done and step 2 runs again,
 * so a re-run heals any half-done deletion. A tombstoned file stays
 * tombstoned: the row is kept as the record of the deletion.
 *
 * A legal hold blocks step 1 (LegalHoldActiveError, 409). The repository
 * checks the hold in the same statement that writes the tombstone, so a hold
 * set after the first read still wins.
 */
export class DeleteFileUseCase {
  constructor(
    private readonly storage: ObjectStoragePort,
    private readonly files: FileRepository,
  ) {}

  async execute(fileId: string): Promise<void> {
    const file = await this.find(fileId);
    if (file.deletedAt === null) {
      if (file.legalHold) throw new LegalHoldActiveError();
      if (!(await this.tombstone(fileId))) {
        // Changed in between: a hold was set, or another call deleted it.
        const now = await this.find(fileId);
        if (now.deletedAt === null) {
          if (now.legalHold) throw new LegalHoldActiveError();
          throw new FileMetadataUnavailableError('TOMBSTONE_NOT_WRITTEN');
        }
      }
    }
    try {
      await this.storage.delete(fileId);
    } catch (err) {
      throw new ObjectStorageUnavailableError(failureCode(err));
    }
  }

  private async find(fileId: string): Promise<StoredFile> {
    let file: StoredFile | null;
    try {
      file = await this.files.findById(fileId);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
    }
    if (!file) throw new FileNotFoundError();
    return file;
  }

  private async tombstone(fileId: string): Promise<boolean> {
    try {
      return await this.files.tombstone(fileId, OWNER_DELETION_REASON);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
    }
  }
}
