import { randomUUID } from 'node:crypto';
import { businessDaysAfter, todayIct } from './calendar.mjs';
import { config } from './config.mjs';
import {
  STATEMENT_VERSIONS,
  buyerReceiptHash,
  cleanReceiptHash,
  contestHash,
  flagHash,
  sellerWeighHash,
} from './consent.mjs';
import { freshLogin, passwordToken } from './keycloak.mjs';
import { SIGNING_SUBJECT, otpCode } from './mailhog.mjs';
import { waitFor } from './poll.mjs';

/*
 * The steps of the golden flow, through the gateway as the frontend calls
 * it, each waiting for the asynchronous effect it starts. Scenarios compose
 * them; every id they create goes to the trace.
 */

/** A 1x1 PNG: the smallest file file-service accepts and ClamAV passes. */
export const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

/** Password-grant tokens of the seeded users. */
export async function tokens() {
  const entries = await Promise.all(
    Object.entries(config.users).map(async ([role, u]) => [role, await passwordToken(u.username)]),
  );
  return Object.fromEntries(entries);
}

/** The shortest lead the terms allow (3 to 60 business days, D64). */
export const FUNDING_LEAD_DAYS = 3;

/**
 * A fresh COFFEE listing of seller1, with its own product and plot, so a run
 * never depends on stock left by an earlier one. Reuses the approved
 * COFFEE category when one exists, otherwise proposes and approves one.
 */
export async function coffeeListing(api, trace, t, { offeredQuantity = 5000 } = {}) {
  trace.step('coffee listing');
  const categories = (await api.get('/api/v1/categories', { token: t.seller })).body;
  const list = Array.isArray(categories) ? categories : (categories?.items ?? categories?.content ?? []);
  let category = list.find((c) => c.commodity === 'COFFEE' && c.status === 'APPROVED');
  if (!category) {
    const proposed = await api.post('/api/v1/categories', {
      token: t.seller,
      body: { name: `Ca phe e2e ${randomUUID().slice(0, 8)}` },
      expect: [200, 201],
    });
    await api.put(`/api/v1/admin/categories/${proposed.body.categoryId}/approve`, {
      token: t.operator,
      body: { commodity: 'COFFEE' },
      expect: [200, 204],
    });
    category = { categoryId: proposed.body.categoryId };
  }

  const product = await api.post('/api/v1/products', {
    token: t.seller,
    body: {
      name: 'Ca phe Robusta Dak Lak (e2e)',
      unit: 'kg',
      categoryId: category.categoryId,
      imageUrls: ['https://cdn.agricontract.test/products/e2e.jpg'],
      cropYear: 2026,
      harvestSeason: 'Nien vu 2026/27',
      certifications: [],
    },
    expect: [200, 201],
  });
  const plot = await api.post('/api/v1/plots', {
    token: t.seller,
    body: {
      householdLabel: 'Ho e2e',
      latitude: 12.666789,
      longitude: 108.050123,
      areaHectares: 1.5,
      accuracyMeters: 8.5,
      declaredProvince: 'DAK_LAK',
    },
    expect: [200, 201],
  });
  // A new plot is PENDING until its geo check ends (UNVERIFIED at once with tests/e2e/compose.e2e.yml); the same
  // Idempotency-Key makes the retries one creation.
  const listingKey = randomUUID();
  const listing = await waitFor(
    trace,
    `plot ${plot.body.registryEntryId} usable for a listing`,
    () => createListing(api, t, listingKey, product.body.productId, plot.body.registryEntryId, offeredQuantity),
    (res) => res.status !== 400,
    { everyMs: 2000 },
  );
  trace.remember('listingId', listing.body.listingId);
  return (await api.get(`/api/v1/listings/${listing.body.listingId}`, { token: t.buyer })).body;
}

