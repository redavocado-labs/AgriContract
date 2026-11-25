/*
 * Where the compose stack listens and the seeded accounts the golden flow
 * acts as. Every value can be overridden by an environment variable of the
 * same name, so CI and a developer machine run the same scenarios.
 */

const env = (name, fallback) => process.env[name] ?? fallback;

export const config = {
  gatewayUrl: env('E2E_GATEWAY_URL', 'http://localhost:8080'),
  keycloakUrl: env('E2E_KEYCLOAK_URL', 'http://localhost:8180'),
  realm: env('E2E_REALM', 'agricontract'),
  clientId: env('E2E_CLIENT_ID', 'agricontract-frontend'),
  /** A redirect URI the frontend client allows; nothing has to listen there. */
  redirectUri: env('E2E_REDIRECT_URI', 'http://localhost:5173/e2e-callback'),
  mailhogUrl: env('E2E_MAILHOG_URL', 'http://localhost:8025'),
  /** The realm import's password of every seeded user (infra/keycloak/agricontract-realm.json). */
  password: env('E2E_PASSWORD', 'pass123'),
  /** Seeded in Keycloak and, for the parties and the inspector, in user_db (scripts/seed/02-user-db.sql). */
  users: {
    buyer: { username: 'buyer1', email: 'buyer1@test.com' },
    seller: { username: 'seller1', email: 'seller1@test.com' },
    inspector: { username: 'inspector1' },
    arbitrator: { username: 'arbitrator1' },
    operator: { username: 'operator1' },
  },
  /** How long one asynchronous step (outbox, broker, consumer) may take. */
  /** How long to watch for an effect that must NOT happen (a replay moving money or queuing events). */
  replayQuietMs: Number(env('E2E_REPLAY_QUIET_MS', '8000')),
  stepTimeoutMs: Number(env('E2E_STEP_TIMEOUT_MS', '60000')),
};
