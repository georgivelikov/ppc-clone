// Merchant API reads at a fixed rate (default 1,000 a second for 60 s) through the gateway: payment list, one payment,
// the stats the dashboard shows. The merchant gets some payments first so the lists are not empty.
//   docker run --rm -v "$PWD/perf/k6:/s" -e RATE=1000 grafana/k6 run /s/reads.js
import http from "k6/http";
import { check } from "k6";
import { GATEWAY, auth, merchant, payOnce } from "./common.js";

const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || "60s";
const SEED = Number(__ENV.SEED || 300);

export const options = {
  scenarios: {
    reads: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.floor(RATE / 4)),
      maxVUs: RATE * 2,
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<200"],
    http_req_failed: ["rate<0.01"],
  },
};

export function setup() {
  const token = merchant();
  for (let i = 0; i < SEED; i++) payOnce(token);
  const list = http.get(`${GATEWAY}/v1/payment_intents?limit=50`, auth(token));
  return { token, ids: list.json("data").map((p) => p.id) };
}

export default function (data) {
  const roll = Math.random();
  let response;
  if (roll < 0.5) {
    response = http.get(`${GATEWAY}/v1/payment_intents?limit=20`, { ...auth(data.token), tags: { call: "list" } });
  } else if (roll < 0.9) {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    response = http.get(`${GATEWAY}/v1/payment_intents/${id}`, { ...auth(data.token), tags: { call: "get" } });
  } else {
    response = http.get(`${GATEWAY}/v1/payment_stats`, { ...auth(data.token), tags: { call: "stats" } });
  }
  check(response, { ok: (r) => r.status === 200 });
}
