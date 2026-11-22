import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { finished } from 'node:stream/promises';
import { newStoredFile } from '../domain/stored-file';
import { StoredBytesMismatchError } from './file-errors';
import { VerifiedRead } from './verified-read';

const sha256 = (b: Buffer) => createHash('sha256').update(b).digest('hex');
const chunks = (bytes: Buffer, size: number) =>
  Array.from({ length: Math.ceil(bytes.length / size) }, (_, i) =>
    bytes.subarray(i * size, (i + 1) * size),
  );

/** What the stream emitted before it ended or failed. */
async function drain(
  source: Buffer[],
  recorded: Buffer,
): Promise<{ out: Buffer; error: unknown }> {
  const file = newStoredFile({
    fileId: '4c7f0a1e-9b2d-4e3f-8a5b-6c7d8e9f0a1b',
    storageHash: sha256(recorded),
    uploadedBy: 'u',
    ingestChannel: 'DIRECT_UPLOAD',
    contentType: 'application/pdf',
    fileSize: recorded.length,
    idempotencyKey: null,
  });
  const verified = new VerifiedRead(file);
  // Every chunk the stream emits, as a piped HTTP response would write it.
  const out: Buffer[] = [];
  verified.on('data', (c: Buffer) => out.push(c));
  Readable.from(source).pipe(verified);
  try {
    await finished(verified);
    return { out: Buffer.concat(out), error: null };
  } catch (error) {
    return { out: Buffer.concat(out), error };
  }
}

describe('VerifiedRead', () => {
  const bytes = Buffer.from(Array.from({ length: 10_000 }, (_, i) => i % 251));

  it('intact bytes go through whole, in any chunking', async () => {
    for (const size of [1, 7, 4096, 10_000]) {
      const { out, error } = await drain(chunks(bytes, size), bytes);
      expect(error).toBeNull();
      expect(out).toEqual(bytes);
    }
  });

  /*
   * The bug CI caught on #560: with every chunk passed on at once, a same-length
   * tampered object reached the reader whole before the hash failed, so an
   * HTTP caller could see a complete response. The last chunk must never go out.
   */
  it.each([1, 7, 4096, 10_000])(
    'same length, other bytes (chunks of %i): fewer bytes than recorded ever come out, then the error',
    async (size) => {
      const tampered = Buffer.from(bytes);
      tampered[tampered.length - 1] ^= 0xff;

      const { out, error } = await drain(chunks(tampered, size), bytes);

      expect(error).toBeInstanceOf(StoredBytesMismatchError);
      expect(out.length).toBeLessThan(bytes.length);
    },
  );

  it('a shorter object fails without passing its last chunk', async () => {
    const { out, error } = await drain(
      chunks(bytes.subarray(0, 5_000), 1_000),
      bytes,
    );

    expect(error).toBeInstanceOf(StoredBytesMismatchError);
    expect(out.length).toBe(4_000); // the 5th, last chunk is never emitted
  });

  it('one byte more than recorded fails at once', async () => {
    const { out, error } = await drain(
      chunks(Buffer.concat([bytes, Buffer.from([1])]), 1_000),
      bytes,
    );

    expect(error).toBeInstanceOf(StoredBytesMismatchError);
    expect(out.length).toBeLessThan(bytes.length);
  });
});
