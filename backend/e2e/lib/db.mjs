import { execFileSync } from 'node:child_process';

/*
 * Read-only SQL on a compose MySQL container, for what the API does not show
 * yet or what the check must not take from the service it checks.
 */

const DB_PASSWORD = process.env.DB_PASSWORD ?? 'password';

export const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

/** Rows of `sql` as arrays of strings (NULL as ''); every interpolated value must be checked first. */
export function query(container, database, sql) {
  const out = execFileSync(
    'docker',
    ['exec', container, 'mysql', '-uroot', `-p${DB_PASSWORD}`, '-N', '-B', database, '-e', sql],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  );
  return out
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => line.split('\t').map((v) => (v === 'NULL' ? '' : v)));
}

/**
 * The milestones of a contract, in milestoneIndex order. ContractResponse
 * does not carry them on main yet (D99 adds them to the API, 24/11/2026),
 * so until it does they are read from contract-db; the API's list wins as
 * soon as it is filled.
 */
export function milestonesOf(contract) {
  if (Array.isArray(contract.milestones) && contract.milestones.length > 0) {
    return [...contract.milestones].sort((a, b) => a.milestoneIndex - b.milestoneIndex);
  }
  if (!UUID.test(contract.contractId)) throw new Error(`not a contract id: ${contract.contractId}`);
  return query(
    process.env.E2E_CONTRACT_DB_CONTAINER ?? 'contract-db',
    'contract_db',
    `SELECT milestone_id, milestone_index, status, funding_status FROM milestone
      WHERE contract_id = '${contract.contractId}' ORDER BY milestone_index`,
  ).map(([milestoneId, milestoneIndex, status, fundingStatus]) => ({
    milestoneId,
    milestoneIndex: Number(milestoneIndex),
    status,
    fundingStatus,
  }));
}

/** How many outbox events of contract-service mention the contract: a replayed request must not add one. */
export function contractOutboxCount(contractId) {
  if (!UUID.test(contractId)) throw new Error(`not a contract id: ${contractId}`);
  const [[count]] = query(
    process.env.E2E_CONTRACT_DB_CONTAINER ?? 'contract-db',
    'contract_db',
    `SELECT COUNT(*) FROM event_outbox WHERE envelope LIKE '%${contractId}%'`,
  );
  return Number(count);
}
