import { config } from './config.mjs';
import { waitFor } from './poll.mjs';

/*
 * The one-time codes notification-service mails (signing, dispute
 * concession...), read back from the compose MailHog as the party would
 * read them from their inbox.
 */

async function messagesTo(address) {
  const res = await fetch(
    `${config.mailhogUrl}/api/v2/search?kind=to&query=${encodeURIComponent(address)}&limit=50`,
  );
  if (!res.ok) throw new Error(`MailHog search answered ${res.status}`);
  return (await res.json()).items;
}

const textOf = (message) =>
  message.Content.Body.replace(/=\r?\n/g, '').replace(/=([0-9A-F]{2})/g, (_, h) =>
    String.fromCharCode(parseInt(h, 16)),
  );

/**
 * The code of the newest mail to `address` with `subject` received after
 * `since`, waiting for it to arrive.
 */
export async function otpCode(trace, address, subject, since) {
  const message = await waitFor(
    trace,
    `the "${subject}" mail to ${address}`,
    async () => {
      const mails = await messagesTo(address);
      return mails.find(
        (m) => m.Content.Headers.Subject?.[0] === subject && new Date(m.Created) >= since,
      ) ?? null;
    },
    (m) => m !== null,
  );
  const code = /^\s*(\d{6})\s*$/m.exec(textOf(message))?.[1];
  if (!code) throw new Error(`no 6-digit code in the "${subject}" mail to ${address}`);
  return code;
}

export const SIGNING_SUBJECT = '[AgriContract] Your contract signing code';
