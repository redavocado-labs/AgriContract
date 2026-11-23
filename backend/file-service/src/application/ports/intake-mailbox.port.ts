import type { Readable } from 'node:stream';

export const INTAKE_MAILBOX = Symbol('INTAKE_MAILBOX');
export const MAIL_READER = Symbol('MAIL_READER');

/** One mail of the intake mailbox, its raw RFC 5322 bytes still to be read. */
export interface IntakeMail {
  uid: number;
  uidValidity: string;
  /** When the mailbox received it (IMAP INTERNALDATE): the receivedAt of the events. */
  internalDate: Date;
  source: Readable;
}

/** The `intake@...` mailbox (file-service-phase2-design.md §4.1, IMAP polling). */
export interface IntakeMailboxPort {
  /** The address the mails were sent to: email_intake_failure.source_mailbox. */
  readonly address: string;
  /**
   * One polling session: runs `handle` for each unseen mail, oldest first,
   * at most `limit`, and flags it \Seen once `handle` resolved. When
   * `handle` rejects, the mail stays unseen (polled again next time) and the
   * session stops with that error. Resolves with the number handled.
   */
  processUnseen(
    handle: (mail: IntakeMail) => Promise<void>,
    limit: number,
  ): Promise<number>;
}

/**
 * An attachment as the MIME reader gives it: its bytes, or only that it was
 * not held, being over the attachment cap (tooLarge) or past what one mail
 * may hold in all (overMailLimit).
 */
export type MailAttachment =
  | { filename: string | null; contentType: string; bytes: Buffer }
  | { filename: string | null; contentType: string; tooLarge: true }
  | { filename: string | null; contentType: string; overMailLimit: true };

export interface ParsedMail {
  messageId: string | null;
  /** The From address, for its domain only. */
  fromAddress: string | null;
  subject: string;
  /** Every Authentication-Results header, topmost (added last, by our side) first. */
  authenticationResults: string[];
  /** The plain-text body, possibly cut. */
  text: string;
  /** Real attachments only: parts embedded in an HTML body (logos, signatures) are left out. */
  attachments: MailAttachment[];
}

/** The mail could not be read as MIME at all: an email_intake_failure, not a retry. */
export class UnreadableMailError extends Error {
  constructor(detail: string) {
    super(`Mail could not be parsed: ${detail}`);
    this.name = UnreadableMailError.name;
  }
}

/** What one mail may hold in memory while it is read. */
export interface MailLimits {
  /** One attachment: past it, its bytes are dropped as they come (tooLarge). */
  attachmentBytes: number;
  /**
   * All the attachments held for one mail together (design §4.1, D88): an
   * attachment that would take the total past it is dropped as it comes
   * (overMailLimit), so a mailbox that is abused cannot run the service out
   * of memory.
   */
  mailBytes: number;
}

/**
 * Reads one mail from its raw bytes, streaming: an attachment is held only
 * within `limits`; past them its bytes are dropped as they come instead of
 * being held (design §3: check while the MIME part is read, never load it
 * all first).
 */
export type MailReader = (
  source: Readable,
  limits: MailLimits,
) => Promise<ParsedMail>;
