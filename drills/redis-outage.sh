#!/usr/bin/env bash
# Drill 4: stop Redis. Expected: payments keep working; idempotency falls back to Postgres (slower,
# still correct); the rate limiter fails open.
source "$(dirname "$0")/lib.sh"
N=${TRANSFERS:-100}

replays() { curl -s "$BASE/actuator/prometheus" | grep "^ledger_idempotency_replays_total{.*source=\"$1\"" | awk '{s+=$2} END {print s+0}'; }
timed_round() {   # timed_round LABEL → N transfers + a retry of each; prints statuses and latency
  local from=$1 to=$2 label=$3 start statuses="" k
  start=$(now_ms)
  for i in $(seq "$N"); do
    k=$(uuid)
    statuses+="$(api POST /v1/transfers "{\"fromAccountId\":$from,\"toAccountId\":$to,\"amount\":100}" "$k" | cut -c1-3) "
    statuses+="$(api POST /v1/transfers "{\"fromAccountId\":$from,\"toAccountId\":$to,\"amount\":100}" "$k" | cut -c1-3)r "
  done
  log "$label: $(echo "$statuses" | tr ' ' '\n' | grep -v '^$' | sort | uniq -c | tr '\n' ' ') in $(( $(now_ms) - start )) ms for $((N * 2)) requests"
}

from=$(new_wallet 100000000); to=$(new_wallet 0)
r0=$(replays redis); p0=$(replays postgres)
timed_round "$from" "$to" "Redis up  "
r1=$(replays redis); p1=$(replays postgres)
log "  replays served from Redis: $((r1 - r0)), from Postgres: $((p1 - p0))"

docker compose stop redis > /dev/null
log "Redis stopped"
timed_round "$from" "$to" "Redis down"
r2=$(replays redis); p2=$(replays postgres)
log "  replays served from Redis: $((r2 - r1)), from Postgres: $((p2 - p1))"
docker compose logs --since 2m app 2>/dev/null | grep -c 'Rate limiter unavailable' | xargs -I{} echo "  rate limiter failed open {} times"

docker compose start redis > /dev/null
wait_healthy redis
log "Redis back. Recipient got $(sql "SELECT COUNT(*) FROM postings WHERE account_id=$to") transfers (must be $((N * 2))); balance $(sql "SELECT balance FROM accounts WHERE id=$to") (must be $((N * 2 * 100)))"
ledger_checks
