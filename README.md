# ledger-service

A double-entry payments ledger: INR wallets, transfers, merchant payments and refunds, built so money never appears or disappears and a retried request never charges twice.

Status: **Week 7 — hardening** (API keys, Redis rate limits, JSON logs with trace ids, Prometheus + Grafana, Swagger UI). Known limit: throughput collapses around 40 req/s on hot merchant/clearing accounts; see [docs/operations.md](docs/operations.md#found-while-testing-throughput-collapses-on-hot-accounts-at-40-reqs).

## Quick start

Requires Java 21 and Docker.

```bash
docker compose up -d          # Postgres 16, Redis 7, Kafka 3.8, Prometheus, Grafana
export LEDGER_ADMIN_API_KEY=lk_local_$(openssl rand -hex 24)   # first admin key, registered at startup
./mvnw spring-boot:run        # API on http://localhost:8080
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Grafana dashboard: http://localhost:3000 (the "Ledger Service" dashboard opens by default)

Every `/v1` call below also needs `-H "Authorization: Bearer $LEDGER_ADMIN_API_KEY"` (omitted for brevity).

```bash
curl -s -X POST localhost:8080/v1/users -H 'Content-Type: application/json' \
     -d '{"email":"asha@example.com"}'
curl -s -X POST localhost:8080/v1/users -H 'Content-Type: application/json' \
     -d '{"email":"ravi@example.com"}'

# ₹1,200 into Asha's wallet (amounts are in paise)
curl -s -X POST localhost:8080/v1/topups -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"accountId":2,"amount":120000}'

# Asha sends Ravi ₹500
curl -s -X POST localhost:8080/v1/transfers -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"fromAccountId":2,"toAccountId":3,"amount":50000,"note":"Dinner split"}'

