import {
  Inject,
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
  type OnApplicationBootstrap,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { EmailIntakeUseCase } from '../../application/email-intake.use-case';
import { failureCode } from '../../application/file-errors';
import type { AppConfig } from '../../config/env.validation';

/** Mails per poll; the rest waits for the next one. */
export const INTAKE_BATCH = 50;
/** The first poll waits for the app to settle (the broker connection, the bucket). */
const FIRST_POLL_DELAY_MS = 10_000;

export const EMAIL_INTAKE = Symbol('EMAIL_INTAKE');

/**
 * Polls the intake mailbox every INTAKE_POLL_INTERVAL_MS (design §4.1: every
 * 5 minutes), one poll at a time. Off when INTAKE_IMAP_HOST is not set. A
 * failed poll (IMAP, storage or database down) is logged and the next one
 * takes the same mails again: they were left unseen.
 */
@Injectable()
export class IntakePoller
  implements OnApplicationBootstrap, BeforeApplicationShutdown
{
  private readonly logger = new Logger('EmailIntake');
  private timer?: NodeJS.Timeout;
  private running?: Promise<void>;
  private stopped = false;

  constructor(
    @Inject(EMAIL_INTAKE) private readonly intake: EmailIntakeUseCase | null,
    private readonly config: ConfigService<AppConfig, true>,
  ) {}

  onApplicationBootstrap(): void {
    const settings = this.config.get('intake', { infer: true });
    if (!this.intake || !settings || settings.pollIntervalMs === 0) {
      this.logger.log('Email intake off (INTAKE_IMAP_HOST not set)');
      return;
    }
    const interval = settings.pollIntervalMs;
    const intake = this.intake;
    const tick = () => {
      if (this.stopped) return;
      this.running = this.pollOnce(intake).finally(() => {
        this.running = undefined;
        if (!this.stopped) this.timer = setTimeout(tick, interval);
      });
    };
    this.logger.log(
      `Email intake on: ${settings.address} via ${settings.host}:${settings.port}, every ${interval} ms`,
    );
    this.timer = setTimeout(tick, Math.min(FIRST_POLL_DELAY_MS, interval));
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.timer);
    await this.running;
  }

  private async pollOnce(intake: EmailIntakeUseCase): Promise<void> {
    try {
      await intake.poll(INTAKE_BATCH);
    } catch (err) {
      this.logger.warn(
        `Email intake poll failed (${failureCode(err)}); the next poll takes the same mails again`,
      );
    }
  }
}
