/**
 * Contract test: parseMilestoneFundingStatusRequested against
 * `milestoneFundingStatusRequested` of contracts/events/notification-commands.yaml
 * (milestone-escrow §6b), read from the repository. If the contract changes,
 * this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  MILESTONE_FUNDING_STATUS_FIELDS,
  MILESTONE_FUNDING_STATUS_REQUESTED,
  MILESTONE_FUNDING_STATUS_REQUIRED,
  parseMilestoneFundingStatusRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import { MILESTONE_FUNDING_STATUS_TYPES } from '../../src/application/commands';
import { fundingEnvelope, fundingPayload } from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface FundingProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown> & { statusType: { enum: string[] } };
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
// The whole document, so $ref and $defs resolve as they do for the publisher.
const contractAccepts = ajv.compile({
  ...contract,
  oneOf: undefined,
  allOf: [{ $ref: '#/$defs/milestoneFundingStatusRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseMilestoneFundingStatusRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

/** The command's own properties, for the field-level assertions. */
const fundingSchema = (): FundingProperties => {
  const def = contract.$defs.milestoneFundingStatusRequested as {
    allOf: Array<{ properties?: FundingProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('milestoneFundingStatusRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('milestoneFundingStatusRequested contract (notification-commands.yaml)', () => {
  const schema = fundingSchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/milestoneFundingStatusRequested',
    });
    expect(
      contract.$defs.milestoneFundingStatusRequested['x-consumers'],
    ).toEqual(['notification-service']);
  });

  it('names the eventType and producer the parser and the kinds use', () => {
    expect(schema.eventType.const).toBe(MILESTONE_FUNDING_STATUS_REQUESTED);
    for (const kind of [
      COMMAND_KINDS.milestoneFundingReminder,
      COMMAND_KINDS.milestoneFundingFailed,
      COMMAND_KINDS.milestoneFundingCureDeadline,
    ]) {
      expect(kind.eventType).toBe(schema.eventType.const);
    }
    expect(schema.producer.const).toBe('contract-service');
  });

  it('declares exactly the payload fields the parser reads, and which are required', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(MILESTONE_FUNDING_STATUS_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(MILESTONE_FUNDING_STATUS_REQUIRED),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it('declares exactly the status types the code accepts', () => {
    expect(sorted(schema.payload.properties.statusType.enum)).toEqual(
      sorted(MILESTONE_FUNDING_STATUS_TYPES),
    );
  });

  it.each([
    ['a failed funding, as published', {}],
    ['a reminder', { statusType: 'FUNDING_REMINDER' }],
    ['a cure deadline', { statusType: 'FUNDING_CURE_DEADLINE' }],
    ['a null deadline', { deadline: null }],
    ['no deadline', { deadline: undefined }],
    ['a null reason', { reason: null }],
    ['no reason', { reason: undefined }],
    ['an empty reason', { reason: '' }],
    ['a deadline with an offset', { deadline: '2026-12-04T23:59:59+07:00' }],
    [
      'only the buyer',
      {
        recipients: [
          { userId: null, email: 'buyer@funding.test', role: 'BUYER' },
        ],
      },
    ],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(fundingEnvelope({ payload: fundingPayload(patch) }));

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
      'an unknown payload field',
      { payload: fundingPayload({ amount: '1.00' }) },
    ],
    ['no recipients', { payload: fundingPayload({ recipients: [] }) }],
    [
      'a recipient without an address',
      {
        payload: fundingPayload({
          recipients: [{ userId: null, email: 'nobody', role: 'BUYER' }],
        }),
      },
    ],
    [
      'a statusType outside the enum',
      { payload: fundingPayload({ statusType: 'FUNDING_OK' }) },
    ],
    [
      'a missing statusType',
      { payload: fundingPayload({ statusType: undefined }) },
    ],
    [
      'a date without time',
      { payload: fundingPayload({ deadline: '2026-12-04' }) },
    ],
    ['a numeric reason', { payload: fundingPayload({ reason: 42 }) }],
    [
      'a milestone id that is not a UUID',
      { payload: fundingPayload({ milestoneId: 'milestone-1' }) },
    ],
    [
      'a missing milestoneId',
      { payload: fundingPayload({ milestoneId: undefined }) },
    ],
    ['another producer', { producer: 'escrow-service' }],
    [
      'the activation-failed eventType',
      { eventType: 'notification.contract_activation_failed_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(fundingEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
