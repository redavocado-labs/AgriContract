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
  type MailReader,
  type ParsedMail,
} from '../../application/ports/intake-mailbox.port';

/** The text kept for the snippet; the rest of the body is read and dropped. */
const TEXT_KEPT = 4_000;

/**
 * MailReader on mailparser's streaming MailParser: headers, the text body
 * and each attachment arrive as the raw mail streams through. An attachment
 * is collected up to the cap; past it the collected bytes are let go and
 * the rest is drained, so memory stays at most one cap per mail.
 */
export const readMail: MailReader = (source: Readable, maxAttachmentBytes) =>
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
        collect(data, maxAttachmentBytes).then((a) => {
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

/** Reads one attachment, holding at most `cap` bytes; null when it is embedded. */
async function collect(
  attachment: AttachmentStream,
  cap: number,
): Promise<MailAttachment | null> {
  let chunks: Buffer[] | null = [];
  let size = 0;
  try {
    for await (const chunk of attachment.content as AsyncIterable<Buffer>) {
      size += chunk.length;
      if (chunks && size <= cap) chunks.push(chunk);
      else chunks = null; // over the cap: let go, drain the rest
    }
  } finally {
    attachment.release?.();
  }
  // Embedded in an HTML body (multipart/related, an inline cid: image): not a
  // document the sender attached.
  if (attachment.related) return null;
  return chunks
    ? {
        filename: attachment.filename ?? null,
        contentType: attachment.contentType,
        bytes: Buffer.concat(chunks),
      }
    : {
        filename: attachment.filename ?? null,
        contentType: attachment.contentType,
        tooLarge: true,
      };
}

function firstAddress(from: AddressObject | undefined): string | null {
  const first = from?.value?.[0];
  return first?.address ? first.address : null;
}
