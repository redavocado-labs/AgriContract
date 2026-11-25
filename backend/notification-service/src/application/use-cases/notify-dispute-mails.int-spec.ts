/**
 * Integration test of the BF-04 mails of #147 (D96):
 * `notification.milestone_status_requested`, `notification.breach_notice_requested`
 * and `notification.remedy_finalized_requested`, against a real MySQL (V2
 * notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * Each command goes through its real parser, so the path from published bytes
 * to stored rows and delivered emails is the production one, minus RabbitMQ.
 * Each test uses its own addresses, so its mails are told apart.
 */
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  BREACH_CASE_ID,
  DISPUTE_CONTRACT_ID,
  breachNoticeBody,
  breachNoticePayload,
  contractCompletedPayload,
  milestoneStatusBody,
  milestoneStatusPayload,
  remedyFinalizedBody,
  remedyFinalizedPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import {
  parseBreachNoticeRequested,
  parseMilestoneStatusRequested,
  parseRemedyFinalizedRequested,
} from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { NotificationSender } from '../notification-sender';
import { NotifyBreachNoticeUseCase } from './notify-breach-notice.use-case';
import { NotifyMilestoneStatusUseCase } from './notify-milestone-status.use-case';
import { NotifyRemedyFinalizedUseCase } from './notify-remedy-finalized.use-case';

const config = validate(process.env);
const db = config.db;
const MAILHOG = process.env.MAILHOG_API_URL ?? 'http://localhost:8025';

interface MailHogMessage {
  ID: string;
  Content: {
    Headers: { Subject: string[]; 'Content-Transfer-Encoding'?: string[] };
    Body: string;
  };
}

async function mailsContaining(text: string): Promise<MailHogMessage[]> {
  const res = await fetch(
    `${MAILHOG}/api/v2/search?kind=containing&query=${encodeURIComponent(text)}`,
  );
  return ((await res.json()) as { items: MailHogMessage[] }).items;
}

