import {
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Logger,
  Param,
  Post,
  Req,
  Res,
  UseFilters,
  UseGuards,
} from '@nestjs/common';
import { pipeline } from 'node:stream/promises';
import type { Request, Response } from 'express';
import { ConfirmAttachedUseCase } from '../../application/confirm-attached.use-case';
import { DeleteFileUseCase } from '../../application/delete-file.use-case';
import {
  StoredBytesMismatchError,
  failureCode,
} from '../../application/file-errors';
import { ReadFileUseCase } from '../../application/read-file.use-case';
import { ErrorEnvelopeFilter } from './error-envelope.filter';
import { parseFileId, requireIdempotencyKey } from './file-id.request';
import { InternalSecretGuard } from './internal-secret.guard';

/**
 * The routes on one file (supplementary-services-api.yaml getFile,
 * deleteFile, confirmAttached; file-service-phase2-design.md §6, §7). Like
 * every /internal route: X-Internal-Secret, never routed by the gateway.
 *
 * Who may call: any service holding the internal secret is a trusted service
 * (§6). file-service does no business check; the owning service did it
 * before it proxies the bytes to its end-user.
 */
@Controller('internal/v1/files/:fileId')
@UseGuards(InternalSecretGuard)
@UseFilters(ErrorEnvelopeFilter)
export class FileByIdController {
  private readonly logger = new Logger('InternalApi');

  constructor(
    private readonly readFile: ReadFileUseCase,
    private readonly confirmAttached: ConfirmAttachedUseCase,
    private readonly deleteFile: DeleteFileUseCase,
  ) {}

  /**
   * 200 with the exact stored bytes, Content-Type and Content-Length of the
   * file; 404 unless it is READY (inspection reads that as "not ready yet",
   * D62). The bytes stream from storage as they come. If they turn out not to
   * be the stored ones (VerifiedRead) or storage fails part way, the
   * connection is cut: the caller sees fewer bytes than Content-Length and an
   * error, never a clean end, so it cannot hash a wrong file as the right one.
   */
  @Get()
  async get(
    @Param('fileId') rawFileId: string,
    @Req() req: Request,
    @Res() res: Response,
  ): Promise<void> {
    const { file, body } = await this.readFile.execute(parseFileId(rawFileId));
    res.status(HttpStatus.OK);
    res.setHeader('Content-Type', file.contentType);
    res.setHeader('Content-Length', String(file.fileSize));
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      await pipeline(body, res);
    } catch (err) {
      // A mismatch is logged by ReadFileUseCase; a caller that went away is
      // its own business.
      if (err instanceof StoredBytesMismatchError || req.destroyed) return;
      this.logger.warn(
        `GET ${req.path} cut after the headers: storage read failed (${failureCode(err)})`,
      );
    }
  }

  /** 204, also on a repeat; 404 for an unknown or deleted file. */
  @Post('confirm-attached')
  @HttpCode(HttpStatus.NO_CONTENT)
  async confirm(
    @Param('fileId') rawFileId: string,
    @Req() req: Request,
  ): Promise<void> {
    const fileId = parseFileId(rawFileId);
    requireIdempotencyKey(req.headers);
    await this.confirmAttached.execute(fileId);
  }

  /**
   * 204 once the row is tombstoned and the blob removed, also on a repeat;
   * 409 LEGAL_HOLD_ACTIVE; 404 for an unknown file; 503 when storage did not
   * remove the blob (send it again: the repeat finishes the deletion).
   */
  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  async delete(
    @Param('fileId') rawFileId: string,
    @Req() req: Request,
  ): Promise<void> {
    const fileId = parseFileId(rawFileId);
    requireIdempotencyKey(req.headers);
    await this.deleteFile.execute(fileId);
  }
}
