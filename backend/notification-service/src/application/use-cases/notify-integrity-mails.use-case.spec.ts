import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  DIGEST_RECORD_HASH,
  FAILURE_SUMMARY,
  INTEGRITY_ADMIN,
  INTEGRITY_SOFTWARE_BUYERS,
  MISMATCH_CONTRACT_ID,
  MISMATCH_SUMMARY,
  SECURITY_LOCK_REASON,
  VERIFIER_EMAIL,
  VERIFIER_KEY_FINGERPRINT,
  auditDigestCommand,
  auditFailureCommand,
  reconciliationCommand,
  securityLockCommand,
  verifierKeyCommand,
} from '../../../test/support/commands';
import type { NotificationRecipient, RecipientRole } from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import {
  ClaimWaitAbortedError,
  NotificationDeliveryError,
  RecipientRoleRefusedError,
} from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import {
  NotifyAuditDigestUseCase,
  auditDigestBody,
} from './notify-audit-digest.use-case';
import {
  NotifyAuditFailureUseCase,
  auditFailureBody,
} from './notify-audit-failure.use-case';
import {
  NotifyReconciliationMismatchUseCase,
  reconciliationMismatchBody,
} from './notify-reconciliation-mismatch.use-case';
import {
  NotifySecurityLockChangedUseCase,
  securityLockChangedBody,
} from './notify-security-lock-changed.use-case';
import {
  NotifyVerifierKeyAnchorUseCase,
  verifierKeyAnchorBody,
} from './notify-verifier-key-anchor.use-case';
import { roleCounts, sendToEach } from './staff-recipients';

function setup() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const sender = new NotificationSender(logs, email, {
    logger: { error: () => undefined },
  });
  return {
    logs,
    email,
    digest: new NotifyAuditDigestUseCase(sender),
    failure: new NotifyAuditFailureUseCase(sender),
    lock: new NotifySecurityLockChangedUseCase(sender),
    mismatch: new NotifyReconciliationMismatchUseCase(sender),
    key: new NotifyVerifierKeyAnchorUseCase(sender),
  };
}

const recipient = (
  role: RecipientRole,
  email: string,
): NotificationRecipient => ({
  userId: null,
  email,
  role,
});

/** Every line within 76 characters, but the ones that quote a payload value. */
const fixedLinesFit = (body: string, quoted: string[]) => {
  for (const line of body.split('\n')) {
    if (quoted.some((q) => line.includes(q))) continue;
    expect(line.length).toBeLessThanOrEqual(76);
  }
};

describe('sendToEach', () => {
  const deliveryError = (step: 'email' | 'log') =>
    new NotificationDeliveryError('e1', step, 'X');

  it('tries every recipient after an email failure, then rethrows the first', async () => {
    const tried: string[] = [];
    const first = deliveryError('email');

    const result = sendToEach(['a', 'b', 'c'], (r) => {
      tried.push(r);
      if (r === 'a') return Promise.reject(first);
      if (r === 'c') return Promise.reject(deliveryError('email'));
      return Promise.resolve();
    });

    await expect(result).rejects.toBe(first);
    expect(tried).toEqual(['a', 'b', 'c']);
  });

  it.each([
    ['a log write failure', deliveryError('log')],
    ['a shutdown during a claim wait', new ClaimWaitAbortedError('e1')],
    ['anything else', new Error('boom')],
  ])('stops at once on %s: it is not about one address', async (_c, error) => {
    const tried: string[] = [];

    const result = sendToEach(['a', 'b'], (r) => {
      tried.push(r);
      return Promise.reject(error);
    });

    await expect(result).rejects.toBe(error);
    expect(tried).toEqual(['a']);
  });
});

describe('roleCounts', () => {
  it('counts the recipients of each role, in order of first appearance', () => {
    expect(
      roleCounts([
        recipient('SOFTWARE_BUYER', 'a@x.test'),
        recipient('ADMIN', 'b@x.test'),
        recipient('SOFTWARE_BUYER', 'c@x.test'),
      ]),
    ).toBe('2 SOFTWARE_BUYER, 1 ADMIN');
  });
});

