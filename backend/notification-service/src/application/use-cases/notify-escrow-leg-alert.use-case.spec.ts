import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  LEG_ALERT_BANK_COMMAND_ID,
  LEG_ALERT_CONTRACT_ID,
  LEG_ALERT_LEG_ID,
  OPERATOR_EMAIL,
  legAlertCommand,
} from '../../../test/support/commands';
import {
  ESCROW_LEG_ALERT_STAGES,
  ESCROW_LEG_STATUSES,
  type EscrowLegAlertRequestedCommand,
} from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OperatorEmailNotConfiguredError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import {
  NotifyEscrowLegAlertUseCase,
  escrowLegAlertBody,
} from './notify-escrow-leg-alert.use-case';

function setup(operatorEmail: string | null = OPERATOR_EMAIL) {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyEscrowLegAlertUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
    operatorEmail,
  );
  return { logs, email, useCase };
}

/** The same leg, later: a new command, so a new eventId. */
const RESOLVED: EscrowLegAlertRequestedCommand = {
  ...legAlertCommand(),
  eventId: '5a6b7c8d-9e0f-4a1b-8c2d-4e5f6a7b8c9d',
  stage: 'RESOLVED',
  legStatus: 'LOCKED',
};
const RETURN_EXHAUSTED: EscrowLegAlertRequestedCommand = {
  ...legAlertCommand(),
  eventId: '6b7c8d9e-0f1a-4b2c-9d3e-5f6a7b8c9d0e',
  stage: 'RETURN_EXHAUSTED',
  legStatus: 'RETURN_FAILED',
  party: 'SELLER',
  since: '2026-11-21T09:30:00Z',
};

