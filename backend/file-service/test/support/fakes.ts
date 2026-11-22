import { Readable } from 'node:stream';
import {
  IdempotencyKeyTakenError,
  type FileRepository,
} from '../../src/application/ports/file.repository';
import {
  ObjectNotFoundError,
  type ObjectStoragePort,
} from '../../src/application/ports/object-storage.port';
import type { StoredFile } from '../../src/domain/stored-file';

/** In-memory bucket. Reads the body like S3 would and keeps what it received. */
export class FakeObjectStorage implements ObjectStoragePort {
  readonly objects = new Map<
    string,
    { bytes: Buffer; contentType: string; contentLength: number }
  >();
  readonly deleted: string[] = [];
  /** Fails `put` with this error after the body is read (or before, see failBeforeRead). */
  putError: Error | null = null;
  failBeforeRead = false;
  /** With putError: fail once this many bytes are read, leaving the rest unread. */
  failAfterBytes: number | null = null;
  deleteError: Error | null = null;
  /** Called with each chunk as it arrives, to observe streaming. */
  onChunk: (chunk: Buffer) => void = () => undefined;

  async put(
    key: string,
    body: Readable,
    meta: { contentType: string; contentLength: number },
  ): Promise<void> {
    if (this.putError && this.failBeforeRead) {
      body.destroy();
      throw this.putError;
    }
    const chunks: Buffer[] = [];
    let read = 0;
    for await (const chunk of body as AsyncIterable<Buffer>) {
      this.onChunk(chunk);
      chunks.push(chunk);
      read += chunk.length;
      if (this.putError && this.failAfterBytes !== null) {
        // Leaving the loop destroys the stream it was reading, as a broken
        // connection to storage would.
        if (read >= this.failAfterBytes) throw this.putError;
      }
    }
    if (this.putError) throw this.putError;
    this.objects.set(key, { bytes: Buffer.concat(chunks), ...meta });
  }

  /** Fails `get` with this error before any byte. */
  getError: Error | null = null;
  /** Fails the stream `get` returns with this error after the first chunk. */
  getStreamError: Error | null = null;

  get(key: string): Promise<Readable> {
    if (this.getError) return Promise.reject(this.getError);
    const object = this.objects.get(key);
    if (!object) return Promise.reject(new ObjectNotFoundError());
    const streamError = this.getStreamError;
    const half = Math.ceil(object.bytes.length / 2);
    const chunks = [
      object.bytes.subarray(0, half),
      object.bytes.subarray(half),
    ];
    let index = 0;
    return Promise.resolve(
      new Readable({
        read() {
          if (index === 1 && streamError) {
            this.destroy(streamError);
            return;
          }
          this.push(index < chunks.length ? chunks[index++] : null);
        },
      }),
    );
  }

  delete(key: string): Promise<void> {
    this.deleted.push(key);
    if (this.deleteError) return Promise.reject(this.deleteError);
    this.objects.delete(key);
    return Promise.resolve();
  }
}

/** The `file` table, with its unique (uploadedBy, idempotencyKey) index. */
export class InMemoryFileRepository implements FileRepository {
  readonly rows = new Map<string, StoredFile>();
  insertError: Error | null = null;
  findError: Error | null = null;
  /** Runs just before insert checks the index, to let a concurrent store win. */
  beforeInsert: (file: StoredFile) => void = () => undefined;

  insert(file: StoredFile): Promise<void> {
    this.beforeInsert(file);
    if (this.insertError) return Promise.reject(this.insertError);
    if (this.rows.has(file.fileId)) {
      return Promise.reject(new Error('duplicate fileId'));
    }
    if (
      file.idempotencyKey !== null &&
      this.byKey(file.uploadedBy, file.idempotencyKey)
    ) {
      return Promise.reject(new IdempotencyKeyTakenError());
    }
    this.rows.set(file.fileId, { ...file });
    return Promise.resolve();
  }

  findById(fileId: string): Promise<StoredFile | null> {
    if (this.findError) return Promise.reject(this.findError);
    return Promise.resolve(this.rows.get(fileId) ?? null);
  }

  findByIdempotencyKey(
    uploadedBy: string,
    idempotencyKey: string,
  ): Promise<StoredFile | null> {
    if (this.findError) return Promise.reject(this.findError);
    const row = this.byKey(uploadedBy, idempotencyKey);
    return Promise.resolve(row ? { ...row } : null);
  }

  updateError: Error | null = null;
  /** Runs just before tombstone checks the row, to let a hold or a delete win. */
  beforeTombstone: (fileId: string) => void = () => undefined;
  readonly tombstones: { fileId: string; reason: string }[] = [];

  markAttached(fileId: string): Promise<void> {
    if (this.updateError) return Promise.reject(this.updateError);
    const row = this.rows.get(fileId);
    if (row && row.deletedAt === null) row.attached = true;
    return Promise.resolve();
  }

  tombstone(fileId: string, reason: string): Promise<boolean> {
    this.beforeTombstone(fileId);
    if (this.updateError) return Promise.reject(this.updateError);
    const row = this.rows.get(fileId);
    if (!row || row.deletedAt !== null || row.legalHold) {
      return Promise.resolve(false);
    }
    row.deletedAt = new Date();
    this.tombstones.push({ fileId, reason });
    return Promise.resolve(true);
  }

  /** A stored row as the scan would leave it, for the read and delete paths. */
  put(file: StoredFile): StoredFile {
    this.rows.set(file.fileId, { ...file });
    return this.rows.get(file.fileId)!;
  }

  private byKey(uploadedBy: string, key: string): StoredFile | undefined {
    return [...this.rows.values()].find(
      (r) => r.uploadedBy === uploadedBy && r.idempotencyKey === key,
    );
  }
}

/** `size` bytes in chunks of `chunkSize`, produced only when read; logs each chunk it makes. */
export function lazyBody(
  size: number,
  chunkSize = 64 * 1024,
  produced: (index: number) => void = () => undefined,
): Readable {
  let sent = 0;
  let index = 0;
  return new Readable({
    read() {
      if (sent >= size) {
        this.push(null);
        return;
      }
      const length = Math.min(chunkSize, size - sent);
      const chunk = Buffer.alloc(length, index % 251);
      sent += length;
      produced(index++);
      this.push(chunk);
    },
  });
}
