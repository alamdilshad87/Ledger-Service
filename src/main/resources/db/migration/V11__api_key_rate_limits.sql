-- Token bucket per key: refills at rate_per_second, holds at most burst tokens; each request costs one.
ALTER TABLE api_keys ADD COLUMN rate_per_second INT NOT NULL DEFAULT 10 CHECK (rate_per_second > 0);
ALTER TABLE api_keys ADD COLUMN burst INT NOT NULL DEFAULT 20 CHECK (burst > 0);
