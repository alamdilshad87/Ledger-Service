-- Transactional outbox: an event row is inserted in the same transaction as the money movement it
-- describes, so the event exists if and only if the movement committed. A relay publishes unpublished
-- rows to Kafka afterwards and stamps published_at.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,                 -- event id; consumers dedupe on it
    seq             BIGSERIAL NOT NULL UNIQUE,        -- publish order
    aggregate_type  TEXT NOT NULL,                    -- e.g. payment, transfer
    aggregate_id    UUID NOT NULL,                    -- Kafka key: events for one aggregate stay in order
    event_type      TEXT NOT NULL,                    -- e.g. payment.completed
    payload         JSONB NOT NULL,                   -- the full event envelope as published
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ                       -- NULL until the relay has sent it
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
