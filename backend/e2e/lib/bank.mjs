import { UUID, query } from './db.mjs';

/*
 * The bank ledger of one contract, read straight from the compose bank-db:
 * bank-service publishes no host port, and the check must not trust the
 * services that moved the money. Remaining lock per fund =
 * Σ LOCK − Σ (RELEASE + REFUND + FORFEITURE + PENALTY + DAMAGES)
 * (bank design §7, "terminal ⇒ remaining lock = 0").
 */

/** Every ledger row of the contract, oldest first. */
export function ledgerOf(contractId) {
  if (!UUID.test(contractId)) throw new Error(`not a contract id: ${contractId}`);
  return query(
    process.env.E2E_BANK_DB_CONTAINER ?? 'bank-db',
    'bank_db',
    `SELECT entry_type, fund_type, amount, milestone_id, remedy_leg_id
       FROM ledger_entry WHERE contract_id = '${contractId}' ORDER BY created_at, entry_id`,
  ).map(([entryType, fundType, amount, milestoneId, remedyLegId]) => ({
    entryType,
    fundType,
    amount,
    milestoneId: milestoneId || null,
    remedyLegId: remedyLegId || null,
  }));
}

/** Remaining lock per fund, in whole cents to stay exact. */
export function remainingLock(entries) {
  const cents = { BUYER_DEPOSIT: 0n, SELLER_DEPOSIT: 0n, MILESTONE_PAYMENT: 0n };
  for (const e of entries) {
    const amount = BigInt(e.amount.replace('.', ''));
    cents[e.fundType] += e.entryType.startsWith('LOCK_') ? amount : -amount;
  }
  return Object.fromEntries(Object.entries(cents).map(([fund, c]) => [fund, formatCents(c)]));
}

const formatCents = (c) => {
  const sign = c < 0n ? '-' : '';
  const abs = c < 0n ? -c : c;
  return `${sign}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
};
