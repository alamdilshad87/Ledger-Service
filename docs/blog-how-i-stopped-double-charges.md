# How I stopped double charges in my payments ledger

A user taps "Pay ₹500". The request reaches the server, the money moves, and then the phone's connection drops before the response gets back. The app shows a spinner, gives up, and retries. If the server treats that retry as a new payment, the user has paid ₹1,000.

Over eight weeks I built a wallet and payments ledger in Java and Spring Boot, mostly to learn how to make that not happen. This is what worked, and what I measured.

## The usual fix, and why it isn't enough

The standard answer is an **idempotency key**: the client generates a random id per payment and sends it with every retry. The server remembers the key, and if it has seen it before, it replays the original answer instead of moving money again.

The obvious implementation looks fine:

```
if (store.has(key)) return store.get(key)
result = moveMoney()
store.put(key, result)
```

It breaks in two places.

1. **Two copies at once.** A flaky network often delivers the retry while the original is still running. Both check the store, both see nothing, and both move money. The check and the write have to be one atomic step.
2. **A crash in the middle.** If the server moves money and dies before `store.put`, the key looks unused, so the next retry charges again. If it records the key first and dies before moving money, the key looks used but nothing happened. The key and the money have to commit together, or not at all.

Putting the key in Redis with `SETNX` fixes the first problem but not the second. Redis and Postgres can't commit atomically, so there's always a window where one has the write and the other doesn't.

## What I did instead

The key lives in **Postgres, in the same database as the money**.

1. **Claim the key** with `INSERT … ON CONFLICT DO NOTHING`. The primary key guarantees exactly one of any number of concurrent requests wins. Every other copy reads the existing row:
   - it's finished: replay the stored response;
   - it's still running: `409 REQUEST_IN_PROGRESS`, retry shortly;
   - same key with a different body: `422`, because that's a client bug, not a retry.
2. **Move the money and mark the key COMPLETED in one transaction**, with the response stored on the key row. Either both commit or neither does.

That second rule is what makes crashes boring. If a key is still `IN_PROGRESS`, it means no money moved. The transaction never committed, so Postgres rolled it back with the dead process. After a 60-second lease, a retry with the same key simply takes over and does the work.

One more subtlety: what if the "dead" request was only slow, and wakes up after its lease was taken over? Each claim gets a random **claim token**, and the final update says `WHERE claim_token = mine`. The old request's update matches zero rows, so it rolls itself back. Only one of them can ever commit.

Redis is still there, but only as a **cache of finished responses**. A finished result never changes, so the cache can be missing but never wrong. When I stopped Redis entirely, every request was still correct, just slower.

## Testing it like I didn't trust it

Unit tests prove the logic. I wanted to see it hold under abuse, so I wrote a [k6](https://k6.io) scenario that behaves like the worst mobile client I could imagine. For every transfer it:

- sends **three identical requests at the same instant**, all with the same key;
- then sends a fourth, late retry.

At 40 transfers per second for a minute, that's 9,600 requests for 2,400 transfers. I didn't trust the client's view, so I compared it with the server's own counter of journal entries created.

| | |
| --- | --- |
| Transfers that succeeded (by key) | 2,400 |
| Journal entries the server created | 2,400 |
| **Duplicates** | **0** |
| Concurrent copies told "in progress" | 4,793 |
| Replays | 2,407 |
| Replay latency, p50 | 2 ms (vs 11 ms for the real request) |

Then I broke things on purpose:

- **`docker kill` on the app with 200 transfers in flight.** 86 had committed; 114 lost their connection mid-request. After a restart I retried every key, and got exactly 200 transfers and exactly ₹200 at the recipient, with no key producing two entries.
- **Redis stopped.** Every transfer and every replay was still correct, with replays served from Postgres instead. It cost about 0.8 s per request (each Redis call waits out a 250 ms timeout), which is on my list to fix with a circuit breaker.

After every drill, the same three checks held:

- the sum of all postings is zero;
- every cached balance equals its postings;
- no journal entry is half-written.

## What it costs

Nothing is free.

- **The database connection is held for the whole request**, because the transaction spans it. In my ramp test the service tops out around 340 req/s on a laptop, and the limit is the connection pool, not the CPU. Holding connections for less time is the next optimisation.
- **Clients have to cooperate.** They must send a key, reuse it on retry, and treat 409 as "try again". The API rejects money-moving requests without one.
- **Declines are remembered too.** A transfer that failed for insufficient funds stays failed under that key, even after a top-up. That surprises people, but it's what a client that already showed the user "declined" expects.

## The takeaway

Idempotency isn't a cache lookup in front of the payment. It's a promise that has to be **in the same transaction as the money**. Once the key and the ledger commit together, the scary cases (concurrent duplicates, crashes, slow requests that come back from the dead) mostly turn into plain database guarantees you already trust.

The code, the load tests and the failure drills are all in the repo, and `docker compose run --rm loadtest` reproduces the numbers above.
