import {
  NonRetryableError,
  parseEnvelope,
  type DeliveryOutcome,
  type MessageConsumer,
} from '@agricontract/eventing';
import { PRODUCER, SCAN_JOB_TYPES } from '../../application/file-events';
import type { ScanFileUseCase } from '../../application/scan-file.use-case';
import { VIRUS_SCAN_QUEUE } from './topology';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * VirusScanConsumer of file-service-phase2-design.md §4, on
 * `file-service.virus-scan`. Through @agricontract/eventing's consumeEvents:
 * a body off the envelope contract, or a job that is not a scan job of
 * file-service for one fileId, goes to the DLQ at once (it can never work);
 * any other failure is retried, then dead-lettered.
 *
 * No eventId dedupe table: the scan is idempotent on the file's status
 * (ScanFileUseCase), so a redelivered or replayed job publishes nothing new.
 */
export class VirusScanConsumer implements MessageConsumer {
  readonly consumerName = VIRUS_SCAN_QUEUE;

  constructor(private readonly scan: ScanFileUseCase) {}

  async handleMessage(body: Buffer): Promise<DeliveryOutcome> {
    const job = parseEnvelope(body);
    if (!SCAN_JOB_TYPES.includes(job.eventType) || job.producer !== PRODUCER) {
      throw new NonRetryableError(
        `Not a scan job: ${job.eventType} from ${job.producer}`,
      );
    }
    const fileId = job.payload.fileId;
    if (typeof fileId !== 'string' || !UUID.test(fileId)) {
      throw new NonRetryableError('Scan job without a fileId UUID');
    }
    await this.scan.execute(fileId.toLowerCase(), job);
    return { status: 'PROCESSED', envelope: job };
  }
}
