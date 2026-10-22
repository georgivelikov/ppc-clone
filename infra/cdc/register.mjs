// Registers (or replaces) the Debezium connector that publishes one service's outbox table:
//   node infra/cdc/register.mjs payments          then run that service with  CUTPAY_OUTBOX_RELAY=cdc
//   node infra/cdc/register.mjs payments --remove    back to the polling relay
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const CONNECT = process.env.CONNECT_URL ?? "http://localhost:8095";
const service = process.argv[2];
if (!service) throw new Error("usage: register.mjs <service> [--remove]");
const name = `${service}-outbox`;

// Removing a connector properly takes three things: stop it, forget where it had read up to (Connect keeps that
// separately, and a connector that later starts from an old position with no slot behind it fails), and drop the
// replication slot (which otherwise makes Postgres keep every log segment since, until the disk is full).
async function remove() {
  await fetch(`${CONNECT}/connectors/${name}/stop`, { method: "PUT" });
  await new Promise((resolve) => setTimeout(resolve, 1500));
  await fetch(`${CONNECT}/connectors/${name}/offsets`, { method: "DELETE" });
  await fetch(`${CONNECT}/connectors/${name}`, { method: "DELETE" });
  try {
    execFileSync("docker", ["exec", "cutpay-postgres-1", "psql", "-U", "cutpay", "-d", service, "-tAc",
      `select pg_drop_replication_slot('debezium_${service}') from pg_replication_slots where slot_name = 'debezium_${service}'`],
    { stdio: "ignore" });
  } catch {
    // no slot
  }
}
await remove();
if (process.argv.includes("--remove")) {
  console.log(`removed ${name} and its replication slot`);
  process.exit(0);
}
// The heartbeat topic (keeps the slot moving on a quiet database) must exist: topics are not created on demand here.
try {
  execFileSync("docker", ["exec", "cutpay-kafka-1", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092",
    "--create", "--if-not-exists", "--topic", `__debezium-heartbeat.cdc_${service}`, "--partitions", "1"],
  { stdio: "ignore", env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
} catch {
  console.warn("could not create the heartbeat topic; create __debezium-heartbeat.cdc_" + service + " by hand");
}
const template = readFileSync(new URL("./outbox-connector.template.json", import.meta.url), "utf8");
const body = template.replaceAll("__SERVICE__", service);
const created = await fetch(`${CONNECT}/connectors`, { method: "POST", headers: { "Content-Type": "application/json" }, body });
if (!created.ok) throw new Error(`could not register: ${created.status} ${await created.text()}`);
for (let i = 0; i < 30; i++) {
  await new Promise((resolve) => setTimeout(resolve, 1000));
  const status = await (await fetch(`${CONNECT}/connectors/${name}/status`)).json();
  const task = status.tasks?.[0]?.state;
  if (status.connector?.state === "RUNNING" && task === "RUNNING") {
    console.log(`${name} is running`);
    process.exit(0);
  }
  if (task === "FAILED") throw new Error(`task failed: ${status.tasks[0].trace?.slice(0, 400)}`);
}
throw new Error("connector did not reach RUNNING");
