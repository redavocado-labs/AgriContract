import type { Readable } from 'node:stream';

export const OBJECT_STORAGE = Symbol('OBJECT_STORAGE');

/** get() found no object under the key. */
export class ObjectNotFoundError extends Error {
  readonly code = 'OBJECT_NOT_FOUND';

  constructor() {
    super('No object under this key');
    this.name = ObjectNotFoundError.name;
  }
}

/**
 * The S3-compatible bucket (file-service-phase2-design.md §2.3: one flat
 * bucket, object key = fileId). Compose runs SeaweedFS; the design says MinIO.
 */
export interface ObjectStoragePort {
  /**
   * Streams `body` to `key`. `contentLength` is the exact number of bytes the
   * body yields: the object is written in one PUT as the bytes arrive, never
   * buffered whole. If the body errors or ends early, nothing is stored and
   * the promise rejects with the body's error when it had one.
   */
  put(
    key: string,
    body: Readable,
    meta: { contentType: string; contentLength: number },
  ): Promise<void>;

  /**
   * Opens `key` for reading: resolves once storage answered, with the bytes
   * still to come as a stream (never buffered whole). Rejects with
   * ObjectNotFoundError when there is no such object.
   */
  get(key: string): Promise<Readable>;

  /** Removes `key`; succeeds when it does not exist. */
  delete(key: string): Promise<void>;
}
