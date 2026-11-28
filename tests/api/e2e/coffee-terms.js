// Source: golden-flow-api.yaml, ContractTermsInput and its component schemas.
function coffeeTerms(deliveryDate, price = '95000.00') {
  const maximum = { direction: 'MAXIMUM', tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 };
  return {
    currency: 'VND',
    agreedPrice: price,
    milestoneSchedule: [{ milestoneIndex: 1, committedQuantity: 1000, expectedDeliveryDate: deliveryDate, graceDays: 2 }],
    qualityTerms: {
      commodity: 'COFFEE',
      committedSpec: {
        commodity: 'COFFEE', type: 'ROBUSTA',
        moisturePercent: 12.5, foreignMatterPercent: 0.5, blackBrokenBeansPercent: 5,
      },
      deviationPolicy: {
        type: { rejectOnMismatch: true },
        moisturePercent: maximum, foreignMatterPercent: maximum, blackBrokenBeansPercent: maximum,
      },
    },
    deliveryTerms: {
      deliveryResponsibility: 'SELLER_DELIVERS',
      riskTransferPoint: 'BUYER_RECEIPT',
      titleTransferPoint: 'FULL_PAYMENT',
      transportCostBearer: 'SELLER',
      unloadingCostBearer: 'BUYER',
    },
    inspectionTerms: {
      requirement: 'NONE_UNLESS_DISPUTED',
      requiredTier: 'LEVEL_1_5',
      inspectionCostResponsibility: 'LOSER_PAYS',
      reportDueBusinessDays: 5,
    },
    priceBand: { minimumUnitPrice: '90000.00', maximumUnitPrice: '100000.00' },
    priceAdjustmentRule: {
      acceptanceMode: 'BOTH_ACCEPT', cutoff: 'BEFORE_FUNDING_REQUEST', maximumAcceptedAdjustmentsPerMilestone: 1,
    },
    legalProfile: {
      contractType: 'COFFEE_SALE', governingLaw: 'VN_COMMERCIAL_LAW',
      maxContractualPenaltyRate: 0.08, damagesPolicy: 'COMMERCIAL_CUMULATIVE_IF_PROVEN',
    },
    toleranceRate: 0.05, shortfallPenaltyThreshold: 0.05,
    buyerPenaltyRate: 0.08, sellerPenaltyRate: 0.06,
    forceMajeureReportWindowDays: 7, buyerConfirmWindowDays: 3, fundingLeadBusinessDays: 3,
    buyerDepositRate: 0.05, sellerDepositRate: 0.03, disputeFloorReleaseRate: 0.5,
  };
}

function deliveryDate(holidays) {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format();
  const date = new Date(today + 'T00:00:00Z');
  const closed = new Set(holidays.split(','));
  const businessDay = () => ![0, 6].includes(date.getUTCDay()) && !closed.has(date.toISOString().slice(0, 10));
  for (let remaining = 3; remaining > 0;) {
    date.setUTCDate(date.getUTCDate() + 1);
    if (businessDay()) remaining--;
  }
  return date.toISOString().slice(0, 10);
}

module.exports = { coffeeTerms, deliveryDate };
