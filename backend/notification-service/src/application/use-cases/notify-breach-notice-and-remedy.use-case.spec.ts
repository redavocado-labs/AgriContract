import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  ANCHOR_RECIPIENTS,
  BREACH_CASE_ID,
  DECISION_ID,
  DISPUTE_CONTRACT_ID,
  DISPUTE_MILESTONE_ID,
  EVIDENCE_FILE_IDS,
  breachNoticeCommand,
  contractCompletedPayload,
  remedyFinalizedCommand,
} from '../../../test/support/commands';
import {
  BREACH_REASON_CODES,
  type BreachNoticeRequestedCommand,
  type RemedyFinalizedRequestedCommand,
} from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { NotificationSender } from '../notification-sender';
import {
  NotifyBreachNoticeUseCase,
  breachNoticeText,
} from './notify-breach-notice.use-case';
import {
  NotifyRemedyFinalizedUseCase,
  isNormalCompletion,
  remedyFinalizedText,
} from './notify-remedy-finalized.use-case';

function sender() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  return {
    logs,
    email,
    sender: new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
  };
}

const notice = (
  patch: Partial<BreachNoticeRequestedCommand> = {},
): BreachNoticeRequestedCommand => ({ ...breachNoticeCommand(), ...patch });

const remedy = (
  patch: Partial<RemedyFinalizedRequestedCommand> = {},
): RemedyFinalizedRequestedCommand => ({
  ...remedyFinalizedCommand(),
  ...patch,
});

const completion = (): RemedyFinalizedRequestedCommand => {
  const p = contractCompletedPayload();
  return remedy({
    breachCaseId: null,
    affectedMilestoneIds: [],
    finalBreachingRole: null,
    breachReasonCode: null,
    qualityDisposition: null,
    decisionSource: 'SYSTEM',
    penaltyEligible: false,
    reputationEligible: false,
    remedyLegs: p.remedyLegs as RemedyFinalizedRequestedCommand['remedyLegs'],
  });
};

const lineLengths = (body: string) =>
  body.split('\n').every((line) => line.length <= 76);

