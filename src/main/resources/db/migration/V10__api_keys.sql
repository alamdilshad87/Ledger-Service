-- API keys identify the calling application (a merchant's or partner's backend), not an end user.
-- Only a SHA-256 hash is stored: keys are 256-bit random values, so a fast hash is enough (bcrypt is for
-- low-entropy passwords) and a database leak doesn't reveal usable keys.
CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    key_prefix  TEXT NOT NULL,                     -- first characters, safe to show in logs and UIs
    key_hash    TEXT NOT NULL UNIQUE,              -- hex SHA-256 of the full key
    role        TEXT NOT NULL CHECK (role IN ('CLIENT', 'ADMIN')),
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);
