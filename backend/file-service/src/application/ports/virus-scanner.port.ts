import type { Readable } from 'node:stream';

export const VIRUS_SCANNER = Symbol('VIRUS_SCANNER');

export type ScanVerdict =
  | { clean: true }
  /** `signature`: the scanner's name for what it found, for the log. */
  | { clean: false; signature: string };

/** The virus scanner (compose `clamav`, file-service-phase2-design.md §4). */
export interface VirusScannerPort {
  /**
   * Scans `body` as it streams, never buffering it whole. Resolves with the
   * verdict once the scanner has seen every byte. Rejects when no verdict
   * could be had (scanner down or erroring, or `body` failed: then with the
   * body's own error), which is worth a retry.
   */
  scan(body: Readable): Promise<ScanVerdict>;
}
