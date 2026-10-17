// Copies the browser build to where the checkout app serves it: http://localhost:5174/v1/cutpay.js
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, "..", "dist");
const target = join(here, "..", "..", "..", "apps", "checkout", "public", "v1");

mkdirSync(target, { recursive: true });
copyFileSync(join(dist, "cutpay.umd.cjs"), join(target, "cutpay.js"));
copyFileSync(join(dist, "cutpay.esm.js"), join(target, "cutpay.esm.js"));
console.log(`Published the SDK to ${target}`);
