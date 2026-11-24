/**
 * Integration test of the milestone funding emails
 * (`notification.milestone_funding_status_requested`, milestone-escrow §6b)
 * against a real MySQL (V2 notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * The command goes through the real parser, so the path from published bytes
 * to stored rows and delivered emails is the production one, minus RabbitMQ.
 * Each test uses its own addresses, so its mails are told apart.
 */
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  FUNDING_CONTRACT_ID,
  FUNDING_MILESTONE_ID,
  fundingBody,
  fundingPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import { parseMilestoneFundingStatusRequested } from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { NotificationSender } from '../notification-sender';
import { NotifyMilestoneFundingStatusUseCase } from './notify-milestone-funding-status.use-case';

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

describe('milestone funding emails on real MySQL and MailHog', () => {
  let pool: Pool;
  let useCase: NotifyMilestoneFundingStatusUseCase;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  /** Buyer, seller and operator addresses unique to one test. */
  const recipientsOf = (tag: string) => [
    {
      userId: '7a6f5e4d-3c2b-4a1f-9e0d-9c8b7a6f5e4d',
      email: `buyer-${tag}@funding.int.test`,
      role: 'BUYER',
    },
    {
      userId: '8b7a6f5e-4d3c-4b2a-8f1e-0d9c8b7a6f5e',
      email: `seller-${tag}@funding.int.test`,
      role: 'SELLER',
    },
    { userId: null, email: `ops-${tag}@funding.int.test`, role: 'OPERATOR' },
  ];

  /** A command whose eventId and addresses are unique to one test. */
  const command = (tag: string, patch: Record<string, unknown> = {}) => {
    const eventId = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(eventId);
    return parseMilestoneFundingStatusRequested(
      fundingBody({
        eventId,
        payload: fundingPayload({ recipients: recipientsOf(tag), ...patch }),
      }),
    );
  };

  const rowsOf = async (eventId: string) => {
    const [rows] = await pool.query<RowDataPacket[]>(
      `SELECT recipient_email, recipient_user_id, event_type, notification_type,
              template_version, status, retry_count
         FROM notification_logs WHERE event_id = ? ORDER BY recipient_email`,
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
    useCase = new NotifyMilestoneFundingStatusUseCase(
      new NotificationSender(new MysqlNotificationLogRepository(pool), email, {
        logger: { error: () => undefined },
      }),
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

  it('delivers FUNDING_FAILED to buyer, seller and operator, one row each', async () => {
    const failed = command('a81');

    await useCase.execute(failed);

    expect(await rowsOf(failed.eventId)).toEqual(
      [
        ['buyer-a81@funding.int.test', '7a6f5e4d-3c2b-4a1f-9e0d-9c8b7a6f5e4d'],
        ['ops-a81@funding.int.test', null],
        ['seller-a81@funding.int.test', '8b7a6f5e-4d3c-4b2a-8f1e-0d9c8b7a6f5e'],
      ].map(([email, userId]) => ({
        recipient_email: email,
        recipient_user_id: userId,
        event_type: 'notification.milestone_funding_status_requested',
        notification_type: 'MILESTONE_FUNDING_FAILED',
        template_version: 'milestone-funding-failed/v1',
        status: 'SENT',
        retry_count: 0,
      })),
    );

    const buyer = await mailsTo('buyer-a81@funding.int.test');
    expect(buyer).toHaveLength(1);
    expect(buyer[0].Content.Headers.Subject[0]).toBe(
      `[AgriContract] Milestone funding failed for contract ${FUNDING_CONTRACT_ID}`,
    );
    // Lines within 76 characters: sent as 7bit, the ids read unbroken.
    expect(buyer[0].Content.Headers['Content-Transfer-Encoding']).toEqual([
      '7bit',
    ]);
    for (const line of [
      `Contract:  ${FUNDING_CONTRACT_ID}`,
      `Milestone: ${FUNDING_MILESTONE_ID}`,
      '2026-12-04 23:59:59 Vietnam time (2026-12-04T16:59:59.999Z UTC)',
      'Insufficient funds in the buyer account',
      'funding breach by you,',
    ]) {
      expect(buyer[0].Content.Body).toContain(line);
    }

    const seller = await mailsTo('seller-a81@funding.int.test');
    expect(seller).toHaveLength(1);
    expect(seller[0].Content.Body).toContain(
      'Your delivery clock for this milestone is paused',
    );
    expect(await mailsTo('ops-a81@funding.int.test')).toHaveLength(1);
  });

  it.each([
    ['FUNDING_REMINDER', 'a82', 'milestone-funding-reminder/v1'],
    ['FUNDING_CURE_DEADLINE', 'a83', 'milestone-funding-cure-deadline/v1'],
  ])('delivers %s with its own template', async (statusType, tag, version) => {
    const sent = command(tag, { statusType, reason: null });

    await useCase.execute(sent);

    const rows = await rowsOf(sent.eventId);
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(row).toMatchObject({ template_version: version, status: 'SENT' });
    }
    expect(await mailsTo(`buyer-${tag}@funding.int.test`)).toHaveLength(1);
  });

  it('a replay of the same eventId sends no second copy', async () => {
    const failed = command('a84');

    await useCase.execute(failed);
    await useCase.execute(failed);

    expect(await rowsOf(failed.eventId)).toHaveLength(3);
    expect(await mailsTo('buyer-a84@funding.int.test')).toHaveLength(1);
  });
});
