/**
 * Contract test: the file.ready / file.failed that file-service publishes
 * against `fileReady` / `fileFailed` of contracts/events/golden-flow-events.yaml
 * (with event-envelope.yaml), read from the repository. If the contract
 * changes, this fails until the code follows it.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createEnvelope } from '@agricontract/eventing';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { load } from 'js-yaml';
import {
  FILE_FAILED,
  FILE_READY,
  PRODUCER,
  SCAN_REQUESTED,
  FILE_EMAIL_NOTICE,
  fileEmailNotice,
  scanRequested,
  fileFailed,
  fileReady,
} from '../../src/application/file-events';
import { FAILURE_REASONS } from '../../src/application/scan-file.use-case';
import { INGEST_CHANNELS, newStoredFile } from '../../src/domain/stored-file';

const CONTRACTS = join(__dirname, '../../../../contracts/events');

interface Contract {
  $id: string;
  $defs: Record<string, { allOf: unknown[] }>;
  oneOf: Array<{ $ref: string }>;
}

interface OwnPart {
  'x-publisher': string;
  properties: {
    eventType: { const: string };
    producer: { const: string };
    payload: {
      required: string[];
      properties: Record<string, unknown> & {
        ingestChannel: { enum: string[] };
      };
    };
  };
}

const events = load(
  readFileSync(join(CONTRACTS, 'golden-flow-events.yaml'), 'utf8'),
) as Contract;
const envelope = load(
  readFileSync(join(CONTRACTS, 'event-envelope.yaml'), 'utf8'),
) as object;

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
// golden-flow-events.yaml refers to "./event-envelope.yaml" by its $id.
ajv.addSchema(envelope);
ajv.addSchema({ ...events, oneOf: undefined });
const accepts = (def: string) => {
  const validate = ajv.getSchema(`${events.$id}#/$defs/${def}`);
  if (!validate) throw new Error(`no schema ${def}`);
  return validate;
};

const ownPart = (def: string): OwnPart => {
  const part = events.$defs[def].allOf.find(
    (p) => (p as Partial<OwnPart>).properties?.payload,
  ) as OwnPart | undefined;
  if (!part) throw new Error(`${def} declares no payload`);
  return part;
};
const own = (def: string): OwnPart['properties'] => ownPart(def).properties;

const file = newStoredFile({
  fileId: '4c7f0a1e-9b2d-4e3f-8a5b-6c7d8e9f0a1b',
  storageHash: 'a'.repeat(64),
  uploadedBy: '0b6f3c1e-7d2a-4c5b-9e8f-1a2b3c4d5e6f',
  ingestChannel: 'DIRECT_UPLOAD',
  contentType: 'image/png',
  fileSize: 12345,
  idempotencyKey: 'k',
});
const job = createEnvelope({
  eventType: SCAN_REQUESTED,
  producer: PRODUCER,
  aggregateId: file.fileId,
  payload: { fileId: file.fileId },
});

describe('file.ready / file.failed (golden-flow-events.yaml)', () => {
  it('both are events of the contract, published by file-service', () => {
    for (const def of ['fileReady', 'fileFailed']) {
      expect(events.oneOf).toContainEqual({ $ref: `#/$defs/${def}` });
      expect(ownPart(def)['x-publisher']).toBe(PRODUCER);
      expect(own(def).producer.const).toBe(PRODUCER);
    }
    expect(own('fileReady').eventType.const).toBe(FILE_READY);
    expect(own('fileFailed').eventType.const).toBe(FILE_FAILED);
  });

  it('the ingest channels are the ones file-service knows', () => {
    for (const def of ['fileReady', 'fileFailed']) {
      expect(
        [...own(def).payload.properties.ingestChannel.enum].sort(),
      ).toEqual([...INGEST_CHANNELS].sort());
    }
  });

  it('file.ready as published is accepted, with exactly the required payload fields', () => {
    const event = fileReady(job, file);

    expect(accepts('fileReady')(event)).toBe(true);
    expect(Object.keys(event.payload).sort()).toEqual(
      [...own('fileReady').payload.required].sort(),
    );
    // Derived from the scan job: the contract's envelope rules for causationId.
    expect(event.causationId).toBe(job.eventId);
    expect(event.aggregateId).toBe(file.fileId);
  });

  it.each(Object.values(FAILURE_REASONS))(
    'file.failed with "%s" is accepted, with exactly the required payload fields',
    (reason) => {
      const event = fileFailed(job, file, reason);

      expect(accepts('fileFailed')(event)).toBe(true);
      expect(Object.keys(event.payload).sort()).toEqual(
        [...own('fileFailed').payload.required].sort(),
      );
      expect(event.payload).toMatchObject({ failureReason: reason });
    },
  );

  it('the schema is strict: a field file-service does not send would be rejected', () => {
    const event = fileReady(job, file);

    expect(
      accepts('fileReady')({
        ...event,
        payload: { ...event.payload, storageHash: file.storageHash },
      }),
    ).toBe(false);
  });
});

describe('EMAIL_INTAKE: file.ready with emailMeta, file.email_notice (#275)', () => {
  const meta = {
    senderDomain: 'sgs.example.com',
    spfDkimResult: 'spf=pass; dkim=pass',
    subject: 'Report CASE-2026-0042',
    receivedAt: '2026-11-22T02:00:05.000Z',
  };
  const intakeFile = { ...file, ingestChannel: 'EMAIL_INTAKE' as const };

  it('file.ready of an intake file carries the emailMeta of its scan job and is accepted', () => {
    const event = fileReady(scanRequested(intakeFile, meta), intakeFile);

    expect(accepts('fileReady')(event)).toBe(true);
    expect(event.payload).toMatchObject({
      ingestChannel: 'EMAIL_INTAKE',
      emailMeta: meta,
    });
    expect(Object.keys(event.payload).sort()).toEqual(
      [...own('fileReady').payload.required, 'emailMeta'].sort(),
    );
  });

  it('file.email_notice is an event of the contract and is accepted with exactly its fields', () => {
    const event = fileEmailNotice(
      'a'.repeat(64),
      meta,
      'We confirm case SGS-VN-7781.',
    );
    const schema = own('fileEmailNotice');

    expect(events.oneOf).toContainEqual({ $ref: '#/$defs/fileEmailNotice' });
    expect(ownPart('fileEmailNotice')['x-publisher']).toBe(PRODUCER);
    expect(schema.eventType.const).toBe(FILE_EMAIL_NOTICE);
    expect(accepts('fileEmailNotice')(event)).toBe(true);
    expect(Object.keys(event.payload).sort()).toEqual(
      [...schema.payload.required].sort(),
    );
  });
});
