// Fails when a language is missing keys the English source has, has keys English does not, lost a placeholder or a
// tag, or lacks a plural form its language needs. Run by `pnpm i18n:check` and by the tests, so CI notices.
import { compare, describe } from "../src/check.ts";
import { CHECKOUT, DASHBOARD } from "../src/resources.ts";

const sets = { checkout: CHECKOUT, dashboard: DASHBOARD };
let failures = 0;
for (const [namespace, languages] of Object.entries(sets)) {
  const english = languages.en;
  for (const [locale, messages] of Object.entries(languages)) {
    if (locale === "en" || !messages) continue;
    for (const problem of compare(english, messages, locale)) {
      console.error(`${namespace} ${describe(problem)}`);
      failures++;
    }
  }
}
if (failures > 0) {
  console.error(`\n${failures} translation problem${failures === 1 ? "" : "s"}.`);
  process.exit(1);
}
console.log("Translations are complete.");
