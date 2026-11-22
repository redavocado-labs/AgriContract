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
/**
 * The scan job of an EMAIL_INTAKE attachment, once the intake parsed the
 * mail (§4: "parse OK → publish into file-service.virus-scan"). Same queue,
 * same consumer; it carries the mail's emailMeta on to file.ready. Internal.
 */
export const SCAN_REQUESTED_EMAIL = 'file.email.parsed';
export const SCAN_JOB_TYPES: readonly string[] = [
  SCAN_REQUESTED,
  SCAN_REQUESTED_EMAIL,
];
export const FILE_READY = 'file.ready';
export const FILE_FAILED = 'file.failed';
export const FILE_EMAIL_NOTICE = 'file.email_notice';

/**
 * What file.ready says about the mail an EMAIL_INTAKE file came in
 * (design §5, inspection §3.6): enough to match a case ID and weigh the
 * sender, never the sender's address itself.
 */
export interface EmailMeta {
  [key: string]: string;
  senderDomain: string;
  /** From the Authentication-Results our mailbox added, e.g. "spf=pass; dkim=pass", or "none". */
  spfDkimResult: string;
  subject: string;
  /** When the intake mailbox received it (IMAP INTERNALDATE), ISO-8601 UTC. */
  receivedAt: string;
}

/** Payload of a scan job: the scan reads everything else from the row. */
export interface ScanRequestedPayload {
  [key: string]: string | EmailMeta;
  fileId: string;
}

export function scanRequested(
  file: StoredFile,
  emailMeta?: EmailMeta,
): EventEnvelope<ScanRequestedPayload> {
  const payload: ScanRequestedPayload = { fileId: file.fileId };
  if (emailMeta) payload.emailMeta = emailMeta;
  return createEnvelope({
    eventType: emailMeta ? SCAN_REQUESTED_EMAIL : SCAN_REQUESTED,
    producer: PRODUCER,
    aggregateId: file.fileId,
    payload,
  });
}

/** The emailMeta a scan job carries, if it is an email one. */
export function emailMetaOf(job: EventEnvelope): EmailMeta | undefined {
  const meta = job.payload.emailMeta;
  return meta && typeof meta === 'object' && !Array.isArray(meta)
    ? (meta as EmailMeta)
    : undefined;
}

/**
 * golden-flow-events.yaml fileReady: the five required fields, plus
 * emailMeta for an EMAIL_INTAKE file (taken from its scan job). No
 * storageHash (design §5). Caused by the scan job, so its causationId is
 * the job's eventId.
 */
export function fileReady(
  cause: EventEnvelope,
  file: StoredFile,
): EventEnvelope {
  const emailMeta = emailMetaOf(cause);
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
      ...(emailMeta ? { emailMeta } : {}),
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

/**
 * golden-flow-events.yaml fileEmailNotice: a mail without attachment (an ack,
 * a case-ID reply, inspection §3.5). No file, no scan (design §4.1). A root
 * event: aggregateId is the message key, as no other aggregate exists.
 */
export function fileEmailNotice(
  messageKey: string,
  meta: EmailMeta,
  snippet: string,
): EventEnvelope {
  return createEnvelope({
    eventType: FILE_EMAIL_NOTICE,
    producer: PRODUCER,
    aggregateId: messageKey,
    payload: {
      senderDomain: meta.senderDomain,
      spfDkimResult: meta.spfDkimResult,
      subject: meta.subject,
      snippet,
      receivedAt: meta.receivedAt,
    },
  });
}
