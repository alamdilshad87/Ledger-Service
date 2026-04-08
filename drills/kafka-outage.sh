#!/usr/bin/env bash
# Drill 2: stop Kafka for a while (default 5 minutes) under steady traffic.
# Expected: transfers keep working; the outbox backlog grows, then drains when Kafka returns,
# and every event is published.
source "$(dirname "$0")/lib.sh"
DOWN=${KAFKA_DOWN_SECONDS:-300}
RATE_SLEEP=0.2   # ~5 transfers/s

from=$(new_wallet 100000000); to=$(new_wallet 0)
pending() { sql "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"; }
send_for() {   # send_for SECONDS → appends status codes to /tmp/drill2-status
  local end=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    api POST /v1/transfers "{\"fromAccountId\":$from,\"toAccountId\":$to,\"amount\":100}" "$(uuid)" | cut -c1-3 >> /tmp/drill2-status &
    sleep $RATE_SLEEP
  done
  wait
}
rm -f /tmp/drill2-status
first_seq=$(sql "SELECT COALESCE(MAX(seq),0) FROM outbox_events")

log "Baseline: outbox backlog $(pending)"
docker compose stop kafka > /dev/null
log "Kafka stopped for ${DOWN} s; sending ~5 transfers/s"
for t in $(seq 60 60 "$DOWN"); do send_for 60; log "  +${t}s: backlog $(pending)"; done
rest=$(( DOWN % 60 )); [ "$rest" -gt 0 ] && send_for "$rest"
peak=$(pending)
log "While Kafka was down: $(sort /tmp/drill2-status | uniq -c | tr '\n' ' '); backlog peaked at $peak"

docker compose start kafka > /dev/null
started=$(now_ms)
wait_healthy kafka
log "Kafka healthy again; waiting for the backlog to drain"
until [ "$(pending)" -eq 0 ]; do sleep 1; done
log "Backlog drained $(( ($(now_ms) - started) / 1000 )) s after Kafka was started"
log "Events written during the drill: $(sql "SELECT COUNT(*) FROM outbox_events WHERE seq > $first_seq"), unpublished: $(sql "SELECT COUNT(*) FROM outbox_events WHERE seq > $first_seq AND published_at IS NULL")"
log "Longest commit→publish lag: $(sql "SELECT round(EXTRACT(EPOCH FROM MAX(published_at - created_at))) FROM outbox_events WHERE seq > $first_seq") s"
rm -f /tmp/drill2-status
ledger_checks
