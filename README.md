# ledger-service

A double-entry payments ledger: INR wallets, transfers, merchant payments and refunds, built so money never appears or disappears and a retried request never charges twice.

Status: **Week 2 — the ledger** (double-entry postings, top-ups, transfers, statements).

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

Every money-moving POST needs an `Idempotency-Key` header (400 `MISSING_IDEMPOTENCY_KEY` without one). The key is stored on the journal entry; replay protection arrives in week 4.

Errors always look like `{ "code": "...", "message": "...", "traceId": "..." }`.

## Design notes

- Amounts are stored in **paise as `BIGINT`**, never floats. A single request is capped at ₹1 crore.
- **Double-entry:** every money movement is one journal entry whose postings sum to zero. `NewJournalEntry` refuses to even construct an unbalanced entry, and `LedgerService` is the only code that changes balances.
- **Append-only:** database triggers reject `UPDATE` and `DELETE` on `journal_entries` and `postings`. Mistakes get a reversing entry.
- **Every rupee has a source:** top-ups debit the seeded `SYSTEM_CLEARING` account, so the sum of all postings is always 0.
- **Cached balances:** `accounts.balance` is updated in the same transaction as the postings, and tests check it always equals the sum of that account's postings.
- **Wallets can't go negative:** an application check returns 402, and a `CHECK` constraint backs it up. The check isn't locked yet, so concurrent transfers can race; week 3 fixes that with `SELECT … FOR UPDATE`.

## Ledger invariants (tested)

`LedgerInvariantTest` runs 1,000 random transfers between 20 wallets (fixed seed) and then checks that:

1. `SELECT SUM(amount) FROM postings` is 0
2. every account's cached balance equals the sum of its postings
3. the 20 wallets still hold exactly what they were topped up with
4. no wallet is negative

## Out of scope

Real payment gateways, KYC, a frontend, multiple currencies, sharding.
