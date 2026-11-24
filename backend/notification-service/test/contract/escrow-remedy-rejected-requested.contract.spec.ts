/**
 * Contract test: parseEscrowRemedyRejectedRequested against
 * `escrowRemedyRejectedRequested` of contracts/events/notification-commands.yaml,
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
  ESCROW_REMEDY_REJECTED_FIELDS,
  ESCROW_REMEDY_REJECTED_REQUESTED,
  parseEscrowRemedyRejectedRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import { ESCROW_REMEDY_REJECTED_REASON_MAX_LENGTH } from '../../src/application/commands';
import {
  remedyRejectedEnvelope,
  remedyRejectedPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
}

interface RemedyRejectedProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown> & {
      reason: { type: string; minLength: number; maxLength: number };
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
  allOf: [{ $ref: '#/$defs/escrowRemedyRejectedRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseEscrowRemedyRejectedRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

/** The command's own properties, for the field-level assertions. */
const remedyRejectedSchema = (): RemedyRejectedProperties => {
  const def = contract.$defs.escrowRemedyRejectedRequested as {
    allOf: Array<{ properties?: RemedyRejectedProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('escrowRemedyRejectedRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('escrowRemedyRejectedRequested contract (notification-commands.yaml)', () => {
  const schema = remedyRejectedSchema();

  /*
   * Not asserted: membership of the document's top-level oneOf, which does
   * not list this command yet (docs PR #608). The $defs entry is what the
   * publisher and this test validate against.
   */
  it('is a command consumed by this service', () => {
    expect(contract.$defs.escrowRemedyRejectedRequested['x-consumers']).toEqual(
      ['notification-service'],
    );
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema.eventType.const).toBe(ESCROW_REMEDY_REJECTED_REQUESTED);
    expect(COMMAND_KINDS.escrowRemedyRejected.eventType).toBe(
      schema.eventType.const,
    );
    expect(schema.producer.const).toBe('escrow-service');
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(ESCROW_REMEDY_REJECTED_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(ESCROW_REMEDY_REJECTED_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it('bounds the reason as the parser does', () => {
    expect(schema.payload.properties.reason).toEqual({
      type: 'string',
      minLength: 1,
      maxLength: ESCROW_REMEDY_REJECTED_REASON_MAX_LENGTH,
    });
  });

  it.each([
    ['a rejected decision, as published', {}],
    ['a one-character reason', { reason: 'x' }],
    ['a 500-character reason', { reason: 'r'.repeat(500) }],
    // Both count code points: 500 of these are 1000 UTF-16 units.
    ['500 characters outside the BMP', { reason: '\u{1F4B8}'.repeat(500) }],
    ['a reason over several lines', { reason: 'first line\nsecond line' }],
    ['an instant with an offset', { rejectedAt: '2026-11-24T15:00:00+07:00' }],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      remedyRejectedEnvelope({ payload: remedyRejectedPayload(patch) }),
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
      { payload: remedyRejectedPayload({ recipients: [] }) },
    ],
    ['an empty reason', { payload: remedyRejectedPayload({ reason: '' }) }],
    [
      'a 501-character reason',
      { payload: remedyRejectedPayload({ reason: 'r'.repeat(501) }) },
    ],
    [
      '501 characters outside the BMP',
      { payload: remedyRejectedPayload({ reason: '\u{1F4B8}'.repeat(501) }) },
    ],
    [
      'a reason as a number',
      { payload: remedyRejectedPayload({ reason: 42 }) },
    ],
    ['a null reason', { payload: remedyRejectedPayload({ reason: null }) }],
    [
      'a decision id that is not a UUID',
      { payload: remedyRejectedPayload({ remedyDecisionId: 'decision-1' }) },
    ],
    [
      'a date without time',
      { payload: remedyRejectedPayload({ rejectedAt: '2026-11-24' }) },
    ],
    [
      'a missing reason',
      { payload: remedyRejectedPayload({ reason: undefined }) },
    ],
    [
      'a missing rejectedAt',
      { payload: remedyRejectedPayload({ rejectedAt: undefined }) },
    ],
    ['another producer', { producer: 'contract-service' }],
    [
      'the remedy leg alert eventType',
      { eventType: 'notification.escrow_remedy_leg_alert_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(remedyRejectedEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
