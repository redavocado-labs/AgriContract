import type {
  MilestoneStatusRequestedCommand,
  MilestoneStatusType,
  NotificationRecipient,
} from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { vnd } from '../vnd';

/*
 * `notification.milestone_status_requested` -> the recipients the command
 * names (design §4; milestone-escrow design §3.2; D96). One kind per
 * statusType. contract-service publishes four of them today:
 * - WEIGHED (buyer): the seller weighed; receive, weigh, then confirm or flag;
 * - BUYER_RECEIVE_REMINDER (buyer): the receive window passed;
 * - CONFIRMED (buyer, seller): the milestone settled, and how;
 * - FLAGGED (seller): the buyer disputes weight or quality; answer by the
 *   deadline, and what silence means (D82).
 * FORCE_MAJEURE, LEVEL2_OPTOUT_REQUESTED, PENALTY and PROVISIONAL_LEVEL2
 * belong to later flows with no statusData defined yet: their emails say
 * what changed and point to the contract, without details they cannot know.
 *
 * statusData is a free object in the contract. The email shows a value only
 * when it has the expected shape (a count, a weight, an amount, a known
 * code); anything else is left out rather than printed as sent.
 */

type Kind = (typeof COMMAND_KINDS)[keyof typeof COMMAND_KINDS];

const KINDS: Record<MilestoneStatusType, Kind> = {
  WEIGHED: COMMAND_KINDS.milestoneWeighed,
  CONFIRMED: COMMAND_KINDS.milestoneConfirmed,
  FLAGGED: COMMAND_KINDS.milestoneFlagged,
  FORCE_MAJEURE: COMMAND_KINDS.milestoneForceMajeure,
  LEVEL2_OPTOUT_REQUESTED: COMMAND_KINDS.milestoneLevel2OptoutRequested,
  BUYER_RECEIVE_REMINDER: COMMAND_KINDS.milestoneBuyerReceiveReminder,
  PENALTY: COMMAND_KINDS.milestonePenalty,
  PROVISIONAL_LEVEL2: COMMAND_KINDS.milestoneProvisionalLevel2,
};

const SUBJECTS: Record<MilestoneStatusType, string> = {
  WEIGHED: 'weighed by the seller',
  CONFIRMED: 'settled',
  FLAGGED: 'flagged by the buyer',
  FORCE_MAJEURE: 'force majeure claim',
  LEVEL2_OPTOUT_REQUESTED: 'choose the provisional release',
  BUYER_RECEIVE_REMINDER: 'waiting to be received',
  PENALTY: 'penalty',
  PROVISIONAL_LEVEL2: 'provisional release during Level 2',
};

export class NotifyMilestoneStatusUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own, in the
   * command's order; a failure rethrows and the redelivery skips whoever is
   * already SENT (as NotifyContractAnchorUseCase).
   */
  async execute(command: MilestoneStatusRequestedCommand): Promise<void> {
    const index = positiveInt(command.statusData, 'milestoneIndex');
    const milestone = index === null ? 'A milestone' : `Milestone ${index}`;
    const subject = `[AgriContract] ${milestone} of contract ${command.contractId}: ${SUBJECTS[command.statusType]}`;

    for (const recipient of command.recipients) {
      await this.sender.send(
        command.eventId,
        KINDS[command.statusType],
        recipient.email,
        subject,
        milestoneStatusText(command, recipient),
        recipient.userId,
      );
    }
  }
}

type Data = Record<string, unknown> | null;

/** A whole number from 1 to 999999, or null. */
function positiveInt(data: Data, key: string): number | null {
  const value = data?.[key];
  return typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= 999_999
    ? value
    : null;
}

/** A plain decimal weight ("1200", "1199.5") as sent, or null. */
function kg(data: Data, key: string): string | null {
  const value = data?.[key];
  const text = typeof value === 'number' ? String(value) : value;
  return typeof text === 'string' && /^\d{1,12}(\.\d{1,6})?$/.test(text)
    ? `${text} kg`
    : null;
}

