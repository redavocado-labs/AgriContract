/**
 * Contract tests: parseMilestoneStatusRequested and parseBreachNoticeRequested
 * against `milestoneStatusRequested` and `breachNoticeRequested` of
 * contracts/events/notification-commands.yaml (D96), read from the
 * repository. If the contract changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  BREACH_NOTICE_FIELDS,
  BREACH_NOTICE_REQUESTED,
  MILESTONE_STATUS_FIELDS,
  MILESTONE_STATUS_REQUESTED,
  MILESTONE_STATUS_REQUIRED,
  parseBreachNoticeRequested,
  parseMilestoneStatusRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import {
  BREACH_REASON_CODES,
  BREACH_SEVERITIES,
  MILESTONE_STATUS_TYPES,
} from '../../src/application/commands';
import {
  breachNoticeEnvelope,
  breachNoticePayload,
  milestoneStatusEnvelope,
  milestoneStatusPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface Own {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, { enum?: string[] }>;
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

/** One Ajv per command: the document's $id may be compiled once per instance. */
const accepts = (def: string) => {
  const ajv = new Ajv2020({ strict: false, allErrors: true });
  addFormats(ajv);
  return ajv.compile({
    ...contract,
    oneOf: undefined,
    allOf: [{ $ref: `#/$defs/${def}` }],
  });
};

const parses =
  (parse: (body: Buffer) => unknown) =>
  (message: unknown): boolean => {
    try {
      parse(Buffer.from(JSON.stringify(message)));
      return true;
    } catch (err) {
      if (err instanceof NonRetryableError) return false;
      throw err;
    }
  };

const ownOf = (def: string): Own => {
  const own = (
    contract.$defs[def] as { allOf: Array<{ properties?: Own }> }
  ).allOf.find((part) => part.properties?.payload);
  if (!own?.properties) throw new Error(`${def} declares no payload`);
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('milestoneStatusRequested contract (notification-commands.yaml)', () => {
  const schema = ownOf('milestoneStatusRequested');
  const contractAccepts = accepts('milestoneStatusRequested');
  const codeAccepts = parses(parseMilestoneStatusRequested);

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/milestoneStatusRequested',
    });
    expect(contract.$defs.milestoneStatusRequested['x-consumers']).toEqual([
      'notification-service',
    ]);
  });

  it('names the eventType and producer the parser and all eight kinds use', () => {
    expect(schema.eventType.const).toBe(MILESTONE_STATUS_REQUESTED);
    const kinds = Object.values(COMMAND_KINDS).filter(
      (k) => k.eventType === MILESTONE_STATUS_REQUESTED,
    );
    expect(kinds).toHaveLength(MILESTONE_STATUS_TYPES.length);
    expect(schema.producer.const).toBe('contract-service');
  });

  it('declares exactly the payload fields and status types the parser reads', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(MILESTONE_STATUS_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(MILESTONE_STATUS_REQUIRED),
    );
    expect(schema.payload.additionalProperties).toBe(false);
    expect(sorted(schema.payload.properties.statusType.enum ?? [])).toEqual(
      sorted(MILESTONE_STATUS_TYPES),
    );
  });

  it.each([
    ['a weighing, as published', {}],
    [
      'a flag with a deadline',
      { statusType: 'FLAGGED', deadline: '2026-12-04T16:59:59Z' },
    ],
    ['no deadline at all', { deadline: undefined }],
    ['no statusData', { statusData: undefined }],
    ['statusData of any shape', { statusData: { anything: [1, 'a'] } }],
    ['a later status type', { statusType: 'PROVISIONAL_LEVEL2' }],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      milestoneStatusEnvelope({ payload: milestoneStatusPayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(true);
    expect(codeAccepts(message)).toBe(true);
  });

  it.each([
    ['another status type', { statusType: 'SETTLED' }],
    ['a null statusData', { statusData: null }],
    ['an array statusData', { statusData: [] }],
    ['a date without time', { deadline: '2026-12-04' }],
    ['an unknown field', { reason: 'x' }],
    ['a missing statusType', { statusType: undefined }],
    ['no recipients', { recipients: [] }],
  ])('rejects %s on both sides', (_case, patch) => {
    const message = sent(
      milestoneStatusEnvelope({ payload: milestoneStatusPayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });

  it('rejects another producer on both sides', () => {
    const message = sent(
      milestoneStatusEnvelope({ producer: 'escrow-service' }),
    );

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});

describe('breachNoticeRequested contract (notification-commands.yaml)', () => {
  const schema = ownOf('breachNoticeRequested');
  const contractAccepts = accepts('breachNoticeRequested');
  const codeAccepts = parses(parseBreachNoticeRequested);

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/breachNoticeRequested',
    });
    expect(contract.$defs.breachNoticeRequested['x-consumers']).toEqual([
      'notification-service',
    ]);
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema.eventType.const).toBe(BREACH_NOTICE_REQUESTED);
    expect(COMMAND_KINDS.breachNotice.eventType).toBe(schema.eventType.const);
    expect(schema.producer.const).toBe('contract-service');
  });

  it('declares exactly the payload fields, severities and reason codes the parser reads', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(BREACH_NOTICE_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(BREACH_NOTICE_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
    expect(sorted(schema.payload.properties.severity.enum ?? [])).toEqual(
      sorted(BREACH_SEVERITIES),
    );
    expect(
      sorted((contract.$defs.breachReasonCode as { enum: string[] }).enum),
    ).toEqual(sorted(BREACH_REASON_CODES));
  });

  it.each([
    ['a material shortfall, as published', {}],
    ['no evidence yet', { evidenceRefs: [] }],
    ['a reference that is not a file id', { evidenceRefs: ['photo 1.jpg'] }],
    [
      'a minor late delivery',
      { severity: 'MINOR', breachReasonCode: 'LATE_DELIVERY' },
    ],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      breachNoticeEnvelope({ payload: breachNoticePayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(true);
    expect(codeAccepts(message)).toBe(true);
  });

  it.each([
    ['another severity', { severity: 'SEVERE' }],
    ['a null reason code', { breachReasonCode: null }],
    ['an unknown reason code', { breachReasonCode: 'THEFT' }],
    ['a numeric evidence reference', { evidenceRefs: [1] }],
    ['a missing evidenceRefs', { evidenceRefs: undefined }],
    ['an unknown field', { allegedBreachingRole: 'SELLER' }],
    ['no recipients', { recipients: [] }],
  ])('rejects %s on both sides', (_case, patch) => {
    const message = sent(
      breachNoticeEnvelope({ payload: breachNoticePayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