describe('NotifyAuditDigestUseCase', () => {
  it('mails every Software Buyer contact audit-digest/v1, one row each', async () => {
    const { logs, email, digest } = setup();
    const command = auditDigestCommand();

    await digest.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual(
      INTEGRITY_SOFTWARE_BUYERS.map((r) => r.email),
    );
    for (const message of email.sent) {
      expect(message.subject).toBe(
        '[AgriContract] Weekly audit chain digest: check passed',
      );
      expect(message.body).toBe(auditDigestBody(command));
      expect(
        logs.get({
          eventId: command.eventId,
          recipientEmail: message.to,
          notificationType: 'AUDIT_DIGEST',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        eventType: 'notification.audit_digest_requested',
        templateVersion: COMMAND_KINDS.auditDigest.templateVersion,
        recipientUserId: null,
      });
    }
  });

  it('states the head hash on a line of its own, and what keeping it proves', () => {
    const body = auditDigestBody(auditDigestCommand());

    expect(body.split('\n')).toContain(DIGEST_RECORD_HASH);
    expect(body).toContain('passed');
    expect(body).toContain(
      "missing from the platform's audit chain, records written before this",
    );
    fixedLinesFit(body, [DIGEST_RECORD_HASH]);
  });

  it.each(['ADMIN', 'BUYER', 'SELLER', 'OPERATOR'] as const)(
    'refuses a %s recipient before mailing anyone',
    async (role) => {
      const { email, digest } = setup();
      const command = auditDigestCommand();
      command.recipients.push(recipient(role, 'other@x.test'));

      const result = digest.execute(command);

      await expect(result).rejects.toBeInstanceOf(RecipientRoleRefusedError);
      await expect(result).rejects.toThrow(
        `recipients[2] has role ${role}, which notification.audit_digest_requested may not reach (only SOFTWARE_BUYER)`,
      );
      expect(email.sent).toEqual([]);
    },
  );

  it('never quotes the refused address', async () => {
    const { digest } = setup();
    const command = auditDigestCommand();
    command.recipients = [recipient('BUYER', 'buyer@secret.test')];

    await expect(digest.execute(command)).rejects.not.toThrow('secret');
  });
});

describe('NotifyAuditFailureUseCase', () => {
  const ALL = [INTEGRITY_ADMIN, ...INTEGRITY_SOFTWARE_BUYERS];

  it('mails the Admin and every Software Buyer contact audit-failure/v1, one row each', async () => {
    const { logs, email, failure } = setup();
    const command = auditFailureCommand();

    await failure.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual(ALL.map((r) => r.email));
    for (const [i, r] of ALL.entries()) {
      expect(email.sent[i].subject).toBe(
        '[AgriContract] ALERT: audit chain check failed',
      );
      expect(email.sent[i].body).toBe(auditFailureBody(command));
      expect(
        logs.get({
          eventId: command.eventId,
          recipientEmail: r.email,
          notificationType: 'AUDIT_FAILURE',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        templateVersion: 'audit-failure/v1',
        recipientUserId: r.userId,
      });
    }
  });

  it('still reaches every other contact when one address is refused, then fails for a retry', async () => {
    const { logs, email, failure } = setup();
    const command = auditFailureCommand();
    email.failFor.add(INTEGRITY_ADMIN.email);

    await expect(failure.execute(command)).rejects.toBeInstanceOf(
      NotificationDeliveryError,
    );
    expect(email.sent.map((m) => m.to)).toEqual(
      INTEGRITY_SOFTWARE_BUYERS.map((r) => r.email),
    );

    email.failFor.clear();
    await failure.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual([
      ...INTEGRITY_SOFTWARE_BUYERS.map((r) => r.email),
      INTEGRITY_ADMIN.email,
    ]);
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: INTEGRITY_ADMIN.email,
        notificationType: 'AUDIT_FAILURE',
      }),
    ).toMatchObject({ status: NotificationStatus.SENT });
  });

  it('shows the whole summary, who else got it, and that the parties are not told', () => {
    const body = auditFailureBody(auditFailureCommand());

    expect(body).toContain(JSON.stringify(FAILURE_SUMMARY, null, 2));
    expect(body).toContain('contact at once: 1 ADMIN, 2 SOFTWARE_BUYER.');
    expect(body).toContain('Buyers and sellers are not told by this alert.');
    fixedLinesFit(body, ['"', '{', '}']);
  });

  it.each(['BUYER', 'SELLER', 'ARBITRATOR', 'INSPECTOR'] as const)(
    'refuses a %s recipient before mailing anyone',
    async (role) => {
      const { email, failure } = setup();
      const command = auditFailureCommand();
      command.recipients.splice(1, 0, recipient(role, 'party@x.test'));

      await expect(failure.execute(command)).rejects.toThrow(
        `recipients[1] has role ${role}, which notification.audit_failure_requested may not reach (only ADMIN, SOFTWARE_BUYER)`,
      );
      expect(email.sent).toEqual([]);
    },
  );
});

