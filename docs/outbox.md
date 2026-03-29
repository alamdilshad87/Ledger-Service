# Transactional outbox: events that can't be lost

Every money movement publishes an event (`topup.completed`, `transfer.completed`, `payment.completed`, `refund.completed`) to the Kafka topic `ledger-events`. Week 6 turns these into merchant webhooks.

## The problem

The obvious code is "commit to Postgres, then send to Kafka". It can half-fail either way:

- commit succeeds, the process dies before the send → money moved, **event lost forever**
- send succeeds, the commit fails → **event for money that never moved**

Two systems can't commit atomically without distributed transactions, which Kafka and Postgres don't share.

## The fix

Write the event into Postgres, in the **same transaction** as the money movement, then publish it separately.

```
┌──────────── one Postgres transaction ────────────┐
│ journal entry + postings + balances               │
│ INSERT INTO outbox_events (… 'transfer.completed')│
└──────────────────────── commit ──────────────────┘
          │  (any time later, even after a crash)
          ▼
OutboxRelay, every 500 ms:
  SELECT … WHERE published_at IS NULL ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED
  for each row: send to Kafka, wait for the broker ack, SET published_at
```

- The event row exists **if and only if** the money moved, because they commit together. `OutboxWriter` uses `Propagation.MANDATORY`, so calling it outside a transaction throws instead of quietly writing a separate one.
- **Kafka key = aggregate id.** A payment and its refunds use the payment id, so they land on one partition and stay in order (`OutboxRelayTest`).
- **`FOR UPDATE SKIP LOCKED`** lets several app instances relay at once: each skips rows another is already sending instead of waiting or sending them twice.
- **Stop on first failure.** If a send fails, the batch stops there so a later event can't overtake it; already-sent rows keep their `published_at`.

## Delivery guarantee: at least once

If the process dies after Kafka acks a record but before `published_at` commits, the row is sent again after restart. Exactly-once delivery isn't possible here, so consumers must **dedupe by `eventId`** (sent as a header and inside the payload). Week 6's webhook consumer does exactly that.

## Crash drill (Week 5 "done when")

Real process, real Kafka, from `docker compose up`:

1. Start the jar with `--ledger.outbox.relay.enabled=false` (it commits but never publishes).
2. Top up a wallet and transfer ₹500.
3. `Stop-Process -Force` the JVM: a hard kill, no shutdown hooks.
4. Postgres: the `transfer.completed` row exists with `published_at = NULL`.
5. Start the jar normally.
6. Read `ledger-events` with `kafka-console-consumer`.

| Time (UTC) | What happened |
| --- | --- |
| 20:11:12 | App started, relay paused |
| 20:11:14.8 | Transfer committed; outbox row written |
| ~20:11:15 | JVM hard-killed |
| 20:11:46.6 | App restarted |
| 20:11:47.1 | Kafka record created for the transfer, ~0.5 s after startup |

Exactly one record for that transfer was on the topic, with its `eventId` and `eventType` headers. `OutboxCrashRecoveryTest` repeats the same scenario automatically on every build (relay paused, then run by hand as the "restarted" app).

Two things the drill caught:

- `published_at` was 0.5 s *earlier* than Kafka's timestamp, because Postgres `now()` is the transaction's start time. It now uses `clock_timestamp()`, which matters once outbox lag is a metric (week 7).
- My first check of Kafka "before restart" used `docker exec … /opt/kafka/bin/…` from Git Bash, which silently rewrote the path into a Windows path, so the command never ran. Run it with `MSYS_NO_PATHCONV=1`. The "no event before restart" evidence above comes from Postgres (`published_at` NULL) and from the Kafka record's create time, which is after the restart.

## Trade-offs

- **Polling every 500 ms** adds up to ~0.5 s of latency and a cheap indexed query (`WHERE published_at IS NULL`, partial index) when idle. Change data capture (Debezium reading the WAL) removes the polling but adds a whole system to run.
- **Ordering across aggregates isn't guaranteed.** `seq` is assigned at insert, but transactions commit in a different order, so a lower `seq` can become visible after a higher one was published. Per-aggregate order is what consumers rely on, and that holds.
- **Published rows are kept.** They're useful for debugging and replay; a cleanup job (like the idempotency one) can trim them later.
- **The relay holds a DB transaction while waiting for Kafka** (up to the 5 s send timeout). Fine for one relay; at higher volume, mark rows sent in a separate short transaction.
