import type { IncomingHttpHeaders } from 'node:http';
import { FileNotFoundError } from '../../application/file-errors';
import {
  IDEMPOTENCY_KEY_PROBLEM,
  InvalidRequestHeadersError,
  UUID,
  idempotencyKeyOf,
} from './store-on-behalf-of.request';

/**
 * The {fileId} of /internal/v1/files/{fileId}[/…], lower case like every
 * stored fileId. Anything that is not a UUID names no file: 404, as the
 * contract's path parameter is a plain string and 404 is its only "no such
 * file" answer.
 */
export function parseFileId(raw: string): string {
  if (!UUID.test(raw)) throw new FileNotFoundError();
  return raw.toLowerCase();
}

/**
 * The Idempotency-Key that confirmAttached and deleteFile require (same rule
 * as storeOnBehalfOf). Both operations are idempotent by nature, so the key
 * is checked but not kept.
 */
export function requireIdempotencyKey(headers: IncomingHttpHeaders): string {
  const key = idempotencyKeyOf(headers);
  if (key === null)
    throw new InvalidRequestHeadersError([IDEMPOTENCY_KEY_PROBLEM]);
  return key;
}
