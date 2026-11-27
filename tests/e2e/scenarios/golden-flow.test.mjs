/*
 * #149: the October golden flow, end to end on the compose stack.
 *
 * A fresh COFFEE listing; the buyer offers a two-milestone contract; both
 * parties sign with a fresh login and the emailed code; the deposits lock and
 * the contract activates; milestone 1 is delivered and confirmed clean;
 * milestone 2 is delivered, flagged for quality and contested, a Level 1.5
 * inspector reports moisture in the penalty zone (PARTIALLY_CONFORMING, no
 * fault) and the Arbitrator approves the computed resolution. The contract
 * then settles, and the bank ledger, read straight from bank-db, holds no
 * money still locked for it.
 *
 * Then the same flow is checked for safety: every service restarted, the
 * contract and ledger are unchanged, and replaying a write with the same
 * Idempotency-Key moves nothing.
 */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';
import { test } from 'node:test';
import { config } from '../lib/config.mjs';
import { ledgerOf, remainingLock } from '../lib/bank.mjs';
import { contractOutboxCount, milestonesOf } from '../lib/db.mjs';
import { APPLICATION_SERVICES, restart } from '../lib/docker.mjs';
import * as f from '../lib/flow.mjs';
import { cannotActivateToday, runScenario } from '../lib/scenario.mjs';

const ZERO = { BUYER_DEPOSIT: '0.00', SELLER_DEPOSIT: '0.00', MILESTONE_PAYMENT: '0.00' };

test('golden flow: sign, activate, clean milestone, disputed milestone, settle with zero lock', { skip: cannotActivateToday() ?? false }, async () => {
  await runScenario('golden-flow', async ({ api, trace }) => {
    const t = await f.tokens();
    const listing = await f.coffeeListing(api, trace, t);
    const offered = await f.offer(api, trace, t, listing, [1000, 1000]);
    assert.equal(offered.status, 'OFFERED');

    await f.sign(api, trace, offered, 'BUYER');
    await f.sign(api, trace, offered, 'SELLER');
    const active = await f.contractReaches(api, trace, t, offered.contractId, 'ACTIVE', (c) =>
      milestonesOf(c).every((m) => m.fundingStatus === 'LOCKED' && m.status === 'IN_PROGRESS'),
    );
    const [clean, disputed] = milestonesOf(active);
    trace.remember('cleanMilestoneId', clean.milestoneId);
    trace.remember('disputedMilestoneId', disputed.milestoneId);

    // Milestone 1: delivered as committed, confirmed clean, settled.
    await f.deliver(api, trace, t, offered.contractId, clean.milestoneId, 1000, 1000);
    const confirmKey = randomUUID();
    await f.confirmClean(api, trace, t, offered.contractId, clean.milestoneId, confirmKey);
    await f.milestoneReaches(api, trace, t, offered.contractId, clean.milestoneId, 'SETTLED', (m) =>
      m.milestone?.status === 'SETTLED',
    );

    // Milestone 2: quality flagged and contested, inspected, decided without fault.
    await f.deliver(api, trace, t, offered.contractId, disputed.milestoneId, 1000, 1000);
    const contested = await f.flagAndContest(api, trace, t, offered.contractId, disputed.milestoneId, 'QUALITY');
    await f.inspect(api, trace, offered.contractId, disputed.milestoneId, {
      resultSchemaVersion: '1.0',
      measuredQuantityKg: 1000,
      acceptedQuantityKg: 1000,
      measurementStatus: 'COMPLETE',
      // Moisture 13.5 % against 12.5 % committed: past the 0.5 tolerance, at
      // the 1.0 penalty threshold, under the 2.0 reject one.
      actualQualityMetrics: {
        commodity: 'COFFEE',
        type: 'ROBUSTA',
        moisturePercent: 13.5,
        foreignMatterPercent: 0.5,
        blackBrokenBeansPercent: 5,
      },
      determinedAt: new Date().toISOString(),
    });
    const decided = await f.arbitrate(api, trace, t, offered.contractId, contested.breachCaseId);
    assert.equal(decided.computedResolution.qualityDisposition, 'PARTIALLY_CONFORMING');
    assert.equal(decided.computedResolution.finalBreachingRole ?? null, null);
    await f.milestoneReaches(api, trace, t, offered.contractId, disputed.milestoneId, 'SETTLED', (m) =>
      m.milestone?.status === 'SETTLED',
    );

    // Completion: SETTLED only once the bank confirmed every leg, and nothing left locked.
    const settled = await f.contractReaches(api, trace, t, offered.contractId, 'SETTLED');
    const ledger = ledgerOf(offered.contractId);
    trace.remember('ledger', ledger);
    assert.deepEqual(remainingLock(ledger), ZERO);

    // Restart: every service that holds state comes back to the same contract and ledger.
    await restart(trace, APPLICATION_SERVICES);
    const after = await f.contractReaches(api, trace, t, offered.contractId, 'SETTLED');
    assert.deepEqual(milestonesOf(after), milestonesOf(settled));
    assert.deepEqual(ledgerOf(offered.contractId), ledger);

    // Replay: the clean confirmation sent again with its Idempotency-Key settles nothing twice.
    trace.step('replay the clean confirmation');
    const outboxBefore = contractOutboxCount(offered.contractId);
    await f.confirmClean(api, trace, t, offered.contractId, clean.milestoneId, confirmKey);
    // Give the outbox poller and the escrow/bank consumers a few cycles: a replay that queued a new event
    // would show as a new outbox row or ledger row within them.
    await sleep(config.replayQuietMs);
    assert.equal(contractOutboxCount(offered.contractId), outboxBefore, 'the replay queued a new outbox event');
    assert.deepEqual(ledgerOf(offered.contractId), ledger);
  });
});
