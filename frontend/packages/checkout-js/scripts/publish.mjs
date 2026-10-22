// Copies the browser build to where the checkout app serves it:
//   http://localhost:5174/v1/cutpay.js              the current v1 build (changes with every release; no SRI possible)
//   http://localhost:5174/v1/<version>/cutpay.js    the same file, pinned to a release
//   http://localhost:5174/v1/<version>/cutpay.js.sri  its Subresource Integrity value (sha384-...)
// A merchant who pins the versioned URL with the integrity attribute is protected if the file on our server is ever
// replaced: the browser refuses to run a script whose hash is not the one the merchant wrote down.
import { createHash } from "node:crypto";
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, "..", "dist");
const target = join(here, "..", "..", "..", "apps", "checkout", "public", "v1");
const { version } = JSON.parse(readFileSync(join(here, "..", "package.json"), "utf8"));
const pinned = join(target, version);

mkdirSync(pinned, { recursive: true });
copyFileSync(join(dist, "cutpay.umd.cjs"), join(target, "cutpay.js"));
copyFileSync(join(dist, "cutpay.esm.js"), join(target, "cutpay.esm.js"));
copyFileSync(join(dist, "cutpay.umd.cjs"), join(pinned, "cutpay.js"));
copyFileSync(join(dist, "cutpay.esm.js"), join(pinned, "cutpay.esm.js"));

for (const file of ["cutpay.js", "cutpay.esm.js"]) {
  const digest = createHash("sha384").update(readFileSync(join(pinned, file))).digest("base64");
  writeFileSync(join(pinned, `${file}.sri`), `sha384-${digest}\n`);
}
console.log(`Published the SDK to ${target} (pinned: ${version})`);
