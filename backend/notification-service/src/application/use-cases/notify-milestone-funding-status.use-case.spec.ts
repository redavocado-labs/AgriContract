import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  FUNDING_CONTRACT_ID,
  FUNDING_MILESTONE_ID,
  FUNDING_RECIPIENTS,
  fundingCommand,
} from '../../../test/support/commands';
import {
  MILESTONE_FUNDING_STATUS_TYPES,
  type MilestoneFundingStatusRequestedCommand,
  type NotificationRecipient,
} from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { NotificationSender } from '../notification-sender';
import {
  NotifyMilestoneFundingStatusUseCase,
  milestoneFundingBody,
} from './notify-milestone-funding-status.use-case';

function setup() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyMilestoneFundingStatusUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
  );
  return { logs, email, useCase };
}

const [BUYER, SELLER, OPERATOR] = FUNDING_RECIPIENTS;

const command = (
  patch: Partial<MilestoneFundingStatusRequestedCommand> = {},
): MilestoneFundingStatusRequestedCommand => ({
  ...fundingCommand(),
  ...patch,
});

const DETAILS = [
  `Contract:  ${FUNDING_CONTRACT_ID}`,
  `Milestone: ${FUNDING_MILESTONE_ID}`,
];
const DEADLINE =
  '2026-12-04 23:59:59 Vietnam time (2026-12-04T16:59:59.999Z UTC)';

describe('NotifyMilestoneFundingStatusUseCase', () => {
  it.each([
    [
      'FUNDING_REMINDER',
      'Funding reminder for a milestone of contract',
      COMMAND_KINDS.milestoneFundingReminder,
    ],
    [
      'FUNDING_FAILED',
      'Milestone funding failed for contract',
      COMMAND_KINDS.milestoneFundingFailed,
    ],
    [
      'FUNDING_CURE_DEADLINE',
      'Milestone funding deadline for contract',
      COMMAND_KINDS.milestoneFundingCureDeadline,
    ],
  ] as const)(
    'mails %s to every recipient with its own template and their user id',
    async (statusType, subject, kind) => {
      const { logs, email, useCase } = setup();
      const sent = command({ statusType });

      await useCase.execute(sent);

      expect(email.sent.map((m) => m.to)).toEqual(
        FUNDING_RECIPIENTS.map((r) => r.email),
      );
      for (const [i, recipient] of FUNDING_RECIPIENTS.entries()) {
        expect(email.sent[i].subject).toBe(
          `[AgriContract] ${subject} ${FUNDING_CONTRACT_ID}`,
        );
        expect(email.sent[i].body).toBe(milestoneFundingBody(sent, recipient));
        expect(
          logs.get({
            eventId: sent.eventId,
            recipientEmail: recipient.email,
            notificationType: kind.notificationType,
          }),
        ).toMatchObject({
          status: NotificationStatus.SENT,
          eventType: 'notification.milestone_funding_status_requested',
          templateVersion: kind.templateVersion,
          recipientUserId: recipient.userId,
        });
      }
    },
  );

  it('a redelivery of the same command sends nothing twice (dedup by eventId)', async () => {
    const { email, useCase } = setup();

    await useCase.execute(command());
    await useCase.execute(command());

    expect(email.sent).toHaveLength(3);
  });

  it('a failure for one recipient rethrows; the redelivery sends only what is missing', async () => {
    const { email, useCase } = setup();
    email.failFor.add(SELLER.email);

    await expect(useCase.execute(command())).rejects.toThrow();
    expect(email.sent.map((m) => m.to)).toEqual([BUYER.email]);

    email.failFor.clear();
    await useCase.execute(command());
    expect(email.sent.map((m) => m.to)).toEqual([
      BUYER.email,
      SELLER.email,
      OPERATOR.email,
    ]);
  });
});

