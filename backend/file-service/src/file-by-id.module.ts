import { Logger, Module } from '@nestjs/common';
import { ConfirmAttachedUseCase } from './application/confirm-attached.use-case';
import { DeleteFileUseCase } from './application/delete-file.use-case';
import {
  FILE_REPOSITORY,
  type FileRepository,
} from './application/ports/file.repository';
import {
  OBJECT_STORAGE,
  type ObjectStoragePort,
} from './application/ports/object-storage.port';
import { ReadFileUseCase } from './application/read-file.use-case';
import { FileByIdController } from './infrastructure/http/file-by-id.controller';
import { InternalSecretGuard } from './infrastructure/http/internal-secret.guard';
import { PersistenceModule } from './infrastructure/persistence/persistence.module';
import { StorageModule } from './infrastructure/storage/storage.module';

/**
 * GET, DELETE and confirm-attached on /internal/v1/files/{fileId}
 * (file-service-phase2-design.md §6, §7; #274).
 */
@Module({
  imports: [PersistenceModule, StorageModule],
  controllers: [FileByIdController],
  providers: [
    InternalSecretGuard,
    {
      provide: ReadFileUseCase,
      inject: [OBJECT_STORAGE, FILE_REPOSITORY],
      useFactory: (storage: ObjectStoragePort, files: FileRepository) =>
        new ReadFileUseCase(storage, files, new Logger(ReadFileUseCase.name)),
    },
    {
      provide: ConfirmAttachedUseCase,
      inject: [FILE_REPOSITORY],
      useFactory: (files: FileRepository) => new ConfirmAttachedUseCase(files),
    },
    {
      provide: DeleteFileUseCase,
      inject: [OBJECT_STORAGE, FILE_REPOSITORY],
      useFactory: (storage: ObjectStoragePort, files: FileRepository) =>
        new DeleteFileUseCase(storage, files),
    },
  ],
})
export class FileByIdModule {}
