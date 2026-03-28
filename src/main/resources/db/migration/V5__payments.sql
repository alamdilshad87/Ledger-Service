-- A payment is a PAYMENT journal entry (wallet → merchant) plus this row, which tracks how much of it
-- has been refunded. The row is what refunds lock, so concurrent refunds can't exceed the payment.
CREATE TABLE payments (
    id                   UUID PRIMARY KEY REFERENCES journal_entries(id),
    wallet_account_id    BIGINT NOT NULL REFERENCES accounts(id),
    merchant_id          UUID NOT NULL REFERENCES merchants(id),
    merchant_account_id  BIGINT NOT NULL REFERENCES accounts(id),
    amount               BIGINT NOT NULL CHECK (amount > 0),     -- paise
    refunded_amount      BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT refund_within_payment CHECK (refunded_amount BETWEEN 0 AND amount)
);

CREATE INDEX idx_payments_wallet ON payments (wallet_account_id, created_at DESC);
CREATE INDEX idx_payments_merchant ON payments (merchant_id, created_at DESC);
