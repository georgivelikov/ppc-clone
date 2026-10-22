// How long does an event wait between being committed (outbox.created_at) and being on Kafka (the record's timestamp)?
// This is the part the outbox relay and Debezium actually differ in; end to end the consumers hide it.
//
//   node perf/relay-lag.mjs [payments=150] [parallel=1]     makes payments, then reads the lag of the last events
import { execFileSync } from "node:child_process";
import { newMerchant, payMany, sleep } from "../e2e/chaos/lib.mjs";

const payments = Number(process.argv[2] || 150);
const parallel = Number(process.argv[3] || 1);
const KAFKA = ["exec", "cutpay-kafka-1", "/opt/kafka/bin"];
const run = (...args) => execFileSync("docker", args, { encoding: "utf8", env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
const quantile = (values, q) => values.slice().sort((a, b) => a - b)[Math.min(values.length - 1, Math.floor(values.length * q))];

const merchant = await newMerchant();
await payMany(merchant, 10, 2); // warm up
await sleep(2000);
const started = Date.now();
await payMany(merchant, payments, parallel);
await sleep(4000);
const seconds = (Date.now() - started) / 1000;

// When each event was committed.
const rows = run("exec", "cutpay-postgres-1", "psql", "-U", "cutpay", "-d", "payments", "-tAc",
  "select event_id || '|' || (extract(epoch from created_at) * 1000)::bigint from outbox where event_type = 'PaymentSucceeded' order by id desc limit " + payments)
  .trim().split("\n").filter(Boolean);
const committed = new Map(rows.map((row) => row.split("|")).map(([id, at]) => [id, Number(at)]));

// When each one reached Kafka: read the tail of every partition with timestamps and headers.
const ends = run(...KAFKA.slice(0, 2), `${KAFKA[2]}/kafka-get-offsets.sh`, "--bootstrap-server", "localhost:9092", "--topic", "payment.events", "--time", "-1")
  .trim().split("\n").map((line) => line.split(":")).map(([, partition, offset]) => [Number(partition), Number(offset)]);
const lags = [];
for (const [partition, end] of ends) {
  if (end === 0) continue;
  const out = run(...KAFKA.slice(0, 2), `${KAFKA[2]}/kafka-console-consumer.sh`, "--bootstrap-server", "localhost:9092", "--topic", "payment.events",
    "--partition", String(partition), "--offset", String(Math.max(0, end - 600)), "--timeout-ms", "6000",
    "--property", "print.timestamp=true", "--property", "print.headers=true", "--property", "print.value=false");
  for (const line of out.split("\n")) {
    const match = /CreateTime:(\d+)\s.*event_id:(evt_[0-9a-f-]+)/.exec(line);
    if (match && committed.has(match[2])) lags.push(Number(match[1]) - committed.get(match[2]));
  }
}
if (lags.length === 0) throw new Error("no events matched; is the payments outbox the source of payment.events?");
console.log(`${lags.length} PaymentSucceeded events, ${payments} payments at ${parallel} at a time over ${seconds.toFixed(0)} s:` +
  ` commit to Kafka p50 ${quantile(lags, 0.5)} ms, p95 ${quantile(lags, 0.95)} ms, max ${Math.max(...lags)} ms`);
