// How long does an event take from "the payment succeeded" to "a merchant's server got the webhook", and how fast can a
// burst drain? Run once with the polling relay and once with Debezium (docs/adr/0038-change-data-capture.md); the only
// difference between the two runs is who moves the outbox rows to Kafka.
//
//   node perf/outbox-latency.mjs [sequential=60] [burst=300]
import { createServer } from "node:http";
import { api, newMerchant, pay, payMany, sleep } from "../e2e/chaos/lib.mjs";

const sequential = Number(process.argv[2] || 60);
const burst = Number(process.argv[3] || 300);

const arrivals = new Map(); // payment id -> arrival time
const server = createServer((req, res) => {
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    try {
      const event = JSON.parse(body);
      if (event.type === "payment_intent.succeeded") arrivals.set(event.data.object.id, Date.now());
    } catch {
      // not an event
    }
    res.writeHead(200).end("ok");
  });
});
await new Promise((resolve) => server.listen(9098, resolve));

const merchant = await newMerchant();
const endpoint = await api("POST", "/v1/webhook_endpoints", {
  token: merchant.token,
  body: { url: "http://localhost:9098/hook", enabled_events: ["payment_intent.succeeded"] },
});
if (endpoint.status !== 201) throw new Error(`endpoint failed: ${JSON.stringify(endpoint)}`);

const quantile = (values, q) => values.slice().sort((a, b) => a - b)[Math.min(values.length - 1, Math.floor(values.length * q))];
const waitFor = async (ids, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  while (ids.some((id) => !arrivals.has(id)) && Date.now() < deadline) await sleep(50);
};

// Warm up so neither mode is measured cold.
await payMany(merchant, 10, 2);
await sleep(3000);

// 1. One payment at a time: the latency a single customer's event sees.
const latencies = [];
for (let i = 0; i < sequential; i++) {
  const result = await pay(merchant);
  const done = Date.now();
  await waitFor([result.paymentId], 15_000);
  if (arrivals.has(result.paymentId)) latencies.push(arrivals.get(result.paymentId) - done);
  await sleep(100);
}
console.log(`one at a time (${latencies.length}/${sequential} delivered): p50 ${quantile(latencies, 0.5)} ms, p95 ${quantile(latencies, 0.95)} ms, max ${Math.max(...latencies)} ms`);

// 2. A burst: how long until the last event is out.
const started = Date.now();
const results = await payMany(merchant, burst, 16);
const paidAt = Date.now();
const ids = results.filter((r) => r.paymentId).map((r) => r.paymentId);
await waitFor(ids, 120_000);
const delivered = ids.filter((id) => arrivals.has(id)).length;
const last = Math.max(...ids.filter((id) => arrivals.has(id)).map((id) => arrivals.get(id)));
console.log(`burst of ${burst}: paid in ${((paidAt - started) / 1000).toFixed(1)} s, ${delivered}/${ids.length} delivered, last one ${((last - paidAt) / 1000).toFixed(1)} s after the last payment`);
server.close();
process.exit(delivered === ids.length ? 0 : 1);
