import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { fileEmailNotice, type EmailMeta } from './file-events';
import {
  InvalidFileLengthError,
  UnsupportedContentTypeError,
} from './file-errors';
import { EMAIL_INTAKE_POLICY } from './ingest-policy';
import type { IntakeRepository } from './ports/intake.repository';
import {
  UnreadableMailError,
  type IntakeMail,
  type IntakeMailboxPort,
  type MailReader,
  type ParsedMail,
} from './ports/intake-mailbox.port';
import type { StoreFileUseCase } from './store-file.use-case';

/**
 * uploaded_by of an EMAIL_INTAKE file: no end-user uploaded it, and §2 says
 * a service id for files the system took in. file.ready requires one.
 */
export const INTAKE_UPLOADER = 'file-service:email-intake';

/** Characters of the body kept in file.email_notice.snippet. */
export const SNIPPET_LENGTH = 200;

export interface IntakeLogger {
  log(message: string): void;
  warn(message: string): void;
}

export type IntakeResult = 'PROCESSED' | 'DUPLICATE';

/**
 * The email intake bridge (file-service-phase2-design.md §4.1, #275), for
 * one mail of `intake@...`:
 *
 * - each attachment becomes an EMAIL_INTAKE file (20 MiB cap, checked while
 *   the MIME part streams; PDF, JPEG or PNG) through the same store core,
 *   so storage_hash is computed as it is stored; its scan job
 *   (file.email.parsed) carries the mail's emailMeta, and the same virus
 *   scan publishes file.ready with it, or file.failed (§4: one scan for
 *   every channel);
 * - a mail without attachment (an ack, a case-ID reply) publishes
 *   file.email_notice and creates no file;
 * - what cannot be taken (type not accepted, over 20 MiB, empty, a mail that
 *   is not MIME) becomes an email_intake_failure row for the Operator (§4:
 *   a business failure, not a dev alert); the other attachments still go.
 *
 * Once per mail: the message key (SHA-256 of the Message-ID, else of
 * UIDVALIDITY:UID) is recorded with the failures and the notice in one
 * transaction; a mail seen again is a DUPLICATE and does nothing. A crash
 * before that record stores the attachments again on the next poll, but each
 * store is idempotent on (uploader, messageKey:index), so it returns the same
 * file and writes no second scan job.
 *
 * A storage or database failure throws: the mail stays unseen and the next
 * poll takes it again.
 */
export class EmailIntakeUseCase {
  constructor(
    private readonly mailbox: IntakeMailboxPort,
    private readonly readMail: MailReader,
    private readonly store: StoreFileUseCase,
    private readonly intake: IntakeRepository,
    private readonly logger: IntakeLogger,
  ) {}

  /** One polling session of at most `limit` mails; resolves with how many were handled. */
  poll(limit: number): Promise<number> {
    return this.mailbox.processUnseen(
      (mail) => this.process(mail).then(() => undefined),
      limit,
    );
  }

  async process(mail: IntakeMail): Promise<IntakeResult> {
    let parsed: ParsedMail | null = null;
    let unreadable: string | null = null;
    try {
      parsed = await this.readMail(mail.source, EMAIL_INTAKE_POLICY.maxBytes);
    } catch (err) {
      if (!(err instanceof UnreadableMailError)) throw err;
      unreadable = err.message;
    }

    const messageKey = sha256(
      parsed?.messageId ?? `uid:${mail.uidValidity}:${mail.uid}`,
    );
    if (await this.intake.isProcessed(messageKey)) return 'DUPLICATE';

    const failures: string[] = [];
    let attachments = 0;
    let notice = null;
    if (!parsed) {
      failures.push(`${unreadable} (mail uid ${mail.uid})`);
    } else {
      const meta = emailMeta(parsed, mail.internalDate);
      for (const [index, attachment] of parsed.attachments.entries()) {
        const name = describe(index, attachment.filename);
        if ('tooLarge' in attachment) {
          failures.push(`${name}: larger than 20 MiB`);
          continue;
        }
        try {
          await this.store.execute(
            {
              body: Readable.from([attachment.bytes]),
              contentType: attachment.contentType,
              contentLength: attachment.bytes.length,
              uploadedBy: INTAKE_UPLOADER,
              idempotencyKey: `${messageKey}:${index}`,
              emailMeta: meta,
            },
            EMAIL_INTAKE_POLICY,
          );
          attachments += 1;
        } catch (err) {
          if (err instanceof UnsupportedContentTypeError) {
            failures.push(
              `${name}: type ${attachment.contentType} not accepted (PDF, JPEG or PNG)`,
            );
          } else if (err instanceof InvalidFileLengthError) {
            failures.push(`${name}: empty`);
          } else {
            throw err;
          }
        }
      }
      if (parsed.attachments.length === 0) {
        notice = fileEmailNotice(messageKey, meta, snippet(parsed.text));
      }
    }

    const recorded = await this.intake.recordProcessed({
      messageKey,
      sourceMailbox: this.mailbox.address,
      attachments,
      failures,
      notice,
    });
    if (!recorded) return 'DUPLICATE';
    this.logger.log(
      `Intake mail ${messageKey.slice(0, 12)}: ${attachments} attachment(s) stored, ` +
        `${failures.length} failure(s)${notice ? ', notice published' : ''}`,
    );
    for (const failure of failures) {
      this.logger.warn(`Intake mail ${messageKey.slice(0, 12)}: ${failure}`);
    }
    return 'PROCESSED';
  }
}

/** emailMeta of design §5 / inspection §3.6. */
export function emailMeta(mail: ParsedMail, receivedAt: Date): EmailMeta {
  return {
    senderDomain: domainOf(mail.fromAddress),
    spfDkimResult: spfDkimResult(mail.authenticationResults),
    subject: mail.subject,
    receivedAt: receivedAt.toISOString(),
  };
}

/**
 * The domain of the From address, lower case, hostname characters only;
 * '' when there is none.
 */
export function domainOf(address: string | null): string {
  const at = address?.lastIndexOf('@') ?? -1;
  if (at < 0) return '';
  const domain = address!
    .slice(at + 1)
    .trim()
    .toLowerCase();
  return /^[a-z0-9.-]*/.exec(domain)![0];
}

const METHOD = /\b(spf|dkim|dmarc)=([a-z]+)/gi;

/**
 * The SPF / DKIM / DMARC verdicts of the topmost Authentication-Results
 * header (RFC 8601), the one our receiving side added, as
 * "spf=pass; dkim=pass; dmarc=pass" (only the methods present, first
 * verdict of each), or "none" when there is no such header. A signal for the
 * Operator's review (inspection §3.6), never a reason to reject.
 */
export function spfDkimResult(headers: readonly string[]): string {
  const top = headers[0];
  if (!top) return 'none';
  const verdicts = new Map<string, string>();
  for (const [, method, result] of top.matchAll(METHOD)) {
    const key = method.toLowerCase();
    if (!verdicts.has(key)) verdicts.set(key, result.toLowerCase());
  }
  if (verdicts.size === 0) return 'none';
  return ['spf', 'dkim', 'dmarc']
    .filter((m) => verdicts.has(m))
    .map((m) => `${m}=${verdicts.get(m)}`)
    .join('; ');
}

/** The body for file.email_notice: whitespace collapsed, at most SNIPPET_LENGTH characters. */
export function snippet(text: string): string {
  return text.replace(/\s+/g, ' ').trim().slice(0, SNIPPET_LENGTH);
}

function describe(index: number, filename: string | null): string {
  return `attachment ${index + 1}${filename ? ` "${filename.slice(0, 120)}"` : ''}`;
}

function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}
