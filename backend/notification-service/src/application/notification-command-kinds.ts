import type { NotificationKind } from '../domain/notification-log';

/*
 * The email kind of each Phase 2 notification command (design §4), the
 * counterpart of PHASE1_KINDS for the commands of
 * contracts/events/notification-commands.yaml.
 *
 * eventType is the command's `eventType` (its routing key too), notificationType
 * names the template semantically, and templateVersion names the exact text the
 * row recorded. Unlike the Phase 1 `/p1` versions, these are not frozen Java
 * texts: a changed subject or body is a new `/vN`, never an edit of this value,
 * because `template_version` is the evidence of what was sent (design §3.1).
 *
 * One command may later send several templates to the same recipient (a
 * `statusType` per template, design §4); the dedup key (event, recipient, type)
 * keeps those apart, so each gets its own entry here rather than a shared one.
 *
 * These values are stored in notification_logs: do not rename them.
 */
export const COMMAND_KINDS = {
  contractAnchor: {
    eventType: 'notification.contract_anchor_requested',
    notificationType: 'CONTRACT_ANCHOR',
    templateVersion: 'contract-anchor/v1',
  },
  activationCancelRequested: {
    eventType: 'notification.activation_cancel_requested',
    notificationType: 'ACTIVATION_CANCEL_REQUESTED',
    templateVersion: 'activation-cancel-requested/v1',
  },
  contractActivationFailed: {
    eventType: 'notification.contract_activation_failed_requested',
    notificationType: 'CONTRACT_ACTIVATION_FAILED',
    templateVersion: 'contract-activation-failed/v1',
  },
  // notification.contract_terminated_requested, terminationType WITHDRAW_OFFER
  // (D43). The other termination types of this command get their own kinds.
  offerWithdrawn: {
    eventType: 'notification.contract_terminated_requested',
    notificationType: 'OFFER_WITHDRAWN',
    templateVersion: 'offer-withdrawn/v1',
  },
  // One email per decision, VERIFIED or REJECTED: the template tells them
  // apart, and a user decided twice gets two commands, two eventIds.
  userKycResult: {
    eventType: 'notification.user_kyc_result_requested',
    notificationType: 'USER_KYC_RESULT',
    templateVersion: 'user-kyc-result/v1',
  },
  // One email per applied lock change: locked, unlocked, or one lock lifted
  // with another still on. The template tells them apart.
  userLockChanged: {
    eventType: 'notification.user_lock_changed_requested',
    notificationType: 'USER_LOCK_CHANGED',
    templateVersion: 'user-lock-changed/v1',
  },
  // notification.milestone_status_requested (D96): one kind per statusType,
  // each its own template, to every recipient the command names.
  milestoneWeighed: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_WEIGHED',
    templateVersion: 'milestone-weighed/v1',
  },
  milestoneConfirmed: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_CONFIRMED',
    templateVersion: 'milestone-confirmed/v1',
  },
  milestoneFlagged: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_FLAGGED',
    templateVersion: 'milestone-flagged/v1',
  },
  milestoneForceMajeure: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_FORCE_MAJEURE',
    templateVersion: 'milestone-force-majeure/v1',
  },
  milestoneLevel2OptoutRequested: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_LEVEL2_OPTOUT_REQUESTED',
    templateVersion: 'milestone-level2-optout-requested/v1',
  },
  milestoneBuyerReceiveReminder: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_BUYER_RECEIVE_REMINDER',
    templateVersion: 'milestone-buyer-receive-reminder/v1',
  },
  milestonePenalty: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_PENALTY',
    templateVersion: 'milestone-penalty/v1',
  },
  milestoneProvisionalLevel2: {
    eventType: 'notification.milestone_status_requested',
    notificationType: 'MILESTONE_PROVISIONAL_LEVEL2',
    templateVersion: 'milestone-provisional-level2/v1',
  },
  // notification.milestone_anchor_requested (hash-chain §4.3): the
  // settlement email with the OpenTimestamps proof attached (design §3.3).
  milestoneAnchor: {
    eventType: 'notification.milestone_anchor_requested',
    notificationType: 'MILESTONE_ANCHOR',
    templateVersion: 'milestone-anchor/v1',
  },
  // notification.breach_notice_requested (D96): the BreachCase notice.
  breachNotice: {
    eventType: 'notification.breach_notice_requested',
    notificationType: 'BREACH_NOTICE',
    templateVersion: 'breach-notice/v1',
  },
  // notification.remedy_finalized_requested (D96): the normal completion
  // (D94) has its own kind, every other finalized decision shares one.
  contractCompleted: {
    eventType: 'notification.remedy_finalized_requested',
    notificationType: 'CONTRACT_COMPLETED',
    templateVersion: 'contract-completed/v1',
  },
  remedyFinalized: {
    eventType: 'notification.remedy_finalized_requested',
    notificationType: 'REMEDY_FINALIZED',
    templateVersion: 'remedy-finalized/v1',
  },
  // notification.contract_document_ready_requested (design §3.3): the final
  // signed contract PDF. Its rows are keyed by documentId, not eventId
  // (design §5: documentId + recipient + CONTRACT_FINAL_PDF).
  contractFinalPdf: {
    eventType: 'notification.contract_document_ready_requested',
    notificationType: 'CONTRACT_FINAL_PDF',
    templateVersion: 'contract-final-pdf/v1',
  },
  // notification.milestone_funding_status_requested (milestone-escrow §6b):
  // one kind per statusType, each its own template (design §4), to every
  // recipient the command names.
  milestoneFundingReminder: {
    eventType: 'notification.milestone_funding_status_requested',
    notificationType: 'MILESTONE_FUNDING_REMINDER',
    templateVersion: 'milestone-funding-reminder/v1',
  },
  milestoneFundingFailed: {
    eventType: 'notification.milestone_funding_status_requested',
    notificationType: 'MILESTONE_FUNDING_FAILED',
    templateVersion: 'milestone-funding-failed/v1',
  },
  milestoneFundingCureDeadline: {
    eventType: 'notification.milestone_funding_status_requested',
    notificationType: 'MILESTONE_FUNDING_CURE_DEADLINE',
    templateVersion: 'milestone-funding-cure-deadline/v1',
  },
  // notification.level1_5_assignment_waiting_requested (D49): one kind per
  // stage, each its own template, all to the configured operator address.
  // Every stage is a new command with its own eventId, so the kinds do not
  // need to differ for dedup; they differ because the texts do.
  level15WaitingStarted: {
    eventType: 'notification.level1_5_assignment_waiting_requested',
    notificationType: 'LEVEL1_5_WAITING_STARTED',
    templateVersion: 'level1-5-waiting-started/v1',
  },
  level15StillWaiting: {
    eventType: 'notification.level1_5_assignment_waiting_requested',
    notificationType: 'LEVEL1_5_STILL_WAITING',
    templateVersion: 'level1-5-still-waiting/v1',
  },
  level15AssignedAfterWait: {
    eventType: 'notification.level1_5_assignment_waiting_requested',
    notificationType: 'LEVEL1_5_ASSIGNED_AFTER_WAIT',
    templateVersion: 'level1-5-assigned-after-wait/v1',
  },
  // Stage CANCELLED (D57): the dispute ended while the request was waiting.
  level15WaitingCancelled: {
    eventType: 'notification.level1_5_assignment_waiting_requested',
    notificationType: 'LEVEL1_5_WAITING_CANCELLED',
    templateVersion: 'level1-5-waiting-cancelled/v1',
  },
  // notification.level1_5_inspector_assignment_requested (D57): one kind per
  // stage, to the assigned inspector.
  level15InspectorAssigned: {
    eventType: 'notification.level1_5_inspector_assignment_requested',
    notificationType: 'LEVEL1_5_INSPECTOR_ASSIGNED',
    templateVersion: 'level1-5-inspector-assigned/v1',
  },
  level15InspectorCancelled: {
    eventType: 'notification.level1_5_inspector_assignment_requested',
    notificationType: 'LEVEL1_5_INSPECTOR_CANCELLED',
    templateVersion: 'level1-5-inspector-cancelled/v1',
  },
  // notification.escrow_leg_alert_requested (D68): one kind per stage, each
  // its own template, all to the configured operator address.
  escrowLegStuck: {
    eventType: 'notification.escrow_leg_alert_requested',
    notificationType: 'ESCROW_LEG_STUCK',
    templateVersion: 'escrow-leg-stuck/v1',
  },
  escrowLegResolved: {
    eventType: 'notification.escrow_leg_alert_requested',
    notificationType: 'ESCROW_LEG_RESOLVED',
    templateVersion: 'escrow-leg-resolved/v1',
  },
  escrowLegReturnExhausted: {
    eventType: 'notification.escrow_leg_alert_requested',
    notificationType: 'ESCROW_LEG_RETURN_EXHAUSTED',
    templateVersion: 'escrow-leg-return-exhausted/v1',
  },
  // notification.escrow_remedy_leg_alert_requested (D93): a remedy leg the
  // bank refused with no resend left, to the configured operator address.
  escrowRemedyLegFailed: {
    eventType: 'notification.escrow_remedy_leg_alert_requested',
    notificationType: 'ESCROW_REMEDY_LEG_FAILED',
    templateVersion: 'escrow-remedy-leg-failed/v1',
  },
  // notification.escrow_remedy_rejected_requested (D93): escrow refused a whole
  // remedy decision, to the configured operator address.
  escrowRemedyRejected: {
    eventType: 'notification.escrow_remedy_rejected_requested',
    notificationType: 'ESCROW_REMEDY_REJECTED',
    templateVersion: 'escrow-remedy-rejected/v1',
  },
  // notification.audit_digest_requested (hash-chain §5.1): the weekly digest
  // to the Software Buyer contacts, only after WEEKLY_VERIFY_OK.
  auditDigest: {
    eventType: 'notification.audit_digest_requested',
    notificationType: 'AUDIT_DIGEST',
    templateVersion: 'audit-digest/v1',
  },
  // notification.audit_failure_requested (hash-chain §5.2): the integrity
  // alert to the Admin and the Software Buyer contacts at once.
  auditFailure: {
    eventType: 'notification.audit_failure_requested',
    notificationType: 'AUDIT_FAILURE',
    templateVersion: 'audit-failure/v1',
  },
  // notification.security_lock_changed_requested (bank §3.5.4): one email per
  // lock or unlock, to the Admin and the External Verifier. The template
  // tells LOCKED and UNLOCKED apart, as user-lock-changed does.
  securityLockChanged: {
    eventType: 'notification.security_lock_changed_requested',
    notificationType: 'SECURITY_LOCK_CHANGED',
    templateVersion: 'security-lock-changed/v1',
  },
  // notification.reconciliation_mismatch_requested (bank §5b.1): Admin only.
  reconciliationMismatch: {
    eventType: 'notification.reconciliation_mismatch_requested',
    notificationType: 'RECONCILIATION_MISMATCH',
    templateVersion: 'reconciliation-mismatch/v1',
  },
  // notification.verifier_key_anchor_requested (bank §3.5.6): the key
  // fingerprint to the External Verifier's own mailbox.
  verifierKeyAnchor: {
    eventType: 'notification.verifier_key_anchor_requested',
    notificationType: 'VERIFIER_KEY_ANCHOR',
    templateVersion: 'verifier-key-anchor/v1',
  },
} as const satisfies Record<string, NotificationKind>;
