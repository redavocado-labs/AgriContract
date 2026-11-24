/**
 * Integration test of the final-contract email
 * (`notification.contract_document_ready_requested`, design §3.3) against a
 * real MySQL (V2 notification_logs), a real MailHog and the real
 * FileServiceClient, run with
 *   npm run test:int
 * DB_*, MAIL_HOST and MAIL_PORT apply with the usual defaults (localhost:3311,
 * localhost:1025); MAILHOG_API_URL defaults to http://localhost:8025.
 *
 * file-service is a local HTTP server answering GET /internal/v1/files/{id}
 * as the contract says, so the path from published bytes to a stored row and
 * a delivered email with its PDF is the production one, minus RabbitMQ.
 */
import { createHash } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { ConfigService } from '@nestjs/config';
import { createPool, type Pool, type RowDataPacket } from 'mysql2/promise';
import { createTransport } from 'nodemailer';
import {
  DOCUMENT_FILE_ID,
  DOCUMENT_PDF,
  DOCUMENT_PDF_SHA256,
  TERMS_SNAPSHOT_HASH,
  documentReadyBody,
  documentReadyPayload,
} from '../../../test/support/commands';
import { validate, type AppConfig } from '../../config/env.validation';
import { NodemailerEmailAdapter } from '../../infrastructure/email/nodemailer-email.adapter';
import { FileServiceClient } from '../../infrastructure/files/file-service.client';
import { parseContractDocumentReadyRequested } from '../../infrastructure/messaging/notification-command.parsers';
import { MysqlNotificationLogRepository } from '../../infrastructure/persistence/notification-log.mysql-repository';
import { FileNotReadyError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import { NotifyContractDocumentReadyUseCase } from './notify-contract-document-ready.use-case';

const config = validate(process.env);
const db = config.db;
const MAILHOG = process.env.MAILHOG_API_URL ?? 'http://localhost:8025';
const SECRET = 'int-spec-internal-secret';

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

const header = (part: MimePart, name: string): string =>
  (part.Headers[name] ?? []).join(' ');

/** Quoted-printable or 7bit text of a part, as UTF-8. */
function partText(part: MimePart): string {
  const encoding = header(part, 'Content-Transfer-Encoding').toLowerCase();
  if (encoding === 'base64') {
    return Buffer.from(part.Body, 'base64').toString('utf8');
  }
  if (encoding === 'quoted-printable') {
    return Buffer.from(
      part.Body.replace(/=\r?\n/g, '').replace(
        /=([0-9A-F]{2})/g,
        (_m, h: string) => String.fromCharCode(parseInt(h, 16)),
      ),
      'latin1',
    ).toString('utf8');
  }
  return part.Body;
}

describe('final-contract email on real MySQL, MailHog and an HTTP file-service', () => {
  let pool: Pool;
  let server: Server;
  let useCase: NotifyContractDocumentReadyUseCase;
  /** What the stand-in file-service answers for DOCUMENT_FILE_ID. */
  let stored: { status: number; body: Buffer };
  const secrets: string[] = [];
  const documentIds: string[] = [];
  const mailIds: string[] = [];

  /** Buyer and seller addresses unique to one test. */
  const recipientsOf = (tag: string) => [
    {
      userId: 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b',
      email: `buyer-${tag}@document.int.test`,
      role: 'BUYER',
    },
    { userId: null, email: `seller-${tag}@document.int.test`, role: 'SELLER' },
  ];

  /** A command whose documentId, eventId and addresses are unique to one test. */
  const command = (tag: string, eventTag = tag) => {
    const documentId = `${'0'.repeat(8)}-0000-4000-9000-${tag.padStart(12, '0')}`;
    documentIds.push(documentId);
    return parseContractDocumentReadyRequested(
      documentReadyBody({
        eventId: `${'0'.repeat(8)}-0000-4000-8000-${eventTag.padStart(12, '0')}`,
        payload: documentReadyPayload({
          documentId,
          recipients: recipientsOf(tag),
        }),
      }),
    );
  };

  const rowsOf = async (documentId: string) => {
    const [rows] = await pool.query<RowDataPacket[]>(
      `SELECT recipient_email, recipient_user_id, event_type, notification_type,
              template_version, status, retry_count
         FROM notification_logs WHERE event_id = ? ORDER BY recipient_email`,
      [documentId],
    );
    return rows;
  };

  /** The mails to `address`, remembered for cleanup. */
  const mailsTo = async (address: string) => {
    const mails = await mailsContaining(address);
    mailIds.push(...mails.map((m) => m.ID));
    return mails;
  };

  beforeAll(async () => {
    server = createServer((req, res) => {
      secrets.push(String(req.headers['x-internal-secret']));
      if (req.url !== `/internal/v1/files/${DOCUMENT_FILE_ID}`) {
        res.writeHead(404).end();
        return;
      }
      res.writeHead(stored.status, {
        'Content-Type': 'application/pdf',
        'Content-Length': stored.body.length,
      });
      res.end(stored.body);
    });
    await new Promise<void>((resolve) =>
      server.listen(0, '127.0.0.1', resolve),
    );
    const fileService = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

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
    useCase = new NotifyContractDocumentReadyUseCase(
      new NotificationSender(new MysqlNotificationLogRepository(pool), email, {
        logger: { error: () => undefined },
      }),
      new FileServiceClient(fileService, SECRET),
    );
  });

  beforeEach(() => {
    stored = { status: 200, body: DOCUMENT_PDF };
  });

  afterAll(async () => {
    try {
      for (const id of mailIds) {
        await fetch(`${MAILHOG}/api/v1/messages/${id}`, { method: 'DELETE' });
      }
      if (documentIds.length > 0) {
        await pool.query(
          'DELETE FROM notification_logs WHERE event_id IN (?)',
          [documentIds],
        );
      }
    } finally {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
      await pool.end(); // always, or Jest never exits
    }
  });

  it('delivers the PDF to buyer and seller, one row each keyed by documentId', async () => {
    const ready = command('b91');

    await useCase.execute(ready);

    expect(secrets.every((s) => s === SECRET)).toBe(true);
    expect(await rowsOf(ready.documentId)).toEqual(
      [
        ['buyer-b91@document.int.test', 'd2a1f3b4-6c5d-4e7f-8a9b-0c1d2e3f4a5b'],
        ['seller-b91@document.int.test', null],
      ].map(([email, userId]) => ({
        recipient_email: email,
        recipient_user_id: userId,
        event_type: 'notification.contract_document_ready_requested',
        notification_type: 'CONTRACT_FINAL_PDF',
        template_version: 'contract-final-pdf/v1',
        status: 'SENT',
        retry_count: 0,
      })),
    );

    const mails = await mailsTo('buyer-b91@document.int.test');
    expect(mails).toHaveLength(1);
    expect(mails[0].Content.Headers.Subject[0]).toBe(
      `[AgriContract] Final signed contract ${ready.contractId}`,
    );
    const parts = mails[0].MIME?.Parts ?? [];
    const text = parts.find((p) =>
      header(p, 'Content-Type').startsWith('text/plain'),
    );
    const pdf = parts.find((p) =>
      header(p, 'Content-Disposition').includes(
        `contract-${ready.contractId}.pdf`,
      ),
    );
    expect(text).toBeDefined();
    expect(pdf).toBeDefined();
    expect(header(pdf!, 'Content-Type')).toContain('application/pdf');
    // The attachment that arrived hashes to the documentFileHash the body prints.
    const bytes = Buffer.from(pdf!.Body.replace(/\s+/g, ''), 'base64');
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(
      DOCUMENT_PDF_SHA256,
    );
    const body = partText(text!);
    expect(body).toContain(DOCUMENT_PDF_SHA256);
    expect(body).toContain(TERMS_SNAPSHOT_HASH);
    expect(body).toContain(`File:         ${DOCUMENT_FILE_ID}`);
    expect(body).toContain(`Size:         ${DOCUMENT_PDF.length} bytes`);

    expect(await mailsTo('seller-b91@document.int.test')).toHaveLength(1);
  });

  it('a second command for the same document (new eventId) sends no second PDF', async () => {
    const first = command('b92');
    const again = parseContractDocumentReadyRequested(
      documentReadyBody({
        eventId: '00000000-0000-4000-8000-0000000001b2',
        payload: documentReadyPayload({
          documentId: first.documentId,
          recipients: recipientsOf('b92'),
        }),
      }),
    );

    await useCase.execute(first);
    await useCase.execute(again);

    expect(await rowsOf(first.documentId)).toHaveLength(2);
    expect(await mailsTo('buyer-b92@document.int.test')).toHaveLength(1);
  });

  it('a file not READY yet (404) mails nobody and records nothing; the retry sends', async () => {
    const ready = command('b93');
    stored = { status: 404, body: Buffer.from('{"code":"NOT_FOUND"}') };

    await expect(useCase.execute(ready)).rejects.toBeInstanceOf(
      FileNotReadyError,
    );
    expect(await rowsOf(ready.documentId)).toEqual([]);
    expect(await mailsTo('buyer-b93@document.int.test')).toEqual([]);

    stored = { status: 200, body: DOCUMENT_PDF };
    await useCase.execute(ready);
    expect(await rowsOf(ready.documentId)).toHaveLength(2);
    expect(await mailsTo('buyer-b93@document.int.test')).toHaveLength(1);
  });

  it('bytes that do not hash to documentFileHash are never mailed', async () => {
    const ready = command('b94');
    stored = { status: 200, body: Buffer.from('%PDF-1.7 a different file') };

    await expect(useCase.execute(ready)).rejects.toThrow(
      'does not hash to the documentFileHash of the command',
    );
    expect(await rowsOf(ready.documentId)).toEqual([]);
    expect(await mailsTo('buyer-b94@document.int.test')).toEqual([]);
  });
});
