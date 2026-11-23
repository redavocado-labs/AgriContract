import type { TestingModuleBuilder } from '@nestjs/testing';
import { AmqpService } from '../../src/infrastructure/messaging/amqp.service';
import { DlqMonitor } from '../../src/infrastructure/messaging/dlq-monitor';
import { ScanWorker } from '../../src/infrastructure/messaging/scan-worker';

/**
 * AppModule for the HTTP int-specs: no broker connection, no scan worker
 * and no DLQ check, so nothing scans the test files behind the test's back
 * (it sets READY itself) and no RabbitMQ is needed. Uploads still write their scan
 * job to the outbox, which is what these tests may check.
 */
export function withoutBroker(
  builder: TestingModuleBuilder,
): TestingModuleBuilder {
  return builder
    .overrideProvider(AmqpService)
    .useValue({})
    .overrideProvider(ScanWorker)
    .useValue({})
    .overrideProvider(DlqMonitor)
    .useValue({});
}
