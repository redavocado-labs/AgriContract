import { randomUUID } from 'node:crypto';

/*
 * HTTP through the gateway, as the frontend calls it. Every exchange goes to
 * the trace; an unexpected status throws with the method, path, status and
 * the service's error envelope, so the failure message alone says which
 * call went wrong and why.
 */

export class HttpError extends Error {
  constructor(method, url, status, body) {
    super(`${method} ${url} answered ${status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
    this.status = status;
    this.body = body;
  }
}

export function client(baseUrl, trace) {
  /**
   * `token` is a bearer token or null; `expect` the accepted statuses.
   * A JSON `body` is sent as JSON, a Buffer as raw bytes. Writes get an
   * Idempotency-Key unless one is given.
   */
  async function request(method, path, { token = null, body, expect = [200], headers = {}, idempotencyKey } = {}) {
    const url = `${baseUrl}${path}`;
    const h = { ...headers };
    if (token) h.authorization = `Bearer ${token}`;
    if (method !== 'GET' && method !== 'HEAD') h['idempotency-key'] = idempotencyKey ?? randomUUID();
    let payload;
    if (Buffer.isBuffer(body)) {
      // Raw bytes (an evidence file): the caller names the content type.
      payload = body;
    } else if (body !== undefined) {
      h['content-type'] = 'application/json';
      payload = JSON.stringify(body);
    }

    const res = await fetch(url, { method, headers: h, body: payload });
    const text = await res.text();
    let parsed = text;
    try {
      parsed = text === '' ? null : JSON.parse(text);
    } catch {
      // not JSON: keep the text
    }
    trace.http({
      method,
      path,
      status: res.status,
      correlationId: res.headers.get('x-correlation-id'),
      body: Buffer.isBuffer(body) ? `(${body.length} bytes)` : body,
      response: parsed,
    });
    if (!expect.includes(res.status)) throw new HttpError(method, path, res.status, parsed);
    return { status: res.status, body: parsed };
  }

  return {
    get: (path, opts) => request('GET', path, opts),
    post: (path, opts) => request('POST', path, opts),
    patch: (path, opts) => request('PATCH', path, opts),
    put: (path, opts) => request('PUT', path, opts),
    delete: (path, opts) => request('DELETE', path, opts),
  };
}
