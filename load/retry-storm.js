// Scenario 3 — retry storm: every transfer is sent 3 times at once with the same Idempotency-Key,
// then once more after it finished.
// Question: duplicate entries (must be 0) and what the idempotency check costs.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { appMetric, BASE, count, createWallets, headers, last, seconds, summarize, SUMMARY_STATS, uuidv4 } from './lib.js';

const WALLETS = Number(__ENV.WALLETS || 500);
const RATE = Number(__ENV.RATE || 40);          // logical transfers/s; 4 requests each
const DURATION = __ENV.DURATION || '1m';

const operations = new Counter('logical_transfers_succeeded');
const clientSideDuplicates = new Counter('different_entry_ids_for_one_key');
const serverEntries = new Gauge('server_transfer_entries_created');
const firstLatency = new Trend('latency_first_request', true);
const replayLatency = new Trend('latency_replay', true);
const inProgress = new Counter('concurrent_copies_409');
const replays = new Counter('replays');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: SUMMARY_STATS,
  scenarios: {
    storm: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 100, maxVUs: 400, tags: { phase: 'load' },
    },
  },
  thresholds: { 'http_req_duration{phase:load}': ['p(95)<100000'], 'http_reqs{phase:load}': ['count>=0'] },
};

export function setup() {
  const wallets = createWallets(WALLETS, 1000000);
  return { wallets, entriesBefore: appMetric('ledger_entries_posted_total', 'type="TRANSFER"') };
}

export default function (data) {
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  let to = w[Math.floor(Math.random() * w.length)];
  if (to === from) to = w[(w.indexOf(from) + 1) % w.length];
  const key = uuidv4();
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount: 200 });
  const req = ['POST', `${BASE}/v1/transfers`, body, headers(key)];

  // The storm: three identical requests in flight at once.
  const storm = http.batch([req, req, req]);
  const ids = new Set();
  for (const r of storm) {
    if (r.status === 201) ids.add(r.json('entryId'));
    if (r.status === 409) inProgress.add(1);
    if (r.headers['Idempotent-Replayed'] === 'true') replays.add(1);
  }
  storm.filter((r) => r.status === 201 && r.headers['Idempotent-Replayed'] !== 'true')
    .forEach((r) => firstLatency.add(r.timings.duration));

  // A late retry after the storm settles: must be a replay of the same entry.
  let retry;
  for (let i = 0; i < 20; i++) {
    retry = http.post(`${BASE}/v1/transfers`, body, headers(key));
    if (retry.status !== 409) break;
  }
  if (retry.status === 201) {
    ids.add(retry.json('entryId'));
    replays.add(1);
    replayLatency.add(retry.timings.duration);
  }
  if (ids.size >= 1) operations.add(1);   // this key moved money (at least once)
  if (ids.size > 1) clientSideDuplicates.add(ids.size - 1);
  check(retry, {
    'late retry is a replay': (r) => r.status === 201 && r.headers['Idempotent-Replayed'] === 'true',
    'one entry id per key': () => ids.size === 1,
  });
}

export function teardown(data) {
  serverEntries.add(appMetric('ledger_entries_posted_total', 'type="TRANSFER"') - data.entriesBefore);
}

export function handleSummary(data) {
  return summarize('retry-storm', `Retry storm: each transfer sent 3× at once, then retried (target ${RATE} transfers/s for ${DURATION})`, data, [
    ['Logical transfers that succeeded', (m) => count(m, 'logical_transfers_succeeded')],
    ['Transfer entries the server created', (m) => last(m, 'server_transfer_entries_created')],
    ['Duplicate entries (server entries − keys that succeeded)', (m) => (last(m, 'server_transfer_entries_created') || 0) - count(m, 'logical_transfers_succeeded')],
    ['Concurrent copies told "in progress" (409) / replays', (m) => `${count(m, 'concurrent_copies_409')} / ${count(m, 'replays')}`],
    ['Latency of the first request p50 / p95', (m) => m.latency_first_request
      ? `${Math.round(m.latency_first_request.values.med)} ms / ${Math.round(m.latency_first_request.values['p(95)'])} ms` : '-'],
    ['Latency of a replay p50 / p95', (m) => m.latency_replay
      ? `${Math.round(m.latency_replay.values.med)} ms / ${Math.round(m.latency_replay.values['p(95)'])} ms` : '-'],
  ], seconds(DURATION));
}
