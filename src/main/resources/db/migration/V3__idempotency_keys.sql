-- One row per Idempotency-Key. The PRIMARY KEY is the real duplicate guarantee: Redis is only a cache.
--
-- Lifecycle: a request claims the key (IN_PROGRESS) in its own small transaction, then the business
-- work and the switch to COMPLETED commit together in one transaction. So an IN_PROGRESS row means
-- the money movement has NOT committed, and a stale one (its lease expired) can be safely taken over.

CREATE TABLE idempotency_keys (
    key            TEXT PRIMARY KEY CHECK (char_length(key) BETWEEN 1 AND 255),
    request_hash   TEXT NOT NULL,                  -- SHA-256 of method, path and canonical JSON body
    status         TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    claim_token    UUID NOT NULL,                  -- fencing token: only the current claimant may complete
    locked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    response_code  INT,
    response_body  TEXT,                           -- TEXT, not JSONB: replays must be byte-for-byte identical
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT completed_has_response CHECK (status = 'IN_PROGRESS' OR response_code IS NOT NULL)
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
