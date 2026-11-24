import type { NotificationRecipient } from './notification-recipient';

/**
 * `notification.contract_document_ready_requested` (notification-commands.yaml
 * `contractDocumentReadyRequested`), published by contract-service once the
 * final signed contract PDF is READY in file-service. The canonical Phase 2
 * final-contract email (design §3.3): the PDF is read from file-service by the
 * trusted `fileId` when the email is sent and attached, and the body gives
 * both hashes and the template that rendered it.
 *
 * Deduplicated by `documentId`, not by the envelope's eventId (design §5,
 * `x-deduplication-key`): a second command for the same document never sends
 * a second final PDF to the same recipient.
 */
export interface ContractDocumentReadyRequestedCommand {
  eventId: string;
  documentId: string;
  contractId: string;
  recipients: NotificationRecipient[];
  /** file-service id of the PDF; read with the internal secret at send time. */
  fileId: string;
  /** SHA-256 of the signed canonical terms the PDF was rendered from. */
  sourceContentHash: string;
  /** SHA-256 of the PDF bytes: the attachment must hash to exactly this. */
  documentFileHash: string;
  templateCode: string;
  templateVersion: string;
  generatedAt: string;
}
