import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  OPERATOR_EMAIL,
  REMEDY_BANK_COMMAND_ID,
  REMEDY_CONTRACT_ID,
  REMEDY_DECISION_ID,
  REMEDY_LEG_ID,
  remedyLegAlertCommand,
} from '../../../test/support/commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import {
  NotifyEscrowRemedyLegAlertUseCase,
  escrowRemedyLegAlertBody,
} from './notify-escrow-remedy-leg-alert.use-case';

function setup(operatorEmail: string | null = OPERATOR_EMAIL) {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyEscrowRemedyLegAlertUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
    operatorEmail,
  );
  return { logs, email, useCase };
}

describe('NotifyEscrowRemedyLegAlertUseCase', () => {
  it('mails the operator address with its own template, no recipient id', async () => {
    const { logs, email, useCase } = setup();
    const command = remedyLegAlertCommand();

    await useCase.execute(command);

    expect(email.sent.map((m) => m.to)).toEqual([OPERATOR_EMAIL]);
    expect(email.sent[0].subject).toBe(
      '[AgriContract] A remedy payment was refused by the bank, no retry left',
    );
    expect(email.sent[0].body).toBe(escrowRemedyLegAlertBody(command));
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: OPERATOR_EMAIL,
        notificationType: 'ESCROW_REMEDY_LEG_FAILED',
      }),
    ).toMatchObject({
      status: NotificationStatus.SENT,
      eventType: 'notification.escrow_remedy_leg_alert_requested',
      notificationType: COMMAND_KINDS.escrowRemedyLegFailed.notificationType,
      templateVersion: 'escrow-remedy-leg-failed/v1',
      // An operator address, not a platform account.
      recipientUserId: null,
    });
  });

  it('a redelivery of the same command sends nothing twice (dedup by eventId)', async () => {
    const { email, useCase } = setup();

    await useCase.execute(remedyLegAlertCommand());
    await useCase.execute(remedyLegAlertCommand());

    expect(email.sent).toHaveLength(1);
  });

  it('another leg of the same decision is its own command, so its own email', async () => {
    const { email, useCase } = setup();

    await useCase.execute(remedyLegAlertCommand());
    await useCase.execute({
      ...remedyLegAlertCommand(),
      eventId: '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f',
      remedyLegId: '1d2e3f4a-5b6c-4d7e-9f8a-0b1c2d3e4f5a',
      party: 'BUYER',
    });

    expect(email.sent).toHaveLength(2);
  });

  it('a failure rethrows and the redelivery sends it', async () => {
    const { email, useCase } = setup();
    email.failFor.add(OPERATOR_EMAIL);

    await expect(useCase.execute(remedyLegAlertCommand())).rejects.toThrow();
    expect(email.sent).toHaveLength(0);

    email.failFor.clear();
    await useCase.execute(remedyLegAlertCommand());
    expect(email.sent).toHaveLength(1);
  });

  it('without an operator address, fails naming OPERATOR_EMAIL and records nothing', async () => {
    const { logs, email, useCase } = setup(null);
    const command = remedyLegAlertCommand();

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

describe('escrowRemedyLegAlertBody', () => {
  it('escrow-remedy-leg-failed/v1: the leg, the bank command to grep for, and the resend', () => {
    expect(escrowRemedyLegAlertBody(remedyLegAlertCommand())).toBe(
      [
        'The bank refused a money transfer of a finalized remedy decision, and',
        'escrow-service has no resend left for it. The contract stays ACTIVE:',
        'it cannot settle or terminate until this transfer completes.',
        '',
        `Contract:     ${REMEDY_CONTRACT_ID}`,
        `Decision:     ${REMEDY_DECISION_ID}`,
        `Remedy leg:   ${REMEDY_LEG_ID}`,
        'Payee:        SELLER',
        'Amount:       45,000,000.00 VND',
        `Bank command: ${REMEDY_BANK_COMMAND_ID}`,
        'Failed since:',
        '2026-11-24 15:14:59 Vietnam time (2026-11-24T08:14:59.000Z UTC)',
        '',
        'Search the bank-service log for the bank command id to see why the bank',
        'refused it (kill switch on, bank error).',
        '',
        'Once the cause is fixed, press resend for this contract:',
        'POST /api/v1/contracts/{contractId}/remedy/retry-legs',
        'escrow-service then resends every failed transfer of the contract with',
        'its original bank command id, so the bank never books one twice.',
        'Transfers already completed are not sent again. The amount and the payee',
        'cannot be changed here: a different payment needs a new decision.',
        '',
      ].join('\n'),
    );
  });

  it.each([
    ['0.00', '0.00 VND'],
    ['999.99', '999.99 VND'],
    ['1000.00', '1,000.00 VND'],
    ['9999999999999999.99', '9,999,999,999,999,999.99 VND'],
  ])('groups the amount %s as text: %s', (amount, shown) => {
    expect(
      escrowRemedyLegAlertBody({ ...remedyLegAlertCommand(), amount }),
    ).toContain(`Amount:       ${shown}\n`);
  });

  it('keeps every line within 76 characters, the largest amount included', () => {
    const body = escrowRemedyLegAlertBody({
      ...remedyLegAlertCommand(),
      amount: '9999999999999999.99',
    });

    for (const line of body.split('\n')) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
  });
});
