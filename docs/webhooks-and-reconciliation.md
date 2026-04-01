# Webhooks and reconciliation

## Webhooks: every event, exactly once (after the merchant's dedupe)

Merchants register a URL with `POST /v1/webhooks` and get back a `whsec_…` secret. Every `payment.completed` and `refund.completed` event for that merchant is then POSTed to the URL.

```
Kafka ledger-events ──► WebhookDispatcher ──► webhook_deliveries (PENDING)
                          (one row per event × subscription,          │
                           UNIQUE (event_id, subscription_id))        ▼
                                                    WebhookDeliveryWorker, every 1 s
                                                    claim due rows (lease + SKIP LOCKED)
                                                    POST, signed ──► merchant
                                                    2xx → DELIVERED
                                                    else → retry in 1 s, 2 s, 4 s … 1 h
                                                    15th failure → DEAD + webhook_dead_letters
```

**What a merchant receives**

| Header | Meaning |
| --- | --- |
| `Ledger-Event-Id` | Unique per event. Dedupe on this. |
| `Ledger-Event-Type` | `payment.completed`, `refund.completed` |
| `Ledger-Signature` | `t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>` |

To verify: recompute the HMAC over `t.body` with your secret, compare in constant time, and reject if `t` is more than 5 minutes old (`WebhookSignature.verify` is the reference code). Signing the timestamp stops a captured request being replayed later.

**Design choices**

- **The Kafka consumer only writes rows; it never calls merchants.** A merchant that takes 5 s to time out would otherwise stall the partition for everyone. The consumer is fast and simple, and the slow part retries on its own schedule.
- **Dedupe happens twice.** Kafka redelivers events (the outbox is at least once), so `UNIQUE (event_id, subscription_id)` + `ON CONFLICT DO NOTHING` collapses duplicates into one delivery row. The worker itself is also at least once (a crash between the merchant's 200 and our `DELIVERED` update means a resend), so merchants must still dedupe on `Ledger-Event-Id`. Exactly-once delivery over HTTP isn't possible; exactly-once *processing* is, with the merchant's help.
- **No DB transaction during HTTP calls.** Claiming moves `next_attempt_at` 30 s into the future (a lease) in a short transaction; the POST happens after. If the app dies mid-send, the lease runs out and another worker retries it.
- **Redirects aren't followed**, so a misconfigured or hostile URL can't bounce a signed payload elsewhere.
- **Retry schedule:** 1 s, 2 s, 4 s … 2048 s, then hourly. 15 attempts take about 3 hours, then the delivery is dead-lettered for a human.
- **Poison events** (unparseable JSON) are logged and skipped. Database errors are retried with backoff forever, because Spring Kafka's default (10 quick tries, then skip) would silently drop webhooks.

**Week 6 "done when": a merchant failing half its requests**

`WebhookDeliveryTest` points a webhook at a mock merchant that answers 500 at random 50% of the time (fixed seed), verifies every signature, and dedupes by event id. 20 payments and 5 refunds produce 25 events. One run:

| | |
| --- | --- |
| Requests received | 53 |
| Answered 500 | 28 (53%) |
| Events accepted | 25 of 25, each exactly once |
| Bad signatures | 0 |
| Most attempts for one event | 7 |
| Dead letters | 0 |

Other tests: publishing the same event to Kafka a second time produces no second webhook; a delivery that always fails ends up in `webhook_dead_letters` with its last status and error, and is never tried again; retries are spaced exponentially.

**Not done yet:** blocking private-network URLs (SSRF), encrypting secrets at rest, secret rotation, a dead-letter replay endpoint, and ordering guarantees (a retried event can arrive after a later one, as with most webhook APIs).

## Reconciliation: does the money we think we have exist?

Every night at 02:30 IST, `ReconciliationJob` checks the previous day and stores the report in `reconciliation_runs`.

| Check | Catches |
| --- | --- |
| Sum of all postings = 0 | money created or destroyed by a bug |
| Each account's cached balance = sum of its postings | balance updates that skipped the ledger, manual DB edits |
| Each top-up has a bank line with the same reference and amount | money we credited that never arrived (`MISSING_IN_BANK`), or arrived short (`AMOUNT_MISMATCH`) |
| Each bank line has a top-up | money the bank sent that we never credited (`MISSING_IN_LEDGER`) |
| Statement well-formed | `DUPLICATE_BANK_LINE`, `MALFORMED_BANK_LINE` |

The statement is `bank-statement-YYYY-MM-DD.csv` in `ledger.reconciliation.statement-dir`:

```csv
date,reference,description,amount_paise
2026-11-20,6f1c2a4e-…,WALLET TOPUP,120000
```

If the file hasn't arrived, the run is `STATEMENT_MISSING` and the internal checks still run.

There's no real bank, so `POST /v1/admin/fake-bank/statements?date=…&fault=NONE|DROP_LAST|SHORT_LAST|ADD_UNKNOWN` writes the statement the bank *would* send, optionally with one deliberate fault. It exists only so the checks have something to compare against; turn it off with `ledger.reconciliation.fake-bank-enabled=false`.

```bash
curl -X POST "localhost:8080/v1/admin/fake-bank/statements?date=2026-11-20&fault=SHORT_LAST"
curl -X POST "localhost:8080/v1/admin/reconciliation/runs?date=2026-11-20"
curl localhost:8080/v1/admin/reconciliation/latest
```

The failure drill from the plan, "manually corrupt one balance", is `ReconciliationTest.manuallyCorruptedBalanceIsFlagged`: it adds ₹1 to a wallet's cached balance with a raw `UPDATE`, and the report shows `BALANCE_DRIFT` for exactly that account, expected 20000, actual 20100.

**Limits:** the drift check scans every posting, which is fine for a project and slow at millions of rows (a real system checks per day, or keeps running totals). Admin endpoints are unauthenticated until week 7.
