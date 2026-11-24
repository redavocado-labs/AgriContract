/**
 * Contract test: the parsers of #278 against `auditDigestRequested`,
 * `auditFailureRequested`, `securityLockChangedRequested`,
 * `reconciliationMismatchRequested` and `verifierKeyAnchorRequested` of
 * contracts/events/notification-commands.yaml, read from the repository. If
 * the contract changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  AUDIT_DIGEST_FIELDS,
  AUDIT_DIGEST_REQUESTED,
  AUDIT_FAILURE_FIELDS,
  AUDIT_FAILURE_REQUESTED,
  RECONCILIATION_MISMATCH_FIELDS,
  RECONCILIATION_MISMATCH_REQUESTED,
  SECURITY_LOCK_CHANGED_FIELDS,
  SECURITY_LOCK_CHANGED_REQUESTED,
  VERIFIER_KEY_ANCHOR_FIELDS,
  VERIFIER_KEY_ANCHOR_REQUESTED,
  parseAuditDigestRequested,
  parseAuditFailureRequested,
  parseReconciliationMismatchRequested,
  parseSecurityLockChangedRequested,
  parseVerifierKeyAnchorRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import { SECURITY_LOCK_ACTIONS } from '../../src/application/commands';
import {
  auditDigestEnvelope,
  auditDigestPayload,
  auditFailureEnvelope,
  auditFailurePayload,
  reconciliationEnvelope,
  reconciliationPayload,
  securityLockEnvelope,
  securityLockPayload,
  verifierKeyEnvelope,
  verifierKeyPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $id: string;
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface CommandProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, { enum?: string[] }>;
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);

type Envelope = (
  overrides?: Record<string, unknown>,
) => Record<string, unknown>;
type Payload = (overrides?: Record<string, unknown>) => Record<string, unknown>;

interface Case {
  def: string;
  eventType: string;
  producer: string;
  kind: keyof typeof COMMAND_KINDS;
  fields: readonly string[];
  parse: (body: Buffer) => unknown;
  envelope: Envelope;
  payload: Payload;
  /** Payload patches the contract and the parser both accept. */
  accepted: Array<[string, Record<string, unknown>]>;
  /** Payload patches the contract and the parser both reject. */
  rejected: Array<[string, Record<string, unknown>]>;
}

const STAFF_RECIPIENT_CASES: Array<[string, Record<string, unknown>]> = [
  ['no recipients', { recipients: [] }],
  [
    'a recipient with an unknown role',
    {
      recipients: [
        { userId: null, email: 'x@y.test', role: 'EXTERNAL_VERIFIER' },
      ],
    },
  ],
  [
    'a recipient without its userId',
    { recipients: [{ email: 'x@y.test', role: 'ADMIN' }] },
  ],
];

