-- Where a merchant wants its events delivered. The secret signs every delivery (HMAC-SHA256).
-- Stored in plain text because we need it to sign; production would encrypt it at rest (KMS).
CREATE TABLE webhook_subscriptions (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    url          TEXT NOT NULL CHECK (url ~ '^https?://'),
    secret       TEXT NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_subscription UNIQUE (merchant_id, url)
);
