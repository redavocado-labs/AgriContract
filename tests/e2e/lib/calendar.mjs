import { execFileSync } from 'node:child_process';
import { container } from './docker.mjs';

/*
 * Vietnam business days, on the very calendar contract-service runs with: its
 * VIETNAM_HOLIDAYS variable, read from the container, so a date the test
 * picks is the date the service computes (Monday to Friday minus those
 * holidays, days in Asia/Ho_Chi_Minh).
 */

let holidays;

function holidaySet() {
  holidays ??= new Set(
    execFileSync('docker', ['exec', process.env.E2E_CONTRACT_CONTAINER ?? container('contract-service'), 'printenv', 'VIETNAM_HOLIDAYS'], {
      encoding: 'utf8',
    })
      .trim()
      .split(',')
      .filter(Boolean),
  );
  return holidays;
}

/** Today in Vietnam, YYYY-MM-DD. */
export function todayIct() {
  return new Date(Date.now() + 7 * 3_600_000).toISOString().slice(0, 10);
}

export const isBusinessDay = (iso) => {
  const day = new Date(`${iso}T00:00:00Z`).getUTCDay();
  return day !== 0 && day !== 6 && !holidaySet().has(iso);
};

/** The date `n` business days after `from` (YYYY-MM-DD). */
export function businessDaysAfter(from, n) {
  let date = new Date(`${from}T00:00:00Z`);
  let left = n;
  while (left > 0) {
    date = new Date(date.getTime() + 86_400_000);
    if (isBusinessDay(date.toISOString().slice(0, 10))) left -= 1;
  }
  return date.toISOString().slice(0, 10);
}
