const { createHash } = require('crypto');
const { fixture, gateway, poll, request, token, variable } = require('./helpers');

const activation = 'e2e/bf03-signing-activation/happy';

async function fundedContract(bru, reuse) {
  const contract = reuse && variable(bru, 'shared_contract_id');
  if (contract) {
    await Promise.all([token(bru, 'buyer'), token(bru, 'seller')]);
    bru.setVar('contract_id', contract);
  } else {
    await fixture(bru, activation, [
      ['010-create-offer', 201], ['020-reservation', 200], ['030-edit-terms', 200],
      ['040-initiate-buyer', 200], ['050-sign-buyer', 204], ['060-initiate-seller', 200],
      ['070-sign-seller', 204], ['080-active', 200], ['090-deposit-ledger', 200],
    ]);
  }
  const result = await poll(() => gateway(bru, '/api/v1/contracts/' + bru.getVar('contract_id'), 'buyer'),
    ({ status, data }) => status === 200 && data.milestones?.some((item) =>
      item.milestoneIndex === 1 && item.status === 'IN_PROGRESS' && item.fundingStatus === 'LOCKED'),
    'funded milestone');
  const milestone = result.data.milestones.find((item) => item.milestoneIndex === 1);
  bru.setVar('milestone_id', milestone.milestoneId);
  bru.setVar('batch_amount', milestone.batchAmount);
}

function path(bru) {
  return '/api/v1/contracts/' + bru.getVar('contract_id') + '/milestones/' + bru.getVar('milestone_id');
}

function milestoneState(bru, condition, label, attempts) {
  return poll(() => gateway(bru, path(bru), 'buyer'),
    ({ status, data }) => status === 200 && condition(data), label, attempts);
}

function consent(action, fields, statementVersion = '1.0') {
  const text = [action, ...fields].join('|');
  return { statementVersion, presentedContentHash: createHash('sha256').update(text).digest('hex') };
}

function receiptFields(bru, detail) {
  return [
    bru.getVar('milestone_id'), detail.sellerDeclaration.declarationNumber,
    Number(detail.sellerDeclaration.sellerDeclaredWeight).toFixed(3),
    Number(detail.buyerReceipt.buyerReceivedWeight).toFixed(3),
  ];
}

// Small, valid test image for the weighing and inspection APIs.
const photo = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

function auditHeaders(bru) {
  return {
    'X-Gateway-Secret': variable(bru, 'gateway_secret'),
    'X-User-Id': variable(bru, 'buyer1_sub'),
    'X-User-Role': 'BUYER',
  };
}

function auditRequest(bru, view) {
  return request(bru, {
    url: variable(bru, 'audit_service_url') + '/api/v1/audit/contracts/' + bru.getVar('contract_id') + '/' + view,
    headers: auditHeaders(bru),
  });
}

function auditEvent(bru, event) {
  return poll(() => auditRequest(bru, 'records'),
    ({ status, data }) => status === 200 && data.records?.some((record) => record.sourceEventType === event),
    'audit event ' + event);
}

async function milestoneMail(bru, recipient, subjectEnding, textPattern) {
  const email = variable(bru, recipient + '1_email');
  const subject = 'of contract ' + bru.getVar('contract_id') + ': ' + subjectEnding;
  const find = (data) => data.items?.find((mail) =>
    mail.Content?.Headers?.Subject?.[0]?.includes(subject) && mail.Raw?.To?.includes(email));
  const read = () => request(bru, {
    url: variable(bru, 'mailhog_url') + '/api/v2/search?' +
      new URLSearchParams({ kind: 'to', query: email, limit: '100' }),
  });
  const result = await poll(read, ({ status, data }) => status === 200 && find(data), subjectEnding + ' mail for ' + email);
  if (textPattern && !textPattern.test(find(result.data).Content.Body.replace(/=\r?\n/g, ''))) {
    throw new Error(subjectEnding + ' mail did not include the expected text for ' + email);
  }
}

module.exports = { auditEvent, auditRequest, consent, fundedContract, milestoneMail, milestoneState, path, photo, receiptFields };

function ageBuyerReceipt(bru) {
  const { execFileSync } = require('child_process');
  const { resolve } = require('path');
  const contract = bru.getVar('contract_id');
  const milestone = bru.getVar('milestone_id');
  const helper = resolve(__dirname, '../../scripts/time-travel.sh');
  const sql = "UPDATE milestone SET buyer_received_at = UTC_TIMESTAMP(6) - INTERVAL 14 DAY" +
    ` WHERE contract_id = '${contract}' AND milestone_id = '${milestone}'`;
  // Windows cannot execute a shell script directly (spawn EFTYPE), so run it through Git Bash there
  const [command, args] = process.platform === 'win32'
    ? ['bash', [helper.replace(/\\/g, '/'), 'contract-db', sql]]
    : [helper, ['contract-db', sql]];
  const output = execFileSync(command, args, {
    env: { ...process.env, E2E_OWNED_CONTRACT_ID: contract },
    encoding: 'utf8',
  });
  console.log(output.trim());
  if (!/^rows changed\t1$/m.test(output)) throw new Error('Timestamp update did not change exactly one fixture row');
}
module.exports.ageBuyerReceipt = ageBuyerReceipt;
