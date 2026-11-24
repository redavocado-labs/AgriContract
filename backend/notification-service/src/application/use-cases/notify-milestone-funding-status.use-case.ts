import type {
  MilestoneFundingStatusRequestedCommand,
  MilestoneFundingStatusType,
  NotificationRecipient,
} from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { vietnamTime } from '../vietnam-time';
import { wrap } from '../wrap';

/*
 * `notification.milestone_funding_status_requested` -> the recipients the
 * command names (design §4; milestone-escrow design §6b). The buyer's money
 * for a milestone is locked in escrow before the seller has to prepare it:
 * - FUNDING_REMINDER: have the money ready for the lock;
 * - FUNDING_FAILED: escrow ran out of lock retries. Nothing is taken and
 *   nobody is in breach yet; the buyer has a cure window, an operator can
 *   retry the lock, and the seller's delivery clock is paused;
 * - FUNDING_CURE_DEADLINE: the end of that window, after which the platform
 *   records a funding breach by the buyer (unless the failure was the bank's
 *   or the platform's).
 *
 * The text depends on the recipient's role: the buyer is told what to do,
 * the seller what it means for delivery, anyone else (an operator) the facts
 * and the retry.
 */

type Kind = (typeof COMMAND_KINDS)[keyof typeof COMMAND_KINDS];

const KINDS: Record<MilestoneFundingStatusType, Kind> = {
  FUNDING_REMINDER: COMMAND_KINDS.milestoneFundingReminder,
  FUNDING_FAILED: COMMAND_KINDS.milestoneFundingFailed,
  FUNDING_CURE_DEADLINE: COMMAND_KINDS.milestoneFundingCureDeadline,
};

const SUBJECTS: Record<MilestoneFundingStatusType, string> = {
  FUNDING_REMINDER: 'Funding reminder for a milestone of contract',
  FUNDING_FAILED: 'Milestone funding failed for contract',
  FUNDING_CURE_DEADLINE: 'Milestone funding deadline for contract',
};

/** Width free text is wrapped to, within the 76 characters of a 7bit line. */
const REASON_WIDTH = 72;

export class NotifyMilestoneFundingStatusUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own, in the
   * command's order; a failure rethrows and the redelivery skips whoever is
   * already SENT (as NotifyContractAnchorUseCase).
   */
  async execute(
    command: MilestoneFundingStatusRequestedCommand,
  ): Promise<void> {
    const subject = `[AgriContract] ${SUBJECTS[command.statusType]} ${command.contractId}`;

    for (const recipient of command.recipients) {
      await this.sender.send(
        command.eventId,
        KINDS[command.statusType],
        recipient.email,
        subject,
        milestoneFundingBody(command, recipient),
        recipient.userId,
      );
    }
  }
}

type Audience = 'BUYER' | 'SELLER' | 'OTHER';

const audienceOf = (recipient: NotificationRecipient): Audience =>
  recipient.role === 'BUYER' || recipient.role === 'SELLER'
    ? recipient.role
    : 'OTHER';

const NOT_THE_BUYERS_FAULT = [
  "unless the failure was on the bank's or the platform's side.",
];

const TEXT: Record<
  MilestoneFundingStatusType,
  { intro: string[]; deadline: string; by: Record<Audience, string[]> }
> = {
  FUNDING_REMINDER: {
    intro: [
      'This is a reminder to fund a milestone of your AgriContract contract.',
    ],
    deadline: 'Fund it by:',
    by: {
      BUYER: [
        'The milestone amount will be locked in escrow from your account. Make',
        'sure the money is available so the lock succeeds: until it is locked,',
        'the seller does not have to prepare this milestone.',
      ],
      SELLER: [
        'The buyer has been reminded to fund this milestone. Until its amount',
        'is locked in escrow you do not have to prepare it, and its delivery',
        'deadline moves by the business days the funding waited.',
      ],
      OTHER: [
        'The buyer has been reminded to fund this milestone. The seller does',
        'not have to prepare it until its amount is locked in escrow.',
      ],
    },
  },
  FUNDING_FAILED: {
    intro: [
      "Locking the buyer's money for a milestone failed after every retry.",
      'The milestone is not funded. Nothing has been taken from anyone, and',
      'nobody is in breach yet.',
    ],
    deadline: 'Cure window ends:',
    by: {
      BUYER: [
        'You can still fix this: make the money available and tell an',
        'AgriContract operator, who will retry the lock. If the milestone is',
        'still not funded when the cure window ends, the platform records a',
        'funding breach by you,',
        ...NOT_THE_BUYERS_FAULT,
      ],
      SELLER: [
        'Your delivery clock for this milestone is paused: you do not have to',
        'prepare or deliver it until it is funded, and its delivery deadline',
        'moves by the business days the funding waited. If the buyer has not',
        'funded it when the cure window ends, you may terminate the rest of',
        "the contract for the buyer's breach, or keep waiting.",
      ],
      OTHER: [
        'An operator can retry the lock once the buyer confirms the money is',
        'available, or once a bank or platform problem is fixed. A failure on',
        "the bank's or the platform's side does not count against the buyer.",
      ],
    },
  },
  FUNDING_CURE_DEADLINE: {
    intro: [
      'A milestone of your AgriContract contract is still not funded, and the',
      'window to fix its funding has a deadline.',
    ],
    deadline: 'Deadline:',
    by: {
      BUYER: [
        'Fund it before the deadline. After that the platform records a',
        'funding breach by you,',
        ...NOT_THE_BUYERS_FAULT,
      ],
      SELLER: [
        'If the buyer has not funded it by the deadline, you may terminate the',
        "rest of the contract for the buyer's breach, or keep waiting. Until",
        'it is funded you do not have to prepare this milestone.',
      ],
      OTHER: [
        'If the milestone is still not funded by the deadline, the platform',
        'records a funding breach by the buyer,',
        ...NOT_THE_BUYERS_FAULT,
      ],
    },
  },
};

/**
 * The email text of the three templates: `milestone-funding-reminder/v1`,
 * `milestone-funding-failed/v1` and `milestone-funding-cure-deadline/v1`.
 * Changing one is a new version of that template: rows already written name
 * the text they sent. The fixed lines stay within 76 characters and the
 * reason is wrapped to 72, so an ASCII reason goes out as 7bit too.
 */
export function milestoneFundingBody(
  command: MilestoneFundingStatusRequestedCommand,
  recipient: NotificationRecipient,
): string {
  const text = TEXT[command.statusType];
  const lines = [
    ...text.intro,
    '',
    `Contract:  ${command.contractId}`,
    `Milestone: ${command.milestoneId}`,
  ];

  if (command.deadline !== null) {
    const deadline = new Date(command.deadline);
    lines.push(
      text.deadline,
      `${vietnamTime(deadline)} Vietnam time (${deadline.toISOString()} UTC)`,
    );
  } else if (command.statusType === 'FUNDING_CURE_DEADLINE') {
    lines.push(`${text.deadline} not given with this notice`);
  }
  // A blank reason says nothing: no "Reason:" heading over an empty line.
  if (command.reason !== null && command.reason.trim() !== '') {
    lines.push('Reason:', ...wrap(command.reason, REASON_WIDTH));
  }

  lines.push('', ...text.by[audienceOf(recipient)], '');
  return lines.join('\n');
}
