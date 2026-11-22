import { Logger, Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import type { Pool } from 'mysql2/promise';
import { EmailIntakeUseCase } from './application/email-intake.use-case';
import { StoreFileUseCase } from './application/store-file.use-case';
import type { AppConfig } from './config/env.validation';
import { ImapIntakeMailbox } from './infrastructure/intake/imap-intake-mailbox';
import {
  EMAIL_INTAKE,
  IntakePoller,
} from './infrastructure/intake/intake-poller';
import { readMail } from './infrastructure/intake/mime-reader';
import { MYSQL_POOL } from './infrastructure/persistence/mysql.provider';
import { IntakeMysqlRepository } from './infrastructure/persistence/intake.mysql-repository';
import { PersistenceModule } from './infrastructure/persistence/persistence.module';
import { StoreFileModule } from './store-file.module';

/**
 * The email intake bridge (file-service-phase2-design.md §4.1, #275): IMAP
 * poll of `intake@...` → EMAIL_INTAKE files through the store core and the
 * virus scan, or file.email_notice. Null (off) without INTAKE_IMAP_HOST.
 */
@Module({
  imports: [PersistenceModule, StoreFileModule],
  providers: [
    {
      provide: EMAIL_INTAKE,
      inject: [ConfigService, StoreFileUseCase, MYSQL_POOL],
      useFactory: (
        config: ConfigService<AppConfig, true>,
        store: StoreFileUseCase,
        pool: Pool,
      ) => {
        const intake = config.get('intake', { infer: true });
        if (!intake) return null;
        return new EmailIntakeUseCase(
          new ImapIntakeMailbox(intake),
          readMail,
          store,
          new IntakeMysqlRepository(pool),
          new Logger('EmailIntake'),
        );
      },
    },
    IntakePoller,
  ],
})
export class EmailIntakeModule {}
