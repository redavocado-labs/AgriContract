import type { EventEnvelope } from '@agricontract/eventing';

export const INTAKE_REPOSITORY = Symbol('INTAKE_REPOSITORY');

export interface ProcessedMail {
  messageKey: string;
  sourceMailbox: string;
  /** Attachments stored as EMAIL_INTAKE files. */
  attachments: number;
  /** email_intake_failure.failure_reason of each problem found (design §8). */
  failures: string[];
  /** file.email_notice of a mail without attachment. */
  notice: EventEnvelope | null;
}

/** email_intake_message and email_intake_failure (V4), with the outbox. */
export interface IntakeRepository {
  isProcessed(messageKey: string): Promise<boolean>;
  /**
   * In one transaction: the message row, its failure rows and its notice
   * (outbox). Returns false and writes nothing when the message was already
   * recorded (another instance got there first).
   */
  recordProcessed(mail: ProcessedMail): Promise<boolean>;
  /**
   * One email_intake_failure row on its own, for a mail given up after its
   * technical attempts (design §4, D88). The mail is not recorded as
   * processed: flagged unread again by hand, it is taken in anew.
   */
  recordFailure(sourceMailbox: string, reason: string): Promise<void>;
}
