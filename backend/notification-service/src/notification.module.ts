import { Logger, Module, type Provider } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { NotificationSender } from './application/notification-sender';
import type { AppConfig } from './config/env.validation';
import { EMAIL_PORT, type EmailPort } from './application/ports/email.port';
import {
  DOCUMENT_FILES,
  type DocumentFiles,
} from './application/ports/document-files.port';
import { FileServiceClient } from './infrastructure/files/file-service.client';
import {
  NOTIFICATION_LOG_REPOSITORY,
  type NotificationLogRepository,
} from './application/ports/notification-log.repository';
import {
  NotifyCategoryApprovedUseCase,
  NotifyCategoryRejectedUseCase,
  NotifyActivationCancelRequestedUseCase,
  NotifyContractActivationFailedUseCase,
  NotifyContractAnchorUseCase,
  NotifyContractDocumentReadyUseCase,
  NotifyContractTerminatedUseCase,
  NotifyEscrowLegAlertUseCase,
  NotifyEscrowRemedyLegAlertUseCase,
  NotifyEscrowRemedyRejectedUseCase,
  NotifyLevel15AssignmentWaitingUseCase,
  NotifyLevel15InspectorAssignmentUseCase,
  NotifyMilestoneFundingStatusUseCase,
  NotifyMilestoneStatusUseCase,
  NotifyBreachNoticeUseCase,
  NotifyRemedyFinalizedUseCase,
  NotifyMilestoneAnchorUseCase,
  NotifyAuditDigestUseCase,
  NotifyAuditFailureUseCase,
  NotifySecurityLockChangedUseCase,
  NotifyReconciliationMismatchUseCase,
  NotifyVerifierKeyAnchorUseCase,
  NotifyUserKycResultUseCase,
  NotifyUserLockChangedUseCase,
} from './application/use-cases';
import {
  NodemailerEmailAdapter,
  mailTransportFactory,
} from './infrastructure/email/nodemailer-email.adapter';
import {
  COMMAND_ROUTES,
  commandRoutes,
} from './infrastructure/messaging/command-routes';
import { DlqMonitor } from './infrastructure/messaging/dlq-monitor';
import {
  LEGACY_ROUTES,
  legacyRoutes,
  type NotificationUseCases,
} from './infrastructure/messaging/legacy-routes';
import { MessagingModule } from './infrastructure/messaging/messaging.module';
import {
  NotificationEventConsumer,
  SHUTDOWN_CONTROLLER,
} from './infrastructure/messaging/notification-event.consumer';
import { MysqlNotificationLogRepository } from './infrastructure/persistence/notification-log.mysql-repository';
import { PersistenceModule } from './infrastructure/persistence/persistence.module';

/*
 * The application layer (src/application) is plain TypeScript: its classes are
 * built here with factories, from the port tokens and plain settings.
 */

type SenderUseCase = new (sender: NotificationSender) => unknown;

const useCaseOf = (useCase: SenderUseCase): Provider => ({
  provide: useCase,
  inject: [NotificationSender],
  useFactory: (sender: NotificationSender) => new useCase(sender),
});

const USE_CASES = {
  categoryApproved: NotifyCategoryApprovedUseCase,
  categoryRejected: NotifyCategoryRejectedUseCase,
} as const;

/**
 * Phase 1 notifications (2 flat category events) and the Phase 2 commands:
 * one RabbitMQ consumer per queue -> use case -> email + notification_logs.
 */
