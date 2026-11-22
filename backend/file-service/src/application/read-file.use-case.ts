import { pipeline, type Readable } from 'node:stream';
import { isReadable, type StoredFile } from '../domain/stored-file';
import {
  FileMetadataUnavailableError,
  FileNotFoundError,
  ObjectStorageUnavailableError,
  StoredBytesMismatchError,
  failureCode,
} from './file-errors';
import type { FileRepository } from './ports/file.repository';
import {
  ObjectNotFoundError,
  type ObjectStoragePort,
} from './ports/object-storage.port';
import { VerifiedRead } from './verified-read';

export interface ReadLogger {
  error(message: string): void;
}

export interface FileContent {
  file: StoredFile;
  /**
   * The bytes, checked as they pass (VerifiedRead): the stream fails at the
   * end instead of ending when they are not the stored ones. A reader that
   * gives up early must destroy it, which also closes the storage connection.
   */
  body: Readable;
}

/**
 * GetFile (file-service-phase2-design.md §6, supplementary-services-api.yaml
 * getFile): the bytes of a READY file for a trusted service, which proxies
 * them to its end-user after its own business check. Anything else (unknown,
 * still PROCESSING, FAILED, deleted) is FileNotFoundError: inspection reads
 * that 404 as "not ready yet" (D62).
 *
 * A READY row whose object is missing is not a 404: the file exists and must
 * not look absent, so it is a storage failure, logged as an error.
 */
export class ReadFileUseCase {
  constructor(
    private readonly storage: ObjectStoragePort,
    private readonly files: FileRepository,
    private readonly logger: ReadLogger,
  ) {}

  async execute(fileId: string): Promise<FileContent> {
    let file: StoredFile | null;
    try {
      file = await this.files.findById(fileId);
    } catch (err) {
      throw new FileMetadataUnavailableError(failureCode(err));
    }
    if (!file || !isReadable(file)) throw new FileNotFoundError();

    let stored: Readable;
    try {
      stored = await this.storage.get(fileId);
    } catch (err) {
      if (err instanceof ObjectNotFoundError) {
        this.logger.error(
          `File ${fileId} is READY but object ${fileId} is missing from storage`,
        );
      }
      throw new ObjectStorageUnavailableError(failureCode(err));
    }

    const body = new VerifiedRead(file);
    body.once('error', (err) => {
      if (err instanceof StoredBytesMismatchError) {
        this.logger.error(
          `File ${fileId}: ${err.message} (tampering or corruption in object storage); the read was cut`,
        );
      }
    });
    // pipeline: an error on either side destroys both, so a reader that goes
    // away also closes the storage connection.
    pipeline(stored, body, () => undefined);
    return { file, body };
  }
}
