import { NonRetryableError } from '@agricontract/eventing';
import {
  activationFailedBody,
  activationFailedCommand,
  activationFailedPayload,
  anchorBody,
  anchorCommand,
  anchorEnvelope,
  anchorPayload,
  cancelBody,
  cancelCommand,
  cancelEnvelope,
  cancelPayload,
  KYC_EMAIL,
  kycBody,
  kycCommand,
  kycPayload,
  kycVerifiedPayload,
  LOCK_EMAIL,
  lockBody,
  lockCommand,
  lockPayload,
  BREACH_TERMINATION,
  terminatedBody,
  terminatedCommand,
  terminatedPayload,
  WAITING_MILESTONE_ID,
  waitingBody,
  waitingCommand,
  waitingPayload,
  INSPECTOR_RECIPIENT,
  inspectorBody,
  inspectorCommand,
  inspectorPayload,
  legAlertBody,
  legAlertCommand,
  legAlertPayload,
  remedyLegAlertBody,
  remedyLegAlertCommand,
  remedyLegAlertPayload,
  remedyRejectedBody,
  remedyRejectedCommand,
  remedyRejectedPayload,
  fundingBody,
  fundingCommand,
  fundingPayload,
} from '../../../test/support/commands';
import {
  parseActivationCancelRequested,
  CONTRACT_ACTIVATION_FAILED_FIELDS,
  parseContractActivationFailedRequested,
  CONTRACT_TERMINATED_FIELDS,
  parseContractAnchorRequested,
  parseContractTerminatedRequested,
  parseUserKycResultRequested,
  USER_KYC_RESULT_REQUIRED,
  parseUserLockChangedRequested,
  USER_LOCK_CHANGED_REQUIRED,
  LEVEL15_ASSIGNMENT_WAITING_FIELDS,
  parseLevel15AssignmentWaitingRequested,
  LEVEL15_INSPECTOR_ASSIGNMENT_FIELDS,
  parseLevel15InspectorAssignmentRequested,
  ESCROW_LEG_ALERT_FIELDS,
  parseEscrowLegAlertRequested,
  ESCROW_REMEDY_LEG_ALERT_FIELDS,
  parseEscrowRemedyLegAlertRequested,
  ESCROW_REMEDY_REJECTED_FIELDS,
  parseEscrowRemedyRejectedRequested,
  MILESTONE_FUNDING_STATUS_REQUIRED,
  parseMilestoneFundingStatusRequested,
} from './notification-command.parsers';

/** Delivers a message built from `overrides` on the envelope. */
const parse = (overrides: Record<string, unknown> = {}) =>
  parseContractAnchorRequested(anchorBody(overrides));

/** Delivers a message whose payload is `anchorPayload(overrides)`. */
const parsePayload = (overrides: Record<string, unknown>) =>
  parse({ payload: anchorPayload(overrides) });

const message = (fn: () => unknown): string => {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(NonRetryableError);
    return (err as Error).message;
  }
  throw new Error('expected the parser to throw');
};