describe('BF-04 milestone, breach and remedy mails on real MySQL and MailHog', () => {
  let pool: Pool;
  let sender: NotificationSender;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  /** Buyer and seller addresses unique to one test. */
  const partiesOf = (tag: string) => [
    {
      userId: 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b',
      email: `buyer-${tag}@dispute.int.test`,
      role: 'BUYER',
    },
    {
      userId: '9e8d7c6b-5a4f-4e3d-2c1b-0a9f8e7d6c5b',
      email: `seller-${tag}@dispute.int.test`,
      role: 'SELLER',
    },
  ];

  const eventIdOf = (tag: string) => {
    const id = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(id);
    return id;
  };

  const rowsOf = async (eventId: string) => {
    const [rows] = await pool.query<RowDataPacket[]>(
      `SELECT recipient_email, recipient_user_id, event_type, notification_type,
              template_version, status
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
    sender = new NotificationSender(
      new MysqlNotificationLogRepository(pool),
      new NodemailerEmailAdapter(
        new ConfigService<AppConfig, true>(config),
        createTransport as never,
      ),
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

  it('WEIGHED goes to the buyer only, as contract-service sends it', async () => {
    const [buyer] = partiesOf('c71');
    const command = parseMilestoneStatusRequested(
      milestoneStatusBody({
        eventId: eventIdOf('c71'),
        payload: milestoneStatusPayload({ recipients: [buyer] }),
      }),
    );

    await new NotifyMilestoneStatusUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toEqual([
      {
        recipient_email: buyer.email,
        recipient_user_id: buyer.userId,
        event_type: 'notification.milestone_status_requested',
        notification_type: 'MILESTONE_WEIGHED',
        template_version: 'milestone-weighed/v1',
        status: 'SENT',
      },
    ]);
    const mails = await mailsTo(buyer.email);
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      `[AgriContract] Milestone 2 of contract ${DISPUTE_CONTRACT_ID}: weighed by the seller`,
    );
    expect(mails[0].Content.Headers['Content-Transfer-Encoding']).toEqual([
      '7bit',
    ]);
    expect(mails[0].Content.Body).toContain('Declared weight: 1195.5 kg');
  });

  it('FLAGGED reaches the seller with the response deadline', async () => {
    const [, seller] = partiesOf('c72');
    const command = parseMilestoneStatusRequested(
      milestoneStatusBody({
        eventId: eventIdOf('c72'),
        payload: milestoneStatusPayload({
          recipients: [seller],
          statusType: 'FLAGGED',
          deadline: '2026-12-04T16:59:59.999Z',
          statusData: {
            milestoneIndex: 2,
            committedQuantityKg: '1200',
            issueType: 'QUANTITY',
          },
        }),
      }),
    );

    await new NotifyMilestoneStatusUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toMatchObject([
      {
        notification_type: 'MILESTONE_FLAGGED',
        template_version: 'milestone-flagged/v1',
      },
    ]);
    const [mail] = await mailsTo(seller.email);
    expect(mail.Content.Body).toContain(
      '2026-12-04 23:59:59 Vietnam time (2026-12-04T16:59:59.999Z UTC)',
    );
  });

  it('the breach notice reaches both parties with the case and the evidence', async () => {
    const parties = partiesOf('c73');
    const command = parseBreachNoticeRequested(
      breachNoticeBody({
        eventId: eventIdOf('c73'),
        payload: breachNoticePayload({ recipients: parties }),
      }),
    );

    await new NotifyBreachNoticeUseCase(sender).execute(command);

    const rows = await rowsOf(command.eventId);
    expect(
      rows.map((r): unknown[] => [
        r.recipient_email,
        r.notification_type,
        r.template_version,
      ]),
    ).toEqual(
      parties.map((p) => [p.email, 'BREACH_NOTICE', 'breach-notice/v1']),
    );
    const [mail] = await mailsTo(parties[0].email);
    expect(mail.Content.Headers['Content-Transfer-Encoding']).toEqual(['7bit']);
    expect(mail.Content.Body).toContain(`Breach case: ${BREACH_CASE_ID}`);
    expect(mail.Content.Body).toContain(
      'This is an allegation, not a decision',
    );
  });

  it('a fault decision is REMEDY_FINALIZED, the normal completion CONTRACT_COMPLETED', async () => {
    const parties = partiesOf('c74');
    const decision = parseRemedyFinalizedRequested(
      remedyFinalizedBody({
        eventId: eventIdOf('c74'),
        payload: remedyFinalizedPayload({ recipients: parties }),
      }),
    );
    const completed = parseRemedyFinalizedRequested(
      remedyFinalizedBody({
        eventId: eventIdOf('1c74'),
        payload: contractCompletedPayload({ recipients: parties }),
      }),
    );
    const useCase = new NotifyRemedyFinalizedUseCase(sender);

    await useCase.execute(decision);
    await useCase.execute(completed);

    expect(await rowsOf(decision.eventId)).toMatchObject(
      parties.map(() => ({
        notification_type: 'REMEDY_FINALIZED',
        template_version: 'remedy-finalized/v1',
        status: 'SENT',
      })),
    );
    expect(await rowsOf(completed.eventId)).toMatchObject(
      parties.map(() => ({
        notification_type: 'CONTRACT_COMPLETED',
        template_version: 'contract-completed/v1',
        status: 'SENT',
      })),
    );
    const mails = await mailsTo(parties[1].email);
    expect(mails.map((m) => m.Content.Headers.Subject[0]).sort()).toEqual([
      `[AgriContract] Decision on contract ${DISPUTE_CONTRACT_ID}`,
      `[AgriContract] Every milestone of contract ${DISPUTE_CONTRACT_ID} has settled`,
    ]);
    for (const mail of mails) {
      expect(mail.Content.Headers['Content-Transfer-Encoding']).toEqual([
        '7bit',
      ]);
    }
  });

  it('a replayed eventId sends no second copy', async () => {
    const parties = partiesOf('c75');
    const command = parseBreachNoticeRequested(
      breachNoticeBody({
        eventId: eventIdOf('c75'),
        payload: breachNoticePayload({ recipients: parties }),
      }),
    );
    const useCase = new NotifyBreachNoticeUseCase(sender);

    await useCase.execute(command);
    await useCase.execute(command);

    expect(await rowsOf(command.eventId)).toHaveLength(2);
    expect(await mailsTo(parties[0].email)).toHaveLength(1);
  });
});
