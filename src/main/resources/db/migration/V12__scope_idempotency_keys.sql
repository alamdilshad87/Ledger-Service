-- Idempotency keys are now stored as "<api key id>:<client's key>", so two clients that happen to pick
-- the same key can't see each other's responses. That adds 37 characters to the 255 a client may send.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_key_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_key_check CHECK (char_length(key) BETWEEN 1 AND 300);
