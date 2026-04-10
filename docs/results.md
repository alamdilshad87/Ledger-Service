# Results: load tests and failure drills

Everything below was measured on one laptop, with the load generator, the app and every dependency competing for the same CPU:

| | |
| --- | --- |
| CPU | Intel i7-10510U, 4 cores / 8 threads, 1.8 GHz base |
| RAM | 16 GB (Docker's WSL 2 VM gets 8 GB of it) |
| OS | Windows 11, Docker Desktop on WSL 2, all 8 threads available to containers |
| Stack | `docker compose up`: app (Java 21, Hikari pool 20), Postgres 16, Redis 7, Kafka 3.8, Prometheus, Grafana |
| Load | k6 0.53 in a container on the same Docker network |

Reproduce with:

```bash
docker compose up -d --build
docker compose run --rm loadtest            # ~12 minutes; writes load/results/SUMMARY.md
SCENARIOS=retry-storm docker compose run --rm loadtest   # one scenario
```

Expect different absolute numbers on different hardware; the correctness results (duplicates, deadlocks, invariants) should not change.

## Load tests

### 1. Steady transfers: 150 req/s for 2 minutes, 10,000 wallets

| Metric | Value |
| --- | --- |
| Requests | 18,000 (150.0/s achieved) |
| Latency p50 / p95 / p99 | 22 ms / 143 ms / 293 ms |
| Latency max | 824 ms |
| Checks passed | 100% |
| 402 declined / 503 busy | 0 / 0 |
| Deadlocks (`pg_stat_database`) | 0 |

### 2. Hot merchant: 500 users paying one merchant, 100 req/s for 1 minute

The scenario that collapsed at 40 req/s in Week 7 (every payment credits the same row). See [operations.md](operations.md#found-while-testing-throughput-collapsed-on-hot-accounts-at-40-reqs-fixed).

| Metric | Value |
| --- | --- |
| Requests | 6,001 (100.0/s achieved) |
| Latency p50 / p95 / p99 | 12 ms / 185 ms / 713 ms |
| Latency max | 1.6 s |
| 503 busy | 0 |
| Deadlocks | 0 |

### 3. Retry storm: every transfer sent 3× at once with one key, then retried late

40 logical transfers/s for 1 minute = 160 req/s. This is the double-charge test: the client is as badly behaved as a flaky mobile network.

| Metric | Value |
| --- | --- |
| Logical transfers that succeeded | 2,400 |
| Transfer entries the server created (Prometheus counter delta) | 2,400 |
| **Duplicate entries** | **0** |
| Concurrent copies answered 409 `REQUEST_IN_PROGRESS` | 4,793 |
| Replays (`Idempotent-Replayed: true`) | 2,407 |
| First request p50 / p95 | 11 ms / 20 ms |
| Replay p50 / p95 | 2 ms / 4 ms |

Every key that got a 201 produced exactly one journal entry, and every response for that key carried the same entry id. Replays are about 5× faster than the original because they're served from Redis without touching Postgres.

### 4. Ramp: 20 → 400 req/s over 4 minutes (70% transfers, 20% payments to 10 merchants, 10% top-ups)

| Target req/s | Achieved req/s | p50 | p95 | p99 | 503 busy |
| --- | --- | --- | --- | --- | --- |
| 20–50 | 35 | 10 ms | 16 ms | 24 ms | 0 |
| 50–100 | 75 | 11 ms | 20 ms | 36 ms | 0 |
| 100–150 | 125 | 14 ms | 88 ms | 198 ms | 0 |
| 150–200 | 175 | 30 ms | 131 ms | 252 ms | 0 |
| 200–250 | 225 | 36 ms | 237 ms | 687 ms | 0 |
| 250–300 | 275 | 47 ms | 165 ms | 283 ms | 0 |
| 300–350 | 325 | 51 ms | 207 ms | 420 ms | 0 |
| 350–400 | 343 | 1,021 ms | 2,135 ms | 2,771 ms | 9 |

Overall: 49,374 requests, 100% of checks passed.

**Where it breaks:** about 340 req/s on this laptop. Up to 325 req/s, p95 stays under 250 ms. Past that, latency jumps by 20× and throughput flattens. The limit is the database, not the app:

- Up to 181 requests were waiting for one of the 20 DB connections at the peak (`hikaricp_connections_pending`).
- The app process never used more than 36% of the machine's CPU. Postgres, Kafka and k6 had the rest.
- The pool's 3 s timeout turned the overflow into 9 fast `503 SERVICE_BUSY` with `Retry-After`, not timeouts.

**Second bottleneck found, and fixed: the outbox relay.** In the run above, the backlog of unpublished events peaked at 14,388. The relay waited for Kafka's ack on each event before sending the next, so it published at most about 200 events/s, below the ~340 entries/s the ledger can commit. Nothing was lost (events stay in Postgres until acked), but webhooks fell behind under sustained load.

The fix sends the whole batch (up to 100 events), then waits for the acks, and marks the acked rows with one `UPDATE` instead of one per event. Per-key order still holds, because the producer is idempotent (`enable.idempotence`, `acks=all`). If an ack fails, only the acked rows before it are marked, and everything from that row on is retried next tick. `OutboxRelayTest` checks the ordering with a 250-event backlog spread across 5 keys.

The same ramp was run before and after the fix, each time from a fresh database. The laptop was busier than during the run above, so compare the two columns with each other rather than with the table above:

| | Before (one ack at a time) | After (pipelined) |
| --- | --- | --- |
| Max outbox backlog | 20,303 | **1,278** |
| Requests completed (avg rate) | 37,755 (157/s) | 44,768 (187/s) |
| Latency p50 / p95 | 3,124 ms / 7,517 ms | 712 ms / 4,514 ms |
| p95 at 150–200 req/s | 1,833 ms | 272 ms |
| 503 busy | 357 | 87 |

Faster publishing also means the relay's transaction holds its DB connection for a shorter time, which leaves more of the 20-connection pool for requests.


## Failure drills

Scripts in [`drills/`](../drills); run them against the compose stack with `bash drills/<name>.sh` (Git Bash works on Windows). Output of the runs below is in [`drills/results/`](../drills/results).

Every drill ends with the same three checks, and all of them held after every drill:

- the sum of all postings is 0;
- no account's cached balance differs from the sum of its postings;
- no journal entry has zero postings.

### Kill the app mid-traffic (`kill-app.sh`)

200 transfers fired in parallel. `docker kill` (SIGKILL, no shutdown hooks) lands once 50 have committed.

| | |
| --- | --- |
| Committed before the kill | 86 |
| Requests that got no answer (connection dropped) | 114 |
| App healthy again | 39 s after `docker compose start` |
| After retrying every key with the same `Idempotency-Key` | 200 × 201 |
| Transfers that reached the recipient | 200 of 200 |
| Keys with more than one entry | 0 |
| Recipient balance | ₹200.00, exactly 200 × ₹1 |

In-flight transactions rolled back with the process. Keys that had been claimed but not finished stayed `IN_PROGRESS`, and their 60 s lease had expired by the time the app was back, so retries took the claims over straight away. The ones that had committed were replayed.

### Kafka down for 5 minutes (`kafka-outage.sh`)

Kafka stopped with `docker compose stop kafka` for 300 s while transfers kept coming (the script aims for 5/s; it achieved about 2.5/s, held back by `curl.exe` start-up on Windows).

| | |
| --- | --- |
| Transfers while Kafka was down | 739, all 201 |
| Outbox backlog | 145 → 302 → 452 → 585 → 739 at one-minute intervals |
| Kafka healthy again | 15 s after `docker compose start kafka` |
| Backlog drained | 19 s after the start, about 4 s after Kafka was healthy |
| Events written / unpublished afterwards | 739 / 0 |
| Longest commit → publish lag | 318 s (the outage itself) |

Payments didn't notice: the request path never talks to Kafka. Events waited in `outbox_events` and went out in order once the broker came back.


### Redis down (`redis-outage.sh`)

100 transfers, each sent twice with the same key, with Redis up and then with Redis stopped.

| | Redis up | Redis down |
| --- | --- | --- |
| Results | 100 × 201, 100 replays | 100 × 201, 100 replays |
| Replays served from | Redis: 100 | Postgres: 100 |
| Time per request (sequential, includes ~0.25 s of `curl.exe` start-up) | 0.29 s | 1.08 s |
| Rate limiter | — | failed open (106 log lines) |

Correct either way: 200 transfers reached the recipient, ₹200 exactly. **The cost is latency.** Each request still makes about three Redis calls (rate limit, cache lookup, cache write), and each waits out its 250 ms timeout. A circuit breaker that skips Redis for a few seconds after a failure would remove that, and it's in "what I'd do next".

### Corrupt a balance by hand (`corrupt-balance.sh`)

`UPDATE accounts SET balance = balance + 100` on one wallet, behind the ledger's back, then a reconciliation run:

```
Reconciliation: MISMATCHED
  {"type":"BALANCE_DRIFT","reference":"account:14144","expected":20000,"actual":20100,
   "detail":"Cached balance differs from its postings"}
Restored; re-running: MATCHED
```

Exactly that account, with the size of the drift.

### Merchant webhook endpoint returning 500

Covered by `WebhookDeliveryTest` rather than a script. A mock merchant fails 50% of requests at random; all 25 events arrive, each exactly once after the merchant dedupes by event id, and the retries follow the 1 s, 2 s, 4 s… schedule. Details: [webhooks-and-reconciliation.md](webhooks-and-reconciliation.md).
