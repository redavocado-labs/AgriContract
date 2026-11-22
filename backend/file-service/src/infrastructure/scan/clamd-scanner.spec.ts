import {
  createServer,
  type AddressInfo,
  type Server,
  type Socket,
} from 'node:net';
import { Readable } from 'node:stream';
import { ClamdError, ClamdScanner, parseReply } from './clamd-scanner';

/**
 * A clamd that speaks INSTREAM: it records what it received, decoded into
 * the command, the chunks and whether the zero-length end came, then
 * answers with `reply(bytes)` (null = say nothing).
 */
class FakeClamd {
  server!: Server;
  port = 0;
  command = '';
  chunks: Buffer[] = [];
  ended = false;
  reply: (bytes: Buffer) => string | null = () => 'stream: OK';
  /** Answer as soon as this many content bytes arrived, without waiting for the end. */
  answerAfterBytes: number | null = null;

  async start(): Promise<void> {
    this.server = createServer((socket) => this.serve(socket));
    await new Promise<void>((r) => this.server.listen(0, '127.0.0.1', r));
    this.port = (this.server.address() as AddressInfo).port;
  }

  stop(): Promise<void> {
    return new Promise((r) => this.server.close(() => r()));
  }

  private serve(socket: Socket): void {
    let buffer = Buffer.alloc(0);
    let answered = false;
    const answer = () => {
      if (answered) return;
      answered = true;
      const text = this.reply(Buffer.concat(this.chunks));
      if (text !== null) socket.end(`${text}\0`);
    };
    socket.on('error', () => undefined);
    socket.on('data', (data: Buffer) => {
      buffer = Buffer.concat([buffer, data]);
      if (!this.command) {
        const nul = buffer.indexOf(0);
        if (nul < 0) return;
        this.command = buffer.subarray(0, nul).toString('latin1');
        buffer = buffer.subarray(nul + 1);
      }
      while (buffer.length >= 4) {
        const length = buffer.readUInt32BE(0);
        if (length === 0) {
          this.ended = true;
          answer();
          return;
        }
        if (buffer.length < 4 + length) return;
        this.chunks.push(buffer.subarray(4, 4 + length));
        buffer = buffer.subarray(4 + length);
        const received = this.chunks.reduce((n, c) => n + c.length, 0);
        if (
          this.answerAfterBytes !== null &&
          received >= this.answerAfterBytes
        ) {
          answer();
          return;
        }
      }
    });
  }
}

describe('ClamdScanner (INSTREAM)', () => {
  let clamd: FakeClamd;
  let scanner: ClamdScanner;
  const bytes = Buffer.alloc(300 * 1024, 7);

  beforeEach(async () => {
    clamd = new FakeClamd();
    await clamd.start();
    scanner = new ClamdScanner({
      host: '127.0.0.1',
      port: clamd.port,
      timeoutMs: 2_000,
    });
  });

  afterEach(() => clamd.stop());

  it('sends zINSTREAM, the bytes in length-prefixed chunks and the end; OK is clean', async () => {
    await expect(scanner.scan(Readable.from([bytes]))).resolves.toEqual({
      clean: true,
    });

    expect(clamd.command).toBe('zINSTREAM');
    expect(Buffer.concat(clamd.chunks)).toEqual(bytes);
    expect(clamd.ended).toBe(true);
  });

  it('FOUND: infected, with the signature', async () => {
    clamd.reply = () => 'stream: Eicar-Test-Signature FOUND';

    await expect(scanner.scan(Readable.from([bytes]))).resolves.toEqual({
      clean: false,
      signature: 'Eicar-Test-Signature',
    });
  });

  it('an answer before the end stands, and the rest is not sent', async () => {
    clamd.answerAfterBytes = 64 * 1024;
    clamd.reply = () => 'stream: Win.Test.Early FOUND';
    const chunks = Array.from({ length: 50 }, () => Buffer.alloc(64 * 1024, 1));

    await expect(scanner.scan(Readable.from(chunks))).resolves.toEqual({
      clean: false,
      signature: 'Win.Test.Early',
    });
    expect(clamd.ended).toBe(false);
  });

  it('an ERROR reply rejects with CLAMD_ERROR', async () => {
    clamd.reply = () => 'INSTREAM size limit exceeded. ERROR';

    await expect(scanner.scan(Readable.from([bytes]))).rejects.toMatchObject({
      code: 'CLAMD_ERROR',
    });
  });

  it('a body that fails never sends the end, and the scan rejects with its error', async () => {
    const broken = new Error('stored bytes do not match');
    const body = new Readable({
      read() {
        this.push(Buffer.alloc(1024, 2));
        this.destroy(broken);
      },
    });

    await expect(scanner.scan(body)).rejects.toBe(broken);
    // The server may still be reading; give it a moment.
    await new Promise((r) => setTimeout(r, 50));
    expect(clamd.ended).toBe(false);
  });

  it('a clamd that never answers: CLAMD_TIMEOUT', async () => {
    clamd.reply = () => null;
    scanner = new ClamdScanner({
      host: '127.0.0.1',
      port: clamd.port,
      timeoutMs: 200,
    });

    await expect(scanner.scan(Readable.from([bytes]))).rejects.toBeInstanceOf(
      ClamdError,
    );
  });

  it('clamd down: rejects with the connection error', async () => {
    // A port that was just free: nothing listens there.
    const probe = createServer();
    await new Promise<void>((r) => probe.listen(0, '127.0.0.1', r));
    const { port } = probe.address() as AddressInfo;
    await new Promise((r) => probe.close(r));
    const down = new ClamdScanner({
      host: '127.0.0.1',
      port,
      timeoutMs: 1_000,
    });

    await expect(down.scan(Readable.from([bytes]))).rejects.toMatchObject({
      code: 'ECONNREFUSED',
    });
  });
});

describe('parseReply', () => {
  it.each([
    ['stream: OK', { clean: true }],
    ['stream: OK\n', { clean: true }],
    [
      'stream: Win.Test.EICAR_HDB-1 FOUND',
      { clean: false, signature: 'Win.Test.EICAR_HDB-1' },
    ],
  ])('%j', (raw, verdict) => {
    expect(parseReply(raw)).toEqual(verdict);
  });

  it('anything else is a ClamdError', () => {
    expect(() => parseReply('UNKNOWN COMMAND')).toThrow(ClamdError);
  });
});
