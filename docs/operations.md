# Operations: auth, rate limits, logs, metrics, dashboard

## Auth: API keys

Every `/v1/**` request needs `Authorization: Bearer lk_…`. Keys identify the calling **application** (a merchant's or partner's backend), not an end user; there is no per-wallet ownership check, which a consumer-facing API would need.

| Role | Can call |
| --- | --- |
| `CLIENT` | everything under `/v1` except `/v1/admin/**` |
| `ADMIN` | everything, including key management and reconciliation |

- Keys are 32 random bytes (`lk_` + 64 hex). Only a **SHA-256 hash** is stored. A fast hash is right here: bcrypt protects low-entropy passwords from guessing, and a 256-bit random key can't be guessed.
- The first admin key comes from `LEDGER_ADMIN_API_KEY` at startup. Use it to create real keys (`POST /v1/admin/api-keys`, the full key is shown once), then unset it. `DELETE /v1/admin/api-keys/{id}` revokes a key immediately.
- `/actuator/**`, `/v3/api-docs` and `/swagger-ui.html` are public. In production, actuator belongs on a separate management port that only Prometheus can reach.
- **Idempotency keys are now scoped per API key** (stored as `<api key id>:<key>`), closing the gap noted in Week 4: two partners who both send `Idempotency-Key: order-1001` no longer collide.

## Rate limits: a token bucket in Redis

Each key has `rate_per_second` (default 10) and `burst` (default 20). `ratelimit/token_bucket.lua` refills and spends tokens in one atomic script using **Redis's clock**, so every app instance shares one bucket per key and skewed host clocks don't matter. Over the limit: `429 RATE_LIMITED` with `Retry-After`. Every response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`.

If Redis is unreachable the limiter **fails open**: losing rate limiting for a few minutes is better than taking payments down. Correctness doesn't depend on it, since locks and idempotency do that job.

## Logs and trace ids

Logs are one JSON object per line (logstash-logback-encoder), so a log platform can index fields instead of parsing text. Run with `--spring.profiles.active=plain-logs` for readable lines locally.

Every request gets a trace id: a sane caller-supplied `X-Request-Id` is kept, otherwise a UUID is generated. It is:

- returned in the `X-Request-Id` response header and in every error body's `traceId`
- on every log line for that request (MDC)
- stored in each outbox event's `traceId`, so a webhook a merchant received can be traced back to the API call that caused it

One access-log line per request records method, path, status, duration and client name.

## Metrics

`/actuator/prometheus` exposes Spring's HTTP, JVM and Hikari metrics plus:

| Metric | What it answers |
| --- | --- |
| `http_server_requests_seconds_bucket` | throughput and p50/p95/p99 per endpoint (histograms, so quantiles aggregate across instances) |
| `ledger_entries_posted_total{type}` | committed entries; counted in an after-commit hook, so rolled-back work never counts |
| `ledger_money_moved_paise_total{type}` | money volume |
| `ledger_entries_declined_total{reason}` | 402s from insufficient funds |
| `ledger_outbox_pending`, `ledger_outbox_oldest_pending_age_seconds` | is the relay keeping up? |
| `ledger_outbox_publish_lag_seconds` | commit → Kafka ack |
| `ledger_webhooks_attempts_total{outcome}`, `ledger_webhooks_pending` | delivered / failed / dead, and backlog |
| `ledger_idempotency_replays_total{source}`, `ledger_idempotency_conflicts_total{type}` | retries answered from Redis or Postgres; 409s and 422s |
| `ledger_ratelimit_rejected_total{client}` | who is hitting their limit |
| `ledger_reconciliation_last_mismatches` | did last night's reconciliation find anything? |

## Dashboard

`docker compose up -d` starts Prometheus (`:9090`) and Grafana (`:3000`, anonymous read-only, admin/admin locally) with the **Ledger Service** dashboard provisioned. Start the app with an admin key, then generate traffic:

```bash
LEDGER_ADMIN_API_KEY=lk_local_... ./mvnw spring-boot:run
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=lk_local_... \
  -e RATE=10 -e DURATION=2m grafana/k6:0.53.0 run - < load/traffic.js
```

**Week 7 "done when".** At 10 requests/second of mixed traffic (transfers with 10% deliberately declined, payments, refunds, top-ups, retries), these are the dashboard's own panel queries, run through Grafana's datasource mid-test:

| Panel | Value |
| --- | --- |
| Requests / s | 10.6 |
| p50 / p99 latency | 54 ms / 331 ms |
| Transfers declined (402) / s | 0.49 |
| Money moved | ₹1,051 / s |
| Outbox backlog / publish lag p99 | 5 events / 0.80 s |
| Idempotent replays / s | 0.95 |
| Connections waiting for the pool | 0 |

(The webhook panel showed about 47 failed attempts/s, because the load script deliberately registers a webhook URL that nothing listens on.)

## Found while testing: throughput collapses on hot accounts at 40 req/s

At 40 requests/second the service fell over: k6 achieved only 6–14 req/s, with an average of 6–13 s per request and some requests hitting the 60 s client timeout. Evidence:

- **Hikari:** all 10 connections busy, up to **93 requests waiting** for one, acquire time up to 30 s.
- **Thread dumps:** about 92 request threads waiting for a connection: 44 of them just to look up their API key, 37 to claim an idempotency key. The ~9 threads holding connections were blocked in `SELECT … FOR UPDATE` on account rows.
- **Postgres:** the most-written rows were the load test's merchant account and the system clearing account. Commit latency was a normal 2–4 ms, so this isn't disk.

The mechanism is a **lock convoy through hot accounts**. Every payment locks the customer's wallet, then queues for the *one* merchant row while still holding the wallet lock. Top-ups do the same on the clearing account. Transfers touching any of those wallets then queue too, and each waiter holds one of only 10 connections. With no connections left, new requests can't even authenticate, so a backlog on one row stalls the whole service.

The first 40 req/s run happened while a test suite was also running, so its numbers are inflated by CPU contention. The second run had the machine to itself and was *worse* (6.4 req/s), which rules contention out as the cause.

Likely fixes, for Week 8 to measure one at a time:

1. **Don't row-lock house accounts (merchant, clearing).** They have no balance check, so they don't need `FOR UPDATE`; apply their balance change last, so their row lock is held for milliseconds at the end of the transaction, not while queueing. Or stop caching their balance and derive it from postings.
2. **Fewer connection borrows per request:** cache API-key lookups in memory for a few seconds, so authentication doesn't compete with money movement for the pool.
3. **Pool size and limits:** size Hikari to the database rather than the default 10, and cap concurrent money requests so overload fails fast with 503 instead of queueing for 30 s.
