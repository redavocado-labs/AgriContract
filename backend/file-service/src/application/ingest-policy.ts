import type { IngestChannel } from '../domain/stored-file';

/** What an entrypoint accepts before a byte is stored (file-service-phase2-design.md §3). */
export interface IngestPolicy {
  channel: IngestChannel;
  /** Hard cap, checked against the declared length and again while streaming. */
  maxBytes: number;
  /** Accepted content types (normalized, see normalizeContentType); null = any. */
  contentTypes: readonly string[] | null;
  /**
   * The stored file waits PROCESSING for the virus scan (§4): the store
   * writes the scan job (file.uploaded.direct) with the row, in one
   * transaction, so no file is left unscanned. Without it the file is READY
   * as soon as it is stored: only the platform's own files skip the scan
   * (SYSTEM_GENERATED, never an end-user's).
   */
  virusScan: boolean;
}

const MiB = 1024 * 1024;

/**
 * DIRECT_UPLOAD (StoreOnBehalfOf, §3): JPG, PNG or PDF, at most 10MB
 * (decided 05/07/2026), read as 10 MiB, then the virus scan (#131).
 */
export const DIRECT_UPLOAD_POLICY: IngestPolicy = {
  channel: 'DIRECT_UPLOAD',
  maxBytes: 10 * MiB,
  contentTypes: ['image/jpeg', 'image/png', 'application/pdf'],
  virusScan: true,
};

/**
 * SYSTEM_GENERATED (store(), §3, D88): a PDF the platform made itself (the
 * final contract PDF, an EUDR report), at most 10 MiB, READY at once with no
 * scan.
 */
export const SYSTEM_GENERATED_POLICY: IngestPolicy = {
  channel: 'SYSTEM_GENERATED',
  maxBytes: 10 * MiB,
  contentTypes: ['application/pdf'],
  virusScan: false,
};

/**
 * EMAIL_INTAKE (the IMAP bridge, §3, §4.1): at most 20MB (decided
 * 05/07/2026, review pass 2), read as 20 MiB, checked while the MIME part
 * streams. The design gives no whitelist; the bridge takes the same three
 * types as DIRECT_UPLOAD (reports are PDF, sometimes scans). Then the same
 * virus scan (§4 fix of review pass 2).
 */
export const EMAIL_INTAKE_POLICY: IngestPolicy = {
  channel: 'EMAIL_INTAKE',
  maxBytes: 20 * MiB,
  contentTypes: ['image/jpeg', 'image/png', 'application/pdf'],
  virusScan: true,
};

/**
 * `Image/JPEG; charset=x` -> `image/jpeg`: parameters dropped, lower case.
 * Returns null for anything that is not a `type/subtype` token pair.
 */
export function normalizeContentType(raw: string): string | null {
  const value = raw.split(';', 1)[0].trim().toLowerCase();
  return /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(value) ? value : null;
}