@Module({
  imports: [PersistenceModule, MessagingModule],
  providers: [
    {
      provide: NOTIFICATION_LOG_REPOSITORY,
      useClass: MysqlNotificationLogRepository,
    },
    { provide: EMAIL_PORT, useClass: NodemailerEmailAdapter },
    mailTransportFactory,
    { provide: SHUTDOWN_CONTROLLER, useFactory: () => new AbortController() },
    {
      provide: NotificationSender,
      inject: [NOTIFICATION_LOG_REPOSITORY, EMAIL_PORT, SHUTDOWN_CONTROLLER],
      useFactory: (
        logs: NotificationLogRepository,
        email: EmailPort,
        shutdown: AbortController,
      ) =>
        new NotificationSender(logs, email, {
          logger: new Logger(NotificationSender.name),
          shutdown: shutdown.signal,
        }),
    },
    useCaseOf(NotifyCategoryApprovedUseCase),
    useCaseOf(NotifyCategoryRejectedUseCase),
    useCaseOf(NotifyContractAnchorUseCase),
    useCaseOf(NotifyActivationCancelRequestedUseCase),
    useCaseOf(NotifyContractActivationFailedUseCase),
    useCaseOf(NotifyContractTerminatedUseCase),
    useCaseOf(NotifyMilestoneFundingStatusUseCase),
    useCaseOf(NotifyMilestoneStatusUseCase),
    useCaseOf(NotifyBreachNoticeUseCase),
    useCaseOf(NotifyRemedyFinalizedUseCase),
    useCaseOf(NotifyMilestoneAnchorUseCase),
    useCaseOf(NotifyAuditDigestUseCase),
    useCaseOf(NotifyAuditFailureUseCase),
    useCaseOf(NotifySecurityLockChangedUseCase),
    useCaseOf(NotifyReconciliationMismatchUseCase),
    useCaseOf(NotifyVerifierKeyAnchorUseCase),
    {
      provide: DOCUMENT_FILES,
      inject: [ConfigService],
      useFactory: (config: ConfigService<AppConfig, true>) =>
        new FileServiceClient(
          config.get('fileServiceUrl', { infer: true }),
          config.get('serviceInternalSecret', { infer: true }),
        ),
    },
    {
      provide: NotifyContractDocumentReadyUseCase,
      inject: [NotificationSender, DOCUMENT_FILES],
      useFactory: (sender: NotificationSender, files: DocumentFiles) =>
        new NotifyContractDocumentReadyUseCase(sender, files),
    },
    useCaseOf(NotifyUserKycResultUseCase),
    useCaseOf(NotifyUserLockChangedUseCase),
    useCaseOf(NotifyLevel15InspectorAssignmentUseCase),
    {
      provide: NotifyLevel15AssignmentWaitingUseCase,
      inject: [NotificationSender, ConfigService],
      useFactory: (
        sender: NotificationSender,
        config: ConfigService<AppConfig, true>,
      ) => {
        const operatorEmail = config.get('operatorEmail', { infer: true });
        if (operatorEmail === null) {
          new Logger(NotifyLevel15AssignmentWaitingUseCase.name).warn(
            'OPERATOR_EMAIL is not set: operator alerts (Level 1.5 inspection waiting for an inspector) will be dead-lettered',
          );
        }
        return new NotifyLevel15AssignmentWaitingUseCase(sender, operatorEmail);
      },
    },
    {
      provide: NotifyEscrowLegAlertUseCase,
      inject: [NotificationSender, ConfigService],
      useFactory: (
        sender: NotificationSender,
        config: ConfigService<AppConfig, true>,
      ) => {
        const operatorEmail = config.get('operatorEmail', { infer: true });
        if (operatorEmail === null) {
          new Logger(NotifyEscrowLegAlertUseCase.name).warn(
            'OPERATOR_EMAIL is not set: operator alerts (escrow deposit leg stuck or out of return retries) will be dead-lettered',
          );
        }
        return new NotifyEscrowLegAlertUseCase(sender, operatorEmail);
      },
    },
    {
      provide: NotifyEscrowRemedyLegAlertUseCase,
      inject: [NotificationSender, ConfigService],
      useFactory: (
        sender: NotificationSender,
        config: ConfigService<AppConfig, true>,
      ) => {
        const operatorEmail = config.get('operatorEmail', { infer: true });
        if (operatorEmail === null) {
          new Logger(NotifyEscrowRemedyLegAlertUseCase.name).warn(
            'OPERATOR_EMAIL is not set: operator alerts (remedy payment refused by the bank, no retry left) will be dead-lettered',
          );
        }
        return new NotifyEscrowRemedyLegAlertUseCase(sender, operatorEmail);
      },
    },
    {
      provide: NotifyEscrowRemedyRejectedUseCase,
      inject: [NotificationSender, ConfigService],
      useFactory: (
        sender: NotificationSender,
        config: ConfigService<AppConfig, true>,
      ) => {
        const operatorEmail = config.get('operatorEmail', { infer: true });
        if (operatorEmail === null) {
          new Logger(NotifyEscrowRemedyRejectedUseCase.name).warn(
            'OPERATOR_EMAIL is not set: operator alerts (remedy decision rejected by escrow) will be dead-lettered',
          );
        }
        return new NotifyEscrowRemedyRejectedUseCase(sender, operatorEmail);
      },
    },
    {
      provide: COMMAND_ROUTES,
      inject: [
        NotifyContractAnchorUseCase,
        NotifyActivationCancelRequestedUseCase,
        NotifyContractActivationFailedUseCase,
        NotifyContractTerminatedUseCase,
        NotifyUserKycResultUseCase,
        NotifyUserLockChangedUseCase,
        NotifyLevel15AssignmentWaitingUseCase,
        NotifyLevel15InspectorAssignmentUseCase,
        NotifyEscrowLegAlertUseCase,
        NotifyEscrowRemedyLegAlertUseCase,
        NotifyEscrowRemedyRejectedUseCase,
        NotifyMilestoneFundingStatusUseCase,
        NotifyContractDocumentReadyUseCase,
        NotifyMilestoneStatusUseCase,
        NotifyBreachNoticeUseCase,
        NotifyRemedyFinalizedUseCase,
        NotifyMilestoneAnchorUseCase,
        NotifyAuditDigestUseCase,
        NotifyAuditFailureUseCase,
        NotifySecurityLockChangedUseCase,
        NotifyReconciliationMismatchUseCase,
        NotifyVerifierKeyAnchorUseCase,
      ],
      useFactory: (
        contractAnchor: NotifyContractAnchorUseCase,
        activationCancelRequested: NotifyActivationCancelRequestedUseCase,
        contractActivationFailed: NotifyContractActivationFailedUseCase,
        contractTerminated: NotifyContractTerminatedUseCase,
        userKycResult: NotifyUserKycResultUseCase,
        userLockChanged: NotifyUserLockChangedUseCase,
        level15AssignmentWaiting: NotifyLevel15AssignmentWaitingUseCase,
        level15InspectorAssignment: NotifyLevel15InspectorAssignmentUseCase,
        escrowLegAlert: NotifyEscrowLegAlertUseCase,
        escrowRemedyLegAlert: NotifyEscrowRemedyLegAlertUseCase,
        escrowRemedyRejected: NotifyEscrowRemedyRejectedUseCase,
        milestoneFundingStatus: NotifyMilestoneFundingStatusUseCase,
        contractDocumentReady: NotifyContractDocumentReadyUseCase,
        milestoneStatus: NotifyMilestoneStatusUseCase,
        breachNotice: NotifyBreachNoticeUseCase,
        remedyFinalized: NotifyRemedyFinalizedUseCase,
        milestoneAnchor: NotifyMilestoneAnchorUseCase,
        auditDigest: NotifyAuditDigestUseCase,
        auditFailure: NotifyAuditFailureUseCase,
        securityLockChanged: NotifySecurityLockChangedUseCase,
        reconciliationMismatch: NotifyReconciliationMismatchUseCase,
        verifierKeyAnchor: NotifyVerifierKeyAnchorUseCase,
      ) =>
        commandRoutes({
          contractAnchor,
          activationCancelRequested,
          contractActivationFailed,
          contractTerminated,
          userKycResult,
          userLockChanged,
          level15AssignmentWaiting,
          level15InspectorAssignment,
          escrowLegAlert,
          escrowRemedyLegAlert,
          escrowRemedyRejected,
          milestoneFundingStatus,
          contractDocumentReady,
          milestoneStatus,
          breachNotice,
          remedyFinalized,
          milestoneAnchor,
          auditDigest,
          auditFailure,
          securityLockChanged,
          reconciliationMismatch,
          verifierKeyAnchor,
        }),
    },
    {
      provide: LEGACY_ROUTES,
      inject: Object.values(USE_CASES),
      useFactory: (...instances: unknown[]) =>
        legacyRoutes(
          Object.fromEntries(
            Object.keys(USE_CASES).map((name, i) => [name, instances[i]]),
          ) as unknown as NotificationUseCases,
        ),
    },
    NotificationEventConsumer,
    DlqMonitor,
  ],
})
export class NotificationModule {}
