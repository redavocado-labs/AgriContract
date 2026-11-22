import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { givenStoredFile } from '../../test/support/stored';
import {
  DeleteFileUseCase,
  OWNER_DELETION_REASON,
} from './delete-file.use-case';
import {
  FileMetadataUnavailableError,
  FileNotFoundError,
  LegalHoldActiveError,
  ObjectStorageUnavailableError,
} from './file-errors';

describe('DeleteFileUseCase', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let deleteFile: DeleteFileUseCase;
  const pdf = Buffer.from('%PDF-1.7\n%%EOF\n');

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    deleteFile = new DeleteFileUseCase(storage, files);
  });

  it('tombstones the row first, then removes the blob; the row stays as the record', async () => {
    const file = givenStoredFile(files, storage, pdf, { attached: true });
    const order: string[] = [];
    files.beforeTombstone = () => order.push('tombstone');
    const remove = storage.delete.bind(storage);
    jest.spyOn(storage, 'delete').mockImplementation((key) => {
      order.push(`delete ${key}`);
      return remove(key);
    });

    await deleteFile.execute(file.fileId);

    expect(order).toEqual(['tombstone', `delete ${file.fileId}`]);
    expect(files.tombstones).toEqual([
      { fileId: file.fileId, reason: OWNER_DELETION_REASON },
    ]);
    expect(files.rows.get(file.fileId)?.deletedAt).toBeInstanceOf(Date);
    expect(storage.objects.has(file.fileId)).toBe(false);
    // Step 2 is marked done, so the lifecycle job never redoes it (#276).
    expect(files.metaOf(file.fileId).blobDeletedAt).toBeInstanceOf(Date);
  });

  it('the blob went but its mark failed: 503; the repeat marks it', async () => {
    const file = givenStoredFile(files, storage, pdf);
    files.markBlobDeletedError = Object.assign(new Error('x'), {
      code: 'ECONNRESET',
    });

    await expect(deleteFile.execute(file.fileId)).rejects.toBeInstanceOf(
      FileMetadataUnavailableError,
    );
    expect(storage.objects.has(file.fileId)).toBe(false);
    expect(files.metaOf(file.fileId).blobDeletedAt).toBeNull();

    files.markBlobDeletedError = null;
    await deleteFile.execute(file.fileId);
    expect(files.metaOf(file.fileId).blobDeletedAt).toBeInstanceOf(Date);
  });

  it('legal hold: 409, nothing tombstoned, blob kept', async () => {
    const file = givenStoredFile(files, storage, pdf, { legalHold: true });

    await expect(deleteFile.execute(file.fileId)).rejects.toBeInstanceOf(
      LegalHoldActiveError,
    );
    expect(files.rows.get(file.fileId)?.deletedAt).toBeNull();
    expect(storage.objects.has(file.fileId)).toBe(true);
    expect(storage.deleted).toEqual([]);
  });

  it('a hold set after the first read still wins (checked by the tombstone itself)', async () => {
    const file = givenStoredFile(files, storage, pdf);
    files.beforeTombstone = (id) => {
      files.rows.get(id)!.legalHold = true;
    };

    await expect(deleteFile.execute(file.fileId)).rejects.toBeInstanceOf(
      LegalHoldActiveError,
    );
    expect(storage.objects.has(file.fileId)).toBe(true);
  });

  it('a concurrent delete that tombstoned first: this one finishes the blob step', async () => {
    const file = givenStoredFile(files, storage, pdf);
    files.beforeTombstone = (id) => {
      files.rows.get(id)!.deletedAt = new Date();
    };

    await deleteFile.execute(file.fileId);

    expect(storage.objects.has(file.fileId)).toBe(false);
  });

  it('blob removal fails: 503 with the row tombstoned; the repeat heals it', async () => {
    const file = givenStoredFile(files, storage, pdf);
    storage.deleteError = Object.assign(new Error('x'), {
      code: 'ECONNREFUSED',
    });

    await expect(deleteFile.execute(file.fileId)).rejects.toBeInstanceOf(
      ObjectStorageUnavailableError,
    );
    expect(files.rows.get(file.fileId)?.deletedAt).toBeInstanceOf(Date);
    expect(storage.objects.has(file.fileId)).toBe(true);

    storage.deleteError = null;
    await deleteFile.execute(file.fileId);

    expect(storage.objects.has(file.fileId)).toBe(false);
    expect(files.tombstones).toHaveLength(1);
  });

  it('a repeat on a deleted file succeeds again (the blob delete of a missing key is a no-op)', async () => {
    const file = givenStoredFile(files, storage, pdf);
    await deleteFile.execute(file.fileId);

    await deleteFile.execute(file.fileId);

    expect(storage.deleted).toEqual([file.fileId, file.fileId]);
    expect(files.tombstones).toHaveLength(1);
  });

  it('404 for an unknown fileId; storage is not touched', async () => {
    await expect(
      deleteFile.execute('8c1d1a52-3f5e-4b8e-9d1a-0b0c0d0e0f10'),
    ).rejects.toBeInstanceOf(FileNotFoundError);
    expect(storage.deleted).toEqual([]);
  });

  it('database down on the tombstone: 503, the blob is not touched', async () => {
    const file = givenStoredFile(files, storage, pdf);
    files.updateError = Object.assign(new Error('x'), { code: 'ECONNRESET' });

    await expect(deleteFile.execute(file.fileId)).rejects.toBeInstanceOf(
      FileMetadataUnavailableError,
    );
    expect(storage.deleted).toEqual([]);
  });
});
