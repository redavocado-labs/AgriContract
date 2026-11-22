import { Logger, Module } from '@nestjs/common';
import { DeleteFileUseCase } from './application/delete-file.use-case';
import {
  FileLifecycleUseCase,
  ORPHAN_AFTER_MS,
  STRAY_BLOB_GRACE_MS,
} from './application/file-lifecycle.use-case';
import {
  FILE_REPOSITORY,
  type FileRepository,
} from './application/ports/file.repository';
import {
  OBJECT_STORAGE,
  type ObjectStoragePort,
} from './application/ports/object-storage.port';
import { LifecycleScheduler } from './infrastructure/lifecycle/lifecycle-scheduler';
import { PersistenceModule } from './infrastructure/persistence/persistence.module';
import { StorageModule } from './infrastructure/storage/storage.module';

/** Rows per kind per run. */
const LIFECYCLE_BATCH = 100;

/** The lifecycle job of file-service-phase2-design.md §7 (#276). */
@Module({
  imports: [PersistenceModule, StorageModule],
  providers: [
    {
      provide: FileLifecycleUseCase,
      inject: [FILE_REPOSITORY, OBJECT_STORAGE],
      useFactory: (files: FileRepository, storage: ObjectStoragePort) =>
        new FileLifecycleUseCase(
          files,
          storage,
          new DeleteFileUseCase(storage, files),
          new Logger('FileLifecycle'),
          {
            batchSize: LIFECYCLE_BATCH,
            orphanAfterMs: ORPHAN_AFTER_MS,
            strayBlobGraceMs: STRAY_BLOB_GRACE_MS,
            now: () => new Date(),
          },
        ),
    },
    LifecycleScheduler,
  ],
})
export class FileLifecycleModule {}
