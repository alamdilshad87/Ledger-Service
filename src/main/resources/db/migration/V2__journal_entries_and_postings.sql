-- Every money movement is one journal entry with two or more postings that sum to zero.
-- Both tables are append-only: mistakes are fixed with a new reversing entry, never UPDATE or DELETE.

CREATE TABLE journal_entries (
    id               UUID PRIMARY KEY,
    type             TEXT NOT NULL
                     CHECK (type IN ('TOPUP', 'TRANSFER', 'PAYMENT', 'REFUND', 'REVERSAL')),
    reference_id     UUID REFERENCES journal_entries(id),   -- e.g. the entry a refund reverses
    idempotency_key  TEXT NOT NULL,                         -- deduplication arrives in week 4
    note             TEXT CHECK (char_length(note) <= 140),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id          BIGSERIAL PRIMARY KEY,
    entry_id    UUID NOT NULL REFERENCES journal_entries(id),
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    amount      BIGINT NOT NULL CHECK (amount <> 0),        -- paise; negative = debit, positive = credit
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_postings_entry ON postings (entry_id);
-- Statement pages walk an account's postings newest first by id.
CREATE INDEX idx_postings_account_id_desc ON postings (account_id, id DESC);

CREATE FUNCTION forbid_ledger_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; post a reversing entry instead', TG_TABLE_NAME;
END
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();
