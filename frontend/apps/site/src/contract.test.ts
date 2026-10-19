// @vitest-environment node
// The docs make promises about the platform. These tests read the backend's own source and fail when the two drift.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { FEE_PLANS } from "./lib/pricing";
import { PROVIDERS } from "./pages/docs/InstalmentsPage";
import { BLIK, FX, LOCALES } from "./pages/docs/MethodsPage";
import { FEE_CARDS, TEST_CARDS } from "./pages/docs/TestCardsPage";

const root = resolve(import.meta.dirname, "../../../..");
const read = (path: string) => readFileSync(resolve(root, path), "utf8");

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const spec = parse(read("docs/api/openapi.yaml")) as Json;
const METHODS = ["get", "put", "post", "delete", "patch"];

function operations(): { path: string; method: string; op: Json }[] {
  return Object.entries(spec.paths as Json).flatMap(([path, item]) =>
    METHODS.filter((method) => item[method]).map((method) => ({ path, method, op: item[method] as Json })),
  );
}

function refs(node: unknown, found: string[] = []): string[] {
  if (Array.isArray(node)) node.forEach((child) => refs(child, found));
  else if (node && typeof node === "object") {
    for (const [key, value] of Object.entries(node)) {
      if (key === "$ref" && typeof value === "string") found.push(value);
      else refs(value, found);
    }
  }
  return found;
}

