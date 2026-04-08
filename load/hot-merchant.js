// Scenario 2 — hot wallet: 500 users all paying one merchant.
// Question: lock contention on one row: p99 and deadlocks (must be 0).
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { appMetric, count, createMerchant, createWallets, last, post, seconds, summarize, SUMMARY_STATS } from './lib.js';

const USERS = Number(__ENV.USERS || 500);
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '1m';

const busy = new Counter('busy_503');
const deadlocks = new Gauge('deadlocks_during_test');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_STATS,
  scenarios: {
    payments: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 100, maxVUs: 400, tags: { phase: 'load' },
    },
  },
  thresholds: { 'http_req_duration{phase:load}': ['p(95)<100000'], 'http_reqs{phase:load}': ['count>=0'] },
};

export function setup() {
  const merchant = createMerchant('Hot Chai Stall');
  return {
    wallets: createWallets(USERS, 1000000),
    merchant,
    deadlocksBefore: appMetric('ledger_db_deadlocks'),
  };
}

const PAYMENT = 1500;   // ₹15 every time, so the merchant's final balance is checkable

export default function (data) {
  const from = data.wallets[Math.floor(Math.random() * data.wallets.length)];
  const res = post('/v1/payments', { fromAccountId: from, merchantId: data.merchant.merchantId, amount: PAYMENT });
  if (res.status === 503) busy.add(1);
  check(res, { 'payment 201': (r) => r.status === 201 });
}

export function teardown(data) {
  deadlocks.add(appMetric('ledger_db_deadlocks') - data.deadlocksBefore);
}

export function handleSummary(data) {
  return summarize('hot-merchant', `Hot merchant: ${USERS} users paying one merchant (target ${RATE}/s for ${DURATION})`, data, [
    ['Busy 503', (m) => count(m, 'busy_503')],
    ['Deadlocks', (m) => last(m, 'deadlocks_during_test')],
  ], seconds(DURATION));
}
