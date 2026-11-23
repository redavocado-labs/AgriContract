import type { Readable } from 'node:stream';
import {
  MailParser,
  type AddressObject,
  type AttachmentStream,
  type MessageText,
} from 'mailparser';
import {
  UnreadableMailError,
  type MailAttachment,
  type MailLimits,
  type MailReader,
  type ParsedMail,
} from '../../application/ports/intake-mailbox.port';

/** The text kept for the snippet; the rest of the body is read and dropped. */
const TEXT_KEPT = 4_000;

/**
 * MailReader on mailparser's streaming MailParser: headers, the text body
 * and each attachment arrive as the raw mail streams through, one
 * attachment at a time (the parser waits for each to be released). An
 * attachment is collected while it stays within its cap and within what is
 * left of the mail's limit; past either, the collected bytes are let go and
 * the rest is drained. So the bytes held for one mail never pass
 * limits.mailBytes, however many attachments it has.
 */
export const readMail: MailReader = (source: Readable, limits: MailLimits) =>
  new Promise<ParsedMail>((resolve, reject) => {
    const parser = new MailParser({
      skipImageLinks: true,
      skipTextToHtml: true,
    });
    const mail: ParsedMail = {
      messageId: null,
      fromAddress: null,
      subject: '',
      authenticationResults: [],
      text: '',
      attachments: [],
    };
    const pending: Promise<void>[] = [];
    // What the mail may still hold, taken chunk by chunk as attachments are kept.
    const room = { bytes: limits.mailBytes };
    // One slot per attachment in the order of the mail, whatever order they finish in.
    const slots: (MailAttachment | null)[] = [];
    let failed = false;
    const fail = (err: unknown) => {
      if (failed) return;
      failed = true;
      source.unpipe(parser);
      // Read the rest and drop it: an IMAP session waits for the whole message.
      source.resume();
      reject(
        new UnreadableMailError(
          err instanceof Error ? err.message : String(err),
        ),
      );
    };

    parser.on('headers', (headers) => {
      const id = headers.get('message-id');
      mail.messageId =
        typeof id === 'string' && id.trim() !== '' ? id.trim() : null;
      const subject = headers.get('subject');
      mail.subject = typeof subject === 'string' ? subject : '';
      mail.fromAddress = firstAddress(
        headers.get('from') as AddressObject | undefined,
      );
      const results = headers.get('authentication-results');
      mail.authenticationResults = (
        Array.isArray(results) ? results : [results]
      ).filter((v): v is string => typeof v === 'string');
    });

    parser.on('data', (data: AttachmentStream | MessageText) => {
      if (data.type === 'text') {
        mail.text = (data.text ?? '').slice(0, TEXT_KEPT);
        return;
      }
      const slot = slots.push(null) - 1;
      pending.push(
        collect(data, limits.attachmentBytes, room).then((a) => {
          slots[slot] = a;
        }),
      );
    });
    parser.once('error', fail);
    parser.once('end', () => {
      Promise.all(pending).then(() => {
        if (failed) return;
        mail.attachments = slots.filter((a): a is MailAttachment => a !== null);
        resolve(mail);
      }, fail);
    });
    source.once('error', (err) => {
      // The mailbox stream broke (IMAP down): not the mail's fault, so not an
      // UnreadableMailError; the poll fails and the mail is read again.
      if (failed) return;
      failed = true;
      reject(err);
    });
    source.pipe(parser);
  });

/**
 * Reads one attachment, holding its bytes only while they stay within `cap`
 * and within `room`, what the mail may still hold: each kept chunk is taken
 * from it at once, and given back when the attachment is let go. Null when
 * it is embedded, which is drained without holding anything.
 */
async function collect(
  attachment: AttachmentStream,
  cap: number,
  room: { bytes: number },
): Promise<MailAttachment | null> {
  // Embedded in an HTML body (multipart/related, an inline cid: image): not a
  // document the sender attached.
  let chunks: Buffer[] | null = attachment.related ? null : [];
  let size = 0;
  try {
    for await (const chunk of attachment.content as AsyncIterable<Buffer>) {
      size += chunk.length;
      if (chunks && size <= cap && chunk.length <= room.bytes) {
        chunks.push(chunk);
        room.bytes -= chunk.length;
      } else if (chunks) {
        // Past a limit: let go of what was held, drain the rest.
        room.bytes += size - chunk.length;
        chunks = null;
      }
    }
  } finally {
    attachment.release?.();
  }
  if (attachment.related) return null;
  const about = {
    filename: attachment.filename ?? null,
    contentType: attachment.contentType,
  };
  if (chunks) return { ...about, bytes: Buffer.concat(chunks) };
  return size > cap
    ? { ...about, tooLarge: true }
    : { ...about, overMailLimit: true };
}

function firstAddress(from: AddressObject | undefined): string | null {
  const first = from?.value?.[0];
  return first?.address ? first.address : null;
}
