import { createHash } from 'node:crypto';

/*
 * presentedContentHash of the milestone consents, built the way the
 * frontend builds it from what it shows (milestone-escrow design §3.2.3,
 * D84): SHA-256, lowercase hex, of the UTF-8 values joined by "|", ids in
 * lower case, weights with exactly three decimals, null as "". Written from
 * the design, not from contract-service's code, so a drift between the two
 * fails here.
 */

/**
 * The statement version in force per action (contract-service
 * application.yml, agricontract.consent): the frontend sends the version of
 * the sentence it showed, and any other is 409 CONSENT_STATEMENT_OUTDATED.
 */
export const STATEMENT_VERSIONS = {
  SIGN_CONTRACT: '1.0',
  DECLARE_SELLER_WEIGHT: '1.0',
  RECORD_BUYER_RECEIPT: '1.0',
  CONFIRM_CLEAN_RECEIPT: '1.0',
  FLAG_MILESTONE_ISSUE: '1.1',
  CONTEST_MILESTONE_FLAG: '1.0',
  CONCEDE_DISPUTE: '1.0',
  SETTLE_DISPUTE: '1.0',
};

const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

/** "9700.000": a weight in kg with three decimals. */
export const kg = (value) => Number(value).toFixed(3);

const id = (value) => (value == null ? '' : String(value).toLowerCase());

export const sellerWeighHash = (milestoneId, weight, evidenceFileId, certificateFileId = null) =>
  sha256(['DECLARE_SELLER_WEIGHT', id(milestoneId), kg(weight), id(evidenceFileId), id(certificateFileId)].join('|'));

export const buyerReceiptHash = (milestoneId, weight, evidenceFileId, certificateFileId = null) =>
  sha256(['RECORD_BUYER_RECEIPT', id(milestoneId), kg(weight), id(evidenceFileId), id(certificateFileId)].join('|'));

export const cleanReceiptHash = (milestoneId, declarationNumber, sellerWeight, buyerWeight) =>
  sha256(['CONFIRM_CLEAN_RECEIPT', id(milestoneId), String(declarationNumber), kg(sellerWeight), kg(buyerWeight)].join('|'));

export const flagHash = (milestoneId, declarationNumber, sellerWeight, buyerWeight, issueType) =>
  sha256(
    ['FLAG_MILESTONE_ISSUE', id(milestoneId), String(declarationNumber), kg(sellerWeight), kg(buyerWeight), issueType].join('|'),
  );

export const contestHash = (milestoneId, sellerWeight, buyerWeight, issueType) =>
  sha256(['CONTEST_MILESTONE_FLAG', id(milestoneId), kg(sellerWeight), kg(buyerWeight), issueType].join('|'));