/** The contract's money ("45000000.00") as grouped VND, or null. */
function amount(data: Data, key: string): string | null {
  const value = data?.[key];
  return typeof value === 'string' &&
    value.length <= 19 &&
    /^\d+\.\d{2}$/.test(value)
    ? vnd(value)
    : null;
}

/** A value of `known`, or null. */
function code<T extends string>(
  data: Data,
  key: string,
  known: readonly T[],
): T | null {
  const value = data?.[key];
  return (known as readonly unknown[]).includes(value) ? (value as T) : null;
}

/** "Label: value" lines for the values that are there. */
function details(rows: Array<[string, string | null]>): string[] {
  return rows
    .filter((row): row is [string, string] => row[1] !== null)
    .map(([label, value]) => `${label.padEnd(17)}${value}`);
}

const SETTLEMENT_PATHS = {
  CLEAN_CONFIRMED: ['The buyer confirmed the receipt.'],
  CLEAN_AUTO_CONFIRMED: [
    'The confirm window passed without an action from the buyer, so the',
    'milestone was confirmed automatically.',
  ],
  SELLER_SILENT_QUANTITY: [
    "The seller did not answer the buyer's quantity flag in time, so the",
    "milestone settled on the buyer's weight, with no penalty.",
  ],
  INSPECTED_CONFORMING: [
    'The Level 1.5 inspection found the goods conforming.',
  ],
  DISPUTE_RESOLVED: ['It was settled by the decision on its dispute.'],
} as const;
const PATHS = Object.keys(SETTLEMENT_PATHS) as Array<
  keyof typeof SETTLEMENT_PATHS
>;

const OPEN_THE_CONTRACT = [
  'Open the contract on AgriContract for the details and what you can do.',
];

interface Text {
  intro: string[];
  details: string[];
  deadline: string;
  action: string[];
}

