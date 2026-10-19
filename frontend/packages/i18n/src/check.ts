// What a translation workflow needs from a build: say which keys a language is missing, which it has too many of, and
// where a placeholder was lost. English is the source of truth.

export type Messages = { [key: string]: string | Messages };

/** `{ a: { b: "x" } }` becomes `{ "a.b": "x" }`. */
export function flatten(messages: Messages, prefix = ""): Record<string, string> {
  const flat: Record<string, string> = {};
  for (const [key, value] of Object.entries(messages)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") flat[path] = value;
    else Object.assign(flat, flatten(value, path));
  }
  return flat;
}

const PLURAL = /^(.*)_(zero|one|two|few|many|other)$/;

/** The plural forms a language has, such as one/few/many/other in Polish. */
export function pluralCategories(locale: string): string[] {
  return new Intl.PluralRules(locale).resolvedOptions().pluralCategories as string[];
}

function placeholders(text: string): string[] {
  return [...new Set([...text.matchAll(/\{\{\s*([\w.]+)\s*[,}]/g)].map((m) => m[1]!))].sort();
}

function tags(text: string): string[] {
  return [...new Set([...text.matchAll(/<(\/?)(\w+)>/g)].map((m) => `${m[1]}${m[2]}`))].sort();
}

export interface Problem {
  locale: string;
  key: string;
  problem: "missing" | "extra" | "placeholders" | "tags" | "empty";
  detail?: string;
}

/**
 * The keys of a flat message set without their plural suffix, each with the suffixes it has. A key that is plural in
 * English must have every plural form the other language needs, not the same ones as English.
 */
function groupPlurals(flat: Record<string, string>): Map<string, Set<string>> {
  const groups = new Map<string, Set<string>>();
  for (const key of Object.keys(flat)) {
    const match = PLURAL.exec(key);
    const base = match ? match[1]! : key;
    const form = match ? match[2]! : "";
    if (!groups.has(base)) groups.set(base, new Set());
    groups.get(base)!.add(form);
  }
  return groups;
}

/** Compares a language with the English source. */
export function compare(source: Messages, other: Messages, locale: string): Problem[] {
  const problems: Problem[] = [];
  const en = flatten(source);
  const translated = flatten(other);
  const enGroups = groupPlurals(en);
  const groups = groupPlurals(translated);

  for (const [base, forms] of enGroups) {
    const have = groups.get(base);
    if (!have) {
      problems.push({ locale, key: base, problem: "missing" });
      continue;
    }
    const plural = [...forms].some((form) => form !== "");
    const needed = plural ? pluralCategories(locale) : [""];
    for (const form of needed) {
      if (!have.has(form)) {
        problems.push({ locale, key: form ? `${base}_${form}` : base, problem: "missing" });
      }
    }
    // The placeholders and tags of the English text must all be in the translation.
    const reference = [...forms].map((f) => en[f ? `${base}_${f}` : base]!).join(" ");
    for (const form of have) {
      const key = form ? `${base}_${form}` : base;
      const text = translated[key]!;
      if (text.trim() === "") problems.push({ locale, key, problem: "empty" });
      // Every placeholder of the English text must survive (the number itself may be spelled out in a plural form).
      const lost = placeholders(reference).filter((p) => !placeholders(text).includes(p) && p !== "count");
      if (lost.length > 0) problems.push({ locale, key, problem: "placeholders", detail: `lost ${lost.join(", ")}` });
      const extraPlaceholders = placeholders(text).filter((p) => !placeholders(reference).includes(p));
      if (extraPlaceholders.length > 0) problems.push({ locale, key, problem: "placeholders", detail: `unknown ${extraPlaceholders.join(", ")}` });
      const lostTags = tags(reference).filter((tag) => !tags(text).includes(tag));
      if (lostTags.length > 0) problems.push({ locale, key, problem: "tags", detail: lostTags.join(", ") });
    }
  }
  for (const base of groups.keys()) {
    if (!enGroups.has(base)) problems.push({ locale, key: base, problem: "extra" });
  }
  return problems;
}

export function describe(problem: Problem): string {
  const labels: Record<Problem["problem"], string> = {
    missing: "is missing",
    extra: "is not in English (remove it, or add it there first)",
    placeholders: "has a wrong placeholder",
    tags: "has lost a tag",
    empty: "is empty",
  };
  return `${problem.locale}: ${problem.key} ${labels[problem.problem]}${problem.detail ? ` (${problem.detail})` : ""}`;
}
