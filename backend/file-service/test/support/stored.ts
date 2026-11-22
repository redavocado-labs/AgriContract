import { createHash, randomUUID } from 'node:crypto';
import { newStoredFile, type StoredFile } from '../../src/domain/stored-file';
import type { FakeObjectStorage, InMemoryFileRepository } from './fakes';

export const sha256 = (bytes: Buffer): string =>
  createHash('sha256').update(bytes).digest('hex');

/**
 * A file already stored (row + object), in the state given by `overrides`:
 * READY by default, as the scan would leave a clean one.
 */
export function givenStoredFile(
  files: InMemoryFileRepository,
  storage: FakeObjectStorage,
  bytes: Buffer,
  overrides: Partial<StoredFile> = {},
): StoredFile {
  const file: StoredFile = {
    ...newStoredFile({
      fileId: randomUUID(),
      storageHash: sha256(bytes),
      uploadedBy: randomUUID(),
      ingestChannel: 'DIRECT_UPLOAD',
      contentType: 'application/pdf',
      fileSize: bytes.length,
      idempotencyKey: null,
    }),
    status: 'READY',
    ...overrides,
  };
  storage.objects.set(file.fileId, {
    bytes,
    contentType: file.contentType,
    contentLength: bytes.length,
  });
  return files.put(file);
}
