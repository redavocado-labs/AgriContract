import { Logger } from '@nestjs/common';
import type { ConfigService } from '@nestjs/config';
import type { FileLifecycleUseCase } from '../../application/file-lifecycle.use-case';
import type { AppConfig } from '../../config/env.validation';
import { LifecycleScheduler } from './lifecycle-scheduler';

describe('LifecycleScheduler', () => {
  let runs: number;
  let fail: boolean;
  let warnings: string[];

  const scheduler = (intervalMs: number) =>
    new LifecycleScheduler(
      {
        runOnce: () => {
          runs += 1;
          return fail
            ? Promise.reject(
                Object.assign(new Error('x'), { code: 'ECONNREFUSED' }),
              )
            : Promise.resolve({} as never);
        },
      } as unknown as FileLifecycleUseCase,
      { get: () => intervalMs } as unknown as ConfigService<AppConfig, true>,
    );

  beforeEach(() => {
    jest.useFakeTimers();
    runs = 0;
    fail = false;
    warnings = [];
    jest.spyOn(Logger.prototype, 'log').mockImplementation(() => undefined);
    jest
      .spyOn(Logger.prototype, 'warn')
      .mockImplementation((m: unknown) => void warnings.push(String(m)));
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('runs a minute after start, then every interval', async () => {
    const s = scheduler(10 * 60_000);
    s.onApplicationBootstrap();

    await jest.advanceTimersByTimeAsync(59_000);
    expect(runs).toBe(0);
    await jest.advanceTimersByTimeAsync(1_000);
    expect(runs).toBe(1);
    await jest.advanceTimersByTimeAsync(10 * 60_000);
    expect(runs).toBe(2);
    await s.beforeApplicationShutdown();
  });

  it('0 turns it off', async () => {
    const s = scheduler(0);
    s.onApplicationBootstrap();

    await jest.advanceTimersByTimeAsync(24 * 60 * 60_000);
    expect(runs).toBe(0);
  });

  it('a failed run is logged with its code, and the next one still comes', async () => {
    fail = true;
    const s = scheduler(60_000);
    s.onApplicationBootstrap();

    await jest.advanceTimersByTimeAsync(120_000);

    expect(runs).toBe(2);
    expect(warnings[0]).toBe(
      'Lifecycle run failed (ECONNREFUSED); the next run tries again',
    );
    await s.beforeApplicationShutdown();
  });

  it('no run after shutdown', async () => {
    const s = scheduler(60_000);
    s.onApplicationBootstrap();
    await s.beforeApplicationShutdown();

    await jest.advanceTimersByTimeAsync(10 * 60_000);
    expect(runs).toBe(0);
  });
});
