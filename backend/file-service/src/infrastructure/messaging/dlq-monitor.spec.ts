import { Logger } from '@nestjs/common';
import type { ConfigService } from '@nestjs/config';
import type { AppConfig } from '../../config/env.validation';
import type { AmqpChannels, AmqpService } from './amqp.service';
import { DlqMonitor } from './dlq-monitor';

describe('DlqMonitor', () => {
  let checked: string[];
  let depth: number;
  let checkError: Error | null;
  let connected: boolean;
  let warnings: string[];
  let logs: string[];

  const channels = {
    consume: {
      checkQueue: (queue: string) => {
        checked.push(queue);
        return checkError
          ? Promise.reject(checkError)
          : Promise.resolve({ queue, messageCount: depth, consumerCount: 0 });
      },
    },
  } as unknown as AmqpChannels;

  const monitor = (intervalMs: number) =>
    new DlqMonitor(
      {
        channels: () => (connected ? channels : undefined),
      } as unknown as AmqpService,
      { get: () => intervalMs } as unknown as ConfigService<AppConfig, true>,
    );

  beforeEach(() => {
    jest.useFakeTimers();
    checked = [];
    depth = 0;
    checkError = null;
    connected = true;
    warnings = [];
    logs = [];
    jest
      .spyOn(Logger.prototype, 'log')
      .mockImplementation((m: unknown) => void logs.push(String(m)));
    jest
      .spyOn(Logger.prototype, 'warn')
      .mockImplementation((m: unknown) => void warnings.push(String(m)));
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('warns with the queue name and the message count while the DLQ holds messages', async () => {
    depth = 3;

    await monitor(60_000).checkOnce();

    expect(checked).toEqual(['file-service.virus-scan.dlq']);
    expect(warnings).toEqual([
      'DLQ file-service.virus-scan.dlq has 3 message(s): their files stay PROCESSING; fix the cause, then replay them',
    ]);
  });

  it('says nothing while the DLQ is empty', async () => {
    await monitor(60_000).checkOnce();

    expect(checked).toHaveLength(1);
    expect(warnings).toEqual([]);
  });

  it('skips the check, with a warning, while RabbitMQ is not connected', async () => {
    connected = false;

    await monitor(60_000).checkOnce();

    expect(checked).toEqual([]);
    expect(warnings).toEqual([
      'DLQ check of file-service.virus-scan.dlq skipped: RabbitMQ is not connected',
    ]);
  });

  it('a failed check is logged with its code only, and does not throw', async () => {
    checkError = Object.assign(new Error('Channel closed by 10.0.0.5'), {
      code: 'ECONNRESET',
    });

    await expect(monitor(60_000).checkOnce()).resolves.toBeUndefined();

    expect(warnings).toEqual([
      'DLQ check of file-service.virus-scan.dlq failed (ECONNRESET)',
    ]);
  });

  it('checks every interval, the first one an interval after start', async () => {
    const m = monitor(5 * 60_000);
    m.onApplicationBootstrap();

    await jest.advanceTimersByTimeAsync(5 * 60_000 - 1);
    expect(checked).toHaveLength(0);
    await jest.advanceTimersByTimeAsync(1);
    expect(checked).toHaveLength(1);
    await jest.advanceTimersByTimeAsync(5 * 60_000);
    expect(checked).toHaveLength(2);
    await m.beforeApplicationShutdown();
  });

  it('0 turns it off', async () => {
    const m = monitor(0);
    m.onApplicationBootstrap();

    await jest.advanceTimersByTimeAsync(24 * 60 * 60_000);

    expect(checked).toEqual([]);
    expect(logs).toEqual(['DLQ check off (FILE_DLQ_CHECK_INTERVAL_MS=0)']);
  });

  it('no check after shutdown', async () => {
    const m = monitor(60_000);
    m.onApplicationBootstrap();
    await m.beforeApplicationShutdown();

    await jest.advanceTimersByTimeAsync(10 * 60_000);

    expect(checked).toEqual([]);
  });
});
