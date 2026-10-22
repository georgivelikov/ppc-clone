// Stops what scripts/start.mjs started: the web apps, the backend jars, and (stop, not down, so data stays)
// the docker compose infrastructure.
//
//   node scripts/stop.mjs              everything
//   node scripts/stop.mjs --keep-infra leave docker compose running (faster restart)
//   node scripts/stop.mjs --wipe       also delete the infrastructure containers and their data
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PORTS, down } from "../e2e/chaos/stack.mjs";

const APPS = Object.keys(PORTS);

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const webPidFile = join(root, "scripts", ".web-pids.json");
const args = new Set(process.argv.slice(2));
const isWindows = process.platform === "win32";

function kill(pid) {
  try {
    if (isWindows) execFileSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore" });
    else process.kill(-pid, "SIGKILL");
  } catch {
    // already gone
  }
}

if (existsSync(webPidFile)) {
  console.log("Stopping web apps...");
  for (const pid of Object.values(JSON.parse(readFileSync(webPidFile, "utf8")))) kill(pid);
  rmSync(webPidFile);
}

console.log("Stopping backend services...");
down();
spawnSync("docker", ["compose", "-f", "infra/docker-compose.yml", "-f", "infra/docker-compose.apps.yml", "stop", ...APPS], { cwd: root, stdio: "inherit", shell: isWindows });

if (!args.has("--keep-infra")) {
  console.log(args.has("--wipe") ? "Removing infrastructure and its data..." : "Stopping infrastructure (data is kept)...");
  spawnSync("docker", ["compose", "-f", "infra/docker-compose.yml", "-f", "infra/docker-compose.apps.yml", args.has("--wipe") ? "down" : "stop", ...(args.has("--wipe") ? ["-v"] : [])], {
    cwd: root,
    stdio: "inherit",
    shell: isWindows,
  });
}
console.log("Stopped.");
