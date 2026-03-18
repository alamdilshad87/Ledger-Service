# ledger-service

A double-entry payments ledger: INR wallets, transfers, merchant payments and refunds, built so money never appears or disappears and a retried request never charges twice.

Status: **Week 1 — foundations** (users, wallets, CI).

## Quick start

Requires Java 21 and Docker.

```bash
docker compose up -d          # Postgres 16 + Redis 7
./mvnw spring-boot:run        # API on http://localhost:8080
```

```bash
curl -s -X POST localhost:8080/v1/users -H 'Content-Type: application/json' \
     -d '{"email":"asha@example.com"}'
curl -s localhost:8080/v1/accounts/2
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

Errors always look like `{ "code": "...", "message": "...", "traceId": "..." }`.

## Design notes

- Amounts are stored in **paise as `BIGINT`**, never floats.
- A `SYSTEM_CLEARING` account is seeded by the first migration; top-ups (week 2) will move money out of it so every rupee has a source.
- User wallets can never go negative (`CHECK` constraint in the schema).

## Out of scope

Real payment gateways, KYC, a frontend, multiple currencies, sharding.
