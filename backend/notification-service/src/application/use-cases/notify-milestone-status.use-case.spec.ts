import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  ANCHOR_RECIPIENTS,
  DISPUTE_CONTRACT_ID,
  DISPUTE_MILESTONE_ID,
  milestoneStatusCommand,
} from '../../../test/support/commands';
import {
  MILESTONE_STATUS_TYPES,
  RECIPIENT_ROLES,
  type MilestoneStatusRequestedCommand,
  type NotificationRecipient,
} from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { NotificationSender } from '../notification-sender';
import {
  NotifyMilestoneStatusUseCase,
  milestoneStatusText,
} from './notify-milestone-status.use-case';

const [BUYER, SELLER] = ANCHOR_RECIPIENTS;

function setup() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyMilestoneStatusUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
  );
  return { logs, email, useCase };
}

const command = (
  patch: Partial<MilestoneStatusRequestedCommand> = {},
): MilestoneStatusRequestedCommand => ({
  ...milestoneStatusCommand(),
  ...patch,
});

const IDS = [
  `Contract:        ${DISPUTE_CONTRACT_ID}`,
  `Milestone:       ${DISPUTE_MILESTONE_ID}`,
  'Number:          2',
];

describe('NotifyMilestoneStatusUseCase', () => {
  it.each(MILESTONE_STATUS_TYPES.map((statusType) => [statusType] as const))(
    'mails %s with its own kind to every recipient, with their user id',
    async (statusType) => {
      const { logs, email, useCase } = setup();
      const sent = command({ statusType, recipients: [BUYER, SELLER] });

      await useCase.execute(sent);

      const kind = Object.values(COMMAND_KINDS).find(
        (k) => k.notificationType === `MILESTONE_${statusType}`,
      );
      expect(kind?.eventType).toBe('notification.milestone_status_requested');
      expect(email.sent.map((m) => m.to)).toEqual([BUYER.email, SELLER.email]);
      for (const recipient of [BUYER, SELLER]) {
        expect(
          logs.get({
            eventId: sent.eventId,
            recipientEmail: recipient.email,
            notificationType: kind!.notificationType,
          }),
        ).toMatchObject({
          status: NotificationStatus.SENT,
          templateVersion: kind!.templateVersion,
          recipientUserId: recipient.userId,
        });
      }
    },
  );

  it('names the milestone number in the subject when statusData has it', async () => {
    const { email, useCase } = setup();

    await useCase.execute(command());
    await useCase.execute(
      command({
        eventId: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
        statusData: null,
      }),
    );

    expect(email.sent.map((m) => m.subject)).toEqual([
      `[AgriContract] Milestone 2 of contract ${DISPUTE_CONTRACT_ID}: weighed by the seller`,
      `[AgriContract] A milestone of contract ${DISPUTE_CONTRACT_ID}: weighed by the seller`,
    ]);
  });

  it('a redelivery sends nothing twice; a failure is retried for whoever is missing', async () => {
    const { email, useCase } = setup();
    const both = command({ recipients: [BUYER, SELLER] });
    email.failFor.add(SELLER.email);

    await expect(useCase.execute(both)).rejects.toThrow();
    email.failFor.clear();
    await useCase.execute(both);
    await useCase.execute(both);

    expect(email.sent.map((m) => m.to)).toEqual([BUYER.email, SELLER.email]);
  });
});

