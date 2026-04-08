// Scenario 4 — ramp to breaking point: mixed traffic from 20 to 400 req/s over 4 minutes.
// Question: where does latency explode, and what gives out first?
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { createMerchant, createWallets, post, promQuery, summarize, SUMMARY_STATS } from './lib.js';

const START = 20;
const PEAK = Number(__ENV.PEAK || 400);
const RAMP_SECONDS = Number(__ENV.RAMP_SECONDS || 240);
const BUCKET = 50;
const WALLETS = Number(__ENV.WALLETS || 2000);

const busy = new Counter('busy_503');
const declined = new Counter('declined_402');
const maxPoolWaiters = new Gauge('max_db_pool_waiters');
const maxProcessCpu = new Gauge('max_app_process_cpu');
const maxOutboxBacklog = new Gauge('max_outbox_backlog');

const buckets = [];
for (let lo = 0; lo < PEAK; lo += BUCKET) buckets.push(lo);
const bucketTag = (lo) => `b${String(lo).padStart(3, '0')}`;

const thresholds = { 'http_req_duration{phase:load}': ['p(95)<100000'], 'http_reqs{phase:load}': ['count>=0'] };
for (const lo of buckets) {   // thresholds make k6 keep per-bucket sub-metrics in the summary
  thresholds[`http_req_duration{bucket:${bucketTag(lo)}}`] = ['p(95)<100000'];
  thresholds[`http_reqs{bucket:${bucketTag(lo)}}`] = ['count>=0'];
  thresholds[`busy_503{bucket:${bucketTag(lo)}}`] = ['count>=0'];
}

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_STATS,
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate', startRate: START, timeUnit: '1s',
      stages: [{ target: PEAK, duration: `${RAMP_SECONDS}s` }],
      preAllocatedVUs: 200, maxVUs: 1000, tags: { phase: 'load' },
    },
  },
  thresholds,
};

export function setup() {
  const merchants = Array.from({ length: 10 }, (_, i) => createMerchant(`Ramp Shop ${i}`).merchantId);
  const wallets = createWallets(WALLETS, 5000000);
  return { wallets, merchants, start: Date.now() };
}

export default function (data) {
  const elapsed = (Date.now() - data.start) / 1000;
  const target = START + (PEAK - START) * Math.min(1, elapsed / RAMP_SECONDS);
  const tags = { bucket: bucketTag(Math.floor(target / BUCKET) * BUCKET) };
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  const roll = Math.random();
  let res;
  if (roll < 0.7) {
    let to = w[Math.floor(Math.random() * w.length)];
    if (to === from) to = w[(w.indexOf(from) + 1) % w.length];
    res = post('/v1/transfers', { fromAccountId: from, toAccountId: to, amount: 100 + Math.floor(Math.random() * 5000) }, undefined, tags);
  } else if (roll < 0.9) {
    const merchant = data.merchants[Math.floor(Math.random() * data.merchants.length)];
    res = post('/v1/payments', { fromAccountId: from, merchantId: merchant, amount: 500 + Math.floor(Math.random() * 3000) }, undefined, tags);
  } else {
    res = post('/v1/topups', { accountId: from, amount: 10000 }, undefined, tags);
  }
  if (res.status === 503) busy.add(1, tags);
  if (res.status === 402) declined.add(1, tags);
  check(res, { 'answered 201 or 503': (r) => r.status === 201 || r.status === 503 });
}

export function teardown() {
  const window = `${RAMP_SECONDS + 30}s`;
  maxPoolWaiters.add(promQuery(`max(max_over_time(hikaricp_connections_pending[${window}]))`) ?? -1);
  maxProcessCpu.add(promQuery(`max(max_over_time(process_cpu_usage[${window}]))`) ?? -1);
  maxOutboxBacklog.add(promQuery(`max(max_over_time(ledger_outbox_pending[${window}]))`) ?? -1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const lines = ['| Target req/s | Achieved req/s | p50 | p95 | p99 | 503 busy |', '| --- | --- | --- | --- | --- | --- |'];
  for (const lo of buckets) {
    const hi = Math.min(lo + BUCKET, PEAK);
    const seconds = (hi - Math.max(lo, START)) / (PEAK - START) * RAMP_SECONDS;
    const reqs = m[`http_reqs{bucket:${bucketTag(lo)}}`];
    const dur = m[`http_req_duration{bucket:${bucketTag(lo)}}`];
    const busyCount = m[`busy_503{bucket:${bucketTag(lo)}}`];
    if (!reqs || !dur || seconds <= 0) continue;
    const v = dur.values;
    lines.push(`| ${Math.max(lo, START)}–${hi} | ${(reqs.values.count / seconds).toFixed(0)} | ${Math.round(v.med)} ms | `
      + `${Math.round(v['p(95)'])} ms | ${Math.round(v['p(99)'])} ms | ${busyCount ? busyCount.values.count : 0} |`);
  }
  const g = (n) => (m[n] ? m[n].values.value : null);
  const out = summarize('ramp', `Ramp from ${START} to ${PEAK} req/s over ${RAMP_SECONDS} s (70% transfers, 20% payments, 10% top-ups)`, data, [
    ['Max requests waiting for a DB connection', () => g('max_db_pool_waiters')],
    ['Max app CPU (process)', () => (g('max_app_process_cpu') >= 0 ? `${Math.round(g('max_app_process_cpu') * 100)}%` : '-')],
    ['Max outbox backlog', () => g('max_outbox_backlog')],
  ], RAMP_SECONDS);
  const table = `\n#### Per step\n\n${lines.join('\n')}\n`;
  out['/results/ramp.md'] += table;
  out.stdout += table;
  return out;
}
