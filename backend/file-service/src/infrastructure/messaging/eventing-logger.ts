import { Logger as NestLogger } from '@nestjs/common';
import type { Logger } from '@agricontract/eventing';

/** @agricontract/eventing's Logger on a Nest logger: the message, then its fields as JSON. */
export function eventingLogger(context: string): Logger {
  const nest = new NestLogger(context);
  const line = (message: string, meta?: Record<string, unknown>) =>
    meta && Object.keys(meta).length > 0
      ? `${message} ${JSON.stringify(meta)}`
      : message;
  return {
    debug: (m, meta) => nest.debug(line(m, meta)),
    info: (m, meta) => nest.log(line(m, meta)),
    warn: (m, meta) => nest.warn(line(m, meta)),
    error: (m, meta) => nest.error(line(m, meta)),
  };
}
