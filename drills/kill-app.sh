#!/usr/bin/env bash
# Drill 1: kill the app mid-transfer (docker kill: SIGKILL, no shutdown hooks).
# Expected: in-flight transactions roll back, no half entries; every client retry with the same
# Idempotency-Key succeeds exactly once.
source "$(dirname "$0")/lib.sh"
N=${TRANSFERS:-200}
AMOUNT=100

log "Setting up two wallets"
from=$(new_wallet 10000000); to=$(new_wallet 0)
dir=$(mktemp -d); trap 'rm -rf "$dir"' EXIT
keys=(); for i in $(seq "$N"); do keys+=("$(uuid)"); done

KILL_AFTER=${KILL_AFTER:-$((N / 4))}
log "Firing $N transfers in parallel; killing the app once $KILL_AFTER have committed"
# Launching the clients takes a while (a curl process each), so kill on progress, not on a timer.
{ for k in "${keys[@]}"; do
  api POST /v1/transfers "{\"fromAccountId\":$from,\"toAccountId\":$to,\"amount\":$AMOUNT}" "$k" | cut -c1-3 > "$dir/$k" &
done; wait; } &
until [ "$(sql "SELECT COUNT(*) FROM postings WHERE account_id=$to")" -ge "$KILL_AFTER" ]; do sleep 0.2; done
docker kill ledger-service-app-1 > /dev/null
log "Killed with $(sql "SELECT COUNT(*) FROM postings WHERE account_id=$to") transfers committed"
wait
before=$(cat "$dir"/* | sort | uniq -c | tr '\n' ' ')
log "Status codes seen before/while dying: $before"
log "Entries committed before the kill: $(sql "SELECT COUNT(*) FROM journal_entries WHERE type='TRANSFER' AND id IN (SELECT entry_id FROM postings WHERE account_id=$to)")"

log "Restarting the app"
restart_at=$(now_ms)
docker compose start app > /dev/null
wait_healthy app
log "App healthy again after $(( ($(now_ms) - restart_at) / 1000 )) s"

log "Retrying every key until it gets a final answer (409 = previous attempt's claim still within its 60 s lease)"
retries=0
for k in "${keys[@]}"; do
  for _ in $(seq 100); do
    s=$(api POST /v1/transfers "{\"fromAccountId\":$from,\"toAccountId\":$to,\"amount\":$AMOUNT}" "$k" | cut -c1-3)
    [ "$s" != 409 ] && [ "$s" != 503 ] && [ "$s" != 000 ] && break
    retries=$((retries + 1)); sleep 1
  done
  echo "$s" > "$dir/$k"
done
log "Final status per key: $(cat "$dir"/* | sort | uniq -c | tr '\n' ' ') (retries that waited: $retries)"

entries=$(sql "SELECT COUNT(*) FROM postings WHERE account_id=$to")
dupes=$(sql "SELECT COUNT(*) FROM (SELECT idempotency_key FROM journal_entries WHERE id IN (SELECT entry_id FROM postings WHERE account_id=$to) GROUP BY idempotency_key HAVING COUNT(*) > 1) d")
log "Transfers that reached the recipient: $entries of $N (must be $N)"
log "Keys with more than one entry: $dupes (must be 0)"
log "Recipient balance: $(sql "SELECT balance FROM accounts WHERE id=$to") paise (must be $((N * AMOUNT)))"
ledger_checks
