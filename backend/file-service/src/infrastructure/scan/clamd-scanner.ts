import { connect, type Socket } from 'node:net';
import type { Readable } from 'node:stream';
import type {
  ScanVerdict,
  VirusScannerPort,
} from '../../application/ports/virus-scanner.port';

/** clamd gave no verdict; `code` is safe to log (failureCode). */
export class ClamdError extends Error {
  constructor(
    readonly code: 'CLAMD_TIMEOUT' | 'CLAMD_NO_REPLY' | 'CLAMD_ERROR',
    detail?: string,
  ) {
    super(detail ? `clamd: ${detail}` : `clamd: ${code}`);
    this.name = ClamdError.name;
  }
}

export interface ClamdOptions {
  host: string;
  port: number;
  /** Socket inactivity limit: a hung clamd fails the scan, never stalls it. */
  timeoutMs: number;
}

/** INSTREAM chunks are a 4-byte big-endian length then the bytes; length 0 ends the stream. */
const END_OF_STREAM = Buffer.alloc(4);
const COMMAND = Buffer.from('zINSTREAM\0', 'latin1');

/**
 * VirusScannerPort on clamd's INSTREAM command over TCP (compose `clamav`).
 * The bytes go to clamd chunk by chunk as they come from storage, under
 * socket backpressure, so a 10 MiB file is never held in memory. The `z`
 * form makes clamd end its reply with a NUL:
 *
 * - `stream: OK` → clean;
 * - `stream: <signature> FOUND` → infected (EICAR: `Eicar-Test-Signature`);
 * - anything else (`INSTREAM size limit exceeded. ERROR`, …) → ClamdError.
 *
 * The end-of-stream marker is sent only once the body has ended without an
 * error, so "clean" always covers every byte; a body that fails part way
 * rejects the scan with the body's own error. clamd may answer early (a
 * signature found part way, a size limit): its answer then stands and the
 * rest of the body is not sent.
 */
export class ClamdScanner implements VirusScannerPort {
  constructor(private readonly options: ClamdOptions) {}

  scan(body: Readable): Promise<ScanVerdict> {
    const socket = connect({
      host: this.options.host,
      port: this.options.port,
    });
    socket.setTimeout(this.options.timeoutMs);

    return new Promise<ScanVerdict>((resolve, reject) => {
      let settled = false;
      let reply = Buffer.alloc(0);
      const finish = (err: Error | null, verdict?: ScanVerdict) => {
        if (settled) return;
        settled = true;
        socket.destroy();
        if (err) reject(err);
        else resolve(verdict!);
      };
      const answer = (text: string) => {
        try {
          finish(null, parseReply(text));
        } catch (err) {
          finish(err as Error);
        }
      };

      socket.on('timeout', () => finish(new ClamdError('CLAMD_TIMEOUT')));
      socket.on('error', (err) => finish(err));
      socket.on('data', (data: Buffer) => {
        reply = Buffer.concat([reply, data]);
        const end = reply.indexOf(0);
        if (end >= 0) answer(reply.subarray(0, end).toString('latin1'));
      });
      socket.on('end', () => {
        if (reply.length > 0) answer(reply.toString('latin1'));
        else finish(new ClamdError('CLAMD_NO_REPLY'));
      });
      socket.on('close', () => finish(new ClamdError('CLAMD_NO_REPLY')));
      socket.once('connect', () => {
        sendStream(socket, body, () => settled).catch((err: Error) =>
          finish(err),
        );
      });
    });
  }
}

async function sendStream(
  socket: Socket,
  body: Readable,
  stopped: () => boolean,
): Promise<void> {
  await write(socket, COMMAND);
  for await (const chunk of body as AsyncIterable<Buffer>) {
    if (stopped()) return;
    const header = Buffer.alloc(4);
    header.writeUInt32BE(chunk.length);
    await write(socket, Buffer.concat([header, chunk]));
  }
  if (!stopped()) await write(socket, END_OF_STREAM);
}

/** Writes and waits for the socket to drain when it asks to (backpressure). */
function write(socket: Socket, data: Buffer): Promise<void> {
  if (socket.destroyed) return Promise.resolve();
  if (socket.write(data)) return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => {
      socket.off('drain', done);
      socket.off('close', done);
      resolve();
    };
    socket.on('drain', done);
    socket.on('close', done);
  });
}

const FOUND = /^stream: (.+) FOUND$/;

export function parseReply(raw: string): ScanVerdict {
  const text = raw.trim();
  if (text === 'stream: OK') return { clean: true };
  const found = FOUND.exec(text);
  if (found) return { clean: false, signature: found[1] };
  // clamd's error texts name limits and paths of its own, never file content.
  throw new ClamdError('CLAMD_ERROR', text.slice(0, 200));
}
