import {
  Inject,
  Injectable,
  type BeforeApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import {
  AmqpConfirmPublisher,
  MysqlOutboxStore,
  MysqlTransactionRunner,
  OutboxPoller,
  consumeEvents,
  type EventPublisher,
  type MessageConsumer,
} from '@agricontract/eventing';
import type { ConfirmChannel } from 'amqplib';
import type { Pool } from 'mysql2/promise';
import { FILES_EXCHANGE } from '../../application/file-events';
import { ScanFileUseCase } from '../../application/scan-file.use-case';
import { MYSQL_POOL } from '../persistence/mysql.provider';
import { AmqpService } from './amqp.service';
import { eventingLogger } from './eventing-logger';
import { VIRUS_SCAN_QUEUE } from './topology';
import { VirusScanConsumer } from './virus-scan.consumer';

/** Scans in flight per instance: each holds a storage read and a clamd connection. */
export const SCAN_PREFETCH = 2;

/**
 * §4 retry: 3 attempts in all, 5 s then 10 s apart, then the DLQ. Long
 * enough for a storage or clamd hiccup, short enough not to hold the queue.
 */
export const SCAN_RETRY = {
  maxAttempts: 3,
  initialBackoffMs: 5_000,
  maxBackoffMs: 10_000,
} as const;

/**
 * Runs the asynchronous side of file-service:
 *
 * - the outbox poller, which publishes committed event_outbox rows to
 *   agricontract.files with publisher confirms (at least once, same eventId on
 *   a repeat) — the scan jobs and file.ready / file.failed;
 * - VirusScanConsumer on file-service.virus-scan, started on every
 *   (re)connect.
 *
 * Shutdown (before the pool and the connection close): the consumer is
 * cancelled, then the poller finishes its batch. A scan cut short is not
 * acked, so the broker redelivers it and the scan runs again.
 */
@Injectable()
export class ScanWorker implements OnModuleInit, BeforeApplicationShutdown {
  private readonly poller: OutboxPoller<unknown>;
  private readonly consumer: MessageConsumer;
  private consumerTag?: string;
  /** The confirm channel of the latest connection; a closed one fails the publish, and the row waits. */
  private publishChannel?: ConfirmChannel;
  private stopped = false;

  constructor(
    private readonly amqp: AmqpService,
    @Inject(MYSQL_POOL) pool: Pool,
    scan: ScanFileUseCase,
  ) {
    this.consumer = new VirusScanConsumer(scan);
    const publisher: EventPublisher = {
      publish: (message) => {
        const channel = this.publishChannel;
        if (!channel) {
          return Promise.reject(new Error('RabbitMQ is not connected'));
        }
        return new AmqpConfirmPublisher(channel).publish(message);
      },
    };
    this.poller = new OutboxPoller({
      store: new MysqlOutboxStore(),
      transactions: new MysqlTransactionRunner(pool),
      publisher,
      exchange: FILES_EXCHANGE,
      logger: eventingLogger('Outbox'),
    });
  }

  async onModuleInit(): Promise<void> {
    await this.amqp.onChannelsReady(async ({ consume, publish }) => {
      if (this.stopped) return;
      this.publishChannel = publish;
      // From the first connection on (start is idempotent): rows written
      // before it simply wait, instead of failing a publish every second.
      this.poller.start();
      await consume.prefetch(SCAN_PREFETCH);
      this.consumerTag = await consumeEvents({
        channel: consume,
        queue: VIRUS_SCAN_QUEUE,
        consumer: this.consumer,
        ...SCAN_RETRY,
        logger: eventingLogger('VirusScan'),
      });
    });
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopped = true;
    const channel = this.amqp.channels()?.consume;
    if (channel && this.consumerTag) {
      await channel.cancel(this.consumerTag).catch(() => undefined);
    }
    await this.poller.stop();
  }
}
