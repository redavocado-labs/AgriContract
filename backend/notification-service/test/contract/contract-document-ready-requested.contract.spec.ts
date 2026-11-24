/**
 * Contract test: parseContractDocumentReadyRequested against
 * `contractDocumentReadyRequested` of contracts/events/notification-commands.yaml
 * (design §3.3), read from the repository. If the contract changes, this fails
 * until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { NonRetryableError } from '@agricontract/eventing';
import { load } from 'js-yaml';
import {
  CONTRACT_DOCUMENT_READY_FIELDS,
  CONTRACT_DOCUMENT_READY_REQUESTED,
  parseContractDocumentReadyRequested,
} from '../../src/infrastructure/messaging/notification-command.parsers';
import { COMMAND_KINDS } from '../../src/application/notification-command-kinds';
import {
  documentReadyEnvelope,
  documentReadyPayload,
} from '../support/commands';

const CONTRACT = join(
  __dirname,
  '../../../../contracts/events/notification-commands.yaml',
);

interface Schema {
  $defs: Record<string, Record<string, unknown>>;
  oneOf: Array<{ $ref: string }>;
}

interface DocumentReadyProperties {
  eventType: { const: string };
  producer: { const: string };
  payload: {
    required: string[];
    additionalProperties: boolean;
    properties: Record<string, unknown> & {
      templateCode: { pattern: string };
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
  allOf: [{ $ref: '#/$defs/contractDocumentReadyRequested' }],
});

const codeAccepts = (message: unknown): boolean => {
  try {
    parseContractDocumentReadyRequested(Buffer.from(JSON.stringify(message)));
    return true;
  } catch (err) {
    if (err instanceof NonRetryableError) return false;
    throw err;
  }
};

/** The command's own properties, for the field-level assertions. */
const documentReadySchema = (): DocumentReadyProperties => {
  const def = contract.$defs.contractDocumentReadyRequested as {
    allOf: Array<{ properties?: DocumentReadyProperties }>;
  };
  const own = def.allOf.find((part) => part.properties?.payload);
  if (!own?.properties) {
    throw new Error('contractDocumentReadyRequested declares no payload');
  }
  return own.properties;
};

/** JSON drops undefined, so a field set to undefined is a missing field. */
const sent = (message: Record<string, unknown>): Record<string, unknown> =>
  JSON.parse(JSON.stringify(message)) as Record<string, unknown>;

const sorted = (values: readonly string[]) => [...values].sort();

describe('contractDocumentReadyRequested contract (notification-commands.yaml)', () => {
  const schema = documentReadySchema();

  it('is a command of the contract, consumed by this service', () => {
    expect(contract.oneOf).toContainEqual({
      $ref: '#/$defs/contractDocumentReadyRequested',
    });
    expect(
      contract.$defs.contractDocumentReadyRequested['x-consumers'],
    ).toEqual(['notification-service']);
  });

  it('names the eventType and producer the parser and the kind use', () => {
    expect(schema.eventType.const).toBe(CONTRACT_DOCUMENT_READY_REQUESTED);
    expect(COMMAND_KINDS.contractFinalPdf.eventType).toBe(
      schema.eventType.const,
    );
    expect(schema.producer.const).toBe('contract-service');
  });

  it('deduplicates by documentId and the kind this service records', () => {
    expect(
      contract.$defs.contractDocumentReadyRequested['x-deduplication-key'],
    ).toBe(
      `documentId + recipient.email + ${COMMAND_KINDS.contractFinalPdf.notificationType}`,
    );
  });

  it('declares exactly the payload fields the parser reads, all required, nothing more', () => {
    expect(sorted(Object.keys(schema.payload.properties))).toEqual(
      sorted(CONTRACT_DOCUMENT_READY_FIELDS),
    );
    expect(sorted(schema.payload.required)).toEqual(
      sorted(CONTRACT_DOCUMENT_READY_FIELDS),
    );
    expect(schema.payload.additionalProperties).toBe(false);
  });

  it('declares the templateCode pattern the parser checks', () => {
    expect(schema.payload.properties.templateCode.pattern).toBe(
      '^VN_COFFEE_[A-Z0-9_]+$',
    );
  });

  it.each([
    ['the final contract, as published', {}],
    ['another VN_COFFEE_ template', { templateCode: 'VN_COFFEE_B2B_V2' }],
    ['a one-character version', { templateVersion: 'x' }],
    [
      'a generatedAt with an offset',
      { generatedAt: '2026-11-25T11:00:00+07:00' },
    ],
    [
      'one recipient without a platform id',
      {
        recipients: [{ userId: null, email: 'buyer@doc.test', role: 'BUYER' }],
      },
    ],
  ])('accepts %s on both sides', (_case, patch) => {
    const message = sent(
      documentReadyEnvelope({ payload: documentReadyPayload(patch) }),
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
      'the documentType of contract.document_generated',
      {
        payload: documentReadyPayload({
          documentType: 'FINAL_SIGNED_CONTRACT',
        }),
      },
    ],
    ['no recipients', { payload: documentReadyPayload({ recipients: [] }) }],
    [
      'another template family',
      { payload: documentReadyPayload({ templateCode: 'VN_RICE_B2B' }) },
    ],
    [
      'a lower-case template code',
      { payload: documentReadyPayload({ templateCode: 'vn_coffee_b2b' }) },
    ],
    [
      'an empty template version',
      { payload: documentReadyPayload({ templateVersion: '' }) },
    ],
    [
      'an upper-case file hash',
      {
        payload: documentReadyPayload({
          documentFileHash:
            'B0C1599C63930FDB7A8EF24E770ABEDF61595981CE0FDEA9277605CF6064D137',
        }),
      },
    ],
    [
      'a short source hash',
      { payload: documentReadyPayload({ sourceContentHash: 'abc' }) },
    ],
    [
      'a file id that is not a UUID',
      { payload: documentReadyPayload({ fileId: 'file-1' }) },
    ],
    [
      'a missing documentFileHash',
      { payload: documentReadyPayload({ documentFileHash: undefined }) },
    ],
    [
      'a date without time',
      { payload: documentReadyPayload({ generatedAt: '2026-11-25' }) },
    ],
    ['another producer', { producer: 'file-service' }],
    [
      'the anchor eventType',
      { eventType: 'notification.contract_anchor_requested' },
    ],
    ['a missing correlationId', { correlationId: undefined }],
  ])('rejects %s on both sides', (_case, overrides) => {
    const message = sent(documentReadyEnvelope(overrides));

    expect(contractAccepts(message)).toBe(false);
    expect(codeAccepts(message)).toBe(false);
  });
});
