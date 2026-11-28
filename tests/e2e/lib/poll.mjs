import { setTimeout as sleep } from 'node:timers/promises';
import { config } from './config.mjs';

/*
 * Waits for an asynchronous effect (an outbox row published, a consumer
 * applying it) by reading state until `done(state)` holds. On timeout the
 * error names what was awaited and quotes the last state read: that is the
 * evidence of where the flow stopped.
 */
export async function waitFor(trace, what, read, done, { timeoutMs = config.stepTimeoutMs, everyMs = 1000 } = {}) {
  const started = Date.now();
  let last;
  for (;;) {
    last = await read();
    if (done(last)) {
      trace.wait({ waitedFor: what, ms: Date.now() - started, last });
      return last;
    }
    if (Date.now() - started > timeoutMs) {
      trace.wait({ waitedFor: what, timedOutAfterMs: timeoutMs, last });
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}. Last state: ${JSON.stringify(last)}`);
    }
    await sleep(everyMs);
  }
}
