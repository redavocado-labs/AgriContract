import { NonRetryableError } from '@agricontract/eventing';
import {
  DocumentFileMismatchError,
  FileServiceRejectedError,
  OperatorEmailNotConfiguredError,
} from '../../application/notification-errors';
import {
  unsupportedTermination,
  type NotifyActivationCancelRequestedUseCase,
  type NotifyContractActivationFailedUseCase,
  type NotifyContractAnchorUseCase,
  type NotifyContractDocumentReadyUseCase,
  type NotifyContractTerminatedUseCase,
  type NotifyEscrowLegAlertUseCase,
  type NotifyEscrowRemedyLegAlertUseCase,
  type NotifyEscrowRemedyRejectedUseCase,
  type NotifyLevel15AssignmentWaitingUseCase,
  type NotifyLevel15InspectorAssignmentUseCase,
  type NotifyMilestoneFundingStatusUseCase,
  type NotifyMilestoneStatusUseCase,
  type NotifyBreachNoticeUseCase,
  type NotifyRemedyFinalizedUseCase,
  type NotifyUserKycResultUseCase,
  type NotifyUserLockChangedUseCase,
} from '../../application/use-cases';
import {
  CONTRACT_TERMINATED_REQUESTED,
  parseActivationCancelRequested,
  parseContractActivationFailedRequested,
  parseContractAnchorRequested,
  parseContractDocumentReadyRequested,
  parseContractTerminatedRequested,
  parseEscrowLegAlertRequested,
  parseEscrowRemedyLegAlertRequested,
  parseEscrowRemedyRejectedRequested,
  parseLevel15AssignmentWaitingRequested,
  parseLevel15InspectorAssignmentRequested,
  parseMilestoneFundingStatusRequested,
  parseMilestoneStatusRequested,
  parseBreachNoticeRequested,
  parseRemedyFinalizedRequested,
  parseUserKycResultRequested,
  parseUserLockChangedRequested,
  unsupportedCommand,
} from './notification-command.parsers';
import type { CommandRoutingKey } from './topology';

/** Injection token of the command routing table built by commandRoutes. */
export const COMMAND_ROUTES = Symbol('COMMAND_ROUTES');

export type CommandRoutes = Record<
  CommandRoutingKey,
  (body: Buffer) => Promise<void>
>;

export interface CommandUseCases {
  contractAnchor: NotifyContractAnchorUseCase;
  activationCancelRequested: NotifyActivationCancelRequestedUseCase;
  contractActivationFailed: NotifyContractActivationFailedUseCase;
  contractTerminated: NotifyContractTerminatedUseCase;
  userKycResult: NotifyUserKycResultUseCase;
  userLockChanged: NotifyUserLockChangedUseCase;
  level15AssignmentWaiting: NotifyLevel15AssignmentWaitingUseCase;
  level15InspectorAssignment: NotifyLevel15InspectorAssignmentUseCase;
  escrowLegAlert: NotifyEscrowLegAlertUseCase;
  escrowRemedyLegAlert: NotifyEscrowRemedyLegAlertUseCase;
  escrowRemedyRejected: NotifyEscrowRemedyRejectedUseCase;
  milestoneFundingStatus: NotifyMilestoneFundingStatusUseCase;
  contractDocumentReady: NotifyContractDocumentReadyUseCase;
  milestoneStatus: NotifyMilestoneStatusUseCase;
  breachNotice: NotifyBreachNoticeUseCase;
  remedyFinalized: NotifyRemedyFinalizedUseCase;
}

/**
 * Phase 2 command -> parser -> use case, per queue; the counterpart of
 * legacyRoutes for the envelope commands of notification-commands.yaml. A
 * Record over CommandRoutingKey: the compiler rejects a command of TOPOLOGY
 * left without a route.
 */
export function commandRoutes(u: CommandUseCases): CommandRoutes {
  return {
    'notification.contract_anchor_requested': (b) =>
      u.contractAnchor.execute(parseContractAnchorRequested(b)),
    'notification.activation_cancel_requested': (b) =>
      u.activationCancelRequested.execute(parseActivationCancelRequested(b)),
    'notification.contract_activation_failed_requested': (b) =>
      u.contractActivationFailed.execute(
        parseContractActivationFailedRequested(b),
      ),
    'notification.contract_terminated_requested': async (b) => {
      const command = parseContractTerminatedRequested(b);
      const reason = unsupportedTermination(command);
      if (reason !== null) {
        throw unsupportedCommand(
          CONTRACT_TERMINATED_REQUESTED,
          command.eventId,
          reason,
        );
      }
      await u.contractTerminated.execute(command);
    },
    'notification.contract_document_ready_requested': (b) =>
      documentMail(() =>
        u.contractDocumentReady.execute(parseContractDocumentReadyRequested(b)),
      ),
    'notification.milestone_status_requested': (b) =>
      u.milestoneStatus.execute(parseMilestoneStatusRequested(b)),
    'notification.breach_notice_requested': (b) =>
      u.breachNotice.execute(parseBreachNoticeRequested(b)),
    'notification.remedy_finalized_requested': (b) =>
      u.remedyFinalized.execute(parseRemedyFinalizedRequested(b)),
    'notification.milestone_funding_status_requested': (b) =>
      u.milestoneFundingStatus.execute(parseMilestoneFundingStatusRequested(b)),
    'notification.user_kyc_result_requested': (b) =>
      u.userKycResult.execute(parseUserKycResultRequested(b)),
    'notification.user_lock_changed_requested': (b) =>
      u.userLockChanged.execute(parseUserLockChangedRequested(b)),
    'notification.level1_5_assignment_waiting_requested': (b) =>
      operatorAlert(() =>
        u.level15AssignmentWaiting.execute(
          parseLevel15AssignmentWaitingRequested(b),
        ),
      ),
    'notification.level1_5_inspector_assignment_requested': (b) =>
      u.level15InspectorAssignment.execute(
        parseLevel15InspectorAssignmentRequested(b),
      ),
    'notification.escrow_leg_alert_requested': (b) =>
      operatorAlert(() =>
        u.escrowLegAlert.execute(parseEscrowLegAlertRequested(b)),
      ),
    'notification.escrow_remedy_leg_alert_requested': (b) =>
      operatorAlert(() =>
        u.escrowRemedyLegAlert.execute(parseEscrowRemedyLegAlertRequested(b)),
      ),
    'notification.escrow_remedy_rejected_requested': (b) =>
      operatorAlert(() =>
        u.escrowRemedyRejected.execute(parseEscrowRemedyRejectedRequested(b)),
      ),
  };
}

/**
 * Sends the final-document email. A PDF that does not hash to the command's
 * documentFileHash, or a read file-service refuses for good (401, size cap),
 * cannot get better on redelivery: straight to the DLQ, unsent. Not READY and
 * unavailable stay retryable.
 */
async function documentMail(send: () => Promise<void>): Promise<void> {
  try {
    await send();
  } catch (err) {
    if (
      err instanceof DocumentFileMismatchError ||
      err instanceof FileServiceRejectedError
    ) {
      throw new NonRetryableError(err.message);
    }
    throw err;
  }
}

/**
 * Sends a staff alert to OPERATOR_EMAIL. No retry can find an address before
 * OPERATOR_EMAIL is set and the service restarted: straight to the DLQ, with
 * the message that says so.
 */
async function operatorAlert(send: () => Promise<void>): Promise<void> {
  try {
    await send();
  } catch (err) {
    if (err instanceof OperatorEmailNotConfiguredError) {
      throw new NonRetryableError(err.message);
    }
    throw err;
  }
}