describe('NotifyEscrowLegAlertUseCase', () => {
  it.each([
    [
      'STUCK',
      legAlertCommand(),
      'An escrow deposit is waiting on the bank too long',
      COMMAND_KINDS.escrowLegStuck,
    ],
    [
      'RESOLVED',
      RESOLVED,
      'A stuck escrow deposit has its bank result',
      COMMAND_KINDS.escrowLegResolved,
    ],
    [
      'RETURN_EXHAUSTED',
      RETURN_EXHAUSTED,
      'An escrow deposit return failed with no retry left',
      COMMAND_KINDS.escrowLegReturnExhausted,
    ],
  ])(
    'mails %s to the operator address with its own template',
    async (_stage, command, subject, kind) => {
      const { logs, email, useCase } = setup();

      await useCase.execute(command);

      expect(email.sent.map((m) => m.to)).toEqual([OPERATOR_EMAIL]);
      expect(email.sent[0].subject).toBe(`[AgriContract] ${subject}`);
      expect(
        logs.get({
          eventId: command.eventId,
          recipientEmail: OPERATOR_EMAIL,
          notificationType: kind.notificationType,
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        eventType: 'notification.escrow_leg_alert_requested',
        notificationType: kind.notificationType,
        templateVersion: kind.templateVersion,
        // An operator address, not a platform account.
        recipientUserId: null,
      });
    },
  );

  it('STUCK then RESOLVED of one leg are two emails', async () => {
    const { email, useCase } = setup();

    await useCase.execute(legAlertCommand());
    await useCase.execute(RESOLVED);

    expect(email.sent).toHaveLength(2);
  });

  it('a redelivery of the same command sends nothing twice (dedup by eventId)', async () => {
    const { email, useCase } = setup();

    await useCase.execute(legAlertCommand());
    await useCase.execute(legAlertCommand());

    expect(email.sent).toHaveLength(1);
  });

  it('a failure rethrows and the redelivery sends it', async () => {
    const { email, useCase } = setup();
    email.failFor.add(OPERATOR_EMAIL);

    await expect(useCase.execute(legAlertCommand())).rejects.toThrow();
    expect(email.sent).toHaveLength(0);

    email.failFor.clear();
    await useCase.execute(legAlertCommand());
    expect(email.sent).toHaveLength(1);
  });

  it('without an operator address, fails naming OPERATOR_EMAIL and records nothing', async () => {
    const { logs, email, useCase } = setup(null);
    const command = legAlertCommand();

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

describe('escrowLegAlertBody', () => {
  const GREP = [
    'Search the bank-service log for the bank command id:',
    '- a bank_command_replay_mismatch line: the bank got another command',
    '  with this id and different content, and will not answer this one;',
    '- a ledger line for it: the bank applied it, and its result was lost',
    '  on the way back to escrow-service;',
    '- nothing: the command never reached the bank; check the escrow-service',
    '  outbox and the dead-letter queues.',
  ];

  it('STUCK (escrow-leg-stuck/v1): every detail of the leg and how to trace the bank command', () => {
    expect(escrowLegAlertBody(legAlertCommand())).toBe(
      [
        'An escrow deposit leg has waited on the bank longer than expected.',
        'escrow-service sent the bank a command for it and has no result yet.',
        'The leg stays as it is until the result arrives; you will get one',
        'more email when it does.',
        '',
        `Contract:     ${LEG_ALERT_CONTRACT_ID}`,
        `Leg:          ${LEG_ALERT_LEG_ID}`,
        'Leg kind:     DEPOSIT',
        'Party:        BUYER',
        'Status:       REQUESTED (lock sent to the bank, no result yet)',
        'Amount:       150,000,000.00 VND',
        `Bank command: ${LEG_ALERT_BANK_COMMAND_ID}`,
        'Waiting since:',
        '2026-11-21 10:00:00 Vietnam time (2026-11-21T03:00:00.000Z UTC)',
        '',
        ...GREP,
        '',
      ].join('\n'),
    );
  });

  it('RESOLVED (escrow-leg-resolved/v1): the leg as it is now, no action', () => {
    expect(escrowLegAlertBody(RESOLVED)).toBe(
      [
        'An escrow deposit leg that was reported stuck has now received its',
        'bank result. Nothing more is needed from you for this leg.',
        '',
        `Contract:     ${LEG_ALERT_CONTRACT_ID}`,
        `Leg:          ${LEG_ALERT_LEG_ID}`,
        'Leg kind:     DEPOSIT',
        'Party:        BUYER',
        'Status:       LOCKED (locked by the bank)',
        'Amount:       150,000,000.00 VND',
        `Bank command: ${LEG_ALERT_BANK_COMMAND_ID}`,
        'Waiting since:',
        '2026-11-21 10:00:00 Vietnam time (2026-11-21T03:00:00.000Z UTC)',
        '',
      ].join('\n'),
    );
  });

  it('RETURN_EXHAUSTED (escrow-leg-return-exhausted/v1): not returned, since is the last failed return', () => {
    expect(escrowLegAlertBody(RETURN_EXHAUSTED)).toBe(
      [
        'Returning an escrow deposit to its owner failed, and no resend is',
        'left: escrow-service will not try this return again on its own.',
        'The deposit has not been returned.',
        '',
        `Contract:     ${LEG_ALERT_CONTRACT_ID}`,
        `Leg:          ${LEG_ALERT_LEG_ID}`,
        'Leg kind:     DEPOSIT',
        'Party:        SELLER',
        'Status:       RETURN_FAILED (return failed)',
        'Amount:       150,000,000.00 VND',
        `Bank command: ${LEG_ALERT_BANK_COMMAND_ID}`,
        'Last failed return:',
        '2026-11-21 16:30:00 Vietnam time (2026-11-21T09:30:00.000Z UTC)',
        '',
        'Search the bank-service log for the bank command id to see why the',
        'return failed.',
        '',
      ].join('\n'),
    );
  });

  it.each([
    ['0.00', '0.00 VND'],
    ['999.50', '999.50 VND'],
    ['1000.00', '1,000.00 VND'],
    ['9999999999999999.99', '9,999,999,999,999,999.99 VND'],
  ])('shows amount %s as %s, digit for digit', (amount, shown) => {
    expect(escrowLegAlertBody({ ...legAlertCommand(), amount })).toContain(
      `Amount:       ${shown}\n`,
    );
  });

  it.each(ESCROW_LEG_ALERT_STAGES)(
    'keeps every line of %s within 76 characters, whatever the status',
    (stage) => {
      for (const legStatus of ESCROW_LEG_STATUSES) {
        const body = escrowLegAlertBody({
          ...legAlertCommand(),
          stage,
          legStatus,
          amount: '9999999999999999.99',
        });
        for (const line of body.split('\n')) {
          expect(line.length).toBeLessThanOrEqual(76);
        }
      }
    },
  );
});
