// Shared helpers for the k6 scenarios.
import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.4/index.js';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const API_KEY = __ENV.API_KEY;
export const PROMETHEUS = __ENV.PROMETHEUS_URL || 'http://prometheus:9090';
export { uuidv4 };

export const SUMMARY_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

export function headers(idempotencyKey) {
  const h = { 'Content-Type': 'application/json', Authorization: `Bearer ${API_KEY}` };
  if (idempotencyKey) h['Idempotency-Key'] = idempotencyKey;
  return { headers: h };
}

export function post(path, body, idempotencyKey, tags) {
  const params = headers(idempotencyKey === undefined ? uuidv4() : idempotencyKey);
  if (tags) params.tags = tags;
  return http.post(`${BASE}${path}`, JSON.stringify(body), params);
}

/** Creates `count` wallets with `paise` each, 100 at a time in parallel; returns their account ids. */
export function createWallets(count, paise) {
  if (!API_KEY) throw new Error('Set API_KEY');
  const wallets = [];
  for (let start = 0; start < count; start += 100) {
    const n = Math.min(100, count - start);
    const users = http.batch(Array.from({ length: n }, () => ['POST', `${BASE}/v1/users`,
      JSON.stringify({ email: `load-${uuidv4()}@example.com` }), { ...headers(), tags: { phase: 'setup' } }]));
    const ids = users.map((r) => r.json('walletAccountId'));
    http.batch(ids.map((id) => ['POST', `${BASE}/v1/topups`, JSON.stringify({ accountId: id, amount: paise }),
      { ...headers(uuidv4()), tags: { phase: 'setup' } }]));
    wallets.push(...ids);
  }
  return wallets;
}

export function createMerchant(name) {
  return http.post(`${BASE}/v1/merchants`, JSON.stringify({ name }), { ...headers(), tags: { phase: 'setup' } }).json();
}

/** Reads one sample from the app's Prometheus endpoint, e.g. metric('ledger_db_deadlocks'). */
export function appMetric(name, labelFilter) {
  const body = http.get(`${BASE}/actuator/prometheus`, { tags: { phase: 'setup' } }).body;
  let total = 0;
  for (const line of body.split('\n')) {
    if (line.startsWith(name + '{') && (!labelFilter || line.includes(labelFilter))) {
      total += Number(line.substring(line.lastIndexOf(' ') + 1));
    }
  }
  return total;
}

/** Instant query against Prometheus; returns the first value or null. */
export function promQuery(expr) {
  const res = http.get(`${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(expr)}`, { tags: { phase: 'setup' } });
  try {
    const result = res.json('data.result');
    return result.length ? Number(result[0].value[1]) : null;
  } catch (e) {
    return null;
  }
}

function ms(v) {
  return v === undefined || v === null ? '-' : `${Math.round(v)} ms`;
}

/**
 * Writes /results/<name>.json (raw k6 data) and /results/<name>.md (the numbers for the README),
 * and prints k6's usual text summary. Only requests tagged as load (not setup) are reported.
 */
export function summarize(name, title, data, extraRows, loadSeconds) {
  const m = data.metrics;
  const d = (m['http_req_duration{phase:load}'] || m.http_req_duration).values;
  const reqs = m['http_reqs{phase:load}'] || m.http_reqs;
  const rows = [
    ['Requests (load phase)', `${reqs.values.count} (${(loadSeconds ? reqs.values.count / loadSeconds : reqs.values.rate).toFixed(1)}/s achieved)`],
    ['Latency p50 / p95 / p99', `${ms(d.med)} / ${ms(d['p(95)'])} / ${ms(d['p(99)'])}`],
    ['Latency max', ms(d.max)],
    ['Checks passed', m.checks ? `${(m.checks.values.rate * 100).toFixed(2)}%` : '-'],
    ...(extraRows || []).map((row) => [row[0], typeof row[1] === 'function' ? row[1](m) : row[1]]),
  ];
  const md = `### ${title}\n\n| Metric | Value |\n| --- | --- |\n${rows.map((r) => `| ${r[0]} | ${r[1]} |`).join('\n')}\n`;
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: false }) + '\n' + md,
    [`/results/${name}.json`]: JSON.stringify(data, null, 2),
    [`/results/${name}.md`]: md,
  };
}

/** "2m" / "45s" → seconds. */
export function seconds(duration) {
  const m = /^(\d+)(s|m)$/.exec(duration);
  return m ? Number(m[1]) * (m[2] === 'm' ? 60 : 1) : null;
}

/** A counter's total from the summary, or 0 if it never fired. */
export function count(m, name) {
  return m[name] ? m[name].values.count : 0;
}

/** A gauge's last value from the summary. */
export function last(m, name) {
  return m[name] ? m[name].values.value : null;
}
