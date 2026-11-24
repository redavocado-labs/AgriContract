/**
 * Integration test of the settlement email with its OpenTimestamps proof
 * (`notification.milestone_anchor_requested`, hash-chain design §4.3)
 * against a real MySQL (V2 notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * The command goes through the real parser, so the path from published bytes
 * to stored rows and delivered emails with the proof is the production one,
 * minus RabbitMQ. Each test uses its own addresses.
 */
import { createHash } from 'node:crypto';
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  ANCHOR_RECORD_HASH,
  DISPUTE_CONTRACT_ID,
  DISPUTE_MILESTONE_ID,
  OTS_PROOF_SHA256,
  milestoneAnchorBody,
  milestoneAnchorPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import { parseMilestoneAnchorRequested } from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { OtsProofInvalidError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import { NotifyMilestoneAnchorUseCase } from './notify-milestone-anchor.use-case';

const config = validate(process.env);
const db = config.db;
const MAILHOG = process.env.MAILHOG_API_URL ?? 'http://localhost:8025';

interface MimePart {
  Headers: Record<string, string[]>;
  Body: string;
}

interface MailHogMessage {
  ID: string;
  Content: { Headers: { Subject: string[] } };
  MIME: { Parts: MimePart[] } | null;
}

async function mailsContaining(text: string): Promise<MailHogMessage[]> {
  const res = await fetch(
    `${MAILHOG}/api/v2/search?kind=containing&query=${encodeURIComponent(text)}`,
  );
  return ((await res.json()) as { items: MailHogMessage[] }).items;
}

/**
 * The attachment's file name, reassembled: a name over the header line
 * limit arrives split per RFC 2231 (filename*0=..., filename*1=...).
 */
function fileName(part: MimePart): string {
  const disposition = (part.Headers['Content-Disposition'] ?? []).join(' ');
  const whole = /filename="?([^";]+)"?/.exec(disposition);
  if (whole) return whole[1];
  return [...disposition.matchAll(/filename\*(\d+)="?([^";]+)"?/g)]
    .sort((x, y) => Number(x[1]) - Number(y[1]))
    .map((m) => m[2])
    .join('');
}

const header = (part: MimePart, name: string): string =>
  (part.Headers[name] ?? []).join(' ');

describe('settlement email with its OTS proof on real MySQL and MailHog', () => {
  let pool: Pool;
  let useCase: NotifyMilestoneAnchorUseCase;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  const partiesOf = (tag: string) => [
    {
      userId: 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b',
      email: `buyer-${tag}@anchor.int.test`,
      role: 'BUYER',
    },
    { userId: null, email: `seller-${tag}@anchor.int.test`, role: 'SELLER' },
  ];

  const command = (tag: string, patch: Record<string, unknown> = {}) => {
    const eventId = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(eventId);
    return parseMilestoneAnchorRequested(
      milestoneAnchorBody({
        eventId,
        payload: milestoneAnchorPayload({
          recipients: partiesOf(tag),
          ...patch,
        }),
      }),
    );
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
    useCase = new NotifyMilestoneAnchorUseCase(
      new NotificationSender(
        new MysqlNotificationLogRepository(pool),
        new NodemailerEmailAdapter(
          new ConfigService<AppConfig, true>(config),
          createTransport as never,
        ),
        { logger: { error: () => undefined } },
      ),
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

  it('delivers the proof to buyer and seller, one row each', async () => {
    const settled = command('d81');

    await useCase.execute(settled);

    expect(await rowsOf(settled.eventId)).toEqual(
      [
        ['buyer-d81@anchor.int.test', 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b'],
        ['seller-d81@anchor.int.test', null],
      ].map(([email, userId]) => ({
        recipient_email: email,
        recipient_user_id: userId,
        event_type: 'notification.milestone_anchor_requested',
        notification_type: 'MILESTONE_ANCHOR',
        template_version: 'milestone-anchor/v1',
        status: 'SENT',
      })),
    );

    const [mail] = await mailsTo('buyer-d81@anchor.int.test');
    expect(mail.Content.Headers.Subject[0]).toBe(
      `[AgriContract] Milestone settled on contract ${DISPUTE_CONTRACT_ID}: timestamp proof`,
    );
    const parts = mail.MIME?.Parts ?? [];
    const text = parts.find((p) =>
      header(p, 'Content-Type').startsWith('text/plain'),
    );
    const proof = parts.find((p) =>
      header(p, 'Content-Disposition').includes('.ots'),
    );
    expect(text).toBeDefined();
    expect(proof).toBeDefined();
    // The text part stays 7bit: no line over 76 characters.
    expect(header(text!, 'Content-Transfer-Encoding')).toBe('7bit');
    expect(text!.Body).toContain(ANCHOR_RECORD_HASH);
    expect(text!.Body).toContain(OTS_PROOF_SHA256);
    expect(fileName(proof!)).toBe(
      `${DISPUTE_CONTRACT_ID}-${DISPUTE_MILESTONE_ID}.ots`,
    );
    // The bytes that arrived hash to what sha256sum gave for the fixture proof.
    const bytes = Buffer.from(proof!.Body.replace(/\s+/g, ''), 'base64');
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(
      OTS_PROOF_SHA256,
    );

    expect(await mailsTo('seller-d81@anchor.int.test')).toHaveLength(1);
  });

  it('a replayed eventId sends no second copy', async () => {
    const settled = command('d82');

    await useCase.execute(settled);
    await useCase.execute(settled);

    expect(await rowsOf(settled.eventId)).toHaveLength(2);
    expect(await mailsTo('buyer-d82@anchor.int.test')).toHaveLength(1);
  });

  it('a proof that is not base64 records nothing and mails nobody', async () => {
    const settled = command('d83', { otsProof: 'not base64!' });

    await expect(useCase.execute(settled)).rejects.toBeInstanceOf(
      OtsProofInvalidError,
    );
    expect(await rowsOf(settled.eventId)).toEqual([]);
    expect(await mailsTo('buyer-d83@anchor.int.test')).toEqual([]);
  });
});
