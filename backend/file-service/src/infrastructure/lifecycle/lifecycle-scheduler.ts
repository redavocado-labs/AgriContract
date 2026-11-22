import {
  Injectable,
  Logger,
  type BeforeApplicationShutdown,
  type OnApplicationBootstrap,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { failureCode } from '../../application/file-errors';
import { FileLifecycleUseCase } from '../../application/file-lifecycle.use-case';
import type { AppConfig } from '../../config/env.validation';

/** The first run waits for the app to settle, then runs come every interval. */
const FIRST_RUN_DELAY_MS = 60_000;

/**
 * Runs FileLifecycleUseCase every FILE_LIFECYCLE_INTERVAL_MS (hourly by
 * default; 0 turns it off). One run at a time per instance; a run that fails
 * (database or storage down) is logged and the next one tries again. Several
 * instances may run at once: every step is idempotent.
 */
@Injectable()
export class LifecycleScheduler
  implements OnApplicationBootstrap, BeforeApplicationShutdown
{
  private readonly logger = new Logger('FileLifecycle');
  private timer?: NodeJS.Timeout;
  private running?: Promise<void>;
  private stopped = false;

  constructor(
    private readonly lifecycle: FileLifecycleUseCase,
    private readonly config: ConfigService<AppConfig, true>,
  ) {}

  onApplicationBootstrap(): void {
    const interval = this.config.get('lifecycleIntervalMs', { infer: true });
    if (interval === 0) {
      this.logger.log('Lifecycle job off (FILE_LIFECYCLE_INTERVAL_MS=0)');
      return;
    }
    const tick = () => {
      if (this.stopped) return;
      this.running = this.runOnce().finally(() => {
        this.running = undefined;
        if (!this.stopped) this.timer = setTimeout(tick, interval);
      });
    };
    this.timer = setTimeout(tick, Math.min(FIRST_RUN_DELAY_MS, interval));
  }

  async beforeApplicationShutdown(): Promise<void> {
    this.stopped = true;
    clearTimeout(this.timer);
    await this.running;
  }

  private async runOnce(): Promise<void> {
    try {
      await this.lifecycle.runOnce();
    } catch (err) {
      this.logger.warn(
        `Lifecycle run failed (${failureCode(err)}); the next run tries again`,
      );
    }
  }
}
