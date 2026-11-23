import { Module } from '@nestjs/common';
import { InternalSecretGuard } from './infrastructure/http/internal-secret.guard';
import { SystemGeneratedController } from './infrastructure/http/system-generated.controller';
import { StoreFileModule } from './store-file.module';

/** The SYSTEM_GENERATED entrypoint: POST /internal/v1/files/system-generated (design §3 `store()`, D88). */
@Module({
  imports: [StoreFileModule],
  controllers: [SystemGeneratedController],
  providers: [InternalSecretGuard],
})
export class StoreSystemGeneratedModule {}
