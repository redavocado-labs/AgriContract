import {
  FakeEmailPort,
  InMemoryNotificationLogRepository,
} from '../../../test/support/fakes';
import {
  ANCHOR_RECIPIENTS,
  ANCHOR_RECORD_HASH,
  DISPUTE_CONTRACT_ID,
  DISPUTE_MILESTONE_ID,
  OTS_PROOF_BASE64,
  OTS_PROOF_BYTES,
  OTS_PROOF_SHA256,
  milestoneAnchorCommand,
} from '../../../test/support/commands';
import type { MilestoneAnchorRequestedCommand } from '../commands';
import { NotificationStatus } from '../../domain/notification-log';
import { COMMAND_KINDS } from '../notification-command-kinds';
import { OtsProofInvalidError } from '../notification-errors';
import { NotificationSender } from '../notification-sender';
import {
  MAX_OTS_PROOF_BYTES,
  NotifyMilestoneAnchorUseCase,
  decodeOtsProof,
  milestoneAnchorText,
  proofName,
} from './notify-milestone-anchor.use-case';

function setup() {
  const logs = new InMemoryNotificationLogRepository();
  const email = new FakeEmailPort();
  const useCase = new NotifyMilestoneAnchorUseCase(
    new NotificationSender(logs, email, {
      logger: { error: () => undefined },
    }),
  );
  return { logs, email, useCase };
}

const command = (
  patch: Partial<MilestoneAnchorRequestedCommand> = {},
): MilestoneAnchorRequestedCommand => ({
  ...milestoneAnchorCommand(),
  ...patch,
});

const PROOF = Buffer.from(OTS_PROOF_BASE64, 'base64');

describe('NotifyMilestoneAnchorUseCase', () => {
  it('mails both parties milestone-anchor/v1 with the decoded proof attached', async () => {
    const { logs, email, useCase } = setup();
    const sent = command();

    await useCase.execute(sent);

    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
    for (const [i, recipient] of ANCHOR_RECIPIENTS.entries()) {
      expect(email.sent[i].subject).toBe(
        `[AgriContract] Milestone settled on contract ${DISPUTE_CONTRACT_ID}: timestamp proof`,
      );
      expect(email.sent[i].body).toBe(milestoneAnchorText(sent, PROOF));
      expect(email.sent[i].attachments).toEqual([
        {
          filename: `${DISPUTE_CONTRACT_ID}-${DISPUTE_MILESTONE_ID}.ots`,
          contentType: 'application/octet-stream',
          content: PROOF,
        },
      ]);
      expect(
        logs.get({
          eventId: sent.eventId,
          recipientEmail: recipient.email,
          notificationType: 'MILESTONE_ANCHOR',
        }),
      ).toMatchObject({
        status: NotificationStatus.SENT,
        eventType: 'notification.milestone_anchor_requested',
        templateVersion: COMMAND_KINDS.milestoneAnchor.templateVersion,
        recipientUserId: recipient.userId,
      });
    }
  });

  it('a redelivery sends nothing twice; a failure is retried for whoever is missing', async () => {
    const { email, useCase } = setup();
    email.failFor.add(ANCHOR_RECIPIENTS[1].email);

    await expect(useCase.execute(command())).rejects.toThrow();
    email.failFor.clear();
    await useCase.execute(command());
    await useCase.execute(command());

    expect(email.sent.map((m) => m.to)).toEqual(
      ANCHOR_RECIPIENTS.map((r) => r.email),
    );
  });

  it.each([
    ['empty', '', 'empty'],
    ['not base64', 'not base64!', 'not strict base64'],
    [
      'base64 without its padding',
      OTS_PROOF_BASE64.replace(/=+$/, ''),
      'not strict base64',
    ],
    ['base64url', 'AE9wZW5U_-1l', 'not strict base64'],
  ])('a proof that is %s is never mailed', async (_case, otsProof, reason) => {
    const { logs, email, useCase } = setup();

    const failure = useCase.execute(command({ otsProof }));

    await expect(failure).rejects.toBeInstanceOf(OtsProofInvalidError);
    await expect(failure).rejects.toThrow(
      `otsProof is not a usable proof (${reason})`,
    );
    expect(email.sent).toEqual([]);
    expect(logs.size).toBe(0);
  });
});

describe('decodeOtsProof', () => {
  it('decodes the fixture proof to its 65 bytes', () => {
    expect(decodeOtsProof('e', OTS_PROOF_BASE64)).toHaveLength(OTS_PROOF_BYTES);
  });

  it('refuses a proof over 1 MiB before decoding it, and never quotes it', () => {
    const big = 'A'.repeat((Math.ceil(MAX_OTS_PROOF_BYTES / 3) + 1) * 4);

    expect(() => decodeOtsProof('e', big)).toThrow('(over 1 MiB)');
    try {
      decodeOtsProof('e', 'secret proof text');
    } catch (err) {
      expect((err as Error).message).not.toContain('secret');
    }
  });

  it('accepts a proof of exactly 1 MiB', () => {
    const exact = Buffer.alloc(MAX_OTS_PROOF_BYTES, 1).toString('base64');

    expect(decodeOtsProof('e', exact)).toHaveLength(MAX_OTS_PROOF_BYTES);
  });
});

describe('milestoneAnchorText', () => {
  it('milestone-anchor/v1: the record hash, the proof size and SHA-256, how to verify', () => {
    expect(milestoneAnchorText(command(), PROOF)).toBe(
      [
        'A milestone of your AgriContract contract is settled. Its record is',
        "written to AgriContract's audit chain, and the OpenTimestamps proof of",
        'that record is attached to this email.',
        '',
        `Contract:       ${DISPUTE_CONTRACT_ID}`,
        `Milestone:      ${DISPUTE_MILESTONE_ID}`,
        'Amount locked:  110,400,000.00 VND',
        'Amount settled: 105,800,000.00 VND',
        '',
        'Audit record hash (SHA-256), the digest the proof commits to:',
        ANCHOR_RECORD_HASH,
        '',
        'Attached proof (.ots):',
        'Size:           65 bytes',
        'SHA-256 of the proof file:',
        OTS_PROOF_SHA256,
        '',
        'Keep the proof with this email. It lets anyone check, without',
        'AgriContract, that this record hash existed no later than the Bitcoin',
        'block the proof is anchored in, using the OpenTimestamps client:',
        '  ots verify -d <audit record hash> <attached .ots file>',
        'A fresh proof may still be pending in the OpenTimestamps calendars;',
        '"ots upgrade" completes it once the Bitcoin transaction is confirmed.',
        '',
      ].join('\n'),
    );
  });

  it.each([
    ['an empty summary', {}],
    [
      'amounts that are not the contract money',
      { lockedAmount: 1e8, actualAmount: '1e8' },
    ],
  ])('leaves the amounts out for %s', (_case, settlementSummary) => {
    expect(
      milestoneAnchorText(command({ settlementSummary }), PROOF),
    ).not.toContain('Amount');
  });

  it('keeps every line within 76 characters, the file name only as the attachment name', () => {
    const body = milestoneAnchorText(
      command({
        settlementSummary: {
          lockedAmount: '9999999999999999.99',
          actualAmount: '9999999999999999.99',
        },
      }),
      PROOF,
    );

    for (const line of body.split('\n')) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
    expect(proofName(DISPUTE_CONTRACT_ID, DISPUTE_MILESTONE_ID)).toHaveLength(
      77,
    );
  });
});
