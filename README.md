# ledger-service

A double-entry payments ledger: INR wallets, transfers, merchant payments and refunds, built so money never appears or disappears and a retried request never charges twice.

Status: **Week 4 — idempotency** (safe retries: replay, 409 in progress, 422 key reuse, Redis fast path).

## Quick start

Requires Java 21 and Docker.

```bash
docker compose up -d          # Postgres 16 + Redis 7
./mvnw spring-boot:run        # API on http://localhost:8080
```

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
