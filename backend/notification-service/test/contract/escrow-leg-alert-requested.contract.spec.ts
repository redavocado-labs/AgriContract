/**
 * Contract test: parseEscrowLegAlertRequested against `escrowLegAlertRequested`
 * of contracts/events/notification-commands.yaml, read from the repository.
 * If the contract changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  ESCROW_LEG_ALERT_FIELDS,
  ESCROW_LEG_ALERT_REQUESTED,
  parseEscrowLegAlertRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import {
  ESCROW_LEG_ALERT_STAGES,
  ESCROW_LEG_KINDS,
  ESCROW_LEG_STATUSES,
  PARTY_ROLES,
} from '../../src/application/commands';
import { legAlertEnvelope, legAlertPayload } from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

type Enum = { enum: string[] };

interface LegAlertProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    properties: Record<string, unknown> & {
      legKind: Enum;
      party: Enum;
      legStatus: Enum;
      stage: Enum;
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
  allOf: [{ $ref: '#/$defs/escrowLegAlertRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseEscrowLegAlertRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

/** The command's own properties, for the field-level assertions. */
const legAlertSchema = (): LegAlertProperties => {
  const def = contract.$defs.escrowLegAlertRequested as {
    allOf: Array<{ properties?: LegAlertProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('escrowLegAlertRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('escrowLegAlertRequested contract (notification-commands.yaml)', () => {
  const schema = legAlertSchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/escrowLegAlertRequested',
    });
    expect(contract.$defs.escrowLegAlertRequested['x-consumers']).toEqual([
      'notification-service',
    ]);
  });

  it('names the eventType and producer the parser and the kinds use', () => {
    expect(schema.eventType.const).toBe(ESCROW_LEG_ALERT_REQUESTED);
    for (const kind of [
      COMMAND_KINDS.escrowLegStuck,
      COMMAND_KINDS.escrowLegResolved,
      COMMAND_KINDS.escrowLegReturnExhausted,
    ]) {
      expect(kind.eventType).toBe(schema.eventType.const);
    }
    expect(schema.producer.const).toBe('escrow-service');
  });

  it('declares exactly the payload fields the parser reads, all required', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(ESCROW_LEG_ALERT_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(ESCROW_LEG_ALERT_FIELDS),
    );
  });

  it('declares exactly the leg kinds, parties, statuses and stages the code accepts', () => {
    const { properties } = schema.payload;
    expect(sorted(properties.legKind.enum)).toEqual(sorted(ESCROW_LEG_KINDS));
    expect(sorted(properties.party.enum)).toEqual(sorted(PARTY_ROLES));
    expect(sorted(properties.legStatus.enum)).toEqual(
      sorted(ESCROW_LEG_STATUSES),
    );
    expect(sorted(properties.stage.enum)).toEqual(
      sorted(ESCROW_LEG_ALERT_STAGES),
    );
  });

  it.each([
    ['a stuck lock, as published', {}],
    ['a stuck cancel', { legStatus: 'CANCEL_PENDING' }],
    ['a stuck return', { legStatus: 'RETURN_REQUESTED', party: 'SELLER' }],
    ['a resolved leg', { stage: 'RESOLVED', legStatus: 'LOCKED' }],
    [
      'an exhausted return',
      { stage: 'RETURN_EXHAUSTED', legStatus: 'RETURN_FAILED' },
    ],
    ['a zero amount', { amount: '0.00' }],
    ['the largest amount', { amount: '9999999999999999.99' }],
    ['an instant with an offset', { since: '2026-11-21T10:00:00+07:00' }],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(legAlertEnvelope({ payload: legAlertPayload(patch) }));

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
      { payload: legAlertPayload({ recipients: [] }) },
    ],
    ['another stage', { payload: legAlertPayload({ stage: 'STALLED' }) }],
    ['a lower-case stage', { payload: legAlertPayload({ stage: 'stuck' }) }],
    [
      'a milestone leg kind',
      { payload: legAlertPayload({ legKind: 'FUNDING' }) },
    ],
    ['another party', { payload: legAlertPayload({ party: 'INSPECTOR' }) }],
    [
      'another leg status',
      { payload: legAlertPayload({ legStatus: 'PENDING' }) },
    ],
    [
      'an amount without decimals',
      { payload: legAlertPayload({ amount: '150000000' }) },
    ],
    [
      'an amount as a number',
      { payload: legAlertPayload({ amount: 150000000 }) },
    ],
    ['a negative amount', { payload: legAlertPayload({ amount: '-1.00' }) }],
    [
      'an amount over 19 characters',
      { payload: legAlertPayload({ amount: '10000000000000000.00' }) },
    ],
    [
      'a bank command id that is not a UUID',
      { payload: legAlertPayload({ bankCommandEventId: 'bank-cmd-1' }) },
    ],
    [
      'a date without time',
      { payload: legAlertPayload({ since: '2026-11-21' }) },
    ],
    [
      'a missing bankCommandEventId',
      { payload: legAlertPayload({ bankCommandEventId: undefined }) },
    ],
    ['a missing stage', { payload: legAlertPayload({ stage: undefined }) }],
    ['another producer', { producer: 'bank-service' }],
    [
      'the Level 1.5 waiting eventType',
      { eventType: 'notification.level1_5_assignment_waiting_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(legAlertEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
