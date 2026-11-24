/** Injection token of the DocumentFiles reader. */
export const DOCUMENT_FILES = Symbol('DOCUMENT_FILES');

/** The bytes of a READY file, with the content type file-service recorded. */
export interface StoredFile {
  content: Buffer;
  contentType: string;
}

/**
 * Reads a generated document from file-service by its trusted `fileId`
 * (`GET /internal/v1/files/{fileId}`, design §3.3). Rejects with
 * FileNotReadyError, FileServiceUnavailableError or FileServiceRejectedError
 * (notification-errors.ts); never with a message that quotes a response.
 */
export interface DocumentFiles {
  readReady(fileId: string): Promise<StoredFile>;
}
