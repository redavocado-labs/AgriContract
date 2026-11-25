import type {
  DecisionSource,
  FundType,
  QualityDisposition,
  RemedyFinalizedRequestedCommand,
  RemedyLeg,
  RemedyType,
} from '../commands';
import { COMMAND_KINDS } from '../notification-command-kinds';
import type { NotificationSender } from '../notification-sender';
import { vnd } from '../vnd';
import { reasonLines } from './breach-reasons';

/*
 * `notification.remedy_finalized_requested` -> both parties (design §4; D96).
 * Every finalized RemedyDecision, three shapes:
 * - the normal completion (D94): SYSTEM, no breach case, no fault, no
 *   quality, no milestone named, written when the last milestone settles.
 *   The remaining deposits go back; the contract stays ACTIVE until escrow
 *   reports all of its money cleared (D93), so the mail does not call it
 *   closed. Its own kind, CONTRACT_COMPLETED, since this decision finds no
 *   new breach (an earlier one may have);
 * - a quality resolution: the inspection's qualityDisposition, shown with
 *   the final attribution (the contract's x-quality-notification-invariant);
 * - any other decision, with or without a party at fault.
 * Kind REMEDY_FINALIZED for the last two.
 *
 * The email shows what the decision says and nothing it does not: the
 * responsible party is finalBreachingRole, never who asked; each money leg
 * is shown as decided, its role being the party that receives it (D86).
 */

export class NotifyRemedyFinalizedUseCase {
  constructor(private readonly sender: NotificationSender) {}

  /**
   * One email per recipient, each claimed and recorded on its own, in the
   * command's order; a failure rethrows and the redelivery skips whoever is
   * already SENT (as NotifyContractAnchorUseCase).
   */
  async execute(command: RemedyFinalizedRequestedCommand): Promise<void> {
    const completion = isNormalCompletion(command);
    const kind = completion
      ? COMMAND_KINDS.contractCompleted
      : COMMAND_KINDS.remedyFinalized;
    const subject = completion
      ? `[AgriContract] Every milestone of contract ${command.contractId} has settled`
      : `[AgriContract] Decision on contract ${command.contractId}`;
    const body = remedyFinalizedText(command);

    for (const recipient of command.recipients) {
      await this.sender.send(
        command.eventId,
        kind,
        recipient.email,
        subject,
        body,
        recipient.userId,
      );
    }
  }
}

/** The D94 normal-completion decision, by the producer rule of milestone-escrow §2.x. */
export function isNormalCompletion(
  command: RemedyFinalizedRequestedCommand,
): boolean {
  return (
    command.decisionSource === 'SYSTEM' &&
    command.breachCaseId === null &&
    command.finalBreachingRole === null &&
    command.breachReasonCode === null &&
    command.qualityDisposition === null &&
    command.affectedMilestoneIds.length === 0
  );
}

const SOURCES: Record<DecisionSource, string> = {
  SYSTEM: 'the platform, by the contract rules',
  ARBITRATOR: 'an arbitrator',
  INSPECTOR_L1_5: 'the Level 1.5 inspection',
  INSPECTOR_L2: 'the Level 2 inspection',
  MUTUAL: 'both parties, by agreement',
};

const QUALITY: Record<QualityDisposition, string> = {
  CONFORMING: 'CONFORMING (the goods meet the contract)',
  PARTIALLY_CONFORMING: 'PARTIALLY_CONFORMING (part of the goods meets it)',
  NON_CONFORMING: 'NON_CONFORMING (the goods do not meet the contract)',
};

const LEG_TYPES: Record<RemedyType, string> = {
  NONE: 'no transfer',
  PAYMENT_SETTLEMENT: 'payment',
  PAYMENT_REFUND: 'refund',
  DEPOSIT_FORFEITURE: 'forfeited deposit',
  CONTRACTUAL_PENALTY: 'contractual penalty',
  DAMAGES_COMPENSATION: 'damages',
};

const FUNDS: Record<FundType, string> = {
  BUYER_DEPOSIT: "the buyer's deposit",
  SELLER_DEPOSIT: "the seller's deposit",
  MILESTONE_PAYMENT: 'the milestone payment',
};

/** "- 45,000,000.00 VND to the seller: payment, from the milestone payment". */
function legLines(leg: RemedyLeg): string[] {
  return [
    `- ${vnd(leg.amount)} to the ${leg.role.toLowerCase()}:`,
    `  ${LEG_TYPES[leg.remedyType]}, from ${FUNDS[leg.fundType]}`,
  ];
}

const yesNo = (value: boolean): string => (value ? 'yes' : 'no');

/**
 * The email text of `contract-completed/v1` (normal completion) and
 * `remedy-finalized/v1` (any other decision). Changing one is a new version
 * of that template: rows already written name the text they sent. Every line
 * stays within 76 characters, so the email goes out as 7bit.
 */
export function remedyFinalizedText(
  command: RemedyFinalizedRequestedCommand,
): string {
  const money =
    command.remedyLegs.length === 0
      ? ['No money moves because of this decision.']
      : [
          'Money, as decided (each line names who receives it):',
          ...command.remedyLegs.flatMap(legLines),
          '',
          'escrow-service carries out these transfers.',
        ];

  if (isNormalCompletion(command)) {
    // D93/D94: this decision is written when the last milestone settles, but
    // the contract stays ACTIVE until escrow reports every fund of it cleared.
    const deposits =
      command.remedyLegs.length === 0
        ? [
            'No deposit is still locked for the contract, so this decision',
            'moves no money.',
          ]
        : [
            'This decision returns the deposits still locked for the contract.',
            ...money,
          ];
    return [
      'Every milestone of your AgriContract contract has settled, and the',
      'platform has made its completion decision.',
      '',
      `Contract: ${command.contractId}`,
      `Decision: ${command.remedyDecisionId}`,
      '',
      'This decision finds no new breach. It does not change any earlier',
      'decision on this contract.',
      '',
      ...deposits,
      '',
      'The contract is not closed yet. It closes once escrow-service confirms',
      'that all money of the contract has cleared, including the final',
      'milestone payment and any earlier transfer that is still pending.',
      '',
    ].join('\n');
  }

  const role = command.finalBreachingRole;
  const headline =
    command.qualityDisposition !== null
      ? [
          'The dispute on your AgriContract contract has been decided on the',
          'quality of the goods.',
        ]
      : role !== null
        ? [
            `A decision was made on your AgriContract contract: the ${role.toLowerCase()} is`,
            'responsible.',
          ]
        : [
            'A decision was made on your AgriContract contract. No party is at',
            'fault.',
          ];
  const milestones =
    command.affectedMilestoneIds.length === 0
      ? ['Milestones: the contract as a whole']
      : ['Milestones:', ...command.affectedMilestoneIds.map((id) => `- ${id}`)];

  return [
    ...headline,
    '',
    `Contract:     ${command.contractId}`,
    `Decision:     ${command.remedyDecisionId}`,
    `Breach case:  ${command.breachCaseId ?? 'none'}`,
    ...milestones,
    `Decided by:   ${SOURCES[command.decisionSource]}`,
    `Responsible:  ${role === null ? 'no party' : role}`,
    ...reasonLines('Reason:       ', command.breachReasonCode),
    ...(command.qualityDisposition === null
      ? []
      : [`Quality:      ${QUALITY[command.qualityDisposition]}`]),
    `Penalty:      ${yesNo(command.penaltyEligible)}`,
    `Reputation:   ${command.reputationEligible ? 'affected' : 'not affected'}`,
    '',
    ...money,
    '',
  ].join('\n');
}
