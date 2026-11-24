import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  OPERATOR_EMAIL,
  REMEDY_CONTRACT_ID,
  REMEDY_DECISION_ID,
  REMEDY_REJECTED_REASON,
  remedyRejectedCommand,
} from '../../../test/support/commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import {
  NotifyEscrowRemedyRejectedUseCase,
  escrowRemedyRejectedBody,
} from './notify-escrow-remedy-rejected.use-case';

function setup(operatorEmail: string | null = OPERATOR_EMAIL) {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyEscrowRemedyRejectedUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
    operatorEmail,
  );
  return { logs, email, useCase };
}

describe('NotifyEscrowRemedyRejectedUseCase', () => {
  it('mails the operator address with its own template, no recipient id', async () => {
    const { logs, email, useCase } = setup();
    const command = remedyRejectedCommand();

    await useCase.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual([OPERATOR_EMAIL]);
    expect(email.sent[0].subject).toBe(
      '[AgriContract] Escrow rejected a remedy decision',
    );
    expect(email.sent[0].body).toBe(escrowRemedyRejectedBody(command));
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: OPERATOR_EMAIL,
        notificationType: 'ESCROW_REMEDY_REJECTED',
      }),
    ).toMatchObject({
      status: NotificationStatus.SENT,
      eventType: 'notification.escrow_remedy_rejected_requested',
      notificationType: COMMAND_KINDS.escrowRemedyRejected.notificationType,
      templateVersion: 'escrow-remedy-rejected/v1',
      // An operator address, not a platform account.
      recipientUserId: null,
    });
  });

  it('a redelivery of the same command sends nothing twice (dedup by eventId)', async () => {
    const { email, useCase } = setup();

    await useCase.execute(remedyRejectedCommand());
    await useCase.execute(remedyRejectedCommand());

    expect(email.sent).toHaveLength(1);
  });

  it('a failure rethrows and the redelivery sends it', async () => {
    const { email, useCase } = setup();
    email.failFor.add(OPERATOR_EMAIL);

    await expect(useCase.execute(remedyRejectedCommand())).rejects.toThrow();
    expect(email.sent).toHaveLength(0);

    email.failFor.clear();
    await useCase.execute(remedyRejectedCommand());
    expect(email.sent).toHaveLength(1);
  });

  it('without an operator address, fails naming OPERATOR_EMAIL and records nothing', async () => {
    const { logs, email, useCase } = setup(null);
    const command = remedyRejectedCommand();

    const failure = useCase.execute(command);

    await expect(failure).rejects.toBeInstanceOf(
      OperatorEmailNotConfiguredError,
    );
    await expect(failure).rejects.toThrow(
      `Event ${command.eventId}: OPERATOR_EMAIL is not set`,
    );
    expect(email.sent).toEqual([]);
    expect(logs.size).toBe(0);
  });
});

describe('escrowRemedyRejectedBody', () => {
  it('escrow-remedy-rejected/v1: the decision, the reason, and that no platform action fixes it', () => {
    expect(escrowRemedyRejectedBody(remedyRejectedCommand())).toBe(
      [
        'escrow-service rejected a whole remedy decision: its transfers add up',
        'to more than the money still locked for the contract, so no bank',
        'command was sent for any of them. Normal operation never gets here: it',
        'points to a calculation or data error.',
        '',
        `Contract:     ${REMEDY_CONTRACT_ID}`,
        `Decision:     ${REMEDY_DECISION_ID}`,
        'Rejected at:',
        '2026-11-24 15:00:00 Vietnam time (2026-11-24T08:00:00.000Z UTC)',
        'Reason given by escrow-service:',
        'Remedy legs total 160000000.00 but only 150000000.00 is still locked for',
        'the contract',
        '',
        'There is no platform action for this: resending does not help, and the',
        'contract stays ACTIVE. It needs a developer to find the error.',
        '',
      ].join('\n'),
    );
    expect(REMEDY_REJECTED_REASON.length).toBeGreaterThan(72);
  });

  it('keeps every line within 76 characters for a 500-character reason of words', () => {
    const reason = 'legs exceed the lock '.repeat(24).slice(0, 500);
    const body = escrowRemedyRejectedBody({
      ...remedyRejectedCommand(),
      reason,
    });

    for (const line of body.split('\n')) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
    expect(body).toContain(reason.split(' ').slice(0, 5).join(' '));
  });
});
