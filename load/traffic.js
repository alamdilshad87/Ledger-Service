// Realistic-ish traffic for watching the dashboard: transfers (some declined), payments, refunds,
// top-ups, and a few client retries with the same Idempotency-Key.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=$LEDGER_ADMIN_API_KEY \
//     grafana/k6:0.53.0 run - < load/traffic.js
//
// Week 8 adds the measured load tests; this script is for seeing live numbers, not for benchmarks.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY;
const WALLETS = 50;

export const options = {
  scenarios: {
    traffic: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 40), timeUnit: '1s',
               duration: __ENV.DURATION || '3m', preAllocatedVUs: 20, maxVUs: 100 },
  },
};

function headers(key) {
  const h = { 'Content-Type': 'application/json', Authorization: `Bearer ${API_KEY}` };
  if (key) h['Idempotency-Key'] = key;
  return { headers: h };
}

function post(path, body, key) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), headers(key === undefined ? uuidv4() : key));
}

export function setup() {
  if (!API_KEY) throw new Error('Set API_KEY (the LEDGER_ADMIN_API_KEY the app was started with)');
  const wallets = [];
  for (let i = 0; i < WALLETS; i++) {
    const user = http.post(`${BASE}/v1/users`, JSON.stringify({ email: `load-${uuidv4()}@example.com` }), headers());
    const wallet = user.json('walletAccountId');
    post('/v1/topups', { accountId: wallet, amount: 500000 });   // ₹5,000 each
    wallets.push(wallet);
  }
  const merchant = http.post(`${BASE}/v1/merchants`, JSON.stringify({ name: 'Load Test Cafe' }), headers()).json();
  // An endpoint nothing listens on: webhooks fail and retry, so the webhook panels have something to show.
  http.post(`${BASE}/v1/webhooks`, JSON.stringify({ merchantId: merchant.merchantId, url: 'http://127.0.0.1:9/hooks' }), headers());
  return { wallets, merchantId: merchant.merchantId };
}

export default function (data) {
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  let to = w[Math.floor(Math.random() * w.length)];
  if (to === from) to = w[(w.indexOf(from) + 1) % w.length];
  const roll = Math.random();

  if (roll < 0.55) {
    // Mostly small transfers; 1 in 10 is too big and gets declined with 402.
    const amount = Math.random() < 0.1 ? 10000000 : 100 + Math.floor(Math.random() * 20000);
    const res = post('/v1/transfers', { fromAccountId: from, toAccountId: to, amount });
    check(res, { 'transfer 201 or 402': (r) => r.status === 201 || r.status === 402 });
  } else if (roll < 0.80) {
    const res = post('/v1/payments', { fromAccountId: from, merchantId: data.merchantId, amount: 500 + Math.floor(Math.random() * 5000) });
    check(res, { 'payment 201 or 402': (r) => r.status === 201 || r.status === 402 });
    if (res.status === 201 && Math.random() < 0.2) {
      post(`/v1/payments/${res.json('paymentId')}/refunds`, {});
    }
  } else if (roll < 0.90) {
    post('/v1/topups', { accountId: from, amount: 20000 + Math.floor(Math.random() * 50000) });
  } else {
    // A client retrying after a timeout: same key, same body, twice.
    const key = uuidv4();
    const body = { fromAccountId: from, toAccountId: to, amount: 150 };
    post('/v1/transfers', body, key);
    const retry = post('/v1/transfers', body, key);
    check(retry, { 'retry replayed': (r) => r.headers['Idempotent-Replayed'] === 'true' || r.status === 409 });
  }
}