describe('milestoneFundingBody', () => {
  const body = (
    patch: Partial<MilestoneFundingStatusRequestedCommand>,
    recipient: NotificationRecipient,
  ) => milestoneFundingBody(command(patch), recipient);

  it('milestone-funding-failed/v1 to the buyer: what to do before the cure window ends', () => {
    expect(body({}, BUYER)).toBe(
      [
        "Locking the buyer's money for a milestone failed after every retry.",
        'The milestone is not funded. Nothing has been taken from anyone, and',
        'nobody is in breach yet.',
        '',
        ...DETAILS,
        'Cure window ends:',
        DEADLINE,
        'Reason:',
        'Insufficient funds in the buyer account',
        '',
        'You can still fix this: make the money available and tell an',
        'AgriContract operator, who will retry the lock. If the milestone is',
        'still not funded when the cure window ends, the platform records a',
        'funding breach by you,',
        "unless the failure was on the bank's or the platform's side.",
        '',
      ].join('\n'),
    );
  });

  it('milestone-funding-failed/v1 to the seller: the delivery clock is paused', () => {
    expect(body({}, SELLER)).toContain(
      [
        'Your delivery clock for this milestone is paused: you do not have to',
        'prepare or deliver it until it is funded, and its delivery deadline',
        'moves by the business days the funding waited. If the buyer has not',
        'funded it when the cure window ends, you may terminate the rest of',
        "the contract for the buyer's breach, or keep waiting.",
      ].join('\n'),
    );
  });

  it('milestone-funding-failed/v1 to an operator: the retry, and that a bank fault is not the buyer’s', () => {
    expect(body({}, OPERATOR)).toContain(
      'An operator can retry the lock once the buyer confirms the money is',
    );
  });

  it('milestone-funding-reminder/v1 to the buyer: have the money ready', () => {
    expect(body({ statusType: 'FUNDING_REMINDER', reason: null }, BUYER)).toBe(
      [
        'This is a reminder to fund a milestone of your AgriContract contract.',
        '',
        ...DETAILS,
        'Fund it by:',
        DEADLINE,
        '',
        'The milestone amount will be locked in escrow from your account. Make',
        'sure the money is available so the lock succeeds: until it is locked,',
        'the seller does not have to prepare this milestone.',
        '',
      ].join('\n'),
    );
  });

  it('milestone-funding-cure-deadline/v1 to the seller: what they may do after the deadline', () => {
    expect(
      body({ statusType: 'FUNDING_CURE_DEADLINE', reason: null }, SELLER),
    ).toBe(
      [
        'A milestone of your AgriContract contract is still not funded, and the',
        'window to fix its funding has a deadline.',
        '',
        ...DETAILS,
        'Deadline:',
        DEADLINE,
        '',
        'If the buyer has not funded it by the deadline, you may terminate the',
        "rest of the contract for the buyer's breach, or keep waiting. Until",
        'it is funded you do not have to prepare this milestone.',
        '',
      ].join('\n'),
    );
  });

  it('a cure deadline without a deadline says none was given', () => {
    expect(
      body({ statusType: 'FUNDING_CURE_DEADLINE', deadline: null }, BUYER),
    ).toContain(`${DETAILS[1]}\nDeadline: not given with this notice\n`);
  });

  it.each(['FUNDING_REMINDER', 'FUNDING_FAILED'] as const)(
    '%s without a deadline leaves the deadline out',
    (statusType) => {
      const text = body({ statusType, deadline: null }, BUYER);

      expect(text).not.toContain('Vietnam time');
      expect(text).not.toContain('not given');
    },
  );

  it.each([null, '', '  \n '])(
    'a reason of %p leaves the Reason heading out',
    (reason) => {
      expect(body({ reason }, BUYER)).not.toContain('Reason:');
    },
  );

  it('wraps a long reason to 72 columns', () => {
    const reason = 'escrow could not lock the milestone amount '.repeat(8);
    const lines = body({ reason }, BUYER).split('\n');
    const start = lines.indexOf('Reason:') + 1;

    expect(lines[start].length).toBeLessThanOrEqual(72);
    expect(lines.slice(start, start + 5).join(' ')).toContain(
      'escrow could not lock the milestone amount escrow could not',
    );
  });

  it.each(
    MILESTONE_FUNDING_STATUS_TYPES.flatMap((statusType) =>
      FUNDING_RECIPIENTS.map((r) => [statusType, r.role, r] as const),
    ),
  )(
    '%s to %s keeps every line within 76 characters',
    (statusType, _role, r) => {
      for (const line of body({ statusType }, r).split('\n')) {
        expect(line.length).toBeLessThanOrEqual(76);
      }
    },
  );

  it.each(['ARBITRATOR', 'ADMIN'] as const)(
    'a %s recipient gets the operator’s text',
    (role) => {
      expect(body({}, { ...OPERATOR, role })).toBe(body({}, OPERATOR));
    },
  );
});
