import type { Readable } from 'node:stream';
import {
  FakeObjectStorage,
  InMemoryFileRepository,
} from '../../test/support/fakes';
import { givenStoredFile } from '../../test/support/stored';
import {
  FileMetadataUnavailableError,
  FileNotFoundError,
  ObjectStorageUnavailableError,
  StoredBytesMismatchError,
} from './file-errors';
import { ReadFileUseCase } from './read-file.use-case';

async function readAll(body: Readable): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of body as AsyncIterable<Buffer>) chunks.push(chunk);
  return Buffer.concat(chunks);
}

describe('ReadFileUseCase', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let errors: string[];
  let readFile: ReadFileUseCase;
  const pdf = Buffer.from('%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n');

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    errors = [];
    readFile = new ReadFileUseCase(storage, files, {
      error: (m) => errors.push(m),
    });
  });

  it('streams the exact stored bytes of a READY file, with its row', async () => {
    const stored = givenStoredFile(files, storage, pdf);

    const { file, body } = await readFile.execute(stored.fileId);

    expect(file).toEqual(stored);
    expect(await readAll(body)).toEqual(pdf);
    expect(errors).toEqual([]);
  });

  it.each([
    ['still PROCESSING', { status: 'PROCESSING' as const }],
    ['FAILED', { status: 'FAILED' as const }],
    ['deleted', { deletedAt: new Date() }],
  ])('404 for a file %s: not READY looks like not found', async (_, state) => {
    const stored = givenStoredFile(files, storage, pdf, state);

    await expect(readFile.execute(stored.fileId)).rejects.toBeInstanceOf(
      FileNotFoundError,
    );
  });

  it('404 for an unknown fileId', async () => {
    await expect(
      readFile.execute('8c1d1a52-3f5e-4b8e-9d1a-0b0c0d0e0f10'),
    ).rejects.toBeInstanceOf(FileNotFoundError);
  });

  it('a READY row without its object is a storage failure, logged, not a 404', async () => {
    const stored = givenStoredFile(files, storage, pdf);
    storage.objects.delete(stored.fileId);

    const err: unknown = await readFile
      .execute(stored.fileId)
      .catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ObjectStorageUnavailableError);
    expect((err as ObjectStorageUnavailableError).code).toBe(
      'OBJECT_NOT_FOUND',
    );
    expect(errors).toEqual([
      `File ${stored.fileId} is READY but object ${stored.fileId} is missing from storage`,
    ]);
  });

  it('storage down: ObjectStorageUnavailableError with the code only', async () => {
    const stored = givenStoredFile(files, storage, pdf);
    storage.getError = Object.assign(
      new Error('connect ECONNREFUSED 10.0.0.5'),
      {
        code: 'ECONNREFUSED',
      },
    );

    const err: unknown = await readFile
      .execute(stored.fileId)
      .catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ObjectStorageUnavailableError);
    expect((err as Error).message).toBe(
      'Object storage unavailable (ECONNREFUSED)',
    );
  });

  it('database down: FileMetadataUnavailableError', async () => {
    files.findError = Object.assign(new Error('x'), { code: 'ECONNRESET' });

    await expect(
      readFile.execute('8c1d1a52-3f5e-4b8e-9d1a-0b0c0d0e0f10'),
    ).rejects.toBeInstanceOf(FileMetadataUnavailableError);
  });

  describe('the bytes are checked against the row as they pass', () => {
    it('other bytes of the same length: the stream fails at the end, logged once', async () => {
      const stored = givenStoredFile(files, storage, pdf);
      const tampered = Buffer.from(pdf);
      tampered[3] ^= 0xff;
      storage.objects.get(stored.fileId)!.bytes = tampered;

      const { body } = await readFile.execute(stored.fileId);

      await expect(readAll(body)).rejects.toBeInstanceOf(
        StoredBytesMismatchError,
      );
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain(`File ${stored.fileId}:`);
      expect(errors[0]).toContain('the read was cut');
    });

    it('a shorter object fails the stream instead of ending it', async () => {
      const stored = givenStoredFile(files, storage, pdf);
      storage.objects.get(stored.fileId)!.bytes = pdf.subarray(0, 10);

      const { body } = await readFile.execute(stored.fileId);

      await expect(readAll(body)).rejects.toBeInstanceOf(
        StoredBytesMismatchError,
      );
    });

    it('a longer object fails as soon as it passes the recorded size', async () => {
      const stored = givenStoredFile(files, storage, pdf);
      storage.objects.get(stored.fileId)!.bytes = Buffer.concat([pdf, pdf]);

      const { body } = await readFile.execute(stored.fileId);

      await expect(readAll(body)).rejects.toBeInstanceOf(
        StoredBytesMismatchError,
      );
    });

    it('a storage stream that breaks part way fails the read with its error', async () => {
      const stored = givenStoredFile(files, storage, pdf);
      storage.getStreamError = Object.assign(new Error('socket hang up'), {
        code: 'ECONNRESET',
      });

      const { body } = await readFile.execute(stored.fileId);

      await expect(readAll(body)).rejects.toMatchObject({ code: 'ECONNRESET' });
      expect(errors).toEqual([]);
    });
  });
});
