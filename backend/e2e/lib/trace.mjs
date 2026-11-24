import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * The evidence a failed run leaves behind: every HTTP exchange and every
 * awaited state, in order, written to out/<scenario>.json. CI uploads the
 * folder, so a red run says which step failed, what the services answered
 * and what the last observed state was, without rerunning it.
 */

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'out');

/** Response bodies are cut here: the evidence is for reading, not replaying. */
const MAX_BODY = 4000;

export class Trace {
  constructor(scenario) {
    this.scenario = scenario;
    this.entries = [];
    this.ids = {};
  }

  /** Ids worth having at hand when reading the logs: contractId, milestoneIds... */
  remember(name, value) {
    this.ids[name] = value;
  }

  step(name) {
    this.entries.push({ at: new Date().toISOString(), step: name });
  }

  http(entry) {
    this.entries.push({
      at: new Date().toISOString(),
      ...entry,
      body: clip(entry.body),
      response: clip(entry.response),
    });
  }

  wait(entry) {
    this.entries.push({ at: new Date().toISOString(), ...entry, last: clip(entry.last) });
  }

  write(outcome, error) {
    mkdirSync(OUT_DIR, { recursive: true });
    const file = join(OUT_DIR, `${this.scenario}.json`);
    writeFileSync(
      file,
      JSON.stringify(
        {
          scenario: this.scenario,
          outcome,
          error: error ? String(error.stack ?? error) : null,
          ids: this.ids,
          entries: this.entries,
        },
        null,
        2,
      ),
    );
    return file;
  }
}

function clip(value) {
  if (value === undefined || value === null) return value;
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text.length > MAX_BODY ? `${text.slice(0, MAX_BODY)}... (${text.length} chars)` : value;
}
