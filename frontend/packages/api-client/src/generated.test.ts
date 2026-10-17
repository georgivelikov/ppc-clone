// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import openapiTS, { astToString } from "openapi-typescript";
import { expect, it } from "vitest";

it("the generated types match docs/api/openapi.yaml (run `pnpm generate` after changing the spec)", async () => {
  const spec = resolve(import.meta.dirname, "../../../../docs/api/openapi.yaml");
  const fresh = astToString(await openapiTS(pathToFileURL(spec)));
  const checkedIn = readFileSync(resolve(import.meta.dirname, "generated/openapi.ts"), "utf8");
  // The command line tool puts a banner comment on top; the library does not.
  const withoutBanner = checkedIn.replace(/\r\n/g, "\n").replace(/^\/\*\*[\s\S]*?\*\/\n\n/, "");
  expect(withoutBanner.trim()).toBe(fresh.replace(/\r\n/g, "\n").trim());
});
