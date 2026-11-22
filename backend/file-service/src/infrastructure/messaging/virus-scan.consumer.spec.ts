import {
  createEnvelope,
  handleDelivery,
  type AmqpChannelLike,
} from '@agricontract/eventing';
import {
  FakeObjectStorage,
  FakeScanner,
  InMemoryFileRepository,
} from '../../../test/support/fakes';
import { givenStoredFile } from '../../../test/support/stored';
import { PRODUCER, SCAN_REQUESTED } from '../../application/file-events';
import { ScanFileUseCase } from '../../application/scan-file.use-case';
import { SCAN_RETRY } from './scan-worker';
import { VirusScanConsumer } from './virus-scan.consumer';

const silent = { debug() {}, info() {}, warn() {}, error() {} };

describe('VirusScanConsumer through handleDelivery (ack / retry / DLQ)', () => {
  let storage: FakeObjectStorage;
  let files: InMemoryFileRepository;
  let scanner: FakeScanner;
  let channel: AmqpChannelLike & { acked: number; dead: number };
  let consumer: VirusScanConsumer;
  let sleeps: number[];

  const deliver = (body: unknown) =>
    handleDelivery(
      {
        content: Buffer.from(
          typeof body === 'string' ? body : JSON.stringify(body),
        ),
      },
      {
        channel,
        queue: 'file-service.virus-scan',
        consumer,
        ...SCAN_RETRY,
        logger: silent,
        sleep: (ms) => {
          sleeps.push(ms);
          return Promise.resolve();
        },
      },
    );
  const job = (fileId: string, overrides: Record<string, unknown> = {}) => ({
    ...createEnvelope({
      eventType: SCAN_REQUESTED,
      producer: PRODUCER,
      aggregateId: fileId,
      payload: { fileId },
    }),
    ...overrides,
  });

  beforeEach(() => {
    storage = new FakeObjectStorage();
    files = new InMemoryFileRepository();
    scanner = new FakeScanner();
    sleeps = [];
    channel = {
      acked: 0,
      dead: 0,
      consume: () => Promise.resolve({ consumerTag: 't' }),
      ack() {
        channel.acked += 1;
      },
      nack(_m, _all, requeue) {
        if (requeue === false) channel.dead += 1;
      },
    };
    const scan = new ScanFileUseCase(storage, files, scanner, {
      log() {},
      warn() {},
      error() {},
    });
    consumer = new VirusScanConsumer(scan);
  });

  it('a scan job is processed and acked; the file becomes READY', async () => {
    const file = givenStoredFile(files, storage, Buffer.from('%PDF-1.7'), {
      status: 'PROCESSING',
    });

    await expect(deliver(job(file.fileId))).resolves.toBe('ACK_PROCESSED');
    expect(channel.acked).toBe(1);
    expect(files.rows.get(file.fileId)?.status).toBe('READY');
  });

  it('a fileId in upper case is the same file', async () => {
    const file = givenStoredFile(files, storage, Buffer.from('%PDF-1.7'), {
      status: 'PROCESSING',
    });

    await deliver(job(file.fileId.toUpperCase()));
    expect(files.rows.get(file.fileId)?.status).toBe('READY');
  });

  it.each([
    ['not JSON', '{oops'],
    ['off the envelope contract', { hello: 'world' }],
    [
      'another event type',
      job('4c7f0a1e-9b2d-4e3f-8a5b-6c7d8e9f0a1b', { eventType: 'file.ready' }),
    ],
    [
      'another producer',
      job('4c7f0a1e-9b2d-4e3f-8a5b-6c7d8e9f0a1b', {
        producer: 'contract-service',
      }),
    ],
    ['no fileId UUID', job('not-a-uuid')],
  ])('%s: straight to the DLQ, no retry', async (_, body) => {
    await expect(deliver(body)).resolves.toBe('DEAD_LETTERED');
    expect(channel.dead).toBe(1);
    expect(sleeps).toEqual([]);
  });

  it('scanner down: 3 attempts 5 s then 10 s apart, then the DLQ; the file stays PROCESSING', async () => {
    const file = givenStoredFile(files, storage, Buffer.from('%PDF-1.7'), {
      status: 'PROCESSING',
    });
    scanner.error = Object.assign(new Error('x'), { code: 'ECONNREFUSED' });

    await expect(deliver(job(file.fileId))).resolves.toBe('DEAD_LETTERED');
    expect(sleeps).toEqual([5_000, 10_000]);
    expect(scanner.scanned).toHaveLength(3);
    expect(files.rows.get(file.fileId)?.status).toBe('PROCESSING');
  });
});
