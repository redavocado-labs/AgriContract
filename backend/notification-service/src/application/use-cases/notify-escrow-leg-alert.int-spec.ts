/**
 * Integration test of the Operator email about an escrow deposit leg
 * (`notification.escrow_leg_alert_requested`, D68) against a real MySQL (V2
 * notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * The command goes through the real parser, so the path from published bytes
 * to a stored row and a delivered email is the production one, minus RabbitMQ.
 * Each test uses its own operator address, so its mails are told apart.
 */
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  LEG_ALERT_BANK_COMMAND_ID,
  LEG_ALERT_CONTRACT_ID,
  LEG_ALERT_LEG_ID,
  legAlertBody,
  legAlertPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import { parseEscrowLegAlertRequested } from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { NotificationSender } from '../notification-sender';
import { NotifyEscrowLegAlertUseCase } from './notify-escrow-leg-alert.use-case';

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

describe('escrow leg alert email on real MySQL and MailHog', () => {
  let pool: Pool;
  let sender: NotificationSender;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  /** The use case mailing an operator address unique to one test. */
  const operator = (tag: string) => ({
    address: `operator-${tag}@leg-alert.int.test`,
    useCase: new NotifyEscrowLegAlertUseCase(
      sender,
      `operator-${tag}@leg-alert.int.test`,
    ),
  });

  /** A command whose eventId is unique to one test. */
  const command = (tag: string, payload = legAlertPayload()) => {
    const eventId = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(eventId);
    return parseEscrowLegAlertRequested(legAlertBody({ eventId, payload }));
  };

  const rowsOf = async (eventId: string) => {
    const [rows] = await pool.query<RowDataPacket[]>(
      `SELECT recipient_email, recipient_user_id, event_type, notification_type,
              template_version, status, retry_count
         FROM notification_logs WHERE event_id = ?`,
      [eventId],
    );
    return rows;
  };

  /** The mails to `address`, remembered for cleanup. */
  const mailsTo = async (address: string) => {
    const mails = await mailsContaining(address);
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

  it('delivers STUCK to the operator with every detail, and records the row', async () => {
    const { address, useCase } = operator('e61');
    const stuck = command('e61');

    await useCase.execute(stuck);

    expect(await rowsOf(stuck.eventId)).toEqual([
      {
        recipient_email: address,
        recipient_user_id: null,
        event_type: 'notification.escrow_leg_alert_requested',
        notification_type: 'ESCROW_LEG_STUCK',
        template_version: 'escrow-leg-stuck/v1',
        status: 'SENT',
        retry_count: 0,
      },
    ]);

    const mails = await mailsTo(address);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      '[AgriContract] An escrow deposit is waiting on the bank too long',
    );
    // Lines within 76 characters: sent as 7bit, the ids read unbroken.
    expect(mails[0].Content.Headers['Content-Transfer-Encoding']).toEqual([
      '7bit',
    ]);
    const body = mails[0].Content.Body;
    for (const line of [
      `Contract:     ${LEG_ALERT_CONTRACT_ID}`,
      `Leg:          ${LEG_ALERT_LEG_ID}`,
      'Party:        BUYER',
      'Status:       REQUESTED (lock sent to the bank, no result yet)',
      'Amount:       150,000,000.00 VND',
      `Bank command: ${LEG_ALERT_BANK_COMMAND_ID}`,
      '2026-11-21 10:00:00 Vietnam time (2026-11-21T03:00:00.000Z UTC)',
      'bank_command_replay_mismatch',
    ]) {
      expect(body).toContain(line);
    }
  });

  it('delivers RESOLVED with its own template', async () => {
    const { address, useCase } = operator('e62');
    const resolved = command(
      'e62',
      legAlertPayload({ stage: 'RESOLVED', legStatus: 'LOCKED' }),
    );

    await useCase.execute(resolved);

    expect(await rowsOf(resolved.eventId)).toMatchObject([
      {
        notification_type: 'ESCROW_LEG_RESOLVED',
        template_version: 'escrow-leg-resolved/v1',
        status: 'SENT',
      },
    ]);
    const mails = await mailsTo(address);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      '[AgriContract] A stuck escrow deposit has its bank result',
    );
    expect(mails[0].Content.Body).toContain(
      'Status:       LOCKED (locked by the bank)',
    );
  });

  it('delivers RETURN_EXHAUSTED with its own template', async () => {
    const { address, useCase } = operator('e63');
    const exhausted = command(
      'e63',
      legAlertPayload({
        stage: 'RETURN_EXHAUSTED',
        legStatus: 'RETURN_FAILED',
        party: 'SELLER',
      }),
    );

    await useCase.execute(exhausted);

    expect(await rowsOf(exhausted.eventId)).toMatchObject([
      {
        recipient_email: address,
        notification_type: 'ESCROW_LEG_RETURN_EXHAUSTED',
        template_version: 'escrow-leg-return-exhausted/v1',
        status: 'SENT',
      },
    ]);
    const mails = await mailsTo(address);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      '[AgriContract] An escrow deposit return failed with no retry left',
    );
    expect(mails[0].Content.Body).toContain(
      'The deposit has not been returned.',
    );
    expect(mails[0].Content.Body).toContain('Last failed return:');
  });

  it('STUCK then RESOLVED of one leg are two rows and two emails', async () => {
    const { address, useCase } = operator('e64');
    const stuck = command('e64');
    const resolved = command(
      '1e64',
      legAlertPayload({ stage: 'RESOLVED', legStatus: 'LOCKED' }),
    );

    await useCase.execute(stuck);
    await useCase.execute(resolved);

    expect(await rowsOf(stuck.eventId)).toHaveLength(1);
    expect(await rowsOf(resolved.eventId)).toHaveLength(1);
    expect(await mailsTo(address)).toHaveLength(2);
  });

  it('a replay of the same eventId sends no second copy', async () => {
    const { address, useCase } = operator('e65');
    const stuck = command('e65');

    await useCase.execute(stuck);
    await useCase.execute(stuck);

    expect(await mailsTo(address)).toHaveLength(1);
    expect(await rowsOf(stuck.eventId)).toHaveLength(1);
  });
});
