import { createHash, type Hash } from 'node:crypto';
import { Transform, type TransformCallback } from 'node:stream';
import type { StoredFile } from '../domain/stored-file';
import { StoredBytesMismatchError } from './file-errors';

/**
 * Passes the stored bytes on and checks them against the row
 * (file-service-phase2-design.md §2: storage_hash detects tampering in object
 * storage). Only the end of the stream can tell whether the SHA-256 matches,
 * so the latest chunk is **held back** until the next one arrives or the
 * stream ends with exactly the recorded size and hash; only then does it go
 * on. A mismatch fails the stream with StoredBytesMismatchError and the held
 * chunk never goes out: a reader told the length (Content-Length) always
 * gets fewer bytes than that and an error, never a complete-looking wrong
 * file. One byte more than the recorded size fails at once.
 *
 * At most one chunk (one socket read of storage, ~64 KiB) is held, as in
 * ByteMeter on the way in.
 */
export class VerifiedRead extends Transform {
  private readonly hash: Hash = createHash('sha256');
  private bytes = 0;
  private held: Buffer | null = null;

  constructor(private readonly file: StoredFile) {
    super();
  }

  override _transform(
    chunk: Buffer,
    _encoding: BufferEncoding,
    callback: TransformCallback,
  ): void {
    this.bytes += chunk.length;
    if (this.bytes > this.file.fileSize) {
      callback(new StoredBytesMismatchError(this.file.fileId));
      return;
    }
    this.hash.update(chunk);
    const previous = this.held;
    this.held = chunk;
    callback(null, previous ?? undefined);
  }

  override _flush(callback: TransformCallback): void {
    const intact =
      this.bytes === this.file.fileSize &&
      this.hash.digest('hex') === this.file.storageHash;
    if (!intact) {
      callback(new StoredBytesMismatchError(this.file.fileId));
      return;
    }
    callback(null, this.held ?? undefined);
  }
}
