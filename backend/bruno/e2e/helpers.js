const { createHash, randomUUID } = require('crypto');

async function request(bru, config, expected) {
  const response = await bru.sendRequest({
    timeout: 5000,
    validateStatus: () => true,
    ...config,
  });
  if (expected !== undefined && response.status !== expected) {
    throw new Error(`${config.method || 'GET'} ${new URL(config.url).pathname}: expected ${expected}, received ${response.status} ${response.data?.code || ''}`);
  }
  return response;
}

async function poll(read, accept, label, attempts = 30) {
  let result;
  for (let attempt = 0; attempt < attempts; attempt++) {
    result = await read();
    if (accept(result)) return result;
    if (attempt + 1 < attempts) await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`Timed out waiting for ${label} after ${attempts} attempts`);
}

function variable(bru, name) {
  return bru.getVar(name) ?? bru.getEnvVar(name);
}

function tokenUrl(bru) {
  return `${variable(bru, 'keycloak_url')}/realms/${variable(bru, 'realm')}/protocol/openid-connect`;
}

async function exchange(bru, fields) {
  const response = await request(bru, {
    method: 'POST',
    url: tokenUrl(bru) + '/token',
    data: new URLSearchParams({ client_id: variable(bru, 'client_id'), ...fields }).toString(),
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
  }, 200);
  if (!response.data.access_token || response.data.token_type !== 'Bearer') {
    throw new Error('Keycloak response is missing a bearer access token');
  }
  return response.data.access_token;
}

function claims(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
}

async function token(bru, role) {
  const accessToken = await exchange(bru, {
    grant_type: 'password',
    username: variable(bru, role + '_username'),
    password: variable(bru, role + '_password'),
  });
  bru.setVar(role + '_token', accessToken);
  return accessToken;
}

async function browserLogin(bru, credentials, register = false) {
  const redirectUri = 'http://localhost:5173/bruno-callback';
  const verifier = randomUUID() + randomUUID();
  const state = randomUUID();
  const params = new URLSearchParams({
    client_id: variable(bru, 'client_id'),
    response_type: 'code',
    scope: 'openid',
    redirect_uri: redirectUri,
    state,
    code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    code_challenge_method: 'S256',
    prompt: 'login',
  });
  const page = await request(bru, { url: tokenUrl(bru) + '/auth?' + params, maxRedirects: 0 }, 200);
  const cookies = new Map();
  const rememberCookies = (response) => {
    for (const header of response.headers['set-cookie'] || []) {
      const pair = header.split(';', 1)[0];
      cookies.set(pair.split('=', 1)[0], pair);
    }
  };
  const cookieHeader = () => [...cookies.values()].join('; ');
  const localLink = (link) => {
    const url = new URL(link.replaceAll('&amp;', '&'), tokenUrl(bru));
    if (url.origin !== new URL(tokenUrl(bru)).origin) throw new Error('Unexpected Keycloak form origin');
    return url.href;
  };

  rememberCookies(page);
  let html = page.data;
  if (register) {
    const link = html.match(/href="([^"]+login-actions\/registration[^"]*)"/)?.[1];
    if (!link) throw new Error('Keycloak registration link is missing');
    const registration = await request(bru, {
      url: localLink(link),
      headers: { cookie: cookieHeader() },
      maxRedirects: 0,
    }, 200);
    rememberCookies(registration);
    html = registration.data;
  }
  const action = html.match(/<form[^>]*action="([^"]+login-actions\/(?:authenticate|registration)[^"]*)"/)?.[1];
  if (!action) throw new Error('Keycloak form action is missing');
  const submitted = await request(bru, {
    method: 'POST',
    url: localLink(action),
    data: new URLSearchParams(credentials).toString(),
    headers: { cookie: cookieHeader(), 'content-type': 'application/x-www-form-urlencoded' },
    maxRedirects: 0,
  }, 302);
  const redirected = new URL(submitted.headers.location);
  const code = redirected.searchParams.get('code');
  if (!code || redirected.searchParams.get('state') !== state ||
      redirected.origin + redirected.pathname !== redirectUri) {
    throw new Error('Keycloak did not return an authorization code for this session');
  }
  return exchange(bru, {
    grant_type: 'authorization_code', code, redirect_uri: redirectUri, code_verifier: verifier,
  });
}

function gateway(bru, path, role, options = {}) {
  return request(bru, {
    ...options,
    url: variable(bru, 'base_url') + path,
    headers: {
      ...(role && { Authorization: 'Bearer ' + variable(bru, role + '_token') }),
      ...options.headers,
    },
  });
}

async function mailCode(bru, email, subject, matchBody = () => true) {
  const response = await poll(async () => {
    return request(bru, {
      url: variable(bru, 'mailhog_url') + '/api/v2/search?' +
        new URLSearchParams({ kind: 'to', query: email, limit: '100' }),
    }, 200);
  }, ({ data }) => data.items?.some((message) =>
    message.Content?.Headers?.Subject?.[0] === subject && matchBody(message.Content.Body)),
  'MailHog message for ' + email);
  const message = response.data.items.find((item) =>
    item.Content?.Headers?.Subject?.[0] === subject && matchBody(item.Content.Body));
  const code = message.Content.Body.replace(/=\r?\n/g, '').match(/\b[0-9]{6}\b/)?.[0];
  if (!code) throw new Error('MailHog message is missing the OTP');
  return code;
}

module.exports = { browserLogin, claims, gateway, mailCode, poll, request, token, variable };