function textOf(
  command: MilestoneStatusRequestedCommand,
  role: NotificationRecipient['role'],
): Text {
  const data = command.statusData;
  const isBuyer = role === 'BUYER';
  const isSeller = role === 'SELLER';

  switch (command.statusType) {
    case 'WEIGHED': {
      const declaration = positiveInt(data, 'declarationNumber');
      return {
        intro: [
          'The seller has weighed a milestone of your AgriContract contract and',
          'declared its weight.',
        ],
        details: details([
          ['Declared weight:', kg(data, 'sellerDeclaredWeightKg')],
          [
            'Declaration:',
            declaration === null ? null : `number ${declaration}`,
          ],
          ['Committed:', kg(data, 'committedQuantityKg')],
        ]),
        deadline: 'Deadline:',
        action: isBuyer
          ? [
              'Receive the goods, weigh them and record your weight on',
              'AgriContract. Then, within your confirm window, either confirm the',
              'milestone or flag an issue with its quantity or quality.',
              'The seller may correct the declaration within one hour of the first',
              'one, and only until you record your weight; you get an email for',
              'each correction.',
            ]
          : [
              'The buyer has been asked to receive and weigh the goods, then to',
              'confirm the milestone or flag an issue.',
            ],
      };
    }
    case 'BUYER_RECEIVE_REMINDER':
      return {
        intro: [
          'The seller weighed a milestone of your AgriContract contract and the',
          'window to receive it has passed, but the buyer has not recorded a',
          'weight yet.',
        ],
        details: details([
          ['Declared weight:', kg(data, 'sellerDeclaredWeightKg')],
          ['Committed:', kg(data, 'committedQuantityKg')],
        ]),
        deadline: 'Deadline:',
        action: isBuyer
          ? [
              'Receive the goods, weigh them and record your weight on',
              "AgriContract. Nothing is settled on the seller's weight alone: if",
              'you stay silent, the milestone is decided on the evidence available.',
            ]
          : ['The buyer has been reminded to receive and weigh the goods.'],
      };
    case 'CONFIRMED': {
      const path = code(data, 'settlementPath', PATHS);
      return {
        intro: [
          'A milestone of your AgriContract contract is settled.',
          ...(path === null ? [] : SETTLEMENT_PATHS[path]),
        ],
        details: details([
          ['Billable weight:', kg(data, 'billableQuantityKg')],
          ['To the seller:', amount(data, 'sellerAmount')],
          ['To the buyer:', amount(data, 'buyerRefundAmount')],
        ]),
        deadline: 'Deadline:',
        action: [
          'escrow-service pays these amounts out of the milestone payment',
          'locked for it: to the seller for the goods, and back to the buyer',
          'for the rest.',
        ],
      };
    }
    case 'FLAGGED': {
      const issue = code(data, 'issueType', ['QUANTITY', 'QUALITY'] as const);
      const silence =
        issue === 'QUANTITY'
          ? [
              'If the seller does not answer in time, the milestone settles on',
              "the buyer's weight, with no penalty.",
            ]
          : issue === 'QUALITY'
            ? [
                'If the seller does not answer in time, the platform contests the',
                'flag for the seller and the milestone goes to inspection.',
              ]
            : [];
      return {
        intro: [
          'The buyer flagged a milestone of your AgriContract contract:',
          issue === 'QUANTITY'
            ? 'they dispute its weight.'
            : issue === 'QUALITY'
              ? 'they dispute the quality of the goods.'
              : 'they dispute it.',
        ],
        details: details([['Committed:', kg(data, 'committedQuantityKg')]]),
        deadline: 'Respond by:',
        action: isSeller
          ? [
              'Before the deadline, accept the flag or contest it. Contesting opens',
              'a breach case and sends the milestone to a Level 1.5 inspection.',
              ...silence,
            ]
          : [
              'The seller has been asked to accept or contest the flag.',
              ...silence,
            ],
      };
    }
    case 'FORCE_MAJEURE':
      return generic(['A force majeure claim concerns it.']);
    case 'LEVEL2_OPTOUT_REQUESTED':
      return generic([
        'While its Level 2 inspection runs, part of its payment is released',
        'to the seller provisionally. The buyer chooses how much: the higher',
        'release level or the lower one. If the buyer does not choose before',
        'the deadline, the lower level applies.',
      ]);
    case 'PENALTY':
      return generic(['A penalty applies to it.']);
    case 'PROVISIONAL_LEVEL2':
      return generic([
        'Part of its payment is released provisionally while its Level 2',
        'inspection runs.',
      ]);
  }
}

function generic(what: string[]): Text {
  return {
    intro: [
      'The status of a milestone of your AgriContract contract changed.',
      ...what,
    ],
    details: [],
    deadline: 'Deadline:',
    action: OPEN_THE_CONTRACT,
  };
}

/**
 * The email text of the eight templates `milestone-<status>/v1`. Changing one
 * is a new version of that template: rows already written name the text they
 * sent. Every line stays within 76 characters, so the email goes out as 7bit
 * and the ids read unbroken.
 */
export function milestoneStatusText(
  command: MilestoneStatusRequestedCommand,
  recipient: NotificationRecipient,
): string {
  const text = textOf(command, recipient.role);
  const index = positiveInt(command.statusData, 'milestoneIndex');
  const lines = [
    ...text.intro,
    '',
    `Contract:        ${command.contractId}`,
    `Milestone:       ${command.milestoneId}`,
    ...(index === null ? [] : [`Number:          ${index}`]),
    ...text.details,
  ];
  if (command.deadline !== null) {
    const deadline = new Date(command.deadline);
    lines.push(
      text.deadline,
      `${vietnamTime(deadline)} Vietnam time (${deadline.toISOString()} UTC)`,
    );
  }
  lines.push('', ...text.action, '');
  return lines.join('\n');
}
