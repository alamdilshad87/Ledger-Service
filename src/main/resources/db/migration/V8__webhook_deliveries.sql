-- One row per (event, subscription). The UNIQUE constraint is the dedupe: Kafka delivers events at
-- least once, and a redelivered event hits ON CONFLICT DO NOTHING instead of a second webhook.
CREATE TABLE webhook_deliveries (
    id                UUID PRIMARY KEY,
    event_id          UUID NOT NULL,
    event_type        TEXT NOT NULL,
    subscription_id   UUID NOT NULL REFERENCES webhook_subscriptions(id),
    payload           TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts          INT NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),   -- also used as a lease while a send is in flight
    last_status_code  INT,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at      TIMESTAMPTZ,
    CONSTRAINT uq_delivery_per_event UNIQUE (event_id, subscription_id)
);

CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';

-- Deliveries that used up every retry. Someone has to look at these (wrong URL, merchant down for hours).
CREATE TABLE webhook_dead_letters (
    delivery_id       UUID PRIMARY KEY REFERENCES webhook_deliveries(id),
    event_id          UUID NOT NULL,
    subscription_id   UUID NOT NULL REFERENCES webhook_subscriptions(id),
    payload           TEXT NOT NULL,
    attempts          INT NOT NULL,
    last_status_code  INT,
    last_error        TEXT,
    dead_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
