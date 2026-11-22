import { Logger, Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { connect } from 'amqplib';
import {
  FILE_REPOSITORY,
  type FileRepository,
} from './application/ports/file.repository';
import {
  OBJECT_STORAGE,
  type ObjectStoragePort,
} from './application/ports/object-storage.port';
import {
  VIRUS_SCANNER,
  type VirusScannerPort,
} from './application/ports/virus-scanner.port';
import { ScanFileUseCase } from './application/scan-file.use-case';
import type { AppConfig } from './config/env.validation';
import {
  AMQP_CONNECT,
  AmqpService,
} from './infrastructure/messaging/amqp.service';
import { ScanWorker } from './infrastructure/messaging/scan-worker';
import { PersistenceModule } from './infrastructure/persistence/persistence.module';
import { ClamdScanner } from './infrastructure/scan/clamd-scanner';
import { StorageModule } from './infrastructure/storage/storage.module';

/** A hung clamd fails the scan (and so the attempt) after this much silence. */
const CLAMD_TIMEOUT_MS = 60_000;

/**
 * The async pipeline of file-service-phase2-design.md §4 for DIRECT_UPLOAD
 * (#131): outbox → agricontract.files → file-service.virus-scan → clamd →
 * READY / FAILED + file.ready / file.failed.
 */
@Module({
  imports: [PersistenceModule, StorageModule],
  providers: [
    { provide: AMQP_CONNECT, useValue: connect },
    AmqpService,
    {
      provide: VIRUS_SCANNER,
      inject: [ConfigService],
      useFactory: (config: ConfigService<AppConfig, true>) =>
        new ClamdScanner({
          ...config.get('clamav', { infer: true }),
          timeoutMs: CLAMD_TIMEOUT_MS,
        }),
    },
    {
      provide: ScanFileUseCase,
      inject: [OBJECT_STORAGE, FILE_REPOSITORY, VIRUS_SCANNER],
      useFactory: (
        storage: ObjectStoragePort,
        files: FileRepository,
        scanner: VirusScannerPort,
      ) =>
        new ScanFileUseCase(
          storage,
          files,
          scanner,
          new Logger(ScanFileUseCase.name),
        ),
    },
    ScanWorker,
  ],
})
export class VirusScanModule {}
