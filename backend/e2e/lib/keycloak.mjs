import { createHash, randomBytes } from 'node:crypto';
import { config } from './config.mjs';

/*
 * Tokens from the compose Keycloak, for the seeded users.
 *
 * Signing and the other step-up actions need a login younger than 300 s,
 * read from the token's `auth_time`; a password-grant token has none (Bruno
 * 11 shows the refusal). freshLogin therefore does what the browser does: an
 * authorization-code login with PKCE through the login form, and the code
 * is taken from the redirect's Location header, so nothing has to listen on
 * the redirect URI.
 */

const realmUrl = () => `${config.keycloakUrl}/realms/${config.realm}/protocol/openid-connect`;

/** A token without auth_time: enough for every call but the step-up ones. */
export async function passwordToken(username) {
  const res = await fetch(`${realmUrl()}/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: config.clientId,
      username,
      password: config.password,
    }),
  });
  if (!res.ok) throw new Error(`password grant for ${username} answered ${res.status}: ${await res.text()}`);
  return (await res.json()).access_token;
}

/** A token with a fresh auth_time, through the login form. */
export async function freshLogin(username) {
  const jar = new CookieJar();
  const verifier = base64url(randomBytes(32));
  const challenge = base64url(createHash('sha256').update(verifier).digest());
  const state = base64url(randomBytes(8));

  const authUrl = `${realmUrl()}/auth?${new URLSearchParams({
    client_id: config.clientId,
    response_type: 'code',
    scope: 'openid',
    redirect_uri: config.redirectUri,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    prompt: 'login',
  })}`;
  const page = await fetch(authUrl, { redirect: 'manual' });
  jar.take(page);
  const html = await page.text();
  const action = kcContextUrl(html, 'loginAction')
    ?? /<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/.exec(html)?.[1]
    ?? /<form[^>]*action="([^"]+login-actions\/authenticate[^"]*)"/.exec(html)?.[1];
  if (!action) throw new Error(`no login form for ${username} (status ${page.status})`);

  const login = await fetch(decodeEntities(action), {
    method: 'POST',
    redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded', cookie: jar.header() },
    body: new URLSearchParams({ username, password: config.password, credentialId: '' }),
  });
  const location = login.headers.get('location');
  const code = location ? new URL(location).searchParams.get('code') : null;
  if (!code) {
    throw new Error(`login of ${username} did not redirect with a code (status ${login.status})`);
  }

  const res = await fetch(`${realmUrl()}/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: config.clientId,
      code,
      redirect_uri: config.redirectUri,
      code_verifier: verifier,
    }),
  });
  if (!res.ok) throw new Error(`code exchange for ${username} answered ${res.status}: ${await res.text()}`);
  return (await res.json()).access_token;
}

/** The `sub` of a token: the platform user id. */
export function subjectOf(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')).sub;
}

class CookieJar {
  constructor() {
    this.cookies = new Map();
  }

  take(res) {
    for (const line of res.headers.getSetCookie()) {
      const [pair] = line.split(';');
      const eq = pair.indexOf('=');
      this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1));
    }
  }

  header() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }
}

const base64url = (buf) => Buffer.from(buf).toString('base64url');

const decodeEntities = (s) => s.replaceAll('&amp;', '&');

/*
 * The Keycloakify theme (#665) draws the form in the browser, so the page has
 * no <form action>; the URL is in the kcContext script as
 * `"loginAction": "..."`, a JSON string. The form regexes stay for the stock
 * theme.
 */
const kcContextUrl = (html, key) => {
  const quoted = new RegExp(`"${key}"\\s*:\\s*("(?:[^"\\\\]|\\\\.)*")`).exec(html)?.[1];
  return quoted === undefined ? undefined : JSON.parse(quoted);
};
