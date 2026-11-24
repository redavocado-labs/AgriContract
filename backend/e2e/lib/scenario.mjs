import { isBusinessDay, todayIct } from './calendar.mjs';
import { config } from './config.mjs';
import { APPLICATION_SERVICES, waitServing } from './docker.mjs';
import { client } from './http.mjs';
import { Trace } from './trace.mjs';

/*
 * Runs one scenario with its own trace: it first waits until every
 * application service serves, the evidence file is written whatever the
 * outcome, and a failure rethrows with the path to it, so the test report
 * points at what to read.
 */
export async function runScenario(name, body) {
  const trace = new Trace(name);
  const api = client(config.gatewayUrl, trace);
  try {
    await waitServing(trace, APPLICATION_SERVICES);
    await body({ api, trace });
    trace.write('passed');
  } catch (err) {
    const file = trace.write('failed', err);
    err.message = `${err.message}\nEvidence: ${file}`;
    throw err;
  }
}

/**
 * Why a scenario that activates a contract cannot run today, or null. A
 * milestone is funded at activation only when its funding date (delivery
 * minus the lead, D64) is today, and the second signature refuses a funding
 * date before today (D67): on a day that is not a Vietnam business day no
 * delivery date satisfies both, so funding would wait for the next one.
 */
export function cannotActivateToday() {
  const today = todayIct();
  return isBusinessDay(today)
    ? null
    : `${today} is not a Vietnam business day: no milestone can be funded at activation today (D63, D67)`;
}
