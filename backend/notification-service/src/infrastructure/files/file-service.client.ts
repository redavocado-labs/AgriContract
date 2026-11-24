import {
  FileNotReadyError,
  FileServiceRejectedError,
  FileServiceUnavailableError,
  failureCode,
} from '../../application/notification-errors';
import type {
  DocumentFiles,
  StoredFile,
} from '../../application/ports/document-files.port';

/** file-service's own cap on a stored file (file-service design §2): 10 MiB. */
export const MAX_FILE_BYTES = 10 * 1024 * 1024;

/** A read that takes longer is abandoned and retried: well inside the consumer's patience. */
export const READ_TIMEOUT_MS = 30_000;

export interface FileServiceClientOptions {
  /** Replaced in tests. */
  fetch?: typeof fetch;
  timeoutMs?: number;
  maxBytes?: number;
}

/**
 * `GET /internal/v1/files/{fileId}` of file-service
 * (supplementary-services-api.yaml `getFile`), authenticated with the shared
 * SERVICE_INTERNAL_SECRET as X-Internal-Secret.
 *
 * 200 is the file's bytes with its Content-Type and Content-Length.
 * file-service checks them against the recorded size and SHA-256 while
 * streaming and cuts the response short on a mismatch, so a body that ends
 * early (fewer bytes than Content-Length, or a stream error) is treated as
 * unavailable and retried, never handed on.
 */
export class FileServiceClient implements DocumentFiles {
  private readonly fetch: typeof fetch;
  private readonly timeoutMs: number;
  private readonly maxBytes: number;

  constructor(
    private readonly baseUrl: string,
    private readonly internalSecret: string,
    options: FileServiceClientOptions = {},
  ) {
    this.fetch = options.fetch ?? fetch;
    this.timeoutMs = options.timeoutMs ?? READ_TIMEOUT_MS;
    this.maxBytes = options.maxBytes ?? MAX_FILE_BYTES;
  }

  async readReady(fileId: string): Promise<StoredFile> {
    const url = `${this.baseUrl.replace(/\/+$/, '')}/internal/v1/files/${encodeURIComponent(fileId)}`;
    let response: Response;
    try {
      response = await this.fetch(url, {
        method: 'GET',
        headers: { 'X-Internal-Secret': this.internalSecret },
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (err) {
      throw new FileServiceUnavailableError(fileId, networkCode(err));
    }

    if (response.status !== 200) {
      await response.body?.cancel().catch(() => undefined);
      if (response.status === 404) throw new FileNotReadyError(fileId);
      if (response.status === 503 || response.status >= 500) {
        throw new FileServiceUnavailableError(
          fileId,
          `HTTP_${response.status}`,
        );
      }
      throw new FileServiceRejectedError(fileId, `HTTP_${response.status}`);
    }

    // No Content-Length (chunked) is NaN, not 0: then only the cap applies.
    const header = response.headers.get('content-length');
    const declared = header === null ? Number.NaN : Number(header);
    if (Number.isFinite(declared) && declared > this.maxBytes) {
      await response.body?.cancel().catch(() => undefined);
      throw new FileServiceRejectedError(fileId, 'TOO_LARGE');
    }

    let content: Buffer;
    try {
      content = Buffer.from(await response.arrayBuffer());
    } catch (err) {
      throw new FileServiceUnavailableError(
        fileId,
        `TRUNCATED/${networkCode(err)}`,
      );
    }
    if (content.length > this.maxBytes) {
      throw new FileServiceRejectedError(fileId, 'TOO_LARGE');
    }
    if (Number.isFinite(declared) && content.length !== declared) {
      throw new FileServiceUnavailableError(fileId, 'TRUNCATED');
    }

    return {
      content,
      contentType:
        response.headers.get('content-type')?.split(';')[0].trim() ||
        'application/octet-stream',
    };
  }
}

/**
 * fetch wraps the socket error in `cause` ("fetch failed"); a timeout is a
 * DOMException named TimeoutError. Only a code leaves, never a message.
 */
function networkCode(err: unknown): string {
  // A DOMException is not always an Error across realms: read its name.
  if ((err as { name?: unknown } | null)?.name === 'TimeoutError') {
    return 'TIMEOUT';
  }
  const cause = (err as { cause?: unknown } | null)?.cause;
  return cause !== undefined ? failureCode(cause) : failureCode(err);
}
