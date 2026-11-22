import {
  createEnvelope,
  deriveEnvelope,
  type EventEnvelope,
} from '@agricontract/eventing';
import type { StoredFile } from '../domain/stored-file';

export const PRODUCER = 'file-service';

/** file-service's topic exchange; routing key = eventType (contract-service binds file.ready/file.failed here). */
export const FILES_EXCHANGE = 'agricontract.files';

/**
 * The scan job of a DIRECT_UPLOAD (file-service-phase2-design.md §4:
 * `file.uploaded.direct → queue file-service.virus-scan`). Internal to
 * file-service: not in golden-flow-events.yaml, no other service binds it.
 */
export const SCAN_REQUESTED = 'file.uploaded.direct';
export const FILE_READY = 'file.ready';
export const FILE_FAILED = 'file.failed';

/** Payload of SCAN_REQUESTED: the scan reads everything else from the row. */
export interface ScanRequestedPayload {
  [key: string]: string;
  fileId: string;
}

export function scanRequested(
  file: StoredFile,
): EventEnvelope<ScanRequestedPayload> {
  return createEnvelope({
    eventType: SCAN_REQUESTED,
    producer: PRODUCER,
    aggregateId: file.fileId,
    payload: { fileId: file.fileId },
  });
}

/**
 * golden-flow-events.yaml fileReady: exactly the five required fields
 * (emailMeta only for EMAIL_INTAKE, #275). No storageHash (design §5).
 * Caused by the scan job, so its causationId is the job's eventId.
 */
export function fileReady(
  cause: EventEnvelope,
  file: StoredFile,
): EventEnvelope {
  return deriveEnvelope(cause, {
    eventType: FILE_READY,
    producer: PRODUCER,
    aggregateId: file.fileId,
    payload: {
      fileId: file.fileId,
      uploadedBy: file.uploadedBy,
      ingestChannel: file.ingestChannel,
      contentType: file.contentType,
      fileSize: file.fileSize,
    },
  });
}

/** golden-flow-events.yaml fileFailed. */
export function fileFailed(
  cause: EventEnvelope,
  file: StoredFile,
  failureReason: string,
): EventEnvelope {
  return deriveEnvelope(cause, {
    eventType: FILE_FAILED,
    producer: PRODUCER,
    aggregateId: file.fileId,
    payload: {
      fileId: file.fileId,
      uploadedBy: file.uploadedBy,
      ingestChannel: file.ingestChannel,
      failureReason,
    },
  });
}
