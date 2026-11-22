import { randomUUID } from 'node:crypto';

export interface TestAttachment {
  filename: string;
  contentType: string;
  bytes: Buffer;
}

export interface TestMail {
  from?: string;
  subject?: string;
  /** null: no Message-ID header at all. */
  messageId?: string | null;
  /** Authentication-Results headers, topmost first. */
  authenticationResults?: string[];
  text?: string;
  attachments?: TestAttachment[];
  /** An image embedded in an HTML body (multipart/related, cid:), not an attachment. */
  inlineImage?: Buffer;
}

const CRLF = '\r\n';

function base64Lines(bytes: Buffer): string {
  return (bytes.toString('base64').match(/.{1,76}/g) ?? []).join(CRLF);
}

/** A raw RFC 5322 mail as an intake mailbox would hold it. */
export function buildMail(mail: TestMail = {}): Buffer {
  const boundary = `b-${randomUUID()}`;
  const headers = [
    ...(mail.authenticationResults ?? []).map(
      (h) => `Authentication-Results: ${h}`,
    ),
    `From: SGS Vietnam <${mail.from ?? 'reports@sgs.example.com'}>`,
    'To: intake@agricontract.local',
    `Subject: ${mail.subject ?? 'Inspection report CASE-2026-0042'}`,
    'Date: Sun, 22 Nov 2026 09:00:00 +0700',
    ...(mail.messageId === null
      ? []
      : [
          `Message-ID: ${mail.messageId ?? `<${randomUUID()}@sgs.example.com>`}`,
        ]),
    'MIME-Version: 1.0',
    `Content-Type: multipart/mixed; boundary="${boundary}"`,
  ];
  const parts: string[] = [];
  const text = mail.text ?? 'Please find the inspection report attached.';
  if (mail.inlineImage) {
    const related = `r-${randomUUID()}`;
    parts.push(
      [
        `Content-Type: multipart/related; boundary="${related}"`,
        '',
        `--${related}`,
        'Content-Type: text/html; charset=utf-8',
        '',
        `<p>${text}</p><img src="cid:logo@sgs">`,
        `--${related}`,
        'Content-Type: image/png',
        'Content-Transfer-Encoding: base64',
        'Content-ID: <logo@sgs>',
        'Content-Disposition: inline; filename="logo.png"',
        '',
        base64Lines(mail.inlineImage),
        `--${related}--`,
      ].join(CRLF),
    );
  } else {
    parts.push(
      ['Content-Type: text/plain; charset=utf-8', '', text].join(CRLF),
    );
  }
  for (const a of mail.attachments ?? []) {
    parts.push(
      [
        `Content-Type: ${a.contentType}; name="${a.filename}"`,
        'Content-Transfer-Encoding: base64',
        `Content-Disposition: attachment; filename="${a.filename}"`,
        '',
        base64Lines(a.bytes),
      ].join(CRLF),
    );
  }
  const body = parts.map((p) => `--${boundary}${CRLF}${p}`).join(CRLF);
  return Buffer.from(
    `${headers.join(CRLF)}${CRLF}${CRLF}${body}${CRLF}--${boundary}--${CRLF}`,
    'utf8',
  );
}

export const PDF = Buffer.from('%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n');