describe('parseContractAnchorRequested', () => {
  it('reads the envelope eventId and every payload field', () => {
    expect(parse()).toEqual(anchorCommand());
  });

  it('takes the dedup event from the envelope, not from the payload', () => {
    const eventId = '7c6b5a49-3827-4615-8403-2f1e0d9c8b7a';

    expect(parse({ eventId }).eventId).toBe(eventId);
  });

  it('keeps a null userId null: the recipient has no account', () => {
    const recipients = [
      { userId: null, email: 'contacts@software-buyer.test', role: 'ADMIN' },
    ];

    expect(parsePayload({ recipients }).recipients).toEqual(recipients);
  });

  describe('rejects, without retrying, a message that', () => {
    it('is not JSON', () => {
      expect(() =>
        parseContractAnchorRequested(Buffer.from('{not json')),
      ).toThrow(NonRetryableError);
    });

    it('has no envelope (a flat Phase 1 style payload)', () => {
      expect(() =>
        parseContractAnchorRequested(
          Buffer.from(JSON.stringify(anchorPayload())),
        ),
      ).toThrow(NonRetryableError);
    });

    it.each([
      ['eventId', { eventId: 'not-a-uuid' }],
      ['eventVersion', { eventVersion: 0 }],
      ['occurredAt', { occurredAt: '2026-11-08 09:30:00' }],
      ['producer', { producer: 'audit-service' }],
      ['aggregateId', { aggregateId: '' }],
      ['correlationId', { correlationId: null }],
      ['eventType', { eventType: 'notification.audit_digest_requested' }],
      ['payload', { payload: [] }],
    ])('has a bad %s', (_field, overrides) => {
      expect(() => parse(overrides)).toThrow(NonRetryableError);
    });

    /*
     * The command envelope of notification-commands.yaml is stricter than
     * event-envelope.yaml: correlationId is required and `subject` is not an
     * allowed property.
     */
    it('carries a `subject`, which the command envelope does not allow', () => {
      expect(() => parse({ subject: { type: 'CONTRACT', id: 'c-1' } })).toThrow(
        NonRetryableError,
      );
    });

    it.each([
      ['contractId', { contractId: 'contract-1' }],
      ['contractTermsSnapshot', { contractTermsSnapshot: 'frozen' }],
      ['signedContentHash', { signedContentHash: 'A'.repeat(64) }],
      ['signedContentHash length', { signedContentHash: 'ab' }],
      ['signedAt', { signedAt: 'yesterday' }],
    ])('has a bad %s', (_field, overrides) => {
      expect(() => parsePayload(overrides)).toThrow(NonRetryableError);
    });

    it('adds a payload property the command does not declare', () => {
      expect(() => parsePayload({ otsProof: 'base64' })).toThrow(
        NonRetryableError,
      );
    });

    it.each([
      ['no recipients at all', []],
      ['a recipient that is not an object', ['buyer@anchor.test']],
      ['a recipient without userId', [{ email: 'b@x.test', role: 'BUYER' }]],
      [
        'a userId that is not a UUID',
        [{ userId: 'u-1', email: 'b@x.test', role: 'BUYER' }],
      ],
      [
        'an address that is not one',
        [{ userId: null, email: 'not an address', role: 'BUYER' }],
      ],
      [
        'a role outside the contract',
        [{ userId: null, email: 'b@x.test', role: 'BANK' }],
      ],
      [
        'a property the recipient does not declare',
        [{ userId: null, email: 'b@x.test', role: 'BUYER', name: 'Bao' }],
      ],
    ])('has %s', (_case, recipients) => {
      expect(() => parsePayload({ recipients })).toThrow(NonRetryableError);
    });
  });

  /*
   * These messages are logged on every dead-letter (review #172), and
   * `payload.recipients` holds email addresses.
   */
  describe('error messages', () => {
    it('name the command, the field and the event, and nothing else', () => {
      const text = message(() => parsePayload({ signedAt: 'yesterday' }));

      expect(text).toContain('notification.contract_anchor_requested');
      expect(text).toContain('"signedAt"');
      expect(text).toContain(anchorEnvelope().eventId as string);
      expect(text).not.toContain('yesterday');
    });

    it('never quote a recipient address, not even the bad one', () => {
      const text = message(() =>
        parsePayload({
          recipients: [
            { userId: null, email: 'leaked@anchor.test', role: 'BANK' },
          ],
        }),
      );

      expect(text).toContain('recipients[0].role');
      expect(text).not.toContain('leaked@anchor.test');
      expect(text).not.toContain('BANK');
    });

    it('point at the recipient by index', () => {
      const text = message(() =>
        parsePayload({
          recipients: [
            { userId: null, email: 'ok@anchor.test', role: 'BUYER' },
            { userId: null, email: 'bad address', role: 'SELLER' },
          ],
        }),
      );

      expect(text).toContain('recipients[1].email');
      expect(text).not.toContain('bad address');
    });

    it('omit an event id that is not a plain id', () => {
      const text = message(() => parse({ eventId: 'not-a-uuid-but-<script>' }));

      expect(text).not.toContain('<script>');
    });
  });
});

