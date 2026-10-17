// Runs the shop's backend (port 4000) and the Vite dev server (port 5175) together.
import { spawn } from "node:child_process";

const run = (command, args) => spawn(command, args, { stdio: "inherit", shell: process.platform === "win32" });
const children = [run("node", ["server/index.ts"]), run("npx", ["vite"])];
const stop = () => children.forEach((child) => child.kill());
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
children.forEach((child) => child.on("exit", stop));