async function createListing(api, t, idempotencyKey, productId, registryEntryId, offeredQuantity) {
  const res = await api.post('/api/v1/listings', {
    token: t.seller,
    idempotencyKey,
    expect: [200, 201, 400],
    body: {
      productId,
      productName: 'Ca phe Robusta Dak Lak (e2e)',
      unitOfMeasure: 'KG',
      offeredQuantity,
      minOrderQuantity: 100,
      askingUnitPrice: '95000.00',
      registryEntryIds: [registryEntryId],
      regionText: 'Buon Ma Thuot, Dak Lak',
      declaredQualitySpec: {
        commodity: 'COFFEE',
        type: 'ROBUSTA',
        moisturePercent: 12.5,
        foreignMatterPercent: 0.5,
        blackBrokenBeansPercent: 5.0,
      },
      packagingDeclaration: 'Bao day 60 kg',
    },
  });
  // Only a plot still waiting for its geo check is worth another try; any other 400 is a real failure.
  if (res.status === 400 && !(res.body?.code === 'PLOT_NOT_ELIGIBLE' && /PENDING/.test(res.body?.message ?? ''))) {
    throw new Error(`POST /api/v1/listings answered 400: ${JSON.stringify(res.body)}`);
  }
  return res;
}

/**
 * The signed terms of the golden flow: Robusta, inspection only when
 * disputed (Level 1.5), one milestone per entry of `quantities`, each
 * due exactly FUNDING_LEAD_DAYS Vietnam business days from today: the
 * funding date (delivery minus the lead, D64) is then today, so the second
 * signature is allowed (D67) and funding is requested at activation (D63).
 */
