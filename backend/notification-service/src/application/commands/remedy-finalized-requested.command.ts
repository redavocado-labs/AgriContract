import type {
  BreachReasonCode,
  PartyRole,
  RemedyLeg,
} from './contract-terminated-requested.command';
import type { NotificationRecipient } from './notification-recipient';

/** The contract's `finalQualityDisposition` values (besides null). */
export const QUALITY_DISPOSITIONS = [
  'CONFORMING',
  'PARTIALLY_CONFORMING',
  'NON_CONFORMING',
] as const;
export type QualityDisposition = (typeof QUALITY_DISPOSITIONS)[number];

/** The contract's `decisionSource` enum. */
export const DECISION_SOURCES = [
  'SYSTEM',
  'ARBITRATOR',
  'INSPECTOR_L1_5',
  'INSPECTOR_L2',
  'MUTUAL',
] as const;
export type DecisionSource = (typeof DECISION_SOURCES)[number];

/**
 * `notification.remedy_finalized_requested` (notification-commands.yaml
 * `remedyFinalizedRequested`), published by contract-service for every
 * finalized RemedyDecision (D96): a fault decision on a milestone or the
 * contract, a quality resolution, and the no-fault normal completion that
 * returns the remaining deposits (D94). Same fields as `remedy.finalized`.
 *
 * `remedyLegs[].role` is the party that RECEIVES the money of the leg (D86).
 * `eventId` is the envelope's: the dedup key's event (design §5).
 */
export interface RemedyFinalizedRequestedCommand {
  eventId: string;
  remedyDecisionId: string;
  attributionDecisionId: string;
  breachCaseId: string | null;
  contractId: string;
  buyerId: string;
  sellerId: string;
  affectedMilestoneIds: string[];
  recipients: NotificationRecipient[];
  finalBreachingRole: PartyRole | null;
  breachReasonCode: BreachReasonCode | null;
  qualityDisposition: QualityDisposition | null;
  decisionSource: DecisionSource;
  penaltyEligible: boolean;
  reputationEligible: boolean;
  remedyLegs: RemedyLeg[];
}
