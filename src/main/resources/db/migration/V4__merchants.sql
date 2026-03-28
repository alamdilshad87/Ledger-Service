CREATE TABLE merchants (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Each merchant has exactly one MERCHANT account that payments are credited to.
ALTER TABLE accounts ADD COLUMN merchant_id UUID REFERENCES merchants(id);
ALTER TABLE accounts ADD CONSTRAINT merchant_account_has_merchant
    CHECK ((type = 'MERCHANT') = (merchant_id IS NOT NULL));
CREATE UNIQUE INDEX uq_accounts_merchant ON accounts (merchant_id) WHERE merchant_id IS NOT NULL;
