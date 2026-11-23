import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import {
  fileEmailNotice,
  type EmailMeta,
  type SpfDkimResult,
} from './file-events';
import {
  InvalidFileLengthError,
  UnsupportedContentTypeError,
  failureCode,
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

const MiB = 1024 * 1024;

/**
 * What the attachments of one mail may hold in memory together (design
 * §4.1, D88); past it the remaining ones become failures.
 */
export const MAIL_HELD_BYTES = 40 * MiB;

/** Technical failures of one mail before it is given up (design §4, D88). */
export const MAX_ATTEMPTS = 3;

export interface IntakeSettings {
  /**
   * authserv-id of our receiving server (INTAKE_AUTHSERV_ID): only its
   * Authentication-Results are read; null = none is trusted.
   */
  authservId: string | null;
}

export interface IntakeLogger {
  log(message: string): void;
  warn(message: string): void;
}

export type IntakeResult = 'PROCESSED' | 'DUPLICATE' | 'GIVEN_UP';

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
 * A technical failure (storage, database or the mailbox stream down, a bug)
 * throws: the mail stays unseen and the next poll takes it again. Mails are
 * taken oldest first and a poll stops at a failure, so a mail that fails
 * every time would hold back every mail behind it: on its MAX_ATTEMPTS-th
 * failure it is given up instead, as one email_intake_failure row ("could
 * not be processed: <code>"), and flagged read so the poll goes on (design
 * §4, D88). Attempts are counted per mail (UIDVALIDITY:UID, known even when
 * the mail cannot be parsed) in this process; a restart counts again from 0.
 * A mail given up is not recorded as processed, so an Operator who flags it
 * unread again has it taken in anew.
 */
export class EmailIntakeUseCase {
  /** Technical failures so far, per mail still failing. */
  private readonly attempts = new Map<string, number>();

  constructor(
    private readonly mailbox: IntakeMailboxPort,
    private readonly readMail: MailReader,
    private readonly store: StoreFileUseCase,
    private readonly intake: IntakeRepository,
    private readonly logger: IntakeLogger,
    private readonly settings: IntakeSettings = { authservId: null },
  ) {}

  /** One polling session of at most `limit` mails; resolves with how many were handled. */
  poll(limit: number): Promise<number> {
    return this.mailbox.processUnseen(
      (mail) => this.process(mail).then(() => undefined),
      limit,
    );
  }

  async process(mail: IntakeMail): Promise<IntakeResult> {
    const mailId = `${mail.uidValidity}:${mail.uid}`;
    try {
      const result = await this.take(mail);
      this.attempts.delete(mailId);
      return result;
    } catch (err) {
      const attempts = (this.attempts.get(mailId) ?? 0) + 1;
      this.attempts.set(mailId, attempts);
      const code = failureCode(err);
      if (attempts < MAX_ATTEMPTS) {
        this.logger.warn(
          `Intake mail uid ${mail.uid}: attempt ${attempts} of ${MAX_ATTEMPTS} failed (${code}); the next poll tries it again`,
        );
        throw err;
      }
      // Throws when even this cannot be written: the mail stays unseen and
      // its next failure gives it up again.
      await this.intake.recordFailure(
        this.mailbox.address,
        `mail uid ${mail.uid}: could not be processed: ${code} (${MAX_ATTEMPTS} attempts)`,
      );
      this.attempts.delete(mailId);
      this.logger.warn(
        `Intake mail uid ${mail.uid}: given up after ${MAX_ATTEMPTS} attempts (${code}); recorded for the Operator and flagged read`,
      );
      return 'GIVEN_UP';
    }
  }

  private async take(mail: IntakeMail): Promise<IntakeResult> {
    let parsed: ParsedMail | null = null;
    let unreadable: string | null = null;
    try {
      parsed = await this.readMail(mail.source, {
        attachmentBytes: EMAIL_INTAKE_POLICY.maxBytes,
        mailBytes: MAIL_HELD_BYTES,
      });
    } catch (err) {
      if (!(err instanceof UnreadableMailError)) throw err;
      unreadable = err.message;
    }

    const messageKey = sha256(
      parsed?.messageId ?? `uid:${mail.uidValidity}:${mail.uid}`,
    );
    if (await this.intake.isProcessed(messageKey)) {
      return this.duplicate(mail, messageKey);
    }

    const failures: string[] = [];
    let attachments = 0;
    let notice = null;
    if (!parsed) {
      failures.push(`${unreadable} (mail uid ${mail.uid})`);
    } else {
      const meta = emailMeta(
        parsed,
        mail.internalDate,
        this.settings.authservId,
      );
      for (const [index, attachment] of parsed.attachments.entries()) {
        const name = describe(index, attachment.filename);
        if ('tooLarge' in attachment) {
          failures.push(`${name}: larger than 20 MiB`);
          continue;
        }
        if ('overMailLimit' in attachment) {
          failures.push(
            `${name}: not taken, the attachments of one mail are limited to 40 MiB in all`,
          );
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
    if (!recorded) return this.duplicate(mail, messageKey);
    this.logger.log(
      `Intake mail ${messageKey.slice(0, 12)}: ${attachments} attachment(s) stored, ` +
        `${failures.length} failure(s)${notice ? ', notice published' : ''}`,
    );
    for (const failure of failures) {
      this.logger.warn(`Intake mail ${messageKey.slice(0, 12)}: ${failure}`);
    }
    return 'PROCESSED';
  }

  /** A mail already taken in: skipped, but logged so a resend that was swallowed can be traced. */
  private duplicate(mail: IntakeMail, messageKey: string): IntakeResult {
    this.logger.log(
      `Intake mail uid ${mail.uid} (${messageKey.slice(0, 12)}): already processed, skipped`,
    );
    return 'DUPLICATE';
  }
}

/** emailMeta of design §5 / inspection §3.6. */
export function emailMeta(
  mail: ParsedMail,
  receivedAt: Date,
  authservId: string | null,
): EmailMeta {
  return {
    senderDomain: domainOf(mail.fromAddress),
    spfDkimResult: spfDkimResult(mail.authenticationResults, authservId),
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

/** A resinfo of RFC 8601 §2.2 for SPF, DKIM or DMARC: "dkim=pass", "dkim/1 = fail". */
const RESINFO = /^(spf|dkim|dmarc)(?:\/\d+)?\s*=\s*([a-z]+)/i;

/**
 * spfDkimResult of emailMeta (golden-flow-events.yaml, D88), from the
 * Authentication-Results header our receiving server wrote: the topmost one
 * carrying `authservId`. Any other was written by someone else, possibly the
 * sender, and is never read (RFC 8601 §5). Our server adds its header on
 * top, above any the sender wrote with the same id; removing those is the
 * server's job (RFC 8601 §5), not this one's.
 *
 * - PASS when DMARC passed: DMARC is the verdict on the From domain, and it
 *   passes with SPF or DKIM aligned, so a forwarded mail with spf=fail can
 *   still be PASS;
 * - FAIL when SPF, DKIM or DMARC failed ("fail" only: softfail, neutral and
 *   the errors are not a failure);
 * - NONE otherwise: no header of ours (or no authservId set), or no verdict
 *   either way.
 *
 * A signal for the Admin's review (inspection §3.3), never a reason to reject.
 */
export function spfDkimResult(
  headers: readonly string[],
  authservId: string | null,
): SpfDkimResult {
  if (!authservId) return 'NONE';
  const id = authservId.trim().toLowerCase();
  const ours = headers.find((h) => authservIdOf(h) === id);
  if (!ours) return 'NONE';
  const verdicts = new Map<string, string>();
  // A comment may say anything ("(dmarc=pass)"); results are the resinfos after the id.
  const resinfos = withoutComments(ours).split(';').slice(1);
  for (const resinfo of resinfos) {
    const match = RESINFO.exec(resinfo.trim());
    if (!match) continue;
    const method = match[1].toLowerCase();
    if (!verdicts.has(method)) verdicts.set(method, match[2].toLowerCase());
  }
  if (verdicts.get('dmarc') === 'pass') return 'PASS';
  if ([...verdicts.values()].includes('fail')) return 'FAIL';
  return 'NONE';
}

/**
 * The authserv-id an Authentication-Results header starts with (RFC 8601
 * §2.2: `authserv-id [ CFWS authres-version ] ( no-result / 1*resinfo )`),
 * lower case, or null when there is none.
 */
export function authservIdOf(header: string): string | null {
  const match = /^\s*([^\s;()]+)/.exec(withoutComments(header));
  return match ? match[1].toLowerCase() : null;
}

function withoutComments(header: string): string {
  return header.replace(/\([^()]*\)/g, ' ');
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
