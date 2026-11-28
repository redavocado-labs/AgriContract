import { execFileSync } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';

/*
 * Compose containers serving or not: healthy when the compose file gives the
 * container a healthcheck, otherwise once its log since its last start says
 * Spring Boot started ("Started ...Application in"). "running" alone is not
 * enough: a JVM takes most of a minute to listen.
 */

const docker = (args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

/** The services the golden flow calls or depends on; the infrastructure has healthchecks. */
/**
 * The compose container of `service`. E2E_CONTAINER_PREFIX (for example `agricontract-e2e-`) names the
 * containers of a stack started under its own project, next to a local dev stack that holds the bare names.
 */
export const container = (service) => `${process.env.E2E_CONTAINER_PREFIX ?? ''}${service}`;

export const APPLICATION_SERVICES = [
  'api-gateway',
  'user-service',
  'product-service',
  'contract-service',
  'escrow-service',
  'bank-service',
  'inspection-service',
  'audit-service',
  'notification-service',
  'file-service',
].map(container);

/** Waits until every container serves, each from its own last start. */
export async function waitServing(trace, containers, { timeoutMs = 300_000 } = {}) {
  const started = Date.now();
  for (const name of containers) {
    const since = docker(['inspect', '-f', '{{.State.StartedAt}}', name]);
    for (;;) {
      const health = docker(['inspect', '-f', '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}', name]);
      if (health === 'healthy') break;
      if (health === 'none' && /Started \w+ in /.test(docker(['logs', '--since', since, name]))) break;
      if (Date.now() - started > timeoutMs) {
        throw new Error(`${name} does not serve ${timeoutMs} ms after the wait began (health: ${health})`);
      }
      await sleep(2000);
    }
  }
  trace.step(`serving: ${containers.join(', ')} (${Date.now() - started} ms)`);
}

/** Restarts the containers and waits until they serve again. */
export async function restart(trace, containers) {
  trace.step(`restart ${containers.join(', ')}`);
  docker(['restart', ...containers]);
  await waitServing(trace, containers);
}
