#!/usr/bin/env bash
# Drill 5: corrupt one balance by hand. Expected: reconciliation flags exactly that account.
source "$(dirname "$0")/lib.sh"
today=$(TZ=Asia/Kolkata date +%F)

wallet=$(new_wallet 20000)
log "Wallet $wallet has $(sql "SELECT balance FROM accounts WHERE id=$wallet") paise; adding ₹1 behind the ledger's back"
sql "UPDATE accounts SET balance = balance + 100 WHERE id = $wallet" > /dev/null

api POST "/v1/admin/fake-bank/statements?date=$today" > /dev/null
report=$(api POST "/v1/admin/reconciliation/runs?date=$today")
log "Reconciliation: $(echo "$report" | json_field status)"
echo "$report" | grep -o '{"type":"BALANCE_DRIFT"[^}]*}' | sed 's/^/    /'

sql "UPDATE accounts SET balance = balance - 100 WHERE id = $wallet" > /dev/null
log "Restored; re-running: $(api POST "/v1/admin/reconciliation/runs?date=$today" | json_field status)"