describe('NotifyBreachNoticeUseCase', () => {
  it('mails both parties breach-notice/v1, keyed by eventId, with their user ids', async () => {
    const { logs, email, sender: s } = sender();
    const command = notice();

    await new NotifyBreachNoticeUseCase(s).execute(command);

    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
    expect(email.sent[0].subject).toBe(
      `[AgriContract] Breach case opened on contract ${DISPUTE_CONTRACT_ID}`,
    );
    for (const r of ANCHOR_RECIPIENTS) {
      expect(
        logs.get({
          eventId: command.eventId,
          recipientEmail: r.email,
          notificationType: 'BREACH_NOTICE',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        eventType: 'notification.breach_notice_requested',
        templateVersion: COMMAND_KINDS.breachNotice.templateVersion,
        recipientUserId: r.userId,
      });
    }
  });

  it('breach-notice/v1: the case, the reason in words, when it was reported, the evidence', () => {
    expect(breachNoticeText(notice())).toBe(
      [
        'This is a formal notice that a breach case was opened on your',
        'AgriContract contract.',
        '',
        `Contract:    ${DISPUTE_CONTRACT_ID}`,
        `Breach case: ${BREACH_CASE_ID}`,
        'Reason:      SHORTFALL',
        '             (less delivered than committed)',
        'Severity:    MATERIAL',
        'Reported at:',
        '2026-12-02 10:30:00 Vietnam time (2026-12-02T03:30:00.250Z UTC)',
        '',
        'Evidence: 2 file reference(s)',
        `- ${EVIDENCE_FILE_IDS[0]}`,
        `- ${EVIDENCE_FILE_IDS[1]}`,
        '',
        'This is an allegation, not a decision: nobody is penalised and no money',
        'moves because of it until the case is decided. You will get an email',
        'with the decision.',
        '',
      ].join('\n'),
    );
  });

  it('counts a reference it does not show, and says when there is none', () => {
    expect(
      breachNoticeText(notice({ evidenceRefs: ['photo 1.jpg', 'f-1'] })),
    ).toContain(
      'Evidence: 2 file reference(s)\n- f-1\n- 1 more not shown here',
    );
    expect(breachNoticeText(notice({ evidenceRefs: [] }))).toContain(
      'Evidence: none attached to the case yet.',
    );
  });

  it.each(BREACH_REASON_CODES)(
    'reason %s stays within 76 characters',
    (code) => {
      expect(
        lineLengths(breachNoticeText(notice({ breachReasonCode: code }))),
      ).toBe(true);
    },
  );
});

describe('NotifyRemedyFinalizedUseCase', () => {
  it('mails both parties remedy-finalized/v1 for a decision', async () => {
    const { logs, email, sender: s } = sender();
    const command = remedy();

    await new NotifyRemedyFinalizedUseCase(s).execute(command);

    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
    expect(email.sent[0].subject).toBe(
      `[AgriContract] Decision on contract ${DISPUTE_CONTRACT_ID}`,
    );
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: ANCHOR_RECIPIENTS[0].email,
        notificationType: 'REMEDY_FINALIZED',
      }),
    ).toMatchObject({
      templateVersion: 'remedy-finalized/v1',
      status: NotificationStatus.SENT,
    });
  });

  it('mails contract-completed/v1 for the D94 normal completion', async () => {
    const { logs, email, sender: s } = sender();
    const command = completion();

    await new NotifyRemedyFinalizedUseCase(s).execute(command);

    expect(email.sent[0].subject).toBe(
      `[AgriContract] Every milestone of contract ${DISPUTE_CONTRACT_ID} has settled`,
    );
    expect(
      logs.get({
        eventId: command.eventId,
        recipientEmail: ANCHOR_RECIPIENTS[1].email,
        notificationType: 'CONTRACT_COMPLETED',
      }),
    ).toMatchObject({ templateVersion: 'contract-completed/v1' });
  });

  it('tells the normal completion apart from every other no-fault SYSTEM decision', () => {
    expect(isNormalCompletion(completion())).toBe(true);
    expect(
      isNormalCompletion({ ...completion(), breachReasonCode: 'MUTUAL_EXIT' }),
    ).toBe(false);
    expect(
      isNormalCompletion({
        ...completion(),
        affectedMilestoneIds: [DISPUTE_MILESTONE_ID],
      }),
    ).toBe(false);
    expect(
      isNormalCompletion({ ...completion(), decisionSource: 'MUTUAL' }),
    ).toBe(false);
    expect(
      isNormalCompletion({ ...completion(), breachCaseId: BREACH_CASE_ID }),
    ).toBe(false);
    expect(
      isNormalCompletion({ ...completion(), qualityDisposition: 'CONFORMING' }),
    ).toBe(false);
    expect(isNormalCompletion(remedy())).toBe(false);
  });

  it('remedy-finalized/v1 for a fault: who is responsible, why, and each transfer with its receiver', () => {
    expect(remedyFinalizedText(remedy())).toBe(
      [
        'A decision was made on your AgriContract contract: the seller is',
        'responsible.',
        '',
        `Contract:     ${DISPUTE_CONTRACT_ID}`,
        `Decision:     ${DECISION_ID}`,
        `Breach case:  ${BREACH_CASE_ID}`,
        'Milestones:',
        `- ${DISPUTE_MILESTONE_ID}`,
        'Decided by:   an arbitrator',
        'Responsible:  SELLER',
        'Reason:       SHORTFALL',
        '              (less delivered than committed)',
        'Penalty:      yes',
        'Reputation:   affected',
        '',
        'Money, as decided (each line names who receives it):',
        '- 90,000,000.00 VND to the seller:',
        '  payment, from the milestone payment',
        '- 5,520,000.00 VND to the buyer:',
        "  forfeited deposit, from the seller's deposit",
        '',
        'escrow-service carries out these transfers.',
        '',
      ].join('\n'),
    );
  });

  const NO_NEW_BREACH = [
    'This decision finds no new breach. It does not change any earlier',
    'decision on this contract.',
  ];
  const CLOSES_ON_RECONCILIATION = [
    'The contract is not closed yet. It closes once escrow-service confirms',
    'that all money of the contract has cleared, including the final',
    'milestone payment and any earlier transfer that is still pending.',
  ];

  it('contract-completed/v1: no new breach, deposits returned, closure awaits reconciliation', () => {
    const body = remedyFinalizedText(completion());

    expect(body).toBe(
      [
        'Every milestone of your AgriContract contract has settled, and the',
        'platform has made its completion decision.',
        '',
        `Contract: ${DISPUTE_CONTRACT_ID}`,
        `Decision: ${DECISION_ID}`,
        '',
        ...NO_NEW_BREACH,
        '',
        'This decision returns the deposits still locked for the contract.',
        'Money, as decided (each line names who receives it):',
        '- 11,040,000.00 VND to the buyer:',
        "  refund, from the buyer's deposit",
        '- 5,520,000.00 VND to the seller:',
        "  payment, from the seller's deposit",
        '',
        'escrow-service carries out these transfers.',
        '',
        ...CLOSES_ON_RECONCILIATION,
        '',
      ].join('\n'),
    );
    expect(body).not.toMatch(/contract is closed|is complete|Nobody breached/);
  });

  it('contract-completed/v1 with no deposit left: no transfer, still not closed', () => {
    const body = remedyFinalizedText({ ...completion(), remedyLegs: [] });

    expect(body).toBe(
      [
        'Every milestone of your AgriContract contract has settled, and the',
        'platform has made its completion decision.',
        '',
        `Contract: ${DISPUTE_CONTRACT_ID}`,
        `Decision: ${DECISION_ID}`,
        '',
        ...NO_NEW_BREACH,
        '',
        'No deposit is still locked for the contract, so this decision',
        'moves no money.',
        '',
        ...CLOSES_ON_RECONCILIATION,
        '',
      ].join('\n'),
    );
    expect(body).not.toMatch(/contract is closed|is complete|Nobody breached/);
  });

  it('a quality resolution shows the disposition with the final attribution', () => {
    const body = remedyFinalizedText(
      remedy({
        finalBreachingRole: null,
        breachReasonCode: null,
        qualityDisposition: 'PARTIALLY_CONFORMING',
        decisionSource: 'INSPECTOR_L1_5',
        penaltyEligible: false,
        reputationEligible: false,
        remedyLegs: [],
      }),
    );

    expect(body).toContain(
      'The dispute on your AgriContract contract has been decided on the\nquality of the goods.',
    );
    expect(body).toContain(
      'Quality:      PARTIALLY_CONFORMING (part of the goods meets it)',
    );
    expect(body).toContain('Responsible:  no party');
    expect(body).toContain('Reason:       none');
    expect(body).toContain('Decided by:   the Level 1.5 inspection');
    expect(body).toContain('No money moves because of this decision.');
  });

  it('a no-fault decision on the whole contract says so', () => {
    const body = remedyFinalizedText(
      remedy({
        breachCaseId: null,
        affectedMilestoneIds: [],
        finalBreachingRole: null,
        breachReasonCode: 'MUTUAL_EXIT',
        decisionSource: 'MUTUAL',
        penaltyEligible: false,
        reputationEligible: false,
      }),
    );

    expect(body).toContain('No party is at\nfault.');
    expect(body).toContain('Milestones: the contract as a whole');
    expect(body).toContain('Breach case:  none');
    expect(body).toContain('Decided by:   both parties, by agreement');
  });

  it.each(BREACH_REASON_CODES)(
    'reason %s stays within 76 characters',
    (code) => {
      expect(
        lineLengths(
          remedyFinalizedText(
            remedy({
              breachReasonCode: code,
              remedyLegs: [
                {
                  remedyLegId: DISPUTE_MILESTONE_ID,
                  remedyType: 'DAMAGES_COMPENSATION',
                  fundType: 'MILESTONE_PAYMENT',
                  role: 'BUYER',
                  amount: '9999999999999999.99',
                },
              ],
            }),
          ),
        ),
      ).toBe(true);
    },
  );
});
