# Concurrency: what broke, and the fix

Week 3 question: what happens when many transfers touch the same wallets at the same instant?

## Setup

Each scenario fires 200 `POST /v1/transfers` requests from 200 threads, released together by a latch, against the real API and a Postgres 16 container (Testcontainers, Docker Desktop on a Windows 11 laptop, Hikari pool of 10 connections). Timings are from one run and only meant for comparison.

- **Drain:** one wallet holding ₹1,000 sends ₹10 two hundred times to four recipients. Exactly 100 should succeed.
- **Opposite directions:** Asha and Ravi each hold ₹10,000. Even requests send Asha → Ravi ₹1, odd ones Ravi → Asha ₹1. All 200 should succeed and both should end where they started.

## Before: check the balance, then write

The original transfer read the sender's balance with a plain `SELECT`, compared it with the amount, then ran `UPDATE accounts SET balance = balance + :delta` for each posting.

| Scenario | 201 | 402 | 500 | Sender ends at | Time |
| --- | --- | --- | --- | --- | --- |
| Drain, `CHECK (balance >= 0)` on | 100 | 100 | 0 | ₹0 | 2.3 s |
| Drain, `CHECK` removed | **109** | 91 | 0 | **−₹90** | 3.1 s |
| Opposite directions | 10 | 0 | **190** | (unchanged, but 190 users saw errors) | 35 s |

Two separate bugs:

1. **Lost check (time-of-check to time-of-use).** Many requests read "₹50 left", all pass the check, and all debit. Without the database constraint, nine extra transfers went through and the wallet went to −₹90. With the constraint on, the result was correct only because Postgres refused the final `UPDATE`: the application check was decoration.
2. **Deadlock.** Asha → Ravi updated Asha's row, then Ravi's. Ravi → Asha updated Ravi's, then Asha's. Each transaction held one row and waited for the other. Postgres broke 57 deadlocks by killing a transaction (`ERROR: deadlock detected`). Each detection takes `deadlock_timeout` (1 s) while holding a pool connection, so the pool drained and another 133 requests timed out waiting 30 s for a connection.

## After: lock in id order, check under the lock

`LedgerService.post` now starts every entry by locking each account with `SELECT … FOR UPDATE`, **lowest account id first**, and checks balances against those locked rows.

- The lock means no other transaction can change the balance between the check and the write, so the check is real.
- The single global order means two transactions that need the same rows queue behind each other instead of each holding half. A deadlock needs a cycle; a fixed order makes a cycle impossible.
- The check lives in the ledger, not in each caller, so top-ups, transfers and future payments all get it.

| Scenario | 201 | 402 | 500 | Sender ends at | Time |
| --- | --- | --- | --- | --- | --- |
| Drain, `CHECK` on | 100 | 100 | 0 | ₹0 | 3.2 s |
| Drain, `CHECK` removed | 100 | 100 | 0 | ₹0 | 3.0 s |
| Opposite directions | 200 | 0 | 0 | both unchanged | 4.0 s |

The `CHECK` constraint stays as a last line of defence against a future bug.

`ConcurrentTransferTest` runs both scenarios 20 times each on every build and asserts the exact counts above, total postings = 0, and that every cached balance equals the sum of its postings.

## Trade-offs

- **Hot accounts serialise.** Every transfer from one wallet waits its turn for that row. That's correct for a wallet. The `SYSTEM_CLEARING` account is the real hot spot: every top-up locks it. Fixes if it ever matters: split it into N shard accounts, or stop caching its balance.
- **Why not `SERIALIZABLE`?** It would catch the lost check too, but by aborting one transaction with a serialization failure that the client must retry. Under contention that means lots of retries. Explicit row locks in a fixed order give the same correctness at `READ COMMITTED` with waiting instead of failing.
- **Why not optimistic locking (`version` column)?** It also works, but under this kind of contention most attempts lose and retry. Pessimistic locks suit a few very hot rows; optimistic suits many rarely-conflicting rows.

## A test-harness gotcha

The first run of the fixed code still had 11 failures, all `Connection refused` on the client. 200 new TCP connections in the same instant overflowed Tomcat's accept backlog (default 100), and Windows refuses rather than queues the overflow. The tests set `server.tomcat.accept-count=1000`. It was a harness limit, not a ledger bug, but it's worth knowing before a load test.
