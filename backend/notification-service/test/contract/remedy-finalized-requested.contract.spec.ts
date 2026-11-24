/**
 * Contract test: parseRemedyFinalizedRequested against
 * `remedyFinalizedRequested` of contracts/events/notification-commands.yaml
 * (D96), read from the repository, its four if/then consistency rules
 * included. If the contract changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  REMEDY_FINALIZED_FIELDS,
  REMEDY_FINALIZED_REQUESTED,
  parseRemedyFinalizedRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import {
  DECISION_SOURCES,
  QUALITY_DISPOSITIONS,
} from '../../src/application/commands';
import {
  DISPUTE_MILESTONE_ID,
  contractCompletedPayload,
  remedyFinalizedEnvelope,
  remedyFinalizedPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface RemedyProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown> & {
      decisionSource: { enum: string[] };
    };
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
// The whole document, so $ref and $defs resolve as they do for the publisher.
const contractAccepts = ajv.compile({
  ...contract,
  oneOf: undefined,
  allOf: [{ $ref: '#/$defs/remedyFinalizedRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseRemedyFinalizedRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

const remedySchema = (): RemedyProperties => {
  const def = contract.$defs.remedyFinalizedRequested as {
    allOf: Array<{ properties?: RemedyProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('remedyFinalizedRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

const leg = (remedyType: string, role = 'BUYER') => ({
  remedyLegId: '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d',
  remedyType,
  fundType: 'SELLER_DEPOSIT',
  role,
  amount: '1000.00',
});

const NO_FAULT = {
  finalBreachingRole: null,
  breachReasonCode: null,
  penaltyEligible: false,
  reputationEligible: false,
};

describe('remedyFinalizedRequested contract (notification-commands.yaml)', () => {
  const schema = remedySchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/remedyFinalizedRequested',
    });
    expect(contract.$defs.remedyFinalizedRequested['x-consumers']).toEqual([
      'notification-service',
    ]);
  });

  it('names the eventType and producer the parser and the kinds use', () => {
    expect(schema.eventType.const).toBe(REMEDY_FINALIZED_REQUESTED);
    for (const kind of [
      COMMAND_KINDS.remedyFinalized,
      COMMAND_KINDS.contractCompleted,
    ]) {
      expect(kind.eventType).toBe(schema.eventType.const);
    }
    expect(schema.producer.const).toBe('contract-service');
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(REMEDY_FINALIZED_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(REMEDY_FINALIZED_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it('declares exactly the decision sources and quality dispositions the code accepts', () => {
    expect(sorted(schema.payload.properties.decisionSource.enum)).toEqual(
      sorted(DECISION_SOURCES),
    );
    const quality = (
      contract.$defs.finalQualityDisposition as {
        oneOf: Array<{ enum?: string[] }>;
      }
    ).oneOf.find((o) => o.enum)?.enum;
    expect(sorted(quality ?? [])).toEqual(sorted(QUALITY_DISPOSITIONS));
  });

  it.each([
    ['a seller shortfall decided by an arbitrator, as published', {}],
    ['the D94 normal completion', contractCompletedPayload()],
    [
      'a conforming quality resolution',
      {
        ...NO_FAULT,
        qualityDisposition: 'CONFORMING',
        decisionSource: 'INSPECTOR_L1_5',
        remedyLegs: [leg('PAYMENT_SETTLEMENT', 'SELLER')],
      },
    ],
    [
      'a partially conforming quality resolution',
      {
        ...NO_FAULT,
        qualityDisposition: 'PARTIALLY_CONFORMING',
        decisionSource: 'INSPECTOR_L1_5',
        remedyLegs: [leg('PAYMENT_REFUND')],
      },
    ],
    [
      'a non-conforming quality resolution with a penalty',
      {
        qualityDisposition: 'NON_CONFORMING',
        breachReasonCode: 'QUALITY_BELOW_COMMITTED',
        decisionSource: 'INSPECTOR_L1_5',
        remedyLegs: [leg('CONTRACTUAL_PENALTY')],
      },
    ],
    ['a fault without penalty', { penaltyEligible: false }],
    [
      'a mutual exit with no fault',
      {
        ...NO_FAULT,
        breachReasonCode: 'MUTUAL_EXIT',
        decisionSource: 'MUTUAL',
        remedyLegs: [],
      },
    ],
    ['a NONE leg', { remedyLegs: [leg('NONE')] }],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      remedyFinalizedEnvelope({ payload: remedyFinalizedPayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(true);
    expect(codeAccepts(message)).toBe(true);
  });

  /*
   * Both directions: a message the contract accepts but the parser rejects
   * would dead-letter a valid command; one the parser accepts but the
   * contract rejects would send an email no publisher promised.
   */
  it.each([
    [
      'no role but penaltyEligible',
      { finalBreachingRole: null, breachReasonCode: null },
    ],
    [
      'no role but reputationEligible',
      { ...NO_FAULT, reputationEligible: true, remedyLegs: [] },
    ],
    [
      'no role with a forfeiture leg',
      { ...NO_FAULT, remedyLegs: [leg('DEPOSIT_FORFEITURE')] },
    ],
    [
      'no role with damages',
      { ...NO_FAULT, remedyLegs: [leg('DAMAGES_COMPENSATION')] },
    ],
    [
      'a penalty leg without penaltyEligible',
      { penaltyEligible: false, remedyLegs: [leg('CONTRACTUAL_PENALTY')] },
    ],
    ['CONFORMING with a role', { qualityDisposition: 'CONFORMING' }],
    [
      'PARTIALLY_CONFORMING with a reason',
      {
        ...NO_FAULT,
        breachReasonCode: 'SHORTFALL',
        qualityDisposition: 'PARTIALLY_CONFORMING',
        remedyLegs: [],
      },
    ],
    [
      'NON_CONFORMING against the buyer',
      {
        qualityDisposition: 'NON_CONFORMING',
        finalBreachingRole: 'BUYER',
        breachReasonCode: 'QUALITY_BELOW_COMMITTED',
      },
    ],
    [
      'NON_CONFORMING with another reason',
      { qualityDisposition: 'NON_CONFORMING' },
    ],
    [
      'NON_CONFORMING without reputation',
      {
        qualityDisposition: 'NON_CONFORMING',
        breachReasonCode: 'QUALITY_BELOW_COMMITTED',
        reputationEligible: false,
      },
    ],
    ['another decision source', { decisionSource: 'ADMIN' }],
    ['a string boolean', { penaltyEligible: 'true' }],
    [
      'a repeated milestone',
      { affectedMilestoneIds: [DISPUTE_MILESTONE_ID, DISPUTE_MILESTONE_ID] },
    ],
    ['a missing breachCaseId', { breachCaseId: undefined }],
    ['the escrow-only settledMilestoneCount', { settledMilestoneCount: 2 }],
    ['no recipients', { recipients: [] }],
  ])('rejects %s on both sides', (_case, patch) => {
    const message = sent(
      remedyFinalizedEnvelope({ payload: remedyFinalizedPayload(patch) }),
    );

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });

  it.each([
    ['another producer', { producer: 'escrow-service' }],
    ['the remedy.finalized eventType', { eventType: 'remedy.finalized' }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(remedyFinalizedEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
