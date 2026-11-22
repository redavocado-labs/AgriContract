import { ImapFlow } from 'imapflow';
import type {
  IntakeMail,
  IntakeMailboxPort,
} from '../../application/ports/intake-mailbox.port';
import type { AppConfig } from '../../config/env.validation';

type IntakeConfig = NonNullable<AppConfig['intake']>;

/** A hung IMAP server fails the poll, never stalls it. */
const SOCKET_TIMEOUT_MS = 60_000;

/**
 * IntakeMailboxPort on IMAP (imapflow), design §4.1: one session per poll,
 * the mailbox locked for its duration, unseen mails taken oldest first by
 * UID. Each one is flagged \Seen once handled; a failed one is unflagged
 * again (a fetch may set \Seen by itself), so the next poll retries it.
 */
export class ImapIntakeMailbox implements IntakeMailboxPort {
  readonly address: string;

  constructor(private readonly config: IntakeConfig) {
    this.address = config.address;
  }

  async processUnseen(
    handle: (mail: IntakeMail) => Promise<void>,
    limit: number,
  ): Promise<number> {
    const client = new ImapFlow({
      host: this.config.host,
      port: this.config.port,
      secure: this.config.secure,
      auth: { user: this.config.username, pass: this.config.password },
      logger: false,
      socketTimeout: SOCKET_TIMEOUT_MS,
    });
    // Without a listener a socket error would be thrown as an unhandled event.
    client.on('error', () => undefined);
    await client.connect();
    try {
      const lock = await client.getMailboxLock(this.config.mailbox);
      try {
        const uidValidity =
          client.mailbox === false ? '0' : String(client.mailbox.uidValidity);
        const found = await client.search({ seen: false }, { uid: true });
        const uids = (found || []).sort((a, b) => a - b).slice(0, limit);
        let handled = 0;
        for (const uid of uids) {
          const range = String(uid);
          const meta = await client.fetchOne(
            range,
            { internalDate: true },
            { uid: true },
          );
          const download = await client.download(range, undefined, {
            uid: true,
          });
          if (!meta || !download.content) continue; // expunged meanwhile
          try {
            await handle({
              uid,
              uidValidity,
              internalDate: new Date(meta.internalDate ?? Date.now()),
              source: download.content,
            });
          } catch (err) {
            await client
              .messageFlagsRemove(range, ['\\Seen'], { uid: true })
              .catch(() => undefined);
            throw err;
          }
          await client.messageFlagsAdd(range, ['\\Seen'], { uid: true });
          handled += 1;
        }
        return handled;
      } finally {
        lock.release();
      }
    } finally {
      await client.logout().catch(() => client.close());
    }
  }
}