describe('milestoneStatusText', () => {
  const text = (
    patch: Partial<MilestoneStatusRequestedCommand>,
    recipient: NotificationRecipient = BUYER,
  ) => milestoneStatusText(command(patch), recipient);

  it('milestone-weighed/v1 to the buyer: the declared weight and what to do', () => {
    expect(text({})).toBe(
      [
        'The seller has weighed a milestone of your AgriContract contract and',
        'declared its weight.',
        '',
        ...IDS,
        'Declared weight: 1195.5 kg',
        'Declaration:     number 1',
        'Committed:       1200 kg',
        '',
        'Receive the goods, weigh them and record your weight on',
        'AgriContract. Then, within your confirm window, either confirm the',
        'milestone or flag an issue with its quantity or quality.',
        'The seller may correct the declaration within one hour of the first',
        'one, and only until you record your weight; you get an email for',
        'each correction.',
        '',
      ].join('\n'),
    );
  });

  it('milestone-weighed/v1 states both D75 limits on a correction: one hour from the first declaration, and only until the buyer records', () => {
    const body = text({});

    expect(body).toContain(
      'The seller may correct the declaration within one hour of the first\none, and only until you record your weight',
    );
    expect(body).not.toContain(
      'Until you\nrecord your weight the seller may correct',
    );
  });

  it('milestone-confirmed/v1: how it settled and the amounts', () => {
    expect(
      text(
        {
          statusType: 'CONFIRMED',
          statusData: {
            milestoneIndex: 2,
            committedQuantityKg: '1200',
            settlementPath: 'SELLER_SILENT_QUANTITY',
            billableQuantityKg: '1150',
            sellerAmount: '105800000.00',
            buyerRefundAmount: '4600000.00',
          },
        },
        SELLER,
      ),
    ).toBe(
      [
        'A milestone of your AgriContract contract is settled.',
        "The seller did not answer the buyer's quantity flag in time, so the",
        "milestone settled on the buyer's weight, with no penalty.",
        '',
        ...IDS,
        'Billable weight: 1150 kg',
        'To the seller:   105,800,000.00 VND',
        'To the buyer:    4,600,000.00 VND',
        '',
        'escrow-service pays these amounts out of the milestone payment',
        'locked for it: to the seller for the goods, and back to the buyer',
        'for the rest.',
        '',
      ].join('\n'),
    );
  });

  it.each([
    ['CLEAN_CONFIRMED', 'The buyer confirmed the receipt.'],
    ['CLEAN_AUTO_CONFIRMED', 'milestone was confirmed automatically.'],
    [
      'INSPECTED_CONFORMING',
      'The Level 1.5 inspection found the goods conforming.',
    ],
    ['DISPUTE_RESOLVED', 'It was settled by the decision on its dispute.'],
  ])('milestone-confirmed/v1 says how %s settled', (settlementPath, line) => {
    expect(
      text({ statusType: 'CONFIRMED', statusData: { settlementPath } }),
    ).toContain(line);
  });

  it('milestone-flagged/v1 to the seller: the deadline and what silence means for QUANTITY', () => {
    expect(
      text(
        {
          statusType: 'FLAGGED',
          deadline: '2026-12-04T16:59:59.999Z',
          statusData: {
            milestoneIndex: 2,
            committedQuantityKg: '1200',
            issueType: 'QUANTITY',
          },
        },
        SELLER,
      ),
    ).toBe(
      [
        'The buyer flagged a milestone of your AgriContract contract:',
        'they dispute its weight.',
        '',
        ...IDS,
        'Committed:       1200 kg',
        'Respond by:',
        '2026-12-04 23:59:59 Vietnam time (2026-12-04T16:59:59.999Z UTC)',
        '',
        'Before the deadline, accept the flag or contest it. Contesting opens',
        'a breach case and sends the milestone to a Level 1.5 inspection.',
        'If the seller does not answer in time, the milestone settles on',
        "the buyer's weight, with no penalty.",
        '',
      ].join('\n'),
    );
  });

  it('milestone-flagged/v1 for QUALITY: silence is contested by the platform', () => {
    expect(
      text(
        { statusType: 'FLAGGED', statusData: { issueType: 'QUALITY' } },
        SELLER,
      ),
    ).toContain(
      'If the seller does not answer in time, the platform contests the\nflag for the seller and the milestone goes to inspection.',
    );
  });

  it('milestone-buyer-receive-reminder/v1: nothing settles on the seller weight alone', () => {
    expect(text({ statusType: 'BUYER_RECEIVE_REMINDER' })).toContain(
      "AgriContract. Nothing is settled on the seller's weight alone: if",
    );
  });

  it.each([
    'FORCE_MAJEURE',
    'LEVEL2_OPTOUT_REQUESTED',
    'PENALTY',
    'PROVISIONAL_LEVEL2',
  ] as const)(
    '%s, with no statusData defined yet, says what changed and points to the contract',
    (statusType) => {
      const body = text({
        statusType,
        statusData: null,
        deadline: '2026-12-04T16:59:59.999Z',
      });

      expect(body).toContain(
        'The status of a milestone of your AgriContract contract changed.',
      );
      expect(body).toContain('Deadline:\n2026-12-04 23:59:59 Vietnam time');
      expect(body).toContain(
        'Open the contract on AgriContract for the details and what you can do.',
      );
    },
  );

  it.each([
    [
      'a weight as a number',
      { sellerDeclaredWeightKg: 1195.5 },
      'Declared weight: 1195.5 kg',
    ],
    ['a weight in words', { sellerDeclaredWeightKg: 'about a ton' }, null],
    ['a negative weight', { sellerDeclaredWeightKg: '-5' }, null],
    ['a declaration of 0', { declarationNumber: 0 }, null],
    ['an index as text', { milestoneIndex: '2' }, null],
  ])('shows %s only when it has the expected shape', (_case, data, line) => {
    const body = text({ statusData: data });

    if (line === null) {
      expect(body).not.toMatch(/Declared weight|Declaration:|Number:/);
    } else {
      expect(body).toContain(line);
    }
  });

  it('never prints an amount that is not the contract money', () => {
    const body = text({
      statusType: 'CONFIRMED',
      statusData: { sellerAmount: '1e9', buyerRefundAmount: 5 },
    });

    expect(body).not.toMatch(/To the (seller|buyer):/);
  });

  it.each(
    MILESTONE_STATUS_TYPES.flatMap((statusType) =>
      RECIPIENT_ROLES.map((role) => [statusType, role] as const),
    ),
  )('%s to %s keeps every line within 76 characters', (statusType, role) => {
    const body = text(
      {
        statusType,
        deadline: '2026-12-04T16:59:59.999Z',
        statusData: {
          milestoneIndex: 999999,
          committedQuantityKg: '999999999999.999999',
          sellerDeclaredWeightKg: '999999999999.999999',
          declarationNumber: 999999,
          billableQuantityKg: '999999999999.999999',
          sellerAmount: '9999999999999999.99',
          buyerRefundAmount: '9999999999999999.99',
          settlementPath: 'CLEAN_AUTO_CONFIRMED',
          issueType: 'QUALITY',
        },
      },
      { ...BUYER, role },
    );
    for (const line of body.split('\n')) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
  });
});
