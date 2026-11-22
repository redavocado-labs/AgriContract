import type { Channel } from 'amqplib';
import { FILES_EXCHANGE, SCAN_REQUESTED } from '../../application/file-events';

/** file-service's own dead-letter exchange (direct: a DLQ binds the routing key it keeps). */
export const FILES_DLX = 'agricontract.files.dlx';

/** file-service-phase2-design.md §4: the one queue every file to scan goes through. */
export const VIRUS_SCAN_QUEUE = 'file-service.virus-scan';
/** §4: technical failures (storage, scanner down) after the retries; a person replays them. */
export const VIRUS_SCAN_DLQ = `${VIRUS_SCAN_QUEUE}.dlq`;

/**
 * Declares what file-service owns, idempotently, on every (re)connect:
 *
 * - topic `agricontract.files` (durable), where the outbox publishes every
 *   event with routing key = eventType: file.uploaded.direct (internal),
 *   file.ready and file.failed (contract-service binds
 *   contract-svc.file.ready / .failed here, with the same arguments);
 * - direct `agricontract.files.dlx`;
 * - `file-service.virus-scan` ← file.uploaded.direct, dead-lettering to the
 *   DLX, and its `.dlq` ← the DLX with the same key.
 *
 * No TTL or length limit on the queue (node-eventing README): retries are
 * in-process (3 attempts), then dead-lettering.
 */
export async function declareTopology(channel: Channel): Promise<void> {
  await channel.assertExchange(FILES_EXCHANGE, 'topic', {
    durable: true,
    autoDelete: false,
  });
  await channel.assertExchange(FILES_DLX, 'direct', {
    durable: true,
    autoDelete: false,
  });
  await channel.assertQueue(VIRUS_SCAN_QUEUE, {
    durable: true,
    arguments: { 'x-dead-letter-exchange': FILES_DLX },
  });
  await channel.assertQueue(VIRUS_SCAN_DLQ, { durable: true });
  await channel.bindQueue(VIRUS_SCAN_QUEUE, FILES_EXCHANGE, SCAN_REQUESTED);
  await channel.bindQueue(VIRUS_SCAN_DLQ, FILES_DLX, SCAN_REQUESTED);
}
