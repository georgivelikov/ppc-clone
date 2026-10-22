# Canary

One browser journey - sign up, take a card payment on the hosted checkout, see it succeed in the dashboard - run on a schedule against a live environment. Internal metrics say the services are up; the canary says a customer can pay.

```
DASHBOARD_URL=https://dashboard.example.com PUSHGATEWAY_URL=http://pushgateway:9091 \
  npx playwright test -c canary/playwright.config.ts
```

- Exits non-zero when the journey fails or when Pay-to-success takes more than 8 s (a budget, not only a pass/fail).
- Writes `canary-result.json` (which stage failed, how long it took).
- With `PUSHGATEWAY_URL` it also pushes `cutpay_canary_success`, `cutpay_canary_duration_seconds`, `cutpay_canary_payment_seconds` and `cutpay_canary_last_run_timestamp_seconds`, which the alert rules in `infra/observability/prometheus/rules/slo.yml` use (`CanaryFailing`, `CanaryStale`). Pushgateway is not part of the local compose stack; point it at one.
- Every run signs up a fresh test-mode merchant, so it leaves test data behind. Run it only against test mode.
- The same spec runs inside the normal suite (`npx playwright test --grep @canary`).
- Schedule it from outside the thing it watches: a Kubernetes CronJob in another cluster, or a hosted scheduler. A canary that runs on the platform dies with it.
