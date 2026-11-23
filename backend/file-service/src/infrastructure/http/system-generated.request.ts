import type { IncomingHttpHeaders } from 'node:http';
import {
  IDEMPOTENCY_KEY_PROBLEM,
  InvalidRequestHeadersError,
  declaredLength,
  idempotencyKeyOf,
} from './store-on-behalf-of.request';

/**
 * The services that may store a file they generated (X-Service-Id of
 * supplementary-services-api.yaml storeSystemGenerated, D88). A fixed list,
 * so a caller cannot make up an uploader; the shared secret does not prove
 * which one is calling (design §9).
 */
export const SYSTEM_SERVICE_IDS = [
  'contract-service',
  'audit-service',
] as const;
export type SystemServiceId = (typeof SYSTEM_SERVICE_IDS)[number];

/**
 * The headers of POST /internal/v1/files/system-generated. As for
 * storeOnBehalfOf the body is the file, so everything else comes in headers;
 * X-Internal-Secret is the guard's.
 */
export interface StoreSystemGeneratedRequest {
  /** X-Service-Id, exactly as listed: stored as uploaded_by. */
  serviceId: SystemServiceId;
  idempotencyKey: string;
  /** As sent; normalized and checked (application/pdf only) by the store core. */
  contentType: string;
  /** Content-Length; checked against the 10 MiB cap by the store core. */
  contentLength: number;
}

export function parseStoreSystemGeneratedRequest(
  headers: IncomingHttpHeaders,
): StoreSystemGeneratedRequest {
  const problems: string[] = [];

  const serviceId = headers['x-service-id'];
  const known = SYSTEM_SERVICE_IDS.find((id) => id === serviceId);
  if (known === undefined) {
    problems.push(
      `X-Service-Id must be one of ${SYSTEM_SERVICE_IDS.join(', ')}`,
    );
  }

  const idempotencyKey = idempotencyKeyOf(headers);
  if (idempotencyKey === null) problems.push(IDEMPOTENCY_KEY_PROBLEM);

  const contentLength = declaredLength(headers);
  if (contentLength === null) {
    problems.push('Content-Length is required; a chunked body is not accepted');
  }

  if (problems.length > 0) {
    throw new InvalidRequestHeadersError(problems);
  }
  return {
    serviceId: known as SystemServiceId,
    idempotencyKey: idempotencyKey as string,
    contentType: headers['content-type'] ?? '',
    contentLength: contentLength as number,
  };
}