const CASES: Case[] = [
  {
    def: 'auditDigestRequested',
    eventType: AUDIT_DIGEST_REQUESTED,
    producer: 'audit-service',
    kind: 'auditDigest',
    fields: AUDIT_DIGEST_FIELDS,
    parse: parseAuditDigestRequested,
    envelope: auditDigestEnvelope,
    payload: auditDigestPayload,
    accepted: [['a weekly digest, as audit-service is to publish it', {}]],
    rejected: [
      ['an upper-case recordHash', { recordHash: 'A'.repeat(64) }],
      ['a short recordHash', { recordHash: 'abc' }],
      ['a contractId, not in the contract', { contractId: 'c' }],
      ...STAFF_RECIPIENT_CASES,
    ],
  },
  {
    def: 'auditFailureRequested',
    eventType: AUDIT_FAILURE_REQUESTED,
    producer: 'audit-service',
    kind: 'auditFailure',
    fields: AUDIT_FAILURE_FIELDS,
    parse: parseAuditFailureRequested,
    envelope: auditFailureEnvelope,
    payload: auditFailurePayload,
    accepted: [
      ['a failed weekly check, as audit-service is to publish it', {}],
      ['an empty failureSummary', { failureSummary: {} }],
    ],
    rejected: [
      ['a null failureSummary', { failureSummary: null }],
      ['a string failureSummary', { failureSummary: 'broken' }],
      ...STAFF_RECIPIENT_CASES,
    ],
  },
  {
    def: 'securityLockChangedRequested',
    eventType: SECURITY_LOCK_CHANGED_REQUESTED,
    producer: 'bank-service',
    kind: 'securityLockChanged',
    fields: SECURITY_LOCK_CHANGED_FIELDS,
    parse: parseSecurityLockChangedRequested,
    envelope: securityLockEnvelope,
    payload: securityLockPayload,
    accepted: [
      ['a lock, as bank-service is to publish it', {}],
      ['an unlock', { action: 'UNLOCKED' }],
      ['an empty reason', { reason: '' }],
    ],
    rejected: [
      ['another action', { action: 'PAUSED' }],
      ['a null reason', { reason: null }],
      ['a date without a time', { timestamp: '2026-11-29' }],
      [
        'the sourceEventId of bank §3.5.4, not in the contract',
        { sourceEventId: 'e-1' },
      ],
      ...STAFF_RECIPIENT_CASES,
    ],
  },
  {
    def: 'reconciliationMismatchRequested',
    eventType: RECONCILIATION_MISMATCH_REQUESTED,
    producer: 'bank-service',
    kind: 'reconciliationMismatch',
    fields: RECONCILIATION_MISMATCH_FIELDS,
    parse: parseReconciliationMismatchRequested,
    envelope: reconciliationEnvelope,
    payload: reconciliationPayload,
    accepted: [
      ['a mismatch, as bank-service is to publish it', {}],
      ['an empty mismatchSummary', { mismatchSummary: {} }],
    ],
    rejected: [
      ['a contractId that is not a UUID', { contractId: 'contract-1' }],
      ['an array mismatchSummary', { mismatchSummary: [] }],
      ...STAFF_RECIPIENT_CASES,
    ],
  },
  {
    def: 'verifierKeyAnchorRequested',
    eventType: VERIFIER_KEY_ANCHOR_REQUESTED,
    producer: 'bank-service',
    kind: 'verifierKeyAnchor',
    fields: VERIFIER_KEY_ANCHOR_FIELDS,
    parse: parseVerifierKeyAnchorRequested,
    envelope: verifierKeyEnvelope,
    payload: verifierKeyPayload,
    accepted: [
      ['a key registration, as bank-service is to publish it', {}],
      ['a one-character fingerprint', { publicKeyFingerprint: 'x' }],
    ],
    rejected: [
      ['an empty fingerprint', { publicKeyFingerprint: '' }],
      ['a numeric fingerprint', { publicKeyFingerprint: 42 }],
      ['an address without @', { recipientEmail: 'verifier' }],
      [
        'recipients, which this command does not carry',
        { recipients: [{ userId: null, email: 'x@y.test', role: 'ADMIN' }] },
      ],
    ],
  },
];

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe.each(CASES)('$def contract (notification-commands.yaml)', (c) => {
  // The whole document, so $ref and $defs resolve as they do for the publisher.
  const contractAccepts = ajv.compile({
    ...contract,
    oneOf: undefined,
    // One compiled schema per command: Ajv refuses a second schema with the same $id.
    $id: `${contract.$id}?command=${c.def}`,
    allOf: [{ $ref: `#/$defs/${c.def}` }],
  });

  const codeAccepts = (message: unknown): boolean => {
    try {
      c.parse(Buffer.from(JSON.stringify(message)));
      return true;
    } catch (err) {
      if (err instanceof NonRetryableError) return false;
      throw err;
    }
  };

  const schema = (): CommandProperties => {
    const def = contract.$defs[c.def] as {
      allOf: Array<{ properties?: CommandProperties }>;
    };
    const own = def.allOf.find((part) => part.properties?.payload);
    if (!own?.properties) throw new Error(`${c.def} declares no payload`);
    return own.properties;
  };

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({ $ref: `#/$defs/${c.def}` });
    expect(contract.$defs[c.def]['x-consumers']).toEqual([
      'notification-service',
    ]);
    expect(contract.$defs[c.def]['x-publisher']).toBe(c.producer);
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema().eventType.const).toBe(c.eventType);
    expect(COMMAND_KINDS[c.kind].eventType).toBe(c.eventType);
    expect(schema().producer.const).toBe(c.producer);
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    const payload = schema().payload;
    expect(sorted(Object.keys(payload.properties))).toEqual(sorted(c.fields));
    expect(sorted(payload.required)).toEqual(sorted(c.fields));
    expect(payload.additionalProperties).toBe(false);
  });

  it.each(c.accepted)('accepts %s on both sides', (_case, patch) => {
    const message = sent(c.envelope({ payload: c.payload(patch) }));

    expect(contractAccepts(message)).toBe(true);
    expect(codeAccepts(message)).toBe(true);
  });

  /*
   * Both directions: a message the contract accepts but the parser rejects
   * would dead-letter a valid command; one the parser accepts but the
   * contract rejects would send an email no publisher promised.
   */
  it.each([
    ...c.rejected.map(
      ([name, patch]) =>
        [name, { payload: c.payload(patch) }] as [
          string,
          Record<string, unknown>,
        ],
    ),
    ...c.fields.map(
      (field) =>
        [
          `a missing ${field}`,
          { payload: c.payload({ [field]: undefined }) },
        ] as [string, Record<string, unknown>],
    ),
    [
      'another producer',
      {
        producer:
          c.producer === 'bank-service' ? 'audit-service' : 'bank-service',
      },
    ],
    ['a missing correlationId', { correlationId: undefined }],
    ['a subject, which the command envelope does not allow', { subject: {} }],
  ] as Array<[string, Record<string, unknown>]>)(
    'rejects %s on both sides',
    (_case, overrides) => {
      const message = sent(c.envelope(overrides));

      expect(contractAccepts(message)).toBe(false);
      expect(codeAccepts(message)).toBe(false);
    },
  );
});

describe('securityLockChangedRequested action', () => {
  it('has exactly the actions the parser accepts', () => {
    const def = contract.$defs.securityLockChangedRequested as {
      allOf: Array<{ properties?: CommandProperties }>;
    };
    const own = def.allOf.find((part) => part.properties?.payload);

    expect(own?.properties?.payload.properties.action.enum).toEqual([
      ...SECURITY_LOCK_ACTIONS,
    ]);
  });
});
