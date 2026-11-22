import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationShutdown,
  type OnModuleInit,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import type { Channel, ChannelModel, ConfirmChannel, Options } from 'amqplib';
import { failureCode } from '../../application/file-errors';
import type { AppConfig } from '../../config/env.validation';
import { declareTopology } from './topology';

/** Injection token of amqplib's `connect`, replaced in tests. */
export const AMQP_CONNECT = Symbol('AMQP_CONNECT');
export type AmqpConnect = (
  url: Options.Connect,
  socketOptions?: Record<string, unknown>,
) => Promise<ChannelModel>;

/** The two channels of one connection: consume on one, publish with confirms on the other. */
export interface AmqpChannels {
  consume: Channel;
  publish: ConfirmChannel;
}

export type ChannelsReadyCallback = (channels: AmqpChannels) => Promise<void>;

const INITIAL_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;
const HEARTBEAT_SECONDS = 60;
const CONNECT_TIMEOUT_MS = 10_000;

/**
 * One RabbitMQ connection, kept alive like notification-service's AmqpService:
 * startup never waits for the broker (uploads and reads do not need it, the
 * outbox keeps their events), failures are retried in the background (1s, 2s
 * … 30s), and every (re)connect declares the topology and runs the
 * `onChannelsReady` callbacks (the scan consumer starts there).
 */
@Injectable()
export class AmqpService implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(AmqpService.name);
  private readonly callbacks: ChannelsReadyCallback[] = [];
  private connection?: ChannelModel;
  private live?: AmqpChannels;
  private retryTimer?: NodeJS.Timeout;
  private failures = 0;
  private stopped = false;

  constructor(
    private readonly config: ConfigService<AppConfig, true>,
    @Inject(AMQP_CONNECT) private readonly connect: AmqpConnect,
  ) {}

  onModuleInit(): void {
    void this.establish();
  }

  /** The live channels, or undefined while disconnected. */
  channels(): AmqpChannels | undefined {
    return this.live;
  }

  async onChannelsReady(callback: ChannelsReadyCallback): Promise<void> {
    this.callbacks.push(callback);
    if (this.live) await callback(this.live);
  }

  /** Stop reconnecting, then close the channels and the connection. */
  async onApplicationShutdown(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    const { live: channels, connection } = this;
    this.live = undefined;
    this.connection = undefined;
    await closeQuietly(channels?.consume);
    await closeQuietly(channels?.publish);
    await closeQuietly(connection);
  }

  private async establish(): Promise<void> {
    if (this.stopped) return;
    const rabbit = this.config.get('rabbitmq', { infer: true });
    const target = `${rabbit.host}:${rabbit.port}`;
    let connection: ChannelModel | undefined;
    try {
      connection = await this.connect(
        {
          protocol: 'amqp',
          hostname: rabbit.host,
          port: rabbit.port,
          username: rabbit.username,
          password: rabbit.password,
          heartbeat: HEARTBEAT_SECONDS,
        },
        { timeout: CONNECT_TIMEOUT_MS },
      );
      const conn = connection;
      conn.on('error', (err: Error) =>
        this.logger.warn(`RabbitMQ connection error (${failureCode(err)})`),
      );
      conn.on('close', () => this.lost(conn, 'connection closed'));

      const consume = await conn.createChannel();
      const publish = await conn.createConfirmChannel();
      for (const ch of [consume, publish]) {
        ch.on('error', (err: Error) =>
          this.logger.warn(`RabbitMQ channel error (${failureCode(err)})`),
        );
        ch.on('close', () => this.lost(conn, 'channel closed'));
      }
      await declareTopology(consume);
      const channels = { consume, publish };
      for (const callback of this.callbacks) await callback(channels);

      if (this.stopped) {
        await closeQuietly(conn);
        return;
      }
      this.connection = conn;
      this.live = channels;
      this.failures = 0;
      this.logger.log(`Connected to RabbitMQ at ${target}, topology declared`);
    } catch (err) {
      const delay = this.scheduleRetry();
      this.logger.warn(
        `RabbitMQ at ${target} not ready (${failureCode(err)}); retrying in ${delay}ms`,
      );
      await closeQuietly(connection);
    }
  }

  private lost(connection: ChannelModel, why: string): void {
    if (this.stopped || this.connection !== connection) return;
    this.connection = undefined;
    this.live = undefined;
    const delay = this.scheduleRetry();
    this.logger.warn(`RabbitMQ ${why}; reconnecting in ${delay}ms`);
    void closeQuietly(connection);
  }

  private scheduleRetry(): number {
    const delay = Math.min(
      INITIAL_BACKOFF_MS * 2 ** this.failures,
      MAX_BACKOFF_MS,
    );
    if (this.stopped || this.retryTimer) return delay;
    this.failures += 1;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      void this.establish();
    }, delay);
    return delay;
  }
}

async function closeQuietly(target?: { close(): Promise<void> }) {
  try {
    await target?.close();
  } catch {
    // already closed
  }
}