curl -s localhost:8080/v1/accounts/2/statement
```

Run the tests (they start their own Postgres with Testcontainers):

```bash
./mvnw test
```

## API so far

| Method | Path | What it does | Errors |
| --- | --- | --- | --- |
| POST | `/v1/users` | Create a user and an empty INR wallet | 400 invalid email, 409 `EMAIL_EXISTS` |
| GET | `/v1/accounts/{id}` | Balance and account details | 404 `ACCOUNT_NOT_FOUND` |
| GET | `/v1/accounts/{id}/statement?cursor=&limit=` | Postings newest first, cursor-paginated (limit 1–100, default 20) | 400, 404 |
| POST | `/v1/topups` | Add money to a wallet from the clearing account | 400, 404, 422 `NOT_A_WALLET` |
| POST | `/v1/transfers` | Wallet to wallet | 402 `INSUFFICIENT_FUNDS`, 404, 422 `SAME_ACCOUNT` |
| GET | `/v1/journal-entries/{id}` | One entry with all its postings | 404 `ENTRY_NOT_FOUND` |
| POST | `/v1/merchants` | Create a merchant and its settlement account | 400 |
| POST | `/v1/payments` | Wallet to merchant (`merchantId`, `amount`) | 402, 404 `MERCHANT_NOT_FOUND`, 422 `NOT_A_WALLET` |
| GET | `/v1/payments/{id}` | Payment, its status and refunds | 404 `PAYMENT_NOT_FOUND` |
| POST | `/v1/payments/{id}/refunds` | Refund part (`amount`) or all of what's left (`{}`) | 404, 422 `REFUND_EXCEEDS_PAYMENT`, 422 `PAYMENT_FULLY_REFUNDED` |
| POST | `/v1/webhooks` | Register a merchant callback URL; returns its signing secret | 400 `INVALID_URL`, 404, 409 `SUBSCRIPTION_EXISTS` |
| GET | `/v1/admin/reconciliation/latest` | Latest reconciliation report and its mismatches | 404 `NO_RECONCILIATION_RUNS` |
| POST | `/v1/admin/reconciliation/runs?date=` | Run reconciliation now (default: yesterday) | 400 |
| POST | `/v1/admin/fake-bank/statements?date=&fault=` | Demo only: write the statement a bank would send | 400 |
| POST | `/v1/admin/api-keys` | Create a CLIENT or ADMIN key (shown once), optional rate limit | 400 |
| DELETE | `/v1/admin/api-keys/{id}` | Revoke a key | 404 |

All `/v1` calls need `Authorization: Bearer <api key>` (401 without, 403 for admin paths with a CLIENT key) and are rate limited per key (429 with `Retry-After`). Every response has an `X-Request-Id`.

Every money-moving POST needs an `Idempotency-Key` header (400 `MISSING_IDEMPOTENCY_KEY` without one). Retrying with the same key never moves money twice:

| Same key, and… | Response |
| --- | --- |
| same request, finished | the original response replayed, with `Idempotent-Replayed: true` |
| same request, still running | 409 `REQUEST_IN_PROGRESS` |
| a different request | 422 `IDEMPOTENCY_KEY_REUSED` |

Keys are remembered for 24 hours. Details below and in [docs/idempotency.md](docs/idempotency.md).

Errors always look like `{ "code": "...", "message": "...", "traceId": "..." }`.

## Design notes

- Amounts are stored in **paise as `BIGINT`**, never floats. A single request is capped at ₹1 crore.
- **Double-entry:** every money movement is one journal entry whose postings sum to zero. `NewJournalEntry` refuses to even construct an unbalanced entry, and `LedgerService` is the only code that changes balances.
- **Append-only:** database triggers reject `UPDATE` and `DELETE` on `journal_entries` and `postings`. Mistakes get a reversing entry.
- **Every rupee has a source:** top-ups debit the seeded `SYSTEM_CLEARING` account, so the sum of all postings is always 0.
- **Cached balances:** `accounts.balance` is updated in the same transaction as the postings, and tests check it always equals the sum of that account's postings.
- **Concurrency:** `LedgerService` locks every account in an entry with `SELECT … FOR UPDATE`, lowest id first, then checks balances under the lock (402 if a wallet would go negative). The fixed order prevents deadlocks; the `CHECK` constraint stays as a backstop. Before this, 200 simultaneous opposite-direction transfers produced 190 errors from deadlocks, and with the constraint removed a wallet went to −₹90. Full write-up with before/after numbers: [docs/concurrency.md](docs/concurrency.md).
- **Idempotency:** `IdempotencyFilter` claims the key with `INSERT … ON CONFLICT` (the Postgres primary key is the real guarantee), then runs the request in one transaction that also marks the key COMPLETED. Money movement and stored response commit together, so an unfinished key always means no money moved: after a crash, a retry can safely take over the claim once its 60 s lease expires, and a claim token stops the old holder committing. Redis caches finished results for fast replays; if Redis is down, Postgres answers instead. Full design: [docs/idempotency.md](docs/idempotency.md).
- **Refunds are reversing entries:** a REFUND entry moves money merchant → wallet and references the payment; the PAYMENT entry is never changed. The payment row is locked first, so concurrent refunds can't exceed the payment (50 parallel refunds of ₹10 on a ₹100 payment: exactly 10 succeed).
- **Transactional outbox:** each money movement inserts its `*.completed` event into `outbox_events` in the same transaction, so the event exists exactly when the money moved. A relay publishes to Kafka every 500 ms with `FOR UPDATE SKIP LOCKED`, keyed by aggregate id (a payment and its refunds stay in order), at least once; consumers dedupe by `eventId`. Tested by killing the JVM between commit and publish: the event arrived ~0.5 s after restart, exactly once. Write-up: [docs/outbox.md](docs/outbox.md).
- **Webhooks:** a Kafka consumer turns payment and refund events into delivery rows (deduped on event id × subscription), and a worker POSTs them signed with `Ledger-Signature: t=…,v1=HMAC-SHA256`, retrying 1 s, 2 s, 4 s … up to 1 h, then dead-lettering after 15 attempts. A mock merchant failing 50% of requests received all 25 events exactly once. Design and numbers: [docs/webhooks-and-reconciliation.md](docs/webhooks-and-reconciliation.md).
- **Operations:** API keys stored as SHA-256 hashes, with idempotency keys scoped per API key; a per-key token bucket in Redis (atomic Lua, fails open); JSON logs carrying a trace id that also travels in outbox events and webhooks; Prometheus metrics and a provisioned Grafana dashboard. Details and the hot-account finding: [docs/operations.md](docs/operations.md).
- **Reconciliation:** nightly at 02:30 IST, checks that postings sum to 0, cached balances match postings, and every top-up matches the bank statement CSV by reference and amount (and vice versa). Reports are stored and exposed at `/v1/admin/reconciliation/latest`.

## Ledger invariants (tested)

`LedgerInvariantTest` runs 1,000 random transfers between 20 wallets (fixed seed) and then checks that:

1. `SELECT SUM(amount) FROM postings` is 0
2. every account's cached balance equals the sum of its postings
3. the 20 wallets still hold exactly what they were topped up with
4. no wallet is negative

`ConcurrentTransferTest` repeats two 200-thread races 20 times each: draining one wallet (exactly 100 of 200 transfers succeed, balance ends at 0) and opposite-direction transfers between two wallets (all 200 succeed, no deadlocks).

`ConcurrentDuplicateTest` sends the same transfer 100 times in parallel with one key, 10 times over: exactly one journal entry each time, and the money moves once.

## Out of scope

Real payment gateways, KYC, a frontend, multiple currencies, sharding.
