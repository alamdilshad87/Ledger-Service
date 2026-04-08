#!/usr/bin/env bash
# Shared helpers for the failure drills. Needs: docker compose stack running, curl, bash.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite container paths like /opt/...

cd "$(dirname "$0")/.."
BASE=${BASE_URL:-http://localhost:8080}
KEY=${LEDGER_ADMIN_API_KEY:-lk_dev_admin_key_for_local_use_only_0000000000}

uuid() {   # random v4-style uuid from /dev/urandom (works in Git Bash, no uuidgen needed)
  local h; h=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  echo "${h:0:8}-${h:8:4}-4${h:13:3}-a${h:17:3}-${h:20:12}"
}
now_ms() { date +%s%3N; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# api METHOD PATH [JSON] [IDEMPOTENCY_KEY] → prints "<status> <body>"
api() {
  local method=$1 path=$2 body=${3:-} idem=${4:-}
  # body on stdout, status appended on its own line (no temp files: MSYS_NO_PATHCONV breaks /tmp paths for curl.exe)
  local args=(-s -w '\n%{http_code}' -X "$method" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json')
  [ -n "$idem" ] && args+=(-H "Idempotency-Key: $idem")
  [ -n "$body" ] && args+=(-d "$body")
  local out
  out=$(curl "${args[@]}" "$BASE$path") || true   # on a connection failure -w still prints 000
  echo "${out##*$'\n'} ${out%$'\n'*}"
}

# json_field NAME: reads "<status> <body>" and prints that field of the body
json_field() { cut -d' ' -f2- | sed -E "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/"; }

sql() { docker compose exec -T postgres psql -U ledger -d ledger -tAc "$1"; }

new_wallet() {   # new_wallet PAISE → prints wallet id
  local wallet
  wallet=$(api POST /v1/users "{\"email\":\"drill-$(uuid)@example.com\"}" | json_field walletAccountId)
  api POST /v1/topups "{\"accountId\":$wallet,\"amount\":$1}" "$(uuid)" > /dev/null
  echo "$wallet"
}

wait_healthy() {   # wait_healthy SERVICE
  for _ in $(seq 120); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "ledger-service-$1-1" 2>/dev/null)" = healthy ] && return 0
    sleep 1
  done
  echo "$1 did not become healthy" >&2; return 1
}

ledger_checks() {   # prints the invariants that must always hold
  log "postings sum (must be 0):            $(sql 'SELECT COALESCE(SUM(amount),0) FROM postings')"
  log "accounts with drifted balance (0):   $(sql 'SELECT COUNT(*) FROM accounts a WHERE balance <> (SELECT COALESCE(SUM(amount),0) FROM postings p WHERE p.account_id=a.id)')"
  log "entries without postings (0):        $(sql 'SELECT COUNT(*) FROM journal_entries j WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.entry_id=j.id)')"
}
