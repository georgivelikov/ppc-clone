// Checkout confirms at a fixed arrival rate (default 200 payments a second for 60 s). Each iteration is a whole
// payment: create the session, tokenize the card, confirm. The confirm is the number that matters.
//   docker run --rm -v "$PWD/perf/k6:/s" -e RATE=200 grafana/k6 run /s/checkout.js
import { Trend } from "k6/metrics";
import { merchant, payOnce } from "./common.js";

const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || "60s";

export const options = {
  scenarios: {
    checkout: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    "http_req_duration{step:confirm}": ["p(95)<500"],
    "http_req_failed{step:confirm}": ["rate<0.01"],
    checks: ["rate>0.99"],
  },
};

export function setup() {
  return { token: merchant() };
}

export default function (data) {
  payOnce(data.token);
}
