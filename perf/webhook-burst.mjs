// Webhook fan-out burst: a merchant with several endpoints takes a burst of payments, and we measure how long the
// platform needs to deliver every event to every endpoint. The receiver is a small server in this process.
//   node perf/webhook-burst.mjs [payments=300] [endpoints=3]
import { createServer } from "node:http";
import { api, newMerchant, payMany, summarise, sleep } from "../e2e/chaos/lib.mjs";

const payments = Number(process.argv[2] || 300);
const endpointCount = Number(process.argv[3] || 3);

const received = new Map(); // path -> Set of event ids
const arrivals = [];
const server = createServer((req, res) => {
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    try {
      const event = JSON.parse(body);
      if (event.type === "payment_intent.succeeded") {
        const set = received.get(req.url) ?? new Set();
        set.add(event.id);
        received.set(req.url, set);
        arrivals.push(Date.now());
      }
    } catch {
      // not an event
    }
    res.writeHead(200).end("ok");
  });
});
await new Promise((resolve) => server.listen(9099, resolve));

const merchant = await newMerchant();
for (let i = 0; i < endpointCount; i++) {
  const created = await api("POST", "/v1/webhook_endpoints", {
    token: merchant.token,
    body: { url: `http://localhost:9099/hook${i}`, enabled_events: ["payment_intent.succeeded"] },
  });
  if (created.status !== 201) throw new Error(`endpoint failed: ${JSON.stringify(created)}`);
}

const started = Date.now();
const results = await payMany(merchant, payments, 16);
const paidAt = Date.now();
const summary = summarise(results);
console.log(`paid ${summary.outcomes.succeeded ?? 0}/${payments} in ${((paidAt - started) / 1000).toFixed(1)} s`, summary);

const expected = (summary.outcomes.succeeded ?? 0) * endpointCount;
const deadline = Date.now() + 180_000;
const total = () => [...received.values()].reduce((n, set) => n + set.size, 0);
while (total() < expected && Date.now() < deadline) await sleep(500);
const done = Date.now();

console.log(`deliveries ${total()}/${expected}; last arrived ${((done - paidAt) / 1000).toFixed(1)} s after the last payment`);
console.log(`delivery rate ${(total() / ((Math.max(...arrivals) - Math.min(...arrivals)) / 1000 || 1)).toFixed(0)}/s while receiving`);
server.close();
process.exit(total() === expected ? 0 : 1);
