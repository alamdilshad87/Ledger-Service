-- One row per reconciliation run: what was compared, what didn't match. Kept for audit.
CREATE TABLE reconciliation_runs (
    id              UUID PRIMARY KEY,
    business_date   DATE NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('MATCHED', 'MISMATCHED', 'STATEMENT_MISSING')),
    mismatch_count  INT NOT NULL,
    report          JSONB NOT NULL,
    ran_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_reconciliation_runs_ran_at ON reconciliation_runs (ran_at DESC);
