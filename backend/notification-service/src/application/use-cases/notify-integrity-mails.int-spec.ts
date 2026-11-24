/**
 * Integration test of the integrity and security emails of #278
 * (audit-service's digest and failure alert, bank-service's lock change,
 * reconciliation mismatch and key fingerprint) against a real MySQL (V2
 * notification_logs) and a real MailHog, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * Each command goes through the real parser, so the path from published bytes
 * to stored rows and delivered emails is the production one, minus RabbitMQ.
 * Each test uses its own addresses.
 */
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  DIGEST_RECORD_HASH,
  VERIFIER_KEY_FINGERPRINT,
  auditDigestBody,
  auditDigestPayload,
  auditFailureBody,
  auditFailurePayload,
  reconciliationBody,
  reconciliationPayload,
  securityLockBody,
  securityLockPayload,
  verifierKeyBody,
  verifierKeyPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import {
  parseAuditDigestRequested,
  parseAuditFailureRequested,
  parseReconciliationMismatchRequested,
  parseSecurityLockChangedRequested,
  parseVerifierKeyAnchorRequested,
} from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { RecipientRoleRefusedError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import { NotifyAuditDigestUseCase } from './notify-audit-digest.use-case';
import { NotifyAuditFailureUseCase } from './notify-audit-failure.use-case';
import { NotifyReconciliationMismatchUseCase } from './notify-reconciliation-mismatch.use-case';
import { NotifySecurityLockChangedUseCase } from './notify-security-lock-changed.use-case';
import { NotifyVerifierKeyAnchorUseCase } from './notify-verifier-key-anchor.use-case';

const config = validate(process.env);
const db = config.db;
const MAILHOG = process.env.MAILHOG_API_URL ?? 'http://localhost:8025';

interface MailHogMessage {
  ID: string;
  Content: {
    Headers: Record<string, string[]>;
    Body: string;
  };
}

async function mailsContaining(text: string): Promise<MailHogMessage[]> {
  const res = await fetch(
    `${MAILHOG}/api/v2/search?kind=containing&query=${encodeURIComponent(text)}`,
  );
  return ((await res.json()) as { items: MailHogMessage[] }).items;
}

/** The text of a single-part mail, its quoted-printable soft breaks undone. */
const textOf = (mail: MailHogMessage): string =>
  mail.Content.Body.replace(/=\r?\n/g, '').replace(/\r\n/g, '\n');

describe('integrity and security emails on real MySQL and MailHog', () => {
  let pool: Pool;
  let sender: NotificationSender;
  const eventIds: string[] = [];
  const mailIds: string[] = [];

  const eventIdOf = (tag: string) => {
    const eventId = `${'0'.repeat(8)}-0000-4000-8000-${tag.padStart(12, '0')}`;
    eventIds.push(eventId);
    return eventId;
  };

  const staff = (tag: string, role: string, n: number) => ({
    userId: null,
    email: `${role.toLowerCase()}-${n}-${tag}@integrity.int.test`,
    role,
  });

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

  it('sends the failure alert to the Admin and every Software Buyer contact, one row each', async () => {
    const recipients = [
      staff('e71', 'ADMIN', 1),
      staff('e71', 'SOFTWARE_BUYER', 1),
      staff('e71', 'SOFTWARE_BUYER', 2),
    ];
    const command = parseAuditFailureRequested(
      auditFailureBody({
        eventId: eventIdOf('e71'),
        payload: auditFailurePayload({ recipients }),
      }),
    );

    await new NotifyAuditFailureUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toEqual(
      recipients
        .map((r) => r.email)
        .sort()
        .map((email) => ({
          recipient_email: email,
          recipient_user_id: null,
          event_type: 'notification.audit_failure_requested',
          notification_type: 'AUDIT_FAILURE',
          template_version: 'audit-failure/v1',
          status: 'SENT',
        })),
    );
    for (const r of recipients) {
      const [mail] = await mailsTo(r.email);
      expect(mail.Content.Headers.Subject[0]).toBe(
        '[AgriContract] ALERT: audit chain check failed',
      );
      expect(textOf(mail)).toContain(
        'contact at once: 1 ADMIN, 2 SOFTWARE_BUYER.',
      );
    }
  });

  it('a failure alert naming a buyer records nothing and mails nobody', async () => {
    const recipients = [staff('e72', 'ADMIN', 1), staff('e72', 'BUYER', 1)];
    const command = parseAuditFailureRequested(
      auditFailureBody({
        eventId: eventIdOf('e72'),
        payload: auditFailurePayload({ recipients }),
      }),
    );

    await expect(
      new NotifyAuditFailureUseCase(sender).execute(command),
    ).rejects.toBeInstanceOf(RecipientRoleRefusedError);
    expect(await rowsOf(command.eventId)).toEqual([]);
    expect(await mailsTo('admin-1-e72@integrity.int.test')).toEqual([]);
  });

  it('sends the digest with the head hash; a replay sends no second copy', async () => {
    const recipients = [staff('e73', 'SOFTWARE_BUYER', 1)];
    const command = parseAuditDigestRequested(
      auditDigestBody({
        eventId: eventIdOf('e73'),
        payload: auditDigestPayload({ recipients }),
      }),
    );
    const useCase = new NotifyAuditDigestUseCase(sender);

    await useCase.execute(command);
    await useCase.execute(command);

    expect(await rowsOf(command.eventId)).toMatchObject([
      { notification_type: 'AUDIT_DIGEST', status: 'SENT' },
    ]);
    const mails = await mailsTo(recipients[0].email);
    expect(mails).toHaveLength(1);
    expect(textOf(mails[0])).toContain(DIGEST_RECORD_HASH);
  });

  it('sends the lock change to the Admin and the External Verifier', async () => {
    const recipients = [
      staff('e74', 'ADMIN', 1),
      staff('e74', 'SOFTWARE_BUYER', 1),
    ];
    const command = parseSecurityLockChangedRequested(
      securityLockBody({
        eventId: eventIdOf('e74'),
        payload: securityLockPayload({ recipients, action: 'UNLOCKED' }),
      }),
    );

    await new NotifySecurityLockChangedUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toHaveLength(2);
    const [mail] = await mailsTo(recipients[1].email);
    expect(mail.Content.Headers.Subject[0]).toBe(
      '[AgriContract] Security lock lifted: money movements resumed',
    );
    expect(textOf(mail)).toContain(
      '2026-11-30 02:29:58 Vietnam time (2026-11-29T19:29:58.250Z UTC)',
    );
  });

  it('sends the reconciliation mismatch to the Admin, recipient_user_id kept', async () => {
    const admin = {
      userId: '0e1d2c3b-4a59-4687-9a6b-5c4d3e2f1a0b',
      email: 'admin-e75@integrity.int.test',
      role: 'ADMIN',
    };
    const command = parseReconciliationMismatchRequested(
      reconciliationBody({
        eventId: eventIdOf('e75'),
        payload: reconciliationPayload({ recipients: [admin] }),
      }),
    );

    await new NotifyReconciliationMismatchUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toEqual([
      {
        recipient_email: admin.email,
        recipient_user_id: admin.userId,
        event_type: 'notification.reconciliation_mismatch_requested',
        notification_type: 'RECONCILIATION_MISMATCH',
        template_version: 'reconciliation-mismatch/v1',
        status: 'SENT',
      },
    ]);
    expect(await mailsTo(admin.email)).toHaveLength(1);
  });

  it('sends the key fingerprint to the External Verifier', async () => {
    const address = 'verifier-e76@integrity.int.test';
    const command = parseVerifierKeyAnchorRequested(
      verifierKeyBody({
        eventId: eventIdOf('e76'),
        payload: verifierKeyPayload({ recipientEmail: address }),
      }),
    );

    await new NotifyVerifierKeyAnchorUseCase(sender).execute(command);

    expect(await rowsOf(command.eventId)).toMatchObject([
      {
        recipient_email: address,
        recipient_user_id: null,
        notification_type: 'VERIFIER_KEY_ANCHOR',
        template_version: 'verifier-key-anchor/v1',
        status: 'SENT',
      },
    ]);
    const [mail] = await mailsTo(address);
    expect(textOf(mail)).toContain(VERIFIER_KEY_FINGERPRINT);
  });
});
