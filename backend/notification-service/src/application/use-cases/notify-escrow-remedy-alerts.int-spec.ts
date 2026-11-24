/**
 * Integration test of the two Operator emails about a finalized remedy
 * decision (`notification.escrow_remedy_leg_alert_requested` and
 * `notification.escrow_remedy_rejected_requested`, D93) against a real MySQL
 * (V2 notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * The commands go through the real parsers, so the path from published bytes
 * to a stored row and a delivered email is the production one, minus RabbitMQ.
 * Each test uses its own operator address, so its mails are told apart.
 */
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  REMEDY_BANK_COMMAND_ID,
  REMEDY_CONTRACT_ID,
  REMEDY_DECISION_ID,
  REMEDY_LEG_ID,
  remedyLegAlertBody,
  remedyLegAlertPayload,
  remedyRejectedBody,
  remedyRejectedPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import {
  parseEscrowRemedyLegAlertRequested,
  parseEscrowRemedyRejectedRequested,
} from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { NotificationSender } from '../notification-sender';
import { NotifyEscrowRemedyLegAlertUseCase } from './notify-escrow-remedy-leg-alert.use-case';
import { NotifyEscrowRemedyRejectedUseCase } from './notify-escrow-remedy-rejected.use-case';

const config = validate(process.env);
const db = config.db;
const MAILHOG = process.env.MAILHOG_API_URL ?? 'http://localhost:8025';

interface MailHogMessage {
  ID: string;
  Content: {
    Headers: {
      Subject: string[];
      'Content-Transfer-Encoding'?: string[];
    };
    Body: string;
  };
}

async function mailsContaining(text: string): Promise<MailHogMessage[]> {
  const res = await fetch(
    `${MAILHOG}/api/v2/search?kind=containing&query=${encodeURIComponent(text)}`,
  );
  return ((await res.json()) as { items: MailHogMessage[] }).items;
}

