// Starts, stops and restarts the Cutpay services as plain jars, so a chaos experiment can kill one the way an
// operator (or the kernel) would. Infrastructure comes from `docker compose -f infra/docker-compose.yml up -d`.
//
//   node chaos/stack.mjs up            build nothing; start every service that is not running
//   node chaos/stack.mjs down          stop every service this script started
//   node chaos/stack.mjs stop ledger   kill one
//   node chaos/stack.mjs start ledger  start one
//   node chaos/stack.mjs status
//
// Build the jars first:  cd backend && ./gradlew bootJar
import { spawn, execFileSync } from "node:child_process";
import { existsSync, mkdirSync, openSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const pidFile = join(here, ".pids.json");
const logs = join(root, "logs");

export const PORTS = {
  gateway: 8080,
  identity: 8081,
  payments: 8082,
  "acquirer-sim": 8083,
  ledger: 8084,
  payouts: 8085,
  webhooks: 8086,
  links: 8087,
  billing: 8088,
  "bnpl-sim": 8089,
  bnpl: 8091,
  notifications: 8092,
  "kyc-sim": 8093,
  reporting: 8094,
};

const pids = () => (existsSync(pidFile) ? JSON.parse(readFileSync(pidFile, "utf8")) : {});
const savePids = (value) => writeFileSync(pidFile, JSON.stringify(value, null, 2));

export async function healthy(name) {
  try {
    const response = await fetch(`http://localhost:${PORTS[name]}/actuator/health`, { signal: AbortSignal.timeout(1500) });
    return response.ok;
  } catch {
    return false;
  }
}

export async function waitHealthy(name, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await healthy(name)) return;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`${name} did not become healthy within ${timeoutMs / 1000} s`);
}

export async function start(name) {
  if (await healthy(name)) return;
  const dir = join(root, "backend", "services", name);
  const jar = join(dir, "build", "libs", `${name}-0.1.0-SNAPSHOT.jar`);
  if (!existsSync(jar)) throw new Error(`No jar for ${name}; run ./gradlew bootJar in backend/`);
  mkdirSync(logs, { recursive: true });
  const out = openSync(join(logs, `${name}.out`), "a");
  const child = spawn("java", ["-jar", jar], {
    cwd: dir,
    detached: true,
    stdio: ["ignore", out, out],
    env: { ...process.env, CUTPAY_LOG_DIR: logs },
  });
  child.unref();
  savePids({ ...pids(), [name]: child.pid });
}

export function stop(name) {
  const known = pids();
  const pid = known[name];
  if (pid) {
    try {
      if (process.platform === "win32") execFileSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore" });
      else process.kill(pid, "SIGKILL");
    } catch {
      // already gone
    }
    delete known[name];
    savePids(known);
  }
}

export async function waitDown(name, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!(await healthy(name))) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`${name} is still answering`);
}

export async function up() {
  for (const name of Object.keys(PORTS)) await start(name);
  for (const name of Object.keys(PORTS)) await waitHealthy(name);
}

export function down() {
  for (const name of Object.keys(pids())) stop(name);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [command, name] = process.argv.slice(2);
  if (command === "up") await up();
  else if (command === "down") down();
  else if (command === "start") (await start(name), await waitHealthy(name));
  else if (command === "stop") (stop(name), await waitDown(name));
  else if (command === "status") for (const n of Object.keys(PORTS)) console.log(n.padEnd(14), (await healthy(n)) ? "up" : "down");
  else console.log("usage: node chaos/stack.mjs up|down|status|start <service>|stop <service>");
}
