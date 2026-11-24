/*
 * Errors whose messages are safe to log: they carry an event id and a failure
 * code, never a recipient address or payload text (review #172). Shared
 * eventing's handleDelivery logs err.message on every retry and dead-letter.
 */

/** Base class of the errors this service lets reach the delivery logs as they are. */
export class NotificationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = new.target.name;
  }
}

/** Retryable: another delivery still held a live claim on the same email after the claim wait. */
export class SendInProgressError extends NotificationError {
  constructor(readonly eventId: string) {
    super(
      `Event ${eventId}: another delivery is sending this email; retrying later`,
    );
  }
}

/**
 * Shutdown began while waiting for another delivery's live claim. The
 * consumer parks the message (no ack, no nack) so the broker redelivers it
 * after the restart; outside shutdown it is retryable like SendInProgressError.
 */
export class ClaimWaitAbortedError extends NotificationError {
  constructor(readonly eventId: string) {
    super(
      `Event ${eventId}: shutdown began while another delivery was sending this email`,
    );
  }
}

/** Retryable: the email or its log write failed; `code` names the cause. */
export class NotificationDeliveryError extends NotificationError {
  constructor(
    readonly eventId: string,
    readonly step: 'email' | 'log',
    readonly code: string,
  ) {
    super(
      `Event ${eventId}: ${step === 'email' ? 'email delivery' : 'notification log write'} failed (${code})`,
    );
  }
}

/**
 * A staff alert arrived but `notification.operatorEmail` (OPERATOR_EMAIL) is
 * not set, so there is nobody to send it to. Not a silent drop: the route
 * dead-letters the message at once with this text, since a redelivery cannot
 * succeed before the variable is set and the service restarted, and the
 * message then waits in the DLQ to be moved back.
 */
export class OperatorEmailNotConfiguredError extends NotificationError {
  constructor(readonly eventId: string) {
    super(
      `Event ${eventId}: OPERATOR_EMAIL is not set, so this operator alert has no recipient`,
    );
  }
}

/**
 * Retryable: file-service answered 404 for a document the command says is
 * ready. Unknown, PROCESSING, FAILED and deleted all look the same (the
 * contract gives one 404 for all), so the message is retried and, if the
 * file never becomes READY, dead-lettered after the last attempt.
 */
export class FileNotReadyError extends NotificationError {
  constructor(readonly fileId: string) {
    super(
      `File ${fileId} is not READY in file-service (unknown, not ready or deleted)`,
    );
  }
}

/** Retryable: file-service could not be reached, or answered 408, 429 or 5xx; `code` names the cause. */
export class FileServiceUnavailableError extends NotificationError {
  constructor(
    readonly fileId: string,
    readonly code: string,
  ) {
    super(`File ${fileId}: file-service unavailable (${code})`);
  }
}

/**
 * Not retryable: file-service refused the read in a way no redelivery can
 * change (401: SERVICE_INTERNAL_SECRET differs; 403 or another 4xx but 404,
 * 408 and 429; a file over the size cap). The route dead-letters it at once.
 */
export class FileServiceRejectedError extends NotificationError {
  constructor(
    readonly fileId: string,
    readonly code: string,
  ) {
    super(`File ${fileId}: file-service refused the read (${code})`);
  }
}

/**
 * Not retryable: the bytes file-service returned do not hash to the
 * command's `documentFileHash`, so sending them would mail a PDF whose hash
 * contradicts the email. The route dead-letters it at once, unsent.
 */
export class DocumentFileMismatchError extends NotificationError {
  constructor(
    readonly eventId: string,
    readonly fileId: string,
  ) {
    super(
      `Event ${eventId}: file ${fileId} does not hash to the documentFileHash of the command`,
    );
  }
}

/**
 * Not retryable: `otsProof` is not a usable proof (empty, not base64, or
 * over the size cap), so there is nothing to attach. The route dead-letters
 * it at once, unsent: a settlement email without its proof would not be
 * the evidence email the design promises. `reason` never quotes the value.
 */
export class OtsProofInvalidError extends NotificationError {
  constructor(
    readonly eventId: string,
    readonly reason: string,
  ) {
    super(`Event ${eventId}: otsProof is not a usable proof (${reason})`);
  }
}

const SAFE_CODE = /^[A-Za-z][A-Za-z0-9_/.-]{0,63}$/;

/**
 * A failure code that is safe to log: the error's `code` (e.g. ER_LOCK_DEADLOCK,
 * ECONNREFUSED) when it looks like one, otherwise its class name. Never the
 * message, which may quote addresses or payload text.
 */
export function failureCode(err: unknown): string {
  const code = (err as { code?: unknown } | null)?.code;
  if (typeof code === 'string' && SAFE_CODE.test(code)) return code;
  if (err instanceof Error && SAFE_CODE.test(err.name)) return err.name;
  return 'UNKNOWN';
}
