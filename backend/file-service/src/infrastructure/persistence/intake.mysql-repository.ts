import { randomUUID } from 'node:crypto';
import { MysqlOutboxStore, Outbox } from '@agricontract/eventing';
import type { Pool, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import type {
  IntakeRepository,
  ProcessedMail,
} from '../../application/ports/intake.repository';

/** IntakeRepository on email_intake_message / email_intake_failure (V4) and event_outbox. */
export class IntakeMysqlRepository implements IntakeRepository {
  private readonly outbox = new Outbox(new MysqlOutboxStore());

  constructor(private readonly pool: Pool) {}

  async isProcessed(messageKey: string): Promise<boolean> {
    const [rows] = await this.pool.query<RowDataPacket[]>(
      'SELECT 1 FROM email_intake_message WHERE message_key = ?',
      [messageKey],
    );
    return rows.length > 0;
  }

  async recordFailure(sourceMailbox: string, reason: string): Promise<void> {
    await this.pool.query(
      `INSERT INTO email_intake_failure (failure_id, file_id, source_mailbox, failure_reason)
       VALUES (?, NULL, ?, ?)`,
      [randomUUID(), sourceMailbox, reason],
    );
  }

  async recordProcessed(mail: ProcessedMail): Promise<boolean> {
    const conn = await this.pool.getConnection();
    try {
      await conn.beginTransaction();
      const [inserted] = await conn.query<ResultSetHeader>(
        `INSERT IGNORE INTO email_intake_message
           (message_key, source_mailbox, attachments, failures) VALUES (?, ?, ?, ?)`,
        [
          mail.messageKey,
          mail.sourceMailbox,
          mail.attachments,
          mail.failures.length,
        ],
      );
      if (inserted.affectedRows !== 1) {
        await conn.rollback();
        return false;
      }
      for (const reason of mail.failures) {
        await conn.query(
          `INSERT INTO email_intake_failure (failure_id, file_id, source_mailbox, failure_reason)
           VALUES (?, NULL, ?, ?)`,
          [randomUUID(), mail.sourceMailbox, reason],
        );
      }
      if (mail.notice) await this.outbox.enqueue(conn, mail.notice);
      await conn.commit();
      return true;
    } catch (err) {
      await conn.rollback().catch(() => undefined);
      throw err;
    } finally {
      conn.release();
    }
  }
}
