/**
 * Contract test: parseEscrowRemedyLegAlertRequested against
 * `escrowRemedyLegAlertRequested` of contracts/events/notification-commands.yaml,
 * read from the repository. If the contract changes, this fails until the code
 * follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  ESCROW_REMEDY_LEG_ALERT_FIELDS,
  ESCROW_REMEDY_LEG_ALERT_REQUESTED,
  parseEscrowRemedyLegAlertRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import { PARTY_ROLES } from '../../src/application/commands';
import {
  remedyLegAlertEnvelope,
  remedyLegAlertPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface RemedyLegAlertProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown> & { party: { enum: string[] } };
  };
}

const contract = load(readFileSync(CONTRACT, 'utf8')) as Schema;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
// The whole document, so $ref and $defs resolve as they do for the publisher.
const contractAccepts = ajv.compile({
  ...contract,
  oneOf: undefined,
  allOf: [{ $ref: '#/$defs/escrowRemedyLegAlertRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseEscrowRemedyLegAlertRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

/** The command's own properties, for the field-level assertions. */
const remedyLegAlertSchema = (): RemedyLegAlertProperties => {
  const def = contract.$defs.escrowRemedyLegAlertRequested as {
    allOf: Array<{ properties?: RemedyLegAlertProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('escrowRemedyLegAlertRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('escrowRemedyLegAlertRequested contract (notification-commands.yaml)', () => {
  const schema = remedyLegAlertSchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/escrowRemedyLegAlertRequested',
    });
    expect(contract.$defs.escrowRemedyLegAlertRequested['x-consumers']).toEqual(
      ['notification-service'],
    );
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema.eventType.const).toBe(ESCROW_REMEDY_LEG_ALERT_REQUESTED);
    expect(COMMAND_KINDS.escrowRemedyLegFailed.eventType).toBe(
      schema.eventType.const,
    );
    expect(schema.producer.const).toBe('escrow-service');
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(ESCROW_REMEDY_LEG_ALERT_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(ESCROW_REMEDY_LEG_ALERT_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it('declares exactly the parties the code accepts', () => {
    expect(sorted(schema.payload.properties.party.enum)).toEqual(
      sorted(PARTY_ROLES),
    );
  });

  it.each([
    ['a seller payout, as published', {}],
    ['a buyer refund', { party: 'BUYER' }],
    ['a zero amount', { amount: '0.00' }],
    ['the largest amount', { amount: '9999999999999999.99' }],
    ['an instant with an offset', { since: '2026-11-24T15:14:59+07:00' }],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      remedyLegAlertEnvelope({ payload: remedyLegAlertPayload(patch) }),
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
      'recipients in the payload',
      { payload: remedyLegAlertPayload({ recipients: [] }) },
    ],
    [
      'a deposit leg alert stage',
      { payload: remedyLegAlertPayload({ stage: 'RETURN_EXHAUSTED' }) },
    ],
    [
      'another party',
      { payload: remedyLegAlertPayload({ party: 'INSPECTOR' }) },
    ],
    [
      'a lower-case party',
      { payload: remedyLegAlertPayload({ party: 'seller' }) },
    ],
    [
      'an amount without decimals',
      { payload: remedyLegAlertPayload({ amount: '45000000' }) },
    ],
    [
      'an amount as a number',
      { payload: remedyLegAlertPayload({ amount: 45000000 }) },
    ],
    [
      'a negative amount',
      { payload: remedyLegAlertPayload({ amount: '-1.00' }) },
    ],
    [
      'an amount over 19 characters',
      { payload: remedyLegAlertPayload({ amount: '10000000000000000.00' }) },
    ],
    [
      'a remedy leg id that is not a UUID',
      { payload: remedyLegAlertPayload({ remedyLegId: 'leg-1' }) },
    ],
    [
      'a bank command id that is not a UUID',
      { payload: remedyLegAlertPayload({ bankCommandEventId: 'bank-cmd-1' }) },
    ],
    [
      'a date without time',
      { payload: remedyLegAlertPayload({ since: '2026-11-24' }) },
    ],
    [
      'a missing remedyDecisionId',
      { payload: remedyLegAlertPayload({ remedyDecisionId: undefined }) },
    ],
    [
      'a missing bankCommandEventId',
      { payload: remedyLegAlertPayload({ bankCommandEventId: undefined }) },
    ],
    ['another producer', { producer: 'contract-service' }],
    [
      'the deposit leg alert eventType',
      { eventType: 'notification.escrow_leg_alert_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(remedyLegAlertEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
