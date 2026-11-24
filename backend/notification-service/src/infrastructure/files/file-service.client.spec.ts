import {
  createServer,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from 'node:http';
import type { AddressInfo } from 'node:net';
import {
  FileNotReadyError,
  FileServiceRejectedError,
  FileServiceUnavailableError,
} from '../../application/notification-errors';
import { FileServiceClient } from './file-service.client';

const FILE_ID = 'c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f';
const SECRET = 'internal-secret-for-tests';
const PDF = Buffer.from('%PDF-1.7 bytes of the final contract');

/** A real HTTP server standing in for file-service, one handler per test. */
let server: Server;
let baseUrl: string;
let handle: (req: IncomingMessage, res: ServerResponse) => void;
const requests: IncomingMessage[] = [];

beforeAll(async () => {
  server = createServer((req, res) => {
    requests.push(req);
    handle(req, res);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
});

beforeEach(() => {
  requests.length = 0;
});

const client = (options = {}) =>
  new FileServiceClient(baseUrl, SECRET, options);

const rejection = async (promise: Promise<unknown>): Promise<Error> => {
  try {
    await promise;
  } catch (err) {
    return err as Error;
  }
  throw new Error('expected a rejection');
};

describe('FileServiceClient', () => {
  it('GETs the file with the internal secret and returns its bytes and content type', async () => {
    handle = (_req, res) => {
      res.writeHead(200, {
        'Content-Type': 'application/pdf',
        'Content-Length': PDF.length,
      });
      res.end(PDF);
    };

    const file = await client().readReady(FILE_ID);

    expect(file).toEqual({ content: PDF, contentType: 'application/pdf' });
    expect(requests[0].method).toBe('GET');
    expect(requests[0].url).toBe(`/internal/v1/files/${FILE_ID}`);
    expect(requests[0].headers['x-internal-secret']).toBe(SECRET);
  });

  it('accepts a base URL with a trailing slash and a chunked body', async () => {
    handle = (_req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/pdf; charset=binary' });
      res.write(PDF.subarray(0, 10));
      res.end(PDF.subarray(10));
    };

    const file = await new FileServiceClient(`${baseUrl}/`, SECRET).readReady(
      FILE_ID,
    );

    expect(file.content).toEqual(PDF);
    expect(file.contentType).toBe('application/pdf');
    expect(requests[0].url).toBe(`/internal/v1/files/${FILE_ID}`);
  });

  it('404 (unknown, not READY or deleted) is FileNotReadyError, retryable', async () => {
    handle = (_req, res) => res.writeHead(404).end('{"code":"NOT_FOUND"}');

    const err = await rejection(client().readReady(FILE_ID));

    expect(err).toBeInstanceOf(FileNotReadyError);
    expect(err.message).toBe(
      `File ${FILE_ID} is not READY in file-service (unknown, not ready or deleted)`,
    );
  });

  it.each([503, 500, 502, 429, 408])(
    '%d is FileServiceUnavailableError, retryable',
    async (status) => {
      handle = (_req, res) => res.writeHead(status).end();

      const err = await rejection(client().readReady(FILE_ID));

      expect(err).toBeInstanceOf(FileServiceUnavailableError);
      expect(err.message).toContain(`(HTTP_${status})`);
    },
  );

  it.each([401, 400, 403])(
    '%d is FileServiceRejectedError, not retryable',
    async (status) => {
      handle = (_req, res) => res.writeHead(status).end('{"message":"x"}');

      const err = await rejection(client().readReady(FILE_ID));

      expect(err).toBeInstanceOf(FileServiceRejectedError);
      expect(err.message).toBe(
        `File ${FILE_ID}: file-service refused the read (HTTP_${status})`,
      );
    },
  );

  it('a body cut short of its Content-Length (file-service hash check) is unavailable, never returned', async () => {
    handle = (_req, res) => {
      res.writeHead(200, {
        'Content-Type': 'application/pdf',
        'Content-Length': PDF.length,
      });
      res.write(PDF.subarray(0, 5));
      setTimeout(() => res.destroy(), 20);
    };

    const err = await rejection(client().readReady(FILE_ID));

    expect(err).toBeInstanceOf(FileServiceUnavailableError);
    expect(err.message).toContain('TRUNCATED');
  });

  it('a declared size over the cap is refused before the body is read', async () => {
    handle = (_req, res) => {
      res.writeHead(200, { 'Content-Length': 11 });
      res.end('x'.repeat(11));
    };

    const err = await rejection(client({ maxBytes: 10 }).readReady(FILE_ID));

    expect(err).toBeInstanceOf(FileServiceRejectedError);
    expect(err.message).toContain('(TOO_LARGE)');
  });

  it('a chunked body over the cap is refused', async () => {
    handle = (_req, res) => {
      res.writeHead(200);
      res.end('x'.repeat(11));
    };

    const err = await rejection(client({ maxBytes: 10 }).readReady(FILE_ID));

    expect(err).toBeInstanceOf(FileServiceRejectedError);
  });

  it('no answer within the timeout is unavailable (TIMEOUT)', async () => {
    handle = () => undefined; // never answers

    const err = await rejection(client({ timeoutMs: 50 }).readReady(FILE_ID));

    expect(err).toBeInstanceOf(FileServiceUnavailableError);
    expect(err.message).toContain('(TIMEOUT)');
  });

  it('a refused connection is unavailable with the socket code', async () => {
    const closed = createServer();
    await new Promise<void>((resolve) =>
      closed.listen(0, '127.0.0.1', resolve),
    );
    const port = (closed.address() as AddressInfo).port;
    await new Promise((resolve) => closed.close(resolve));

    const err = await rejection(
      new FileServiceClient(`http://127.0.0.1:${port}`, SECRET).readReady(
        FILE_ID,
      ),
    );

    expect(err).toBeInstanceOf(FileServiceUnavailableError);
    expect(err.message).toContain('(ECONNREFUSED)');
  });

  it('never puts the secret or the response text in an error', async () => {
    handle = (_req, res) => res.writeHead(401).end(`bad secret ${SECRET}`);

    const err = await rejection(client().readReady(FILE_ID));

    expect(err.message).not.toContain(SECRET);
    expect(err.message).not.toContain('bad secret');
  });
});
