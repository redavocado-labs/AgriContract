import { createHash, type Hash } from 'node:crypto';
import { Transform, type TransformCallback } from 'node:stream';
import type { StoredFile } from '../domain/stored-file';
import { StoredBytesMismatchError } from './file-errors';

/**
 * Passes the stored bytes through as they come and checks them against the
 * row (file-service-phase2-design.md §2: storage_hash detects tampering in
 * object storage). Every chunk goes on at once, so the check can only fail
 * at the end: one byte too many fails at once; otherwise, once the stream
 * ends, a short object or another SHA-256 fails the stream with
 * StoredBytesMismatchError instead of ending it. A reader that was told the
 * length (Content-Length) then sees a cut connection, never a clean end.
 */
export class VerifiedRead extends Transform {
  private readonly hash: Hash = createHash('sha256');
  private bytes = 0;

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
    callback(null, chunk);
  }

  override _flush(callback: TransformCallback): void {
    const intact =
      this.bytes === this.file.fileSize &&
      this.hash.digest('hex') === this.file.storageHash;
    callback(intact ? null : new StoredBytesMismatchError(this.file.fileId));
  }
}