export function coffeeTerms(quantities) {
  return {
    agreedPrice: '95000.00',
    buyerConfirmWindowDays: 3,
    buyerDepositRate: 0.05,
    buyerPenaltyRate: 0.08,
    currency: 'VND',
    deliveryTerms: {
      deliveryResponsibility: 'SELLER_DELIVERS',
      riskTransferPoint: 'BUYER_RECEIPT',
      titleTransferPoint: 'FULL_PAYMENT',
      transportCostBearer: 'SELLER',
      unloadingCostBearer: 'BUYER',
    },
    disputeFloorReleaseRate: 0.5,
    forceMajeureReportWindowDays: 7,
    fundingLeadBusinessDays: FUNDING_LEAD_DAYS,
    inspectionTerms: {
      inspectionCostResponsibility: 'LOSER_PAYS',
      reportDueBusinessDays: 5,
      requiredTier: 'LEVEL_1_5',
      requirement: 'NONE_UNLESS_DISPUTED',
    },
    legalProfile: {
      contractType: 'COFFEE_SALE',
      damagesPolicy: 'COMMERCIAL_CUMULATIVE_IF_PROVEN',
      governingLaw: 'VN_COMMERCIAL_LAW',
      maxContractualPenaltyRate: 0.08,
    },
    level2InspectorOrg: null,
    milestoneSchedule: quantities.map((q, i) => ({
      committedQuantity: q,
      expectedDeliveryDate: businessDaysAfter(todayIct(), FUNDING_LEAD_DAYS),
      graceDays: 2,
      milestoneIndex: i + 1,
    })),
    priceAdjustmentRule: {
      acceptanceMode: 'BOTH_ACCEPT',
      cutoff: 'BEFORE_FUNDING_REQUEST',
      maximumAcceptedAdjustmentsPerMilestone: 1,
    },
    priceBand: { maximumUnitPrice: '100000.00', minimumUnitPrice: '90000.00' },
    qualityTerms: {
      commodity: 'COFFEE',
      committedSpec: {
        commodity: 'COFFEE',
        type: 'ROBUSTA',
        moisturePercent: 12.5,
        foreignMatterPercent: 0.5,
        blackBrokenBeansPercent: 5,
      },
      deviationPolicy: {
        moisturePercent: { direction: 'MAXIMUM', tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
        foreignMatterPercent: { direction: 'MAXIMUM', tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
        blackBrokenBeansPercent: { direction: 'MAXIMUM', tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
        type: { rejectOnMismatch: true },
      },
    },
    sellerDepositRate: 0.03,
    sellerPenaltyRate: 0.06,
    shortfallPenaltyThreshold: 0.05,
    toleranceRate: 0.05,
  };
}

export const getContract = async (api, t, contractId) =>
  (await api.get(`/api/v1/contracts/${contractId}`, { token: t.buyer })).body;

/** The buyer offers: the contract is OFFERED with the listing kg reserved. */
export async function offer(api, trace, t, listing, quantities) {
  trace.step('offer');
  const created = await api.post('/api/v1/contracts', {
    token: t.buyer,
    body: { listingId: listing.listingId, listingVersionToken: listing.listingVersionToken, terms: coffeeTerms(quantities) },
    expect: [201],
  });
  trace.remember('contractId', created.body.contractId);
  return created.body;
}

/**
 * One party signs: a fresh login (step-up), consent on the signed content
 * hash, the emailed code read back from MailHog, then verify.
 */
export async function sign(api, trace, contract, role) {
  trace.step(`sign as ${role}`);
  const user = role === 'BUYER' ? config.users.buyer : config.users.seller;
  const token = await freshLogin(user.username);
  const since = new Date(Date.now() - 1000);
  const initiated = await api.post(`/api/v1/contracts/${contract.contractId}/sign/initiate`, {
    token,
    body: {
      signerRole: role,
      consentStatementVersion: STATEMENT_VERSIONS.SIGN_CONTRACT,
      presentedContentHash: contract.signedContentHash,
    },
  });
  const code = await otpCode(trace, user.email, SIGNING_SUBJECT, since);
  await api.post(`/api/v1/contracts/${contract.contractId}/sign/verify`, {
    token,
    body: { otpId: initiated.body.otpId, otpCode: code },
    expect: [204],
  });
}

/** Waits for the contract to reach `status` (and optionally more). */
export const contractReaches = (api, trace, t, contractId, status, extra = () => true) =>
  waitFor(
    trace,
    `contract ${contractId} ${status}`,
    () => getContract(api, t, contractId),
    (c) => c.status === status && extra(c),
  );

export const getMilestone = async (api, t, contractId, milestoneId) =>
  (await api.get(`/api/v1/contracts/${contractId}/milestones/${milestoneId}`, { token: t.buyer })).body;

export const milestoneReaches = (api, trace, t, contractId, milestoneId, what, done) =>
  waitFor(trace, `milestone ${milestoneId} ${what}`, () => getMilestone(api, t, contractId, milestoneId), done);

/** Uploads a weighing photo as `party` and waits for file-service to mark it READY. */
export async function evidence(api, trace, t, contractId, milestoneId, party) {
  const uploaded = await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/evidence`, {
    token: t[party],
    body: PNG,
    headers: { 'content-type': 'image/png' },
    expect: [202],
  });
  const fileId = uploaded.body.fileId;
  await milestoneReaches(api, trace, t, contractId, milestoneId, `evidence ${fileId} READY`, (m) =>
    (m.evidence ?? []).some((e) => e.fileId === fileId && e.status === 'READY'),
  );
  return fileId;
}

/** Seller declares, buyer records the receipt: the milestone is BUYER_RECEIVED. */
export async function deliver(api, trace, t, contractId, milestoneId, sellerKg, buyerKg) {
  trace.step(`deliver milestone ${milestoneId}`);
  const sellerFile = await evidence(api, trace, t, contractId, milestoneId, 'seller');
  await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/weigh`, {
    token: t.seller,
    body: {
      sellerDeclaredWeight: sellerKg,
      sellerEvidenceFileId: sellerFile,
      consent: {
        statementVersion: STATEMENT_VERSIONS.DECLARE_SELLER_WEIGHT,
        presentedContentHash: sellerWeighHash(milestoneId, sellerKg, sellerFile),
      },
    },
    expect: [204],
  });
  const buyerFile = await evidence(api, trace, t, contractId, milestoneId, 'buyer');
  await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/receive`, {
    token: t.buyer,
    body: {
      buyerReceivedWeight: buyerKg,
      buyerEvidenceFileId: buyerFile,
      consent: {
        statementVersion: STATEMENT_VERSIONS.RECORD_BUYER_RECEIPT,
        presentedContentHash: buyerReceiptHash(milestoneId, buyerKg, buyerFile),
      },
    },
    expect: [204],
  });
  return milestoneReaches(api, trace, t, contractId, milestoneId, 'BUYER_RECEIVED', (m) =>
    m.milestone?.status === 'BUYER_RECEIVED',
  );
}

/** The buyer flags an issue and the seller contests it: the milestone is CONTESTED with a BreachCase. */
export async function flagAndContest(api, trace, t, contractId, milestoneId, issueType) {
  trace.step(`flag ${issueType} on milestone ${milestoneId}, seller contests`);
  const m = await getMilestone(api, t, contractId, milestoneId);
  const seller = m.sellerDeclaration.sellerDeclaredWeight;
  const buyer = m.buyerReceipt.buyerReceivedWeight;
  await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/flag`, {
    token: t.buyer,
    body: {
      issueType,
      consent: {
        statementVersion: STATEMENT_VERSIONS.FLAG_MILESTONE_ISSUE,
        presentedContentHash: flagHash(milestoneId, m.sellerDeclaration.declarationNumber, seller, buyer, issueType),
      },
    },
    expect: [204],
  });
  await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/respond`, {
    token: t.seller,
    body: {
      statementVersion: STATEMENT_VERSIONS.CONTEST_MILESTONE_FLAG,
      presentedContentHash: contestHash(milestoneId, seller, buyer, issueType),
    },
    expect: [204],
  });
  const contested = await milestoneReaches(api, trace, t, contractId, milestoneId, 'CONTESTED with a breach case', (d) =>
    d.milestone?.status === 'CONTESTED' && d.breachCaseId,
  );
  trace.remember('breachCaseId', contested.breachCaseId);
  return contested;
}

/**
 * The assigned Level 1.5 inspector (inspector1, the only one seeded) uploads
 * the report file and submits the normalized result with a fresh login.
 * Waits for the assignment first: inspection-service creates it when it
 * consumes inspection.level1_5_requested.
 */
export async function inspect(api, trace, contractId, milestoneId, result) {
  trace.step(`Level 1.5 report on milestone ${milestoneId}`);
  const token = await freshLogin(config.users.inspector.username);
  const file = await waitFor(
    trace,
    `an inspection assignment for milestone ${milestoneId}`,
    async () => {
      const res = await api.post(`/api/v1/inspections/${contractId}/milestones/${milestoneId}/report-files`, {
        token,
        body: PNG,
        headers: { 'content-type': 'image/png' },
        expect: [200, 201, 202, 403],
      });
      return res.status === 403 ? null : res.body;
    },
    (b) => b !== null,
    { everyMs: 2000 },
  );
  trace.remember('reportFileId', file.fileId);
  await waitFor(
    trace,
    `report ${file.fileId} accepted`,
    async () =>
      (
        await api.post(`/api/v1/inspections/${contractId}/milestones/${milestoneId}/reports`, {
          token,
          body: { reportFileId: file.fileId, normalizedResult: result },
          idempotencyKey: `e2e-report-${file.fileId}`,
          expect: [201, 409],
        })
      ).status,
    (status) => status === 201,
    { everyMs: 2000 },
  );
}

/** The Arbitrator takes the case and approves the computed resolution (D81). */
export async function arbitrate(api, trace, t, contractId, breachCaseId) {
  trace.step(`arbitrate breach case ${breachCaseId}`);
  const awaiting = await waitFor(
    trace,
    `breach case ${breachCaseId} with a computed resolution`,
    async () =>
      (await api.get(`/api/v1/contracts/${contractId}/breach-cases/${breachCaseId}`, { token: t.arbitrator })).body,
    (c) => c.computedResolution != null,
  );
  if (awaiting.status === 'REPORTED') {
    await api.post(`/api/v1/contracts/${contractId}/breach-cases/${breachCaseId}/review`, {
      token: t.arbitrator,
      expect: [204],
    });
  }
  await api.post(`/api/v1/contracts/${contractId}/breach-cases/${breachCaseId}/resolve`, {
    token: t.arbitrator,
    body: {
      finalBreachingRole: awaiting.computedResolution.finalBreachingRole ?? null,
      decisionSource: 'INSPECTOR_L1_5',
      evidenceRefs: [],
    },
    expect: [204],
  });
  return awaiting;
}

/** The buyer confirms a clean receipt, from what the milestone shows. */
export async function confirmClean(api, trace, t, contractId, milestoneId, idempotencyKey) {
  trace.step(`confirm milestone ${milestoneId}`);
  const m = await getMilestone(api, t, contractId, milestoneId);
  await api.post(`/api/v1/contracts/${contractId}/milestones/${milestoneId}/confirm`, {
    token: t.buyer,
    idempotencyKey,
    body: {
      statementVersion: STATEMENT_VERSIONS.CONFIRM_CLEAN_RECEIPT,
      presentedContentHash: cleanReceiptHash(
        milestoneId,
        m.sellerDeclaration.declarationNumber,
        m.sellerDeclaration.sellerDeclaredWeight,
        m.buyerReceipt.buyerReceivedWeight,
      ),
    },
    expect: [204],
  });
}
