#!/usr/bin/env bash
# Restarts the payments service with the given environment, warms it up, then runs the checkout scenario.
#   perf/variant.sh <rate> [VAR=value ...]
# Run from the repository root with the jar stack up (see docs/perf/README.md).
set -u
rate=$1; shift
(cd e2e && node chaos/stack.mjs stop payments >/dev/null && env "$@" node chaos/stack.mjs start payments >/dev/null)
run() {
  MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/perf/k6:/s" -e RATE="$1" -e DURATION="$2" grafana/k6 run --quiet --no-color /s/checkout.js 2>&1
}
run 40 20s >/dev/null
run "$rate" 30s | grep -E "step:confirm \}\.\.|dropped_iterations|^ +iterations" | sed 's/min=[^ ]* //'
curl -s localhost:8082/actuator/prometheus | grep -E "^hikaricp_connections_(max|usage_seconds_max)" | sed 's/{.*}//'
