#!/bin/sh
# Runs the load scenarios one after another inside the k6 container and collects their numbers in
# /results/SUMMARY.md. Started by:  docker compose run --rm loadtest
# Pick scenarios with SCENARIOS="steady ramp" (default: all four).
set -u
cd /scripts
mkdir -p /results
SCENARIOS=${SCENARIOS:-"steady hot-merchant retry-storm ramp"}

{
  echo "# Load test results"
  echo
  echo "Run at $(date -u '+%Y-%m-%d %H:%M UTC') against ${BASE_URL}. Hardware and setup: see docs/results.md."
  echo
} > /results/SUMMARY.md

status=0
for scenario in $SCENARIOS; do
  echo
  echo "=================== ${scenario} ==================="
  if ! k6 run --quiet "${scenario}.js"; then
    status=1
    echo "Scenario ${scenario} failed" >&2
  fi
  [ -f "/results/${scenario}.md" ] && { cat "/results/${scenario}.md"; echo; } >> /results/SUMMARY.md
done

echo
echo "Results written to load/results/SUMMARY.md"
exit $status
