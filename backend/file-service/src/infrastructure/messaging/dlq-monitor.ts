import {
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
  type OnApplicationBootstrap,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { failureCode } from '../../application/file-errors';
import type { AppConfig } from '../../config/env.validation';
import { AmqpService } from './amqp.service';
import { VIRUS_SCAN_DLQ } from './topology';

/**
 * Warns while file-service.virus-scan.dlq holds messages, every
 * FILE_DLQ_CHECK_INTERVAL_MS (5 minutes by default, 0 turns it off), like
 * notification-service's DlqMonitor. A job lands there when storage or clamd
 * stayed down through its 3 attempts, and its file stays PROCESSING until a
 * person fixes the cause and replays the message: without this nobody would
 * know. The check is passive (checkQueue) and never takes a message.
 */
@Injectable()
export class DlqMonitor
  implements OnApplicationBootstrap, BeforeApplicationShutdown
{
  private readonly logger = new Logger(DlqMonitor.name);
  private timer?: NodeJS.Timeout;
  private running?: Promise<void>;
  private stopped = false;

  constructor(
    private readonly amqp: AmqpService,
    private readonly config: ConfigService<AppConfig, true>,
  ) {}

  onApplicationBootstrap(): void {
    const interval = this.config.get('dlqCheckIntervalMs', { infer: true });
    if (interval === 0) {
      this.logger.log('DLQ check off (FILE_DLQ_CHECK_INTERVAL_MS=0)');
      return;
    }
    const tick = () => {
      if (this.stopped) return;
      this.running = this.checkOnce().finally(() => {
        this.running = undefined;
        if (!this.stopped) this.timer = setTimeout(tick, interval);
      });
    };
    this.timer = setTimeout(tick, interval);
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.timer);
    await this.running;
  }

  async checkOnce(): Promise<void> {
    const channels = this.amqp.channels();
    if (!channels) {
      this.logger.warn(
        `DLQ check of ${VIRUS_SCAN_DLQ} skipped: RabbitMQ is not connected`,
      );
      return;
    }
    try {
      const { messageCount } =
        await channels.consume.checkQueue(VIRUS_SCAN_DLQ);
      if (messageCount > 0) {
        this.logger.warn(
          `DLQ ${VIRUS_SCAN_DLQ} has ${messageCount} message(s): their files stay PROCESSING; fix the cause, then replay them`,
        );
      }
    } catch (err) {
      // The channel may be gone: AmqpService reconnects, the next check tries again.
      this.logger.warn(
        `DLQ check of ${VIRUS_SCAN_DLQ} failed (${failureCode(err)})`,
      );
    }
  }
}