describe("the OpenAPI description", () => {
  it("is OpenAPI 3.1 with the basics filled in", () => {
    expect(spec.openapi).toMatch(/^3\.1/);
    expect(spec.info.title).toBe("Cutpay API");
    expect(spec.servers.length).toBeGreaterThan(0);
  });

  it("only points at things that exist", () => {
    for (const ref of refs(spec)) {
      expect(ref.startsWith("#/"), ref).toBe(true);
      let node: any = spec; // eslint-disable-line @typescript-eslint/no-explicit-any
      for (const part of ref.slice(2).split("/")) node = node?.[part];
      expect(node, `unresolved ${ref}`).toBeDefined();
    }
  });

  it("names every operation uniquely and documents its responses", () => {
    const ids = new Set<string>();
    for (const { path, method, op } of operations()) {
      const label = `${method.toUpperCase()} ${path}`;
      expect(op.operationId, label).toBeTruthy();
      expect(ids.has(op.operationId), `duplicate operationId ${op.operationId}`).toBe(false);
      ids.add(op.operationId);
      expect(op.summary, label).toBeTruthy();
      expect(op.tags?.length, label).toBeGreaterThan(0);
      const codes = Object.keys(op.responses ?? {});
      expect(codes.some((code) => code.startsWith("2")), `${label} needs a success response`).toBe(true);
    }
  });

  it("declares every path parameter and no more", () => {
    for (const { path, method, op } of operations()) {
      const inPath = [...path.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
      const resolveParam = (p: Json) => (p.$ref ? (spec.components.parameters as Json)[p.$ref.split("/").pop()] : p) as Json;
      const declared = [...(spec.paths[path].parameters ?? []), ...(op.parameters ?? [])]
        .map(resolveParam)
        .filter((p) => p.in === "path")
        .map((p) => p.name);
      expect(declared.sort(), `${method.toUpperCase()} ${path}`).toEqual(inPath.sort());
    }
  });

  it("is a subset of what the gateway routes", () => {
    const gateway = parse(read("backend/services/gateway/src/main/resources/application.yml")) as Json;
    const patterns: string[] = gateway.spring.cloud.gateway.server.webflux.routes
      .flatMap((route: Json) => route.predicates as string[])
      .filter((predicate: string) => predicate.startsWith("Path="))
      .flatMap((predicate: string) => predicate.slice("Path=".length).split(","));
    const matches = (pattern: string, path: string) => {
      const regex = new RegExp("^" + pattern.replace(/[.+?^$()|[\]\\]/g, "\\$&").replace(/\*\*/g, ".*").replace(/\{(\w+)\}/g, "[^/]+") + "$");
      return regex.test(path);
    };
    for (const { path } of operations()) {
      const concrete = path.replace(/\{\w+\}/g, "x1");
      expect(
        patterns.some((pattern) => matches(pattern, concrete)),
        `${path} is documented but the gateway does not route it`,
      ).toBe(true);
    }
  });

  it("lists exactly the event types the webhooks service can send", () => {
    const source = read("backend/services/webhooks/src/main/java/dev/cutpay/webhooks/event/EventTypes.java");
    const block = source.slice(source.indexOf("Set.of("), source.indexOf(");", source.indexOf("Set.of(")));
    const javaTypes = [...block.matchAll(/"([a-z_]+(?:\.[a-z_]+)+)"/g)].map((m) => m[1]);
    javaTypes.push("webhook_endpoint.test"); // written as a constant in the source
    const documented = spec.components.schemas.EventType.enum as string[];
    expect([...new Set(javaTypes)].sort()).toEqual([...documented].sort());
  });

  it("documents the payment statuses the payments service uses", () => {
    const source = read("backend/services/payments/src/main/java/dev/cutpay/payments/domain/PaymentStatus.java");
    const start = source.indexOf("public enum PaymentStatus {") + "public enum PaymentStatus {".length;
    // Doc comments on the constants are not constants.
    const constants = source.slice(start, source.indexOf(";", start)).replace(/\/\*[\s\S]*?\*\//g, "");
    const wire = constants.split(",").map((name) => name.trim().toLowerCase()).filter(Boolean);
    expect(wire.length).toBeGreaterThan(5);
    expect([...wire].sort()).toEqual([...(spec.components.schemas.PaymentStatus.enum as string[])].sort());
  });
});

describe("the docs agree with the platform", () => {
  it("lists every special test card of the simulated card network", () => {
    const source = read("backend/services/acquirer-sim/src/main/java/dev/cutpay/acquirer/TestCards.java");
    const numbers = [...source.matchAll(/"(4\d{15})", Outcome/g)].map((m) => m[1]);
    expect(numbers.length).toBe(8);
    const documented = TEST_CARDS.map((card) => card.number.replaceAll(" ", ""));
    for (const number of numbers) expect(documented, number).toContain(number);
  });

  it("describes the BNPL providers with the limits and fees the platform really has", () => {
    const catalogue = read("backend/services/bnpl/src/main/java/dev/cutpay/bnpl/model/ProviderCatalog.java");
    const entries = [...catalogue.matchAll(/new ProviderInfo\(\w+, "(\w+)", ([\d_]+), ([\d_]+),/g)].map((m) => ({
      name: m[1],
      min: Number(m[2]!.replaceAll("_", "")),
      max: Number(m[3]!.replaceAll("_", "")),
    }));
    expect(entries.length).toBe(PROVIDERS.length);
    for (const provider of PROVIDERS) {
      expect(entries, provider.name).toContainEqual({ name: provider.name, min: provider.min, max: provider.max });
    }
    const feeSources: Record<string, string> = {
      splitnow: "SplitNowController",
      lendo: "LendoController",
      kreditko: "KreditKoController",
      paylatereu: "PayLaterEuController",
    };
    for (const provider of PROVIDERS) {
      const source = read(`backend/services/bnpl-sim/src/main/java/dev/cutpay/bnplsim/${feeSources[provider.code]}.java`);
      expect(source, provider.name).toMatch(new RegExp(`FEE_BPS = ${provider.feeBps};`));
    }
  });

  it("promises the BLIK codes, fee and currency the platform really has", () => {
    const simulator = read("backend/services/acquirer-sim/src/main/java/dev/cutpay/acquirer/BlikController.java");
    expect(simulator).toContain(`APPROVED_CODE = "${BLIK.approves}"`);
    expect(simulator).toContain(`DECLINED_CODE = "${BLIK.declines}"`);
    expect(simulator).toContain(`"${BLIK.currency}".equals(request.currency())`);
    expect(read("backend/services/ledger/src/main/java/dev/cutpay/ledger/fees/PricingPlan.java")).toContain(
      `BLIK = new Rate(${BLIK.bps}, ${BLIK.fixed})`,
    );
    expect(read("backend/services/payments/src/main/java/dev/cutpay/payments/payment/PaymentMethodRules.java")).toContain(
      `"${BLIK.currency}".equals(currency)`,
    );
  });

  it("promises the currency markup, the quote validity and the lev rate the platform really uses", () => {
    expect(read("backend/services/ledger/src/main/java/dev/cutpay/ledger/fx/FxService.java")).toContain(
      `QUOTE_VALIDITY = Duration.ofMinutes(${FX.quoteMinutes})`,
    );
    expect(read("backend/services/ledger/src/main/resources/db/migration/V5__fx.sql")).toContain(`fx_markup_bps         INT         NOT NULL DEFAULT ${FX.markupBps}`);
    expect(read("frontend/packages/i18n/src/format.ts")).toContain(`BGN_PER_EUR = ${FX.bgnPerEur}`);
  });

  it("lists the languages the checkout is translated into", () => {
    expect(read("frontend/packages/i18n/src/locale.ts")).toContain(`LOCALES = [${LOCALES.map((l) => `"${l}"`).join(", ")}]`);
    const payments = read("backend/services/payments/src/main/java/dev/cutpay/payments/payment/PaymentDtos.java");
    expect(payments).toContain(`regexp = "${LOCALES.join("|")}"`);
    const documented = (spec.components.schemas.CreatePaymentIntent as Json).properties.locale.enum as string[];
    expect([...documented].sort()).toEqual([...LOCALES].sort());
  });

  it("uses fee-tier cards that really have those BINs", () => {
    const source = read("backend/services/acquirer-sim/src/main/java/dev/cutpay/acquirer/BinTable.java");
    for (const card of FEE_CARDS.slice(1)) {
      expect(source, card.number).toContain(`"${card.number.replaceAll(" ", "").slice(0, 6)}"`);
    }
  });

  it("only documents card numbers that pass the checksum", () => {
    const luhn = (digits: string) => {
      let sum = 0;
      [...digits].reverse().forEach((ch, i) => {
        let d = Number(ch);
        if (i % 2 === 1) d = d * 2 > 9 ? d * 2 - 9 : d * 2;
        sum += d;
      });
      return sum % 10 === 0;
    };
    for (const card of [...TEST_CARDS, ...FEE_CARDS]) expect(luhn(card.number.replaceAll(" ", "")), card.number).toBe(true);
  });

  it("quotes the fees the ledger charges", () => {
    const source = read("backend/services/ledger/src/main/java/dev/cutpay/ledger/fees/PricingPlan.java");
    expect(source).toContain(`new Rate(${FEE_PLANS.eea_consumer.bps}, ${FEE_PLANS.eea_consumer.fixed})`);
    expect(source).toContain(`new Rate(${FEE_PLANS.other.bps}, ${FEE_PLANS.other.fixed})`);
  });
});
