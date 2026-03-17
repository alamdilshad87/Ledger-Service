-- All money is stored in paise (1 INR = 100 paise) as BIGINT. Never floats.

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    email       TEXT UNIQUE NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    UUID REFERENCES users(id),           -- NULL for system accounts
    type        TEXT NOT NULL
                CHECK (type IN ('USER_WALLET', 'SYSTEM_CLEARING', 'MERCHANT')),
    currency    CHAR(3) NOT NULL DEFAULT 'INR',
    balance     BIGINT NOT NULL DEFAULT 0,           -- paise, cached from postings
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT non_negative_wallet CHECK (type <> 'USER_WALLET' OR balance >= 0)
);

CREATE INDEX idx_accounts_owner ON accounts (owner_id);

-- The source of every top-up. Its balance goes negative as money enters the system.
INSERT INTO accounts (owner_id, type, currency) VALUES (NULL, 'SYSTEM_CLEARING', 'INR');
