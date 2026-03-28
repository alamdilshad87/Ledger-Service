# Idempotency: retries never charge twice

A phone on a flaky network sends "pay ₹500", the response times out, and the app retries. Did the first one go through? With an `Idempotency-Key` the client doesn't need to know: every retry with the same key gets the same result, and the money moves at most once.

## The contract

Every `POST` that moves money (`/v1/topups`, `/v1/transfers`) must send `Idempotency-Key: <uuid>`.

| Situation | Response |
| --- | --- |
| New key | Runs the request once |
| Same key, same request, finished | The stored response, byte for byte, with `Idempotent-Replayed: true` |
| Same key, same request, still running | `409 REQUEST_IN_PROGRESS`: retry shortly |
| Same key, different request | `422 IDEMPOTENCY_KEY_REUSED`: a client bug |
| No key / key over 255 chars | `400 MISSING_IDEMPOTENCY_KEY` / `400 INVALID_IDEMPOTENCY_KEY` |

"Same request" means the same method, path and JSON body, compared by a SHA-256 of the body re-serialised with sorted keys. Key order and whitespace don't matter; any value change does.

Which outcomes are remembered:

- **2xx:** always.
- **402, 404, 422:** remembered. They are the answer to that operation: a transfer declined for insufficient funds stays declined under that key, even after a top-up. A new attempt needs a new key.
- **400 and 5xx:** not remembered. Nothing happened, so the key is released and the client can fix the request or retry with the same key.

Keys are forgotten 24–25 h after first use (hourly cleanup job in Postgres, TTL in Redis). After that, the same key is a new request.

## How it works

```
request ──► Redis has a finished result?  ── yes ──► replay (or 422 if the body differs)
               │ no / Redis down
               ▼
            INSERT … ON CONFLICT claim in Postgres  ── someone else holds it ──► replay / 409 / 422
               │ claimed (status IN_PROGRESS, claim_token)
               ▼
   ┌──────── one transaction ─────────────────────────────┐
   │ lock accounts → check balances → entry + postings     │
   │ UPDATE idempotency_keys SET status = 'COMPLETED',     │
   │        response = … WHERE claim_token = mine          │
   └───────────────────────────── commit ─────────────────┘
               ▼
            copy the result to Redis, send the response
```

`IdempotencyFilter` wraps the controller call in a transaction, buffers the response, and commits the money movement and the COMPLETED status together.

### Why this order is safe

- **The Postgres primary key is the guarantee.** `INSERT … ON CONFLICT DO NOTHING` lets exactly one of N concurrent requests claim a key. `ConcurrentDuplicateTest` sends one transfer 100 times in parallel (10 repetitions): one journal entry, money moved once, every other copy got a 409 or a replay of the same entry id.
- **Money and COMPLETED commit together.** There is never a moment where the transfer committed but the key still looks unfinished, or the reverse. So an `IN_PROGRESS` row always means *no money moved yet*.
- **That makes crash recovery simple.** If the server dies mid-request, the row stays `IN_PROGRESS` and the transaction is rolled back. After a 60 s lease, a retry with the same key and body takes the claim over and runs the request.
- **The claim token fences out the old holder.** If a slow original request is still alive after its lease was taken over, its final `UPDATE … WHERE claim_token = mine` matches no row. It then rolls back its own transfer and returns 409. Only one of them can ever commit.
- **Redis only caches finished results**, written after the Postgres commit. A finished result never changes, so the cache can be missing but never wrong. Every Redis call has a 250 ms timeout and failures are ignored (`RedisDownTest` runs with Redis pointed at a dead port and still catches the duplicate).

## Trade-offs

- **The DB connection is held for the whole request**, including JSON serialisation, because the transaction spans the controller. Fine at this scale; a busier service would complete the key from inside the service layer instead.
- **The 60 s lease must be longer than any real request.** Requests here take milliseconds; anything still running after a minute has a bigger problem. The claim token makes a too-short lease safe anyway: worst case a request is refused, never doubled.
- **Keys are global.** With no auth yet, two clients choosing the same key would collide. Week 7 adds API keys; keys should then be scoped per client.
- **Remembering 402** is deliberate but debatable: some APIs only store successes. Storing the decline means a retry can't silently succeed later, which is what a client that "already showed the user a decline" expects.