describe('NotifySecurityLockChangedUseCase', () => {
  it('mails the Admin and the External Verifier security-lock-changed/v1', async () => {
    const { logs, email, lock } = setup();
    const command = securityLockCommand();

    await lock.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual([
      INTEGRITY_ADMIN.email,
      VERIFIER_EMAIL,
    ]);
    for (const message of email.sent) {
      expect(message.subject).toBe(
        '[AgriContract] Security lock applied: all money movements stopped',
      );
      expect(message.body).toBe(securityLockChangedBody(command));
      expect(
        logs.get({
          eventId: command.eventId,
          recipientEmail: message.to,
          notificationType: 'SECURITY_LOCK_CHANGED',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        templateVersion: 'security-lock-changed/v1',
      });
    }
  });

  it('words a lock: when, in Vietnam time and UTC, what stops, who lifts it, the reason', () => {
    const body = securityLockChangedBody(securityLockCommand());

    expect(body).toContain(
      "The External Verifier's signed emergency lock was applied at:\n" +
        '2026-11-30 02:29:58 Vietnam time (2026-11-29T19:29:58.250Z UTC)',
    );
    expect(body).toContain('no money moves on the');
    expect(body).toContain('the platform administrator cannot.');
    expect(body).toContain(
      `Reason given with the lock: ${SECURITY_LOCK_REASON}`,
    );
    expect(body).toContain('Buyers and sellers are not told by it.');
    fixedLinesFit(body, [SECURITY_LOCK_REASON, 'Vietnam time']);
  });

  it('words an unlock with its own subject and text', async () => {
    const { email, lock } = setup();
    const command = { ...securityLockCommand(), action: 'UNLOCKED' as const };

    await lock.execute(command);

    expect(email.sent[0].subject).toBe(
      '[AgriContract] Security lock lifted: money movements resumed',
    );
    const body = email.sent[0].body;
    expect(body).toContain(
      "The External Verifier's signed unlock was applied at:",
    );
    expect(body).toContain('stays refused');
    expect(body).toContain(
      `Reason given with the unlock: ${SECURITY_LOCK_REASON}`,
    );
    expect(body).not.toContain('emergency lock was applied');
    fixedLinesFit(body, [SECURITY_LOCK_REASON, 'Vietnam time']);
  });

  it('says so when no reason was given', () => {
    const body = securityLockChangedBody({
      ...securityLockCommand(),
      reason: '  ',
    });

    expect(body).toContain('Reason given with the lock: (none given)');
  });

  it.each(['BUYER', 'SELLER', 'OPERATOR'] as const)(
    'refuses a %s recipient before mailing anyone',
    async (role) => {
      const { email, lock } = setup();
      const command = securityLockCommand();
      command.recipients.push(recipient(role, 'party@x.test'));

      await expect(lock.execute(command)).rejects.toBeInstanceOf(
        RecipientRoleRefusedError,
      );
      expect(email.sent).toEqual([]);
    },
  );
});

describe('NotifyReconciliationMismatchUseCase', () => {
  it('mails the Admin reconciliation-mismatch/v1 naming the contract', async () => {
    const { logs, email, mismatch } = setup();
    const command = reconciliationCommand();

    await mismatch.execute(command);

    expect(email.sent).toHaveLength(1);
    expect(email.sent[0].to).toBe(INTEGRITY_ADMIN.email);
    expect(email.sent[0].subject).toBe(
      `[AgriContract] Ledger does not match the audit record: contract ${MISMATCH_CONTRACT_ID}`,
    );
    expect(email.sent[0].body).toBe(reconciliationMismatchBody(command));
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: INTEGRITY_ADMIN.email,
        notificationType: 'RECONCILIATION_MISMATCH',
      }),
    ).toMatchObject({
      status: NotificationStatus.SENT,
      templateVersion: 'reconciliation-mismatch/v1',
      recipientUserId: INTEGRITY_ADMIN.userId,
    });
  });

  it('shows the whole summary and that nothing was corrected', () => {
    const body = reconciliationMismatchBody(reconciliationCommand());

    expect(body).toContain(JSON.stringify(MISMATCH_SUMMARY, null, 2));
    expect(body).toContain('Nothing was corrected automatically.');
    fixedLinesFit(body, ['"', '{', '}']);
  });

  it.each(['SOFTWARE_BUYER', 'BUYER', 'SELLER'] as const)(
    'refuses a %s recipient: Admin only',
    async (role) => {
      const { email, mismatch } = setup();
      const command = reconciliationCommand();
      command.recipients.push(recipient(role, 'other@x.test'));

      await expect(mismatch.execute(command)).rejects.toThrow('(only ADMIN)');
      expect(email.sent).toEqual([]);
    },
  );
});

describe('NotifyVerifierKeyAnchorUseCase', () => {
  it('mails the External Verifier verifier-key-anchor/v1 with the fingerprint', async () => {
    const { logs, email, key } = setup();
    const command = verifierKeyCommand();

    await key.execute(command);

    expect(email.sent).toHaveLength(1);
    expect(email.sent[0].to).toBe(VERIFIER_EMAIL);
    expect(email.sent[0].subject).toBe(
      '[AgriContract] External Verifier public key registered',
    );
    expect(email.sent[0].body).toBe(verifierKeyAnchorBody(command));
    expect(email.sent[0].body.split('\n')).toContain(VERIFIER_KEY_FINGERPRINT);
    fixedLinesFit(email.sent[0].body, [VERIFIER_KEY_FINGERPRINT]);
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: VERIFIER_EMAIL,
        notificationType: 'VERIFIER_KEY_ANCHOR',
      }),
    ).toMatchObject({
      status: NotificationStatus.SENT,
      templateVersion: 'verifier-key-anchor/v1',
      recipientUserId: null,
    });
  });

  it('sends nothing twice on a redelivery', async () => {
    const { email, key } = setup();

    await key.execute(verifierKeyCommand());
    await key.execute(verifierKeyCommand());

    expect(email.sent).toHaveLength(1);
  });
});
