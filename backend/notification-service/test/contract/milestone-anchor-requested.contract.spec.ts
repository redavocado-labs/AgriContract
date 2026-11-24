/**
 * Contract test: parseMilestoneAnchorRequested against
 * `milestoneAnchorRequested` of contracts/events/notification-commands.yaml
 * (hash-chain design §4.3), read from the repository. If the contract
 * changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  MILESTONE_ANCHOR_FIELDS,
  MILESTONE_ANCHOR_REQUESTED,
  parseMilestoneAnchorRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import {
  milestoneAnchorEnvelope,
  milestoneAnchorPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface AnchorProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown>;
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
// The whole document, so $ref and $defs resolve as they do for the publisher.
const contractAccepts = ajv.compile({
  ...contract,
  oneOf: undefined,
  allOf: [{ $ref: '#/$defs/milestoneAnchorRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseMilestoneAnchorRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

const anchorSchema = (): AnchorProperties => {
  const def = contract.$defs.milestoneAnchorRequested as {
    allOf: Array<{ properties?: AnchorProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('milestoneAnchorRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('milestoneAnchorRequested contract (notification-commands.yaml)', () => {
  const schema = anchorSchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/milestoneAnchorRequested',
    });
    expect(contract.$defs.milestoneAnchorRequested['x-consumers']).toEqual([
      'notification-service',
    ]);
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema.eventType.const).toBe(MILESTONE_ANCHOR_REQUESTED);
    expect(COMMAND_KINDS.milestoneAnchor.eventType).toBe(
      schema.eventType.const,
    );
    expect(schema.producer.const).toBe('audit-service');
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(MILESTONE_ANCHOR_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(MILESTONE_ANCHOR_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it.each([
    ['a settled milestone, as audit-service is to publish it', {}],
    ['an empty settlementSummary', { settlementSummary: {} }],
    // Plain string in the contract: the use case, not the parser, rejects it.
    ['an otsProof that is not base64', { otsProof: 'not base64!' }],
    [
      'one recipient without a platform id',
      {
        recipients: [
          { userId: null, email: 'buyer@anchor.test', role: 'BUYER' },
        ],
      },
    ],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      milestoneAnchorEnvelope({ payload: milestoneAnchorPayload(patch) }),
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
      'the sourceEventId of hash-chain §4.3, not in the contract',
      { payload: milestoneAnchorPayload({ sourceEventId: 'e-1' }) },
    ],
    ['no recipients', { payload: milestoneAnchorPayload({ recipients: [] }) }],
    [
      'an upper-case record hash',
      { payload: milestoneAnchorPayload({ recordHash: 'A'.repeat(64) }) },
    ],
    ['a numeric proof', { payload: milestoneAnchorPayload({ otsProof: 1 }) }],
    [
      'a missing proof',
      { payload: milestoneAnchorPayload({ otsProof: undefined }) },
    ],
    [
      'a null settlementSummary',
      { payload: milestoneAnchorPayload({ settlementSummary: null }) },
    ],
    ['another producer', { producer: 'contract-service' }],
    [
      'the signed-contract anchor eventType',
      { eventType: 'notification.contract_anchor_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(milestoneAnchorEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