describe('parseActivationCancelRequested', () => {
  const parseCancel = (payload: Record<string, unknown> = {}) =>
    parseActivationCancelRequested(
      cancelBody({ payload: cancelPayload(payload) }),
    );

  it('reads the envelope eventId and every payload field', () => {
    expect(parseCancel()).toEqual(cancelCommand());
  });

  it('reads a seller request', () => {
    expect(parseCancel({ requestedByRole: 'SELLER' }).requestedByRole).toBe(
      'SELLER',
    );
  });

  it.each([
    ['requestedByRole', 'OPERATOR', 'must be one of BUYER, SELLER'],
    ['requestedByRole', null, 'must be one of BUYER, SELLER'],
    ['oneSidedCancelAllowedFrom', '2026-11-14', 'RFC 3339'],
    ['contractId', 'contract-1', 'must be a UUID'],
    ['trigger', 'ONE_SIDED', 'unknown payload property "trigger"'],
  ])(
    'rejects %s = %p, naming the field and not the value',
    (field, value, reason) => {
      const text = message(() => parseCancel({ [field]: value }));

      expect(text).toContain(
        'Malformed notification.activation_cancel_requested',
      );
      expect(text).toContain(reason);
      if (typeof value === 'string' && field !== 'trigger') {
        expect(text).not.toContain(`"${value}"`);
      }
    },
  );

  it('rejects a missing requestedByRole', () => {
    const payload = cancelPayload();
    delete payload.requestedByRole;

    expect(
      message(() =>
        parseActivationCancelRequested(
          Buffer.from(JSON.stringify(cancelEnvelope({ payload }))),
        ),
      ),
    ).toContain('requestedByRole');
  });

  it('rejects the anchor command, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseActivationCancelRequested(anchorBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseContractActivationFailedRequested', () => {
  const parseFailed = (payload: Record<string, unknown> = {}) =>
    parseContractActivationFailedRequested(
      activationFailedBody({ payload: activationFailedPayload(payload) }),
    );

  it('reads the envelope eventId and every payload field', () => {
    expect(parseFailed()).toEqual(activationFailedCommand());
  });

  it.each([
    ['an empty buyer state', { buyerDepositState: '' }],
    ['a state it has no words for', { sellerDepositState: 'RETURNED' }],
    ['no next action', { nextActions: [] }],
    [
      'a repeated next action',
      { nextActions: ['RETRY_DEPOSIT_LOCK', 'RETRY_DEPOSIT_LOCK'] },
    ],
    ['failedLeg BOTH', { failedLeg: 'BOTH' }],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseFailed(patch)).toMatchObject(patch);
  });

  it.each([
    [
      'failedLeg',
      'NONE',
      'field "failedLeg" must be one of BUYER, SELLER, BOTH',
    ],
    ['buyerDepositState', null, 'field "buyerDepositState" must be a string'],
    ['sellerDepositState', 7, 'field "sellerDepositState" must be a string'],
    [
      'nextActions',
      'RETRY_DEPOSIT_LOCK',
      'field "nextActions" must be an array',
    ],
    [
      'nextActions',
      ['RETRY_DEPOSIT_LOCK', 'MARK_FAILED'],
      'nextActions[1] must be one of RETRY_DEPOSIT_LOCK, REQUEST_ACTIVATION_CANCEL',
    ],
    ['oneSidedCancelAllowedFrom', '2026-11-14', 'RFC 3339'],
    ['contractId', 'contract-1', 'must be a UUID'],
    [
      'reason',
      'BUYER: insufficient funds',
      'unknown payload property "reason"',
    ],
  ])(
    'rejects %s = %p, naming the field and not the value',
    (field, value, reason) => {
      const text = message(() => parseFailed({ [field]: value }));

      expect(text).toContain(
        'Malformed notification.contract_activation_failed_requested',
      );
      expect(text).toContain(reason);
      if (typeof value === 'string' && field !== 'reason') {
        expect(text).not.toContain(`"${value}"`);
      }
      if (field === 'reason') expect(text).not.toContain('insufficient');
      if (Array.isArray(value)) expect(text).not.toContain('MARK_FAILED');
    },
  );

  it.each(CONTRACT_ACTIVATION_FAILED_FIELDS)(
    'rejects a missing %s',
    (field) => {
      const payload = activationFailedPayload();
      delete payload[field];

      expect(
        message(() =>
          parseContractActivationFailedRequested(
            activationFailedBody({ payload }),
          ),
        ),
      ).toContain(field);
    },
  );

  it('rejects the activation-cancel command, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseContractActivationFailedRequested(cancelBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseContractTerminatedRequested', () => {
  const parseTerminated = (payload: Record<string, unknown> = {}) =>
    parseContractTerminatedRequested(
      terminatedBody({ payload: terminatedPayload(payload) }),
    );

  it('reads the envelope eventId and every payload field of a withdrawn offer', () => {
    expect(parseTerminated()).toEqual(terminatedCommand());
  });

  it('reads every field of a breach termination too: all types parse', () => {
    expect(parseTerminated(BREACH_TERMINATION)).toMatchObject(
      BREACH_TERMINATION,
    );
  });

  it.each([
    ['terminationType', 'CANCELLED', 'must be one of WITHDRAW_OFFER,'],
    ['requestedBy', 'OPERATOR', 'must be one of BUYER, SELLER, SYSTEM'],
    ['finalBreachingRole', 'SYSTEM', 'must be one of BUYER, SELLER'],
    ['breachReasonCode', 'LATE', 'field "breachReasonCode" must be one of'],
    [
      'remedyDecisionId',
      'decision-1',
      'field "remedyDecisionId" must be a UUID',
    ],
    [
      'replacesContractId',
      7,
      'field "replacesContractId" must be a non-empty string',
    ],
    [
      'affectedMilestoneIds',
      null,
      'field "affectedMilestoneIds" must be an array',
    ],
    ['affectedMilestoneIds', ['m-1'], 'affectedMilestoneIds[0] must be a UUID'],
    [
      'affectedMilestoneIds',
      [
        '2c3d4e5f-6a7b-4c8d-8e9f-1a2b3c4d5e6f',
        '2c3d4e5f-6a7b-4c8d-8e9f-1a2b3c4d5e6f',
      ],
      'affectedMilestoneIds[1] repeats an earlier item',
    ],
    ['remedyLegs', {}, 'field "remedyLegs" must be an array'],
    ['reason', 'changed my mind', 'unknown payload property "reason"'],
  ])(
    'rejects %s = %p, naming the field and not the value',
    (field, value, reason) => {
      const text = message(() => parseTerminated({ [field]: value }));

      expect(text).toContain(
        'Malformed notification.contract_terminated_requested',
      );
      expect(text).toContain(reason);
      if (typeof value === 'string' && field !== 'reason') {
        expect(text).not.toContain(`"${value}"`);
      }
      expect(text).not.toContain('changed my mind');
    },
  );

  it.each(CONTRACT_TERMINATED_FIELDS)(
    'rejects a missing %s, nullable or not',
    (field) => {
      const payload = terminatedPayload();
      delete payload[field];

      expect(
        message(() =>
          parseContractTerminatedRequested(terminatedBody({ payload })),
        ),
      ).toContain(field);
    },
  );

  describe('remedyLegs', () => {
    const leg = (BREACH_TERMINATION.remedyLegs as Record<string, unknown>[])[0];
    const withLeg = (patch: Record<string, unknown>) =>
      parseTerminated({
        ...BREACH_TERMINATION,
        remedyLegs: [{ ...leg, ...patch }],
      });

    it.each([
      ['remedyLegId', 'leg-1', 'remedyLegs[0].remedyLegId must be a UUID'],
      ['remedyType', 'FINE', 'remedyLegs[0].remedyType must be one of'],
      ['fundType', 'ESCROW', 'remedyLegs[0].fundType must be one of'],
      ['role', 'SYSTEM', 'remedyLegs[0].role must be one of BUYER, SELLER'],
      ['amount', '7126425', 'remedyLegs[0].amount must be a decimal string'],
      ['amount', 7126425, 'remedyLegs[0].amount must be a decimal string'],
      ['amount', '-1.00', 'remedyLegs[0].amount must be a decimal string'],
      ['amount', `${'9'.repeat(17)}.00`, 'at most 19 characters'],
      ['note', 'x', 'unknown property "note" in remedyLegs[0]'],
    ])('rejects %s = %p', (field, value, reason) => {
      expect(message(() => withLeg({ [field]: value }))).toContain(reason);
    });

    it('accepts the largest amount the contract allows', () => {
      const amount = `${'9'.repeat(16)}.99`;
      expect(withLeg({ amount }).remedyLegs[0].amount).toBe(amount);
    });
  });

  it('rejects the activation-cancel command, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseContractTerminatedRequested(cancelBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseUserKycResultRequested', () => {
  const parseKyc = (payload: Record<string, unknown> = kycPayload()) =>
    parseUserKycResultRequested(kycBody({ payload }));

  it('reads the envelope eventId and every payload field of a rejection', () => {
    expect(parseKyc()).toEqual(kycCommand());
  });

  it('reads a verification, whose reason user-service leaves out, as a null reason', () => {
    expect(parseKyc(kycVerifiedPayload())).toEqual({
      ...kycCommand(),
      result: 'VERIFIED',
      reason: null,
    });
  });

  it.each([
    ['a rejection without a reason', kycPayload({ reason: undefined })],
    ['a verification with a reason', kycVerifiedPayload({ reason: 'ok' })],
    ['an empty organisation name', kycPayload({ organizationName: '' })],
    ['an empty reason', kycPayload({ reason: '' })],
  ])('accepts %s, as the contract does', (_case, payload) => {
    // JSON drops undefined, as it drops the property.
    const sent = JSON.parse(JSON.stringify(payload)) as Record<string, unknown>;
    expect(() => parseKyc(sent)).not.toThrow();
  });

  it.each([
    ['result', 'PENDING', 'field "result" must be one of VERIFIED, REJECTED'],
    ['result', 'rejected', 'field "result" must be one of VERIFIED, REJECTED'],
    ['reason', null, 'field "reason" must be a string, got null'],
    ['organizationName', null, 'field "organizationName" must be a string'],
    ['recipientEmail', 'owner.kyc.test', 'must be an email address'],
    ['userId', 'user-1', 'field "userId" must be a UUID'],
    ['decidedAt', '2026-11-11', 'RFC 3339'],
    ['recipients', [], 'unknown payload property "recipients"'],
  ])(
    'rejects %s = %p, naming the field and not the value',
    (field, value, reason) => {
      const text = message(() => parseKyc(kycPayload({ [field]: value })));

      expect(text).toContain(
        'Malformed notification.user_kyc_result_requested',
      );
      expect(text).toContain(reason);
      if (typeof value === 'string') expect(text).not.toContain(`"${value}"`);
    },
  );

  it.each(USER_KYC_RESULT_REQUIRED)('rejects a missing %s', (field) => {
    const payload = kycPayload();
    delete payload[field];

    expect(message(() => parseKyc(payload))).toContain(`"${field}"`);
  });

  it('never quotes the address it rejects', () => {
    const text = message(() =>
      parseKyc(kycPayload({ recipientEmail: `${KYC_EMAIL} ` })),
    );

    expect(text).not.toContain(KYC_EMAIL);
  });

  it('rejects the command from another producer', () => {
    expect(
      message(() =>
        parseUserKycResultRequested(kycBody({ producer: 'contract-service' })),
      ),
    ).toContain('producer must be "user-service"');
  });

  it('rejects the activation-cancel command, whose eventType is not this queue’s', () => {
    expect(message(() => parseUserKycResultRequested(cancelBody()))).toContain(
      "is not this queue's command",
    );
  });
});

describe('parseUserLockChangedRequested', () => {
  const parseLock = (payload: Record<string, unknown> = lockPayload()) =>
    parseUserLockChangedRequested(lockBody({ payload }));

  it('reads the envelope eventId and every payload field of a lock', () => {
    expect(parseLock()).toEqual(lockCommand());
  });

  it.each([
    [
      'an unlock with no lock left',
      { action: 'UNLOCKED', effectiveUntil: null },
    ],
    [
      'an unlock with another lock still on',
      { action: 'UNLOCKED', effectiveUntil: '2026-11-20T00:00:00Z' },
    ],
    ['a lock without an end', { effectiveUntil: null }],
    ['an empty reason code', { reasonCode: '' }],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseLock(lockPayload(patch))).toMatchObject(patch);
  });

  it('reads an absent effectiveUntil as null, since it is not required', () => {
    const payload = lockPayload();
    delete payload.effectiveUntil;

    expect(parseLock(payload).effectiveUntil).toBeNull();
  });

  it.each([
    ['action', 'SUSPENDED', 'field "action" must be one of LOCKED, UNLOCKED'],
    ['action', 'locked', 'field "action" must be one of LOCKED, UNLOCKED'],
    ['effectiveUntil', '2026-12-11', 'RFC 3339'],
    ['effectiveUntil', 0, 'must be a non-empty string'],
    ['reasonCode', null, 'field "reasonCode" must be a string, got null'],
    ['recipientEmail', 'trader.lock.test', 'must be an email address'],
    ['userId', 'user-1', 'field "userId" must be a UUID'],
    ['lockRevision', 3, 'unknown payload property "lockRevision"'],
  ])(
    'rejects %s = %p, naming the field and not the value',
    (field, value, reason) => {
      const text = message(() => parseLock(lockPayload({ [field]: value })));

      expect(text).toContain(
        'Malformed notification.user_lock_changed_requested',
      );
      expect(text).toContain(reason);
      if (typeof value === 'string') expect(text).not.toContain(`"${value}"`);
    },
  );

  it.each(USER_LOCK_CHANGED_REQUIRED)('rejects a missing %s', (field) => {
    const payload = lockPayload();
    delete payload[field];

    expect(message(() => parseLock(payload))).toContain(`"${field}"`);
  });

  it('never quotes the address it rejects', () => {
    const text = message(() =>
      parseLock(lockPayload({ recipientEmail: `${LOCK_EMAIL} ` })),
    );

    expect(text).not.toContain(LOCK_EMAIL);
  });

  it('rejects the KYC-result command, whose eventType is not this queue’s', () => {
    expect(message(() => parseUserLockChangedRequested(kycBody()))).toContain(
      "is not this queue's command",
    );
  });
});

describe('parseLevel15AssignmentWaitingRequested', () => {
  const parseWaiting = (payload: Record<string, unknown> = waitingPayload()) =>
    parseLevel15AssignmentWaitingRequested(waitingBody({ payload }));

  it('reads the envelope eventId and every payload field', () => {
    expect(parseWaiting()).toEqual(waitingCommand());
  });

  it.each([
    ['a wait that goes on', { stage: 'STILL_WAITING' }],
    ['an assignment at last', { stage: 'ASSIGNED' }],
    ['a re-pick with everyone excluded', { reason: 'ALL_EXCLUDED' }],
    [
      'an instant with an offset',
      { waitingSince: '2026-11-16T10:00:00+07:00' },
    ],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseWaiting(waitingPayload(patch))).toMatchObject(patch);
  });

  it.each([
    [
      'stage',
      'DONE',
      'field "stage" must be one of WAITING_STARTED, STILL_WAITING, ASSIGNED',
    ],
    ['stage', 'assigned', 'field "stage" must be one of'],
    [
      'reason',
      'NO_INSPECTOR',
      'field "reason" must be one of NO_ELIGIBLE, ALL_EXCLUDED',
    ],
    ['reason', null, 'field "reason" must be one of'],
    ['waitingSince', '2026-11-16', 'RFC 3339'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    ['milestoneId', 42, 'field "milestoneId" must be a non-empty string'],
    [
      'inspectorId',
      WAITING_MILESTONE_ID,
      'unknown payload property "inspectorId"',
    ],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseWaiting(waitingPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.level1_5_assignment_waiting_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it.each(LEVEL15_ASSIGNMENT_WAITING_FIELDS)(
    'rejects a missing %s',
    (field) => {
      const payload = waitingPayload();
      delete payload[field];

      expect(message(() => parseWaiting(payload))).toContain(`"${field}"`);
    },
  );

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseLevel15AssignmentWaitingRequested(
          waitingBody({ producer: 'contract-service' }),
        ),
      ),
    ).toContain('producer must be "inspection-service"');
  });

  it('rejects the lock-changed command, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseLevel15AssignmentWaitingRequested(lockBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseLevel15InspectorAssignmentRequested', () => {
  const parseInspector = (
    payload: Record<string, unknown> = inspectorPayload(),
  ) => parseLevel15InspectorAssignmentRequested(inspectorBody({ payload }));
  const OTHER = 'other.inspector@inspect.test';

  it('reads the envelope eventId, the one inspector and every payload field', () => {
    expect(parseInspector()).toEqual(inspectorCommand());
  });

  it.each([
    ['a cancellation', { stage: 'CANCELLED' }],
    [
      'instants with an offset',
      {
        reportDueAt: '2026-11-25T17:00:00+07:00',
        freeCancelUntil: '2026-11-21T09:00:00+07:00',
      },
    ],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseInspector(inspectorPayload(patch))).toMatchObject(patch);
  });

  it('accepts an inspector without a platform id (userId null)', () => {
    expect(
      parseInspector(
        inspectorPayload({
          recipients: [{ ...INSPECTOR_RECIPIENT, userId: null }],
        }),
      ).recipient,
    ).toEqual({ ...INSPECTOR_RECIPIENT, userId: null });
  });

  /*
   * The contract narrows recipients to one INSPECTOR (maxItems 1, role
   * const): anything else is not a mail this template may send (#493 AC).
   */
  it.each([
    [
      'a second recipient',
      [{ ...INSPECTOR_RECIPIENT }, { ...INSPECTOR_RECIPIENT, email: OTHER }],
      'field "recipients" must hold exactly one inspector, got 2',
    ],
    [
      'a BUYER',
      [{ ...INSPECTOR_RECIPIENT, role: 'BUYER' }],
      'recipients[0].role must be INSPECTOR',
    ],
    [
      'an OPERATOR',
      [{ ...INSPECTOR_RECIPIENT, role: 'OPERATOR' }],
      'recipients[0].role must be INSPECTOR',
    ],
    ['no recipient', [], 'field "recipients" must be a non-empty array'],
    [
      'an unknown role',
      [{ ...INSPECTOR_RECIPIENT, role: 'VERIFIER' }],
      'recipients[0].role must be one of',
    ],
  ])(
    'rejects %s, without retrying and without the address',
    (_case, recipients, reason) => {
      const text = message(() =>
        parseInspector(inspectorPayload({ recipients })),
      );

      expect(text).toContain(
        'Malformed notification.level1_5_inspector_assignment_requested',
      );
      expect(text).toContain(reason);
      expect(text).not.toContain(INSPECTOR_RECIPIENT.email);
      expect(text).not.toContain(OTHER);
    },
  );

  it.each([
    [
      'stage',
      'STILL_WAITING',
      'field "stage" must be one of ASSIGNED, CANCELLED',
    ],
    ['stage', 'assigned', 'field "stage" must be one of'],
    ['reportDueAt', '2026-11-25', 'RFC 3339'],
    ['freeCancelUntil', null, '"freeCancelUntil"'],
    ['commissionId', 'commission-1', 'field "commissionId" must be a UUID'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    ['milestoneId', 42, 'field "milestoneId" must be a non-empty string'],
    [
      'inspectorId',
      INSPECTOR_RECIPIENT.userId,
      'unknown payload property "inspectorId"',
    ],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseInspector(inspectorPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.level1_5_inspector_assignment_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it.each(LEVEL15_INSPECTOR_ASSIGNMENT_FIELDS)(
    'rejects a missing %s',
    (field) => {
      const payload = inspectorPayload();
      delete payload[field];

      expect(message(() => parseInspector(payload))).toContain(`"${field}"`);
    },
  );

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseLevel15InspectorAssignmentRequested(
          inspectorBody({ producer: 'contract-service' }),
        ),
      ),
    ).toContain('producer must be "inspection-service"');
  });

  it('rejects the waiting alert, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseLevel15InspectorAssignmentRequested(waitingBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseEscrowLegAlertRequested', () => {
  const parseLegAlert = (
    payload: Record<string, unknown> = legAlertPayload(),
  ) => parseEscrowLegAlertRequested(legAlertBody({ payload }));

  it('reads the envelope eventId and every payload field', () => {
    expect(parseLegAlert()).toEqual(legAlertCommand());
  });

  it.each([
    ['a stuck leg that resolved', { stage: 'RESOLVED', legStatus: 'LOCKED' }],
    [
      'a return out of resends',
      { stage: 'RETURN_EXHAUSTED', legStatus: 'RETURN_FAILED' },
    ],
    ['a stuck cancel', { legStatus: 'CANCEL_PENDING' }],
    ['a stuck return', { legStatus: 'RETURN_REQUESTED' }],
    ['the seller’s leg', { party: 'SELLER' }],
    ['a zero amount', { amount: '0.00' }],
    ['the largest amount', { amount: '9999999999999999.99' }],
    ['an instant with an offset', { since: '2026-11-21T10:00:00+07:00' }],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseLegAlert(legAlertPayload(patch))).toMatchObject(patch);
  });

  it.each([
    [
      'stage',
      'STALLED',
      'field "stage" must be one of STUCK, RESOLVED, RETURN_EXHAUSTED',
    ],
    ['stage', 'stuck', 'field "stage" must be one of'],
    ['legKind', 'FUNDING', 'field "legKind" must be one of DEPOSIT'],
    ['party', 'INSPECTOR', 'field "party" must be one of BUYER, SELLER'],
    ['legStatus', 'PENDING', 'field "legStatus" must be one of REQUESTED,'],
    ['amount', '150000000', 'field "amount" must be a decimal string'],
    ['amount', 150000000, 'field "amount" must be a decimal string'],
    ['amount', '-1.00', 'field "amount" must be a decimal string'],
    ['amount', '10000000000000000.00', 'at most 19 characters'],
    ['since', '2026-11-21', 'RFC 3339'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    ['legId', 42, 'field "legId" must be a non-empty string'],
    [
      'bankCommandEventId',
      'bank-cmd-1',
      'field "bankCommandEventId" must be a UUID',
    ],
    ['recipients', [], 'unknown payload property "recipients"'],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseLegAlert(legAlertPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.escrow_leg_alert_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it.each(ESCROW_LEG_ALERT_FIELDS)('rejects a missing %s', (field) => {
    const payload = legAlertPayload();
    delete payload[field];

    expect(message(() => parseLegAlert(payload))).toContain(`"${field}"`);
  });

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseEscrowLegAlertRequested(
          legAlertBody({ producer: 'bank-service' }),
        ),
      ),
    ).toContain('producer must be "escrow-service"');
  });

  it('rejects the Level 1.5 waiting alert, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseEscrowLegAlertRequested(waitingBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseEscrowRemedyLegAlertRequested', () => {
  const parseRemedyLegAlert = (
    payload: Record<string, unknown> = remedyLegAlertPayload(),
  ) => parseEscrowRemedyLegAlertRequested(remedyLegAlertBody({ payload }));

  it('reads the envelope eventId and every payload field', () => {
    expect(parseRemedyLegAlert()).toEqual(remedyLegAlertCommand());
  });

  it.each([
    ['the buyer as payee', { party: 'BUYER' }],
    ['a zero amount', { amount: '0.00' }],
    ['the largest amount', { amount: '9999999999999999.99' }],
    ['an instant with an offset', { since: '2026-11-24T15:14:59+07:00' }],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseRemedyLegAlert(remedyLegAlertPayload(patch))).toMatchObject(
      patch,
    );
  });

  it.each([
    ['party', 'INSPECTOR', 'field "party" must be one of BUYER, SELLER'],
    ['party', 'seller', 'field "party" must be one of BUYER, SELLER'],
    ['amount', '45000000', 'field "amount" must be a decimal string'],
    ['amount', 45000000, 'field "amount" must be a decimal string'],
    ['amount', '-1.00', 'field "amount" must be a decimal string'],
    ['amount', '10000000000000000.00', 'at most 19 characters'],
    ['since', '2026-11-24', 'RFC 3339'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    [
      'remedyDecisionId',
      'decision-1',
      'field "remedyDecisionId" must be a UUID',
    ],
    ['remedyLegId', 42, 'field "remedyLegId" must be a non-empty string'],
    [
      'bankCommandEventId',
      'bank-cmd-1',
      'field "bankCommandEventId" must be a UUID',
    ],
    ['recipients', [], 'unknown payload property "recipients"'],
    // Fields of the deposit leg alert are not this command's.
    ['legStatus', 'FAILED', 'unknown payload property "legStatus"'],
    ['stage', 'RETURN_EXHAUSTED', 'unknown payload property "stage"'],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseRemedyLegAlert(remedyLegAlertPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.escrow_remedy_leg_alert_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it.each(ESCROW_REMEDY_LEG_ALERT_FIELDS)('rejects a missing %s', (field) => {
    const payload = remedyLegAlertPayload();
    delete payload[field];

    expect(message(() => parseRemedyLegAlert(payload))).toContain(`"${field}"`);
  });

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseEscrowRemedyLegAlertRequested(
          remedyLegAlertBody({ producer: 'contract-service' }),
        ),
      ),
    ).toContain('producer must be "escrow-service"');
  });

  it('rejects the deposit leg alert, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseEscrowRemedyLegAlertRequested(legAlertBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseEscrowRemedyRejectedRequested', () => {
  const parseRemedyRejected = (
    payload: Record<string, unknown> = remedyRejectedPayload(),
  ) => parseEscrowRemedyRejectedRequested(remedyRejectedBody({ payload }));

  it('reads the envelope eventId and every payload field', () => {
    expect(parseRemedyRejected()).toEqual(remedyRejectedCommand());
  });

  it.each([
    ['a one-character reason', { reason: 'x' }],
    ['a 500-character reason', { reason: 'r'.repeat(500) }],
    // JSON Schema counts code points: these 500 are 1000 UTF-16 units.
    ['500 characters outside the BMP', { reason: '\u{1F4B8}'.repeat(500) }],
    ['a reason over several lines', { reason: 'first line\nsecond line' }],
    ['an instant with an offset', { rejectedAt: '2026-11-24T15:00:00+07:00' }],
  ])('accepts %s, as the contract does', (_case, patch) => {
    expect(parseRemedyRejected(remedyRejectedPayload(patch))).toMatchObject(
      patch,
    );
  });

  it.each([
    ['reason', '', 'field "reason" must be 1 to 500 characters, got 0'],
    [
      'reason',
      'r'.repeat(501),
      'field "reason" must be 1 to 500 characters, got 501',
    ],
    ['reason', 42, 'field "reason" must be a string, got a number'],
    ['reason', null, 'field "reason" must be a string, got null'],
    ['rejectedAt', '2026-11-24', 'RFC 3339'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    [
      'remedyDecisionId',
      'decision-1',
      'field "remedyDecisionId" must be a UUID',
    ],
    ['recipients', [], 'unknown payload property "recipients"'],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseRemedyRejected(remedyRejectedPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.escrow_remedy_rejected_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it('never quotes the reason, which is free text', () => {
    const reason = 'secret words '.repeat(40);

    expect(
      message(() => parseRemedyRejected(remedyRejectedPayload({ reason }))),
    ).not.toContain('secret');
  });

  it.each(ESCROW_REMEDY_REJECTED_FIELDS)('rejects a missing %s', (field) => {
    const payload = remedyRejectedPayload();
    delete payload[field];

    expect(message(() => parseRemedyRejected(payload))).toContain(`"${field}"`);
  });

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseEscrowRemedyRejectedRequested(
          remedyRejectedBody({ producer: 'contract-service' }),
        ),
      ),
    ).toContain('producer must be "escrow-service"');
  });

  it('rejects the remedy leg alert, whose eventType is not this queue’s', () => {
    expect(
      message(() => parseEscrowRemedyRejectedRequested(remedyLegAlertBody())),
    ).toContain("is not this queue's command");
  });
});

describe('parseMilestoneFundingStatusRequested', () => {
  const parseFunding = (payload: Record<string, unknown> = fundingPayload()) =>
    parseMilestoneFundingStatusRequested(fundingBody({ payload }));

  it('reads the envelope eventId and every payload field', () => {
    expect(parseFunding()).toEqual(fundingCommand());
  });

  it.each(['FUNDING_REMINDER', 'FUNDING_FAILED', 'FUNDING_CURE_DEADLINE'])(
    'accepts statusType %s',
    (statusType) => {
      expect(parseFunding(fundingPayload({ statusType })).statusType).toBe(
        statusType,
      );
    },
  );

  it.each([
    ['deadline', null],
    ['reason', null],
  ] as const)('reads %s = null as null', (field, value) => {
    expect(parseFunding(fundingPayload({ [field]: value }))[field]).toBeNull();
  });

  it.each(['deadline', 'reason'])('reads an absent %s as null', (field) => {
    const payload = fundingPayload();
    delete payload[field];

    expect(parseFunding(payload)[field as 'deadline' | 'reason']).toBeNull();
  });

  it('keeps an empty reason, as the contract allows', () => {
    expect(parseFunding(fundingPayload({ reason: '' })).reason).toBe('');
  });

  it('accepts a deadline with an offset', () => {
    expect(
      parseFunding(fundingPayload({ deadline: '2026-12-04T23:59:59+07:00' }))
        .deadline,
    ).toBe('2026-12-04T23:59:59+07:00');
  });

  it.each([
    [
      'statusType',
      'FUNDING_OK',
      'field "statusType" must be one of FUNDING_REMINDER, FUNDING_FAILED, FUNDING_CURE_DEADLINE',
    ],
    ['statusType', 'funding_failed', 'field "statusType" must be one of'],
    ['deadline', '2026-12-04', 'field "deadline" must be an RFC 3339'],
    ['deadline', 42, 'field "deadline" must be a non-empty string'],
    ['reason', 42, 'field "reason" must be a string, got a number'],
    ['contractId', 'contract-1', 'field "contractId" must be a UUID'],
    ['milestoneId', 'milestone-1', 'field "milestoneId" must be a UUID'],
    ['recipients', [], 'field "recipients"'],
    ['amount', '1.00', 'unknown payload property "amount"'],
  ])(
    'rejects %s = %p as an invalid payload, without retrying',
    (field, value, reason) => {
      const text = message(() =>
        parseFunding(fundingPayload({ [field]: value })),
      );

      expect(text).toContain(
        'Malformed notification.milestone_funding_status_requested',
      );
      expect(text).toContain(reason);
    },
  );

  it.each(MILESTONE_FUNDING_STATUS_REQUIRED)(
    'rejects a missing %s',
    (field) => {
      const payload = fundingPayload();
      delete payload[field];

      expect(message(() => parseFunding(payload))).toContain(`"${field}"`);
    },
  );

  it('never quotes a recipient address', () => {
    const recipients = [
      { userId: null, email: 'not-an-address', role: 'BUYER' },
    ];

    expect(
      message(() => parseFunding(fundingPayload({ recipients }))),
    ).not.toContain('not-an-address');
  });

  it('rejects a command from another producer', () => {
    expect(
      message(() =>
        parseMilestoneFundingStatusRequested(
          fundingBody({ producer: 'escrow-service' }),
        ),
      ),
    ).toContain('producer must be "contract-service"');
  });

  it('rejects the activation-failed command, whose eventType is not this queue’s', () => {
    expect(
      message(() =>
        parseMilestoneFundingStatusRequested(activationFailedBody()),
      ),
    ).toContain("is not this queue's command");
  });
});