describe('escrow remedy alert emails on real MySQL and MailHog', () => {
  let pool: Pool;
  let sender: NotificationSender;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  /** An operator address unique to one test. */
  const address = (tag: string) => `operator-${tag}@remedy-alert.int.test`;

  /** An eventId unique to one test. */
  const eventId = (tag: string) => {
    const id = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(id);
    return id;
  };

  const legAlert = (tag: string, payload = remedyLegAlertPayload()) =>
    parseEscrowRemedyLegAlertRequested(
      remedyLegAlertBody({ eventId: eventId(tag), payload }),
    );

  const rejected = (tag: string, payload = remedyRejectedPayload()) =>
    parseEscrowRemedyRejectedRequested(
      remedyRejectedBody({ eventId: eventId(tag), payload }),
    );

  const rowsOf = async (id: string) => {
    const [rows] = await pool.query<RowDataPacket[]>(
      `SELECT recipient_email, recipient_user_id, event_type, notification_type,
              template_version, status, retry_count
         FROM notification_logs WHERE event_id = ?`,
      [id],
    );
    return rows;
  };

  /** The mails to `to`, remembered for cleanup. */
  const mailsTo = async (to: string) => {
    const mails = await mailsContaining(to);
    mailIds.push(...mails.map((m) => m.ID));
    return mails;
  };

  beforeAll(() => {
    pool = createPool({
      host: db.host,
      port: db.port,
      database: db.name,
      user: db.username,
      password: db.password,
      timezone: 'Z',
      connectionLimit: 10,
    });
    const email = new NodemailerEmailAdapter(
      new ConfigService<AppConfig, true>(config),
      createTransport as never,
    );
    sender = new NotificationSender(
      new MysqlNotificationLogRepository(pool),
      email,
      { logger: { error: () => undefined } },
    );
  });

  afterAll(async () => {
    try {
      for (const id of mailIds) {
        await fetch(`${MAILHOG}/api/v1/messages/${id}`, { method: 'DELETE' });
      }
      if (eventIds.length > 0) {
        await pool.query(
          'DELETE FROM notification_logs WHERE event_id IN (?)',
          [eventIds],
        );
      }
    } finally {
      await pool.end(); // always, or Jest never exits
    }
  });

  it('delivers a failed remedy leg to the operator with every detail, and records the row', async () => {
    const to = address('f71');
    const command = legAlert('f71');

    await new NotifyEscrowRemedyLegAlertUseCase(sender, to).execute(command);

    expect(await rowsOf(command.eventId)).toEqual([
      {
        recipient_email: to,
        recipient_user_id: null,
        event_type: 'notification.escrow_remedy_leg_alert_requested',
        notification_type: 'ESCROW_REMEDY_LEG_FAILED',
        template_version: 'escrow-remedy-leg-failed/v1',
        status: 'SENT',
        retry_count: 0,
      },
    ]);

    const mails = await mailsTo(to);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      '[AgriContract] A remedy payment was refused by the bank, no retry left',
    );
    // Lines within 76 characters: sent as 7bit, the ids read unbroken.
    expect(mails[0].Content.Headers['Content-Transfer-Encoding']).toEqual([
      '7bit',
    ]);
    const body = mails[0].Content.Body;
    for (const line of [
      `Contract:     ${REMEDY_CONTRACT_ID}`,
      `Decision:     ${REMEDY_DECISION_ID}`,
      `Remedy leg:   ${REMEDY_LEG_ID}`,
      'Payee:        SELLER',
      'Amount:       45,000,000.00 VND',
      `Bank command: ${REMEDY_BANK_COMMAND_ID}`,
      '2026-11-24 15:14:59 Vietnam time (2026-11-24T08:14:59.000Z UTC)',
      'POST /api/v1/contracts/{contractId}/remedy/retry-legs',
    ]) {
      expect(body).toContain(line);
    }
  });

  it('a replay of the same remedy leg alert sends no second copy', async () => {
    const to = address('f72');
    const command = legAlert('f72');
    const useCase = new NotifyEscrowRemedyLegAlertUseCase(sender, to);

    await useCase.execute(command);
    await useCase.execute(command);

    expect(await mailsTo(to)).toHaveLength(1);
    expect(await rowsOf(command.eventId)).toHaveLength(1);
  });

  it('delivers a rejected decision to the operator with the reason, and records the row', async () => {
    const to = address('f73');
    const command = rejected('f73');

    await new NotifyEscrowRemedyRejectedUseCase(sender, to).execute(command);

    expect(await rowsOf(command.eventId)).toEqual([
      {
        recipient_email: to,
        recipient_user_id: null,
        event_type: 'notification.escrow_remedy_rejected_requested',
        notification_type: 'ESCROW_REMEDY_REJECTED',
        template_version: 'escrow-remedy-rejected/v1',
        status: 'SENT',
        retry_count: 0,
      },
    ]);

    const mails = await mailsTo(to);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      '[AgriContract] Escrow rejected a remedy decision',
    );
    expect(mails[0].Content.Headers['Content-Transfer-Encoding']).toEqual([
      '7bit',
    ]);
    const body = mails[0].Content.Body;
    for (const line of [
      `Contract:     ${REMEDY_CONTRACT_ID}`,
      `Decision:     ${REMEDY_DECISION_ID}`,
      '2026-11-24 15:00:00 Vietnam time (2026-11-24T08:00:00.000Z UTC)',
      'Remedy legs total 160000000.00 but only 150000000.00 is still locked for',
      'It needs a developer to find the error.',
    ]) {
      expect(body).toContain(line);
    }
  });

  it('a rejected decision with a non-ASCII reason still reaches the operator whole', async () => {
    const to = address('f74');
    const reason = 'Tổng các lệnh vượt số tiền còn khoá của hợp đồng';
    const command = rejected('f74', remedyRejectedPayload({ reason }));

    await new NotifyEscrowRemedyRejectedUseCase(sender, to).execute(command);

    expect(await rowsOf(command.eventId)).toMatchObject([{ status: 'SENT' }]);
    const mails = await mailsTo(to);
    expect(mails).toHaveLength(1);
    // Not 7bit any more: the transfer encoding is nodemailer's choice.
    const encoding = mails[0].Content.Headers['Content-Transfer-Encoding'];
    const raw = mails[0].Content.Body;
    const text =
      encoding?.[0] === 'base64'
        ? Buffer.from(raw, 'base64').toString('utf8')
        : decodeQuotedPrintable(raw);
    expect(text).toContain(reason);
  });

  it('a replay of the same rejected decision sends no second copy', async () => {
    const to = address('f75');
    const command = rejected('f75');
    const useCase = new NotifyEscrowRemedyRejectedUseCase(sender, to);

    await useCase.execute(command);
    await useCase.execute(command);

    expect(await mailsTo(to)).toHaveLength(1);
    expect(await rowsOf(command.eventId)).toHaveLength(1);
  });
});

/** Quoted-printable to UTF-8: soft line breaks dropped, =XX bytes decoded. */
function decodeQuotedPrintable(text: string): string {
  const bytes = text
    .replace(/=\r?\n/g, '')
    .replace(/=([0-9A-F]{2})/g, (_m, hex: string) =>
      String.fromCharCode(parseInt(hex, 16)),
    );
  return Buffer.from(bytes, 'latin1').toString('utf8');
}
