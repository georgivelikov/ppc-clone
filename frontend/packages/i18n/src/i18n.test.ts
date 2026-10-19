import { describe, expect, it } from "vitest";
import { compare, flatten, pluralCategories } from "./check";
import { bgnEquivalent, formatMoney } from "./format";
import { detectLocale, LOCALES, normaliseLocale, parseAcceptLanguage } from "./locale";
import { CHECKOUT } from "./resources";

describe("choosing a language", () => {
  it("understands regional tags and underscores, and ignores languages it does not have", () => {
    expect(normaliseLocale("bg-BG")).toBe("bg");
    expect(normaliseLocale("PL_pl")).toBe("pl");
    expect(normaliseLocale("el")).toBe("el");
    expect(normaliseLocale("de-DE")).toBeNull();
    expect(normaliseLocale("")).toBeNull();
    expect(normaliseLocale(undefined)).toBeNull();
  });

  it("prefers what was asked for, then the merchant's choice for the payment, then the browser, then the merchant's default", () => {
    expect(detectLocale({ chosen: "ro", session: "pl", browser: ["hu"] })).toBe("ro");
    expect(detectLocale({ session: "pl", browser: ["hu"] })).toBe("pl");
    expect(detectLocale({ browser: ["de", "hu-HU", "en"] })).toBe("hu");
    expect(detectLocale({ browser: ["de"], merchantDefault: "el" })).toBe("el");
    expect(detectLocale({ browser: ["de"] })).toBe("en");
    expect(detectLocale()).toBe("en");
    // A language we do not have is skipped, not an error.
    expect(detectLocale({ chosen: "xx", session: "bg" })).toBe("bg");
  });

  it("reads an Accept-Language header in order of preference", () => {
    expect(parseAcceptLanguage("pl,en-US;q=0.8,en;q=0.7")).toEqual(["pl", "en-US", "en"]);
    expect(parseAcceptLanguage("en;q=0.5, bg;q=0.9, *;q=0.1")).toEqual(["bg", "en", "*"].filter((tag) => tag !== "*"));
    expect(parseAcceptLanguage("de;q=0")).toEqual([]);
    expect(parseAcceptLanguage(null)).toEqual([]);
  });
});

describe("formatting for each language", () => {
  it("writes amounts the way people in that language do", () => {
    expect(formatMoney(123456, "EUR", "en")).toBe("€1,234.56");
    expect(formatMoney(123456, "EUR", "bg")).toMatch(/^1\s?234,56\s€$/);
    expect(formatMoney(123456, "PLN", "pl")).toMatch(/^1\s?234,56\szł$/);
    expect(formatMoney(123456, "EUR", "ro")).toMatch(/1\.234,56/);
    expect(formatMoney(123456, "EUR", "hu")).toMatch(/1\s?234,56/);
    expect(formatMoney(123456, "EUR", "el")).toMatch(/1\.234,56/);
  });

  it("keeps two decimals for the forint, as ISO 4217 does", () => {
    expect(formatMoney(150000, "HUF", "en").replace(/\u00a0/g, " ")).toBe("HUF 1,500.00");
  });

  it("shows the lev amount next to euros in Bulgarian only, at the fixed rate", () => {
    expect(bgnEquivalent(2500, "EUR", "bg")).toMatch(/^48,90\sлв\.$/);
    expect(bgnEquivalent(100, "EUR", "bg-BG")).toMatch(/^1,96\sлв\.$/);
    expect(bgnEquivalent(2500, "EUR", "en")).toBeNull();
    expect(bgnEquivalent(2500, "PLN", "bg")).toBeNull();
  });
});

describe("the translations", () => {
  it.each(LOCALES.filter((locale) => locale !== "en"))("%s has everything English has, and nothing it should not", (locale) => {
    expect(compare(CHECKOUT.en, CHECKOUT[locale], locale)).toEqual([]);
  });

  it("has every plural form each language needs", () => {
    expect([...pluralCategories("en")].sort()).toEqual(["one", "other"]);
    expect([...pluralCategories("pl")].sort()).toEqual(["few", "many", "one", "other"]);
    expect([...pluralCategories("ro")].sort()).toEqual(["few", "one", "other"]);
    const pl = flatten(CHECKOUT.pl);
    for (const form of ["one", "few", "many", "other"]) expect(pl[`bnpl.plan.many_${form}`], form).toBeTruthy();
  });

  it("is checked: a missing key, a lost placeholder, a lost tag and a missing plural form are all reported", () => {
    const english = { a: "Hello", pay: "Pay {{amount}}", link: "Open <appLink>it</appLink>", things_one: "{{count}} thing", things_other: "{{count}} things" };
    const broken = { pay: "Plăteşte", link: "Deschide", things_one: "o chestie", things_other: "{{count}} chestii", extra: "x" };
    const problems = compare(english, broken, "ro").map((p) => `${p.key}:${p.problem}`);
    expect(problems).toEqual(
      expect.arrayContaining(["a:missing", "pay:placeholders", "link:tags", "things_few:missing", "extra:extra"]),
    );
  });

  it("says nothing when a translation is complete", () => {
    const english = { pay: "Pay {{amount}}" };
    expect(compare(english, { pay: "Plătește {{amount}}" }, "ro")).toEqual([]);
  });
});
