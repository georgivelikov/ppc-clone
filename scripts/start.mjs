// Starts the whole local system: infrastructure (docker compose), the 14 backend services as jars, and the
// web apps (Vite dev servers).
//
//   node scripts/start.mjs                everything
//   node scripts/start.mjs --no-web       infrastructure and backend only
//   node scripts/start.mjs --no-backend   infrastructure and web apps only
//   node scripts/start.mjs --build        build the jars even if they exist
//
// Stop with: node scripts/stop.mjs
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, openSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PORTS, up } from "../e2e/chaos/stack.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const logs = join(root, "logs");
const webPidFile = join(root, "scripts", ".web-pids.json");
const args = new Set(process.argv.slice(2));
const isWindows = process.platform === "win32";

const WEB = {
  dashboard: { filter: "@cutpay/dashboard", port: 5173 },
  checkout: { filter: "@cutpay/checkout", port: 5174 },
  "demo-shop": { filter: "@cutpay/demo-shop", port: 5175 },
  site: { filter: "@cutpay/site", port: 5176 },
  backoffice: { filter: "@cutpay/backoffice", port: 5177 },
};

function run(command, commandArgs, cwd) {
  const result = spawnSync(command, commandArgs, { cwd, stdio: "inherit", shell: isWindows });
  if (result.status !== 0) throw new Error(`${command} ${commandArgs.join(" ")} failed`);
}

async function reachable(port) {
  try {
    await fetch(`http://localhost:${port}`, { signal: AbortSignal.timeout(1500) });
    return true;
  } catch {
    return false;
  }
}

async function waitReachable(name, port, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await reachable(port)) return;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`${name} did not answer on port ${port} within ${timeoutMs / 1000} s`);
}

function startInfrastructure() {
  console.log("Infrastructure (docker compose)...");
  run("docker", ["compose", "-f", "infra/docker-compose.yml", "up", "-d", "--wait"], root);
}

async function startBackend() {
  const gateway = join(root, "backend", "services", "gateway", "build", "libs", "gateway-0.1.0-SNAPSHOT.jar");
  if (args.has("--build") || !existsSync(gateway)) {
    console.log("Building the service jars (first run takes a few minutes)...");
    run(isWindows ? ".\\gradlew.bat" : "./gradlew", ["bootJar", "-q"], join(root, "backend"));
  }
  console.log(`Backend services (${Object.keys(PORTS).length}); logs in logs/<service>.out...`);
  await up();
}

async function startWeb() {
  console.log("Web apps (Vite dev servers)...");
  mkdirSync(logs, { recursive: true });
  const pids = {};
  for (const [name, app] of Object.entries(WEB)) {
    if (await reachable(app.port)) continue;
    const out = openSync(join(logs, `web-${name}.out`), "a");
    const child = spawn("corepack", ["pnpm", "--filter", app.filter, "dev"], {
      cwd: join(root, "frontend"),
      detached: true,
      shell: isWindows,
      stdio: ["ignore", out, out],
    });
    child.unref();
    pids[name] = child.pid;
  }
  writeFileSync(webPidFile, JSON.stringify(pids, null, 2));
  for (const [name, app] of Object.entries(WEB)) await waitReachable(name, app.port);
}

if (!args.has("--no-infra")) startInfrastructure();
if (!args.has("--no-backend")) await startBackend();
if (!args.has("--no-web")) await startWeb();

console.log(`
Cutpay is up.
  Dashboard    http://localhost:5173
  Checkout     http://localhost:5174
  Demo shop    http://localhost:5175
  Site / docs  http://localhost:5176
  Back office  http://localhost:5177
  API gateway  http://localhost:8080
  Grafana      http://localhost:3000
Stop with: node scripts/stop.mjs`);
