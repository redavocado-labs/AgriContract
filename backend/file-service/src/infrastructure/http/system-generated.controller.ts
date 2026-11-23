import {
  Controller,
  HttpCode,
  HttpStatus,
  Post,
  Req,
  Res,
  UseFilters,
  UseGuards,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { SYSTEM_GENERATED_POLICY } from '../../application/ingest-policy';
import { StoreFileUseCase } from '../../application/store-file.use-case';
import { ErrorEnvelopeFilter } from './error-envelope.filter';
import { InternalSecretGuard } from './internal-secret.guard';
import { parseStoreSystemGeneratedRequest } from './system-generated.request';
import { discardUnreadBody } from './unread-body';

/** StoredSystemFile of supplementary-services-api.yaml. */
export interface StoredSystemFile {
  fileId: string;
  status: 'READY';
  /** SHA-256 of the stored bytes, for the caller to keep next to its own document hash. */
  storageHash: string;
  fileSize: number;
  contentType: 'application/pdf';
}

/**
 * POST /internal/v1/files/system-generated (supplementary-services-api.yaml
 * storeSystemGenerated, design §3 `store()`, D88): a service stores a PDF it
 * generated itself, e.g. the final contract PDF or an EUDR report. Never for
 * a file that came from an end-user: those go through storeOnBehalfOf and the
 * virus scan, and keeping that line is the calling service's job (§2.1).
 *
 * - 201 StoredSystemFile: stored and READY at once (no scan, no
 *   file.ready/file.failed), or a repeat of the same (X-Service-Id,
 *   Idempotency-Key) answering the earlier file without storing again;
 * - 400 VALIDATION_ERROR (headers, a type other than application/pdf, a body
 *   that does not match Content-Length), 401 UNAUTHORIZED, 413 FILE_TOO_LARGE;
 * - 503 FILE_STORAGE_UNAVAILABLE / FILE_METADATA_UNAVAILABLE: nothing kept,
 *   safe to retry with the same Idempotency-Key.
 *
 * Not routed by the API gateway (/internal/** is never routed).
 */
@Controller('internal/v1/files/system-generated')
@UseGuards(InternalSecretGuard)
@UseFilters(ErrorEnvelopeFilter)
export class SystemGeneratedController {
  constructor(private readonly storeFile: StoreFileUseCase) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async storeSystemGenerated(
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ): Promise<StoredSystemFile> {
    const request = parseStoreSystemGeneratedRequest(req.headers);
    try {
      const file = await this.storeFile.execute(
        {
          body: req,
          contentType: request.contentType,
          contentLength: request.contentLength,
          uploadedBy: request.serviceId,
          idempotencyKey: request.idempotencyKey,
        },
        SYSTEM_GENERATED_POLICY,
      );
      // The policy takes application/pdf only and never scans, and a repeat
      // finds only a file of this entrypoint: no other one stores under a
      // service id.
      return {
        fileId: file.fileId,
        status: 'READY',
        storageHash: file.storageHash,
        fileSize: file.fileSize,
        contentType: 'application/pdf',
      };
    } finally {
      // A repeat, or a store that failed part way, leaves the body unread.
      await discardUnreadBody(req, res);
    }
  }
}
