// Scenario 1 — steady transfers between 10,000 random wallets.
// Question: sustained throughput and p50/p95/p99 when contention is low.
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { appMetric, count, createWallets, last, post, seconds, summarize, SUMMARY_STATS } from './lib.js';

const WALLETS = Number(__ENV.WALLETS || 10000);
const RATE = Number(__ENV.RATE || 150);
const DURATION = __ENV.DURATION || '2m';

const declined = new Counter('declined_402');
const busy = new Counter('busy_503');
const deadlocks = new Gauge('deadlocks_during_test');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_STATS,
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 100, maxVUs: 400, tags: { phase: 'load' },
    },
  },
  thresholds: { 'http_req_duration{phase:load}': ['p(95)<100000'], 'http_reqs{phase:load}': ['count>=0'] },
};

export function setup() {
  return { wallets: createWallets(WALLETS, 1000000), deadlocksBefore: appMetric('ledger_db_deadlocks') };
}

export default function (data) {
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  let to = w[Math.floor(Math.random() * w.length)];
  if (to === from) to = w[(w.indexOf(from) + 1) % w.length];
  const res = post('/v1/transfers', { fromAccountId: from, toAccountId: to, amount: 100 + Math.floor(Math.random() * 5000) });
  if (res.status === 402) declined.add(1);
  if (res.status === 503) busy.add(1);
  check(res, { 'transfer 201': (r) => r.status === 201 });
}

export function teardown(data) {
  deadlocks.add(appMetric('ledger_db_deadlocks') - data.deadlocksBefore);
}

export function handleSummary(data) {
  return summarize('steady', `Steady transfers between ${WALLETS.toLocaleString()} wallets (target ${RATE}/s for ${DURATION})`, data, [
    ['Declined 402 / busy 503', (m) => `${count(m, 'declined_402')} / ${count(m, 'busy_503')}`],
    ['Deadlocks', (m) => last(m, 'deadlocks_during_test')],
  ], seconds(DURATION));
}
