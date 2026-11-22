import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { givenStoredFile } from '../../test/support/stored';
import { ConfirmAttachedUseCase } from './confirm-attached.use-case';
import { FileMetadataUnavailableError, FileNotFoundError } from './file-errors';

describe('ConfirmAttachedUseCase', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let confirm: ConfirmAttachedUseCase;
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]);

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    confirm = new ConfirmAttachedUseCase(files);
  });

  it.each(['PROCESSING', 'READY'] as const)(
    'sets attached on a %s file',
    async (status) => {
      const file = givenStoredFile(files, storage, png, { status });

      await confirm.execute(file.fileId);

      expect(files.rows.get(file.fileId)?.attached).toBe(true);
    },
  );

  it('is idempotent: a repeat succeeds and changes nothing', async () => {
    const file = givenStoredFile(files, storage, png);
    await confirm.execute(file.fileId);
    const markAttached = jest.spyOn(files, 'markAttached');

    await confirm.execute(file.fileId);
    await confirm.execute(file.fileId);

    expect(files.rows.get(file.fileId)?.attached).toBe(true);
    expect(markAttached).not.toHaveBeenCalled();
  });

  it('404 for an unknown fileId', async () => {
    await expect(
      confirm.execute('8c1d1a52-3f5e-4b8e-9d1a-0b0c0d0e0f10'),
    ).rejects.toBeInstanceOf(FileNotFoundError);
  });

  it('404 for a deleted file, which stays unattached', async () => {
    const file = givenStoredFile(files, storage, png, {
      deletedAt: new Date(),
    });

    await expect(confirm.execute(file.fileId)).rejects.toBeInstanceOf(
      FileNotFoundError,
    );
    expect(files.rows.get(file.fileId)?.attached).toBe(false);
  });

  it('database down on the update: FileMetadataUnavailableError, safe to retry', async () => {
    const file = givenStoredFile(files, storage, png);
    files.updateError = Object.assign(new Error('x'), { code: 'ECONNRESET' });

    await expect(confirm.execute(file.fileId)).rejects.toBeInstanceOf(
      FileMetadataUnavailableError,
    );

    files.updateError = null;
    await confirm.execute(file.fileId);
    expect(files.rows.get(file.fileId)?.attached).toBe(true);
  });
});
