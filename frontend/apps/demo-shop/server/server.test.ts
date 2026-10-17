// @vitest-environment node
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createApp, type Config, type FetchLike } from "./app.ts";
import { sign, verify } from "./signature.ts";

const SECRET = "whsec_test";
const NOW = 1_760_000_000_000;

describe("signature", () => {
  it("matches the platform's known value", () => {
    // Same vector as the Java library: printf '1760000000.{}' | openssl dgst -sha256 -hmac secret
    expect(sign("secret", "{}", 1_760_000_000)).toBe(
      "t=1760000000,v1=53dc054739ad94d3532227ba8d397f66ed2166a6b66940c2006692fa6db6829f",
    );
  });

  it("accepts a fresh correct signature and rejects tampering, wrong secrets and old timestamps", () => {
    const body = '{"id":"evt_1"}';
    const header = sign(SECRET, body, 1000);
    expect(verify(SECRET, body, header, 1000)).toBe("valid");
    expect(verify(SECRET, body + " ", header, 1000)).toBe("no_matching_signature");
    expect(verify("whsec_other", body, header, 1000)).toBe("no_matching_signature");
    expect(verify(SECRET, body, header, 1000 + 301)).toBe("timestamp_out_of_range");
    expect(verify(SECRET, body, header, 1000 + 300)).toBe("valid");
    expect(verify(SECRET, body, header, 1000 - 301)).toBe("timestamp_out_of_range");
  });

  it("rejects malformed headers", () => {
    for (const header of [undefined, "", "garbage", "t=abc,v1=x", "v1=abc", "t=1000"]) {
      expect(verify(SECRET, "{}", header, 1000)).toBe("malformed_header");
    }
  });

  it("does not let moving the timestamp reuse an old signature", () => {
    const old = sign(SECRET, "{}", 1000);
    const forged = old.replace("t=1000", "t=5000");
    expect(verify(SECRET, "{}", forged, 5000)).toBe("no_matching_signature");
  });
});

describe("shop backend", () => {
  let cutpayCalls: { url: string; headers: Record<string, string>; body: Record<string, unknown> }[];
  let cutpayAnswer: { ok: boolean; status: number; body: unknown };
  let server: Server;
  let base: string;
  let config: Config;
  let clock: number;
  let app: ReturnType<typeof createApp>;

  const fakeCutpay: FetchLike = async (url, init) => {
    cutpayCalls.push({ url, headers: init.headers, body: JSON.parse(init.body) as Record<string, unknown> });
    return { ok: cutpayAnswer.ok, status: cutpayAnswer.status, json: async () => cutpayAnswer.body };
  };

  beforeEach(async () => {
    cutpayCalls = [];
    cutpayAnswer = { ok: true, status: 201, body: { id: "pay_1", client_secret: "pay_1_secret_x" } };
    clock = NOW;
    config = {
      apiUrl: "http://cutpay.test",
      checkoutUrl: "http://checkout.test",
      shopUrl: "http://shop.test",
      webhookUrl: "http://shop.test:4000/webhooks/cutpay",
      secretKey: "sk_test_" + "s".repeat(32),
      publishableKey: "pk_test_" + "p".repeat(32),
      webhookSecret: SECRET,
      allowSetup: true,
    };
    app = createApp(config, fakeCutpay, () => clock);
    server = createServer(app.handler);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
  });

  const post = (path: string, body: unknown, headers: Record<string, string> = {}) =>
    fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: typeof body === "string" ? body : JSON.stringify(body) });

  const order = async (items = [{ productId: "mug", quantity: 2 }], mode = "embedded") => {
    const response = await post("/api/orders", { items, mode, email: "anna@example.com" });
    return { response, body: (await response.json()) as Record<string, unknown> };
  };

  const deliver = (payload: object, timestamp = Math.floor(clock / 1000), secret = SECRET) => {
    const raw = JSON.stringify(payload);
    return post("/webhooks/cutpay", raw, { "Cutpay-Signature": sign(secret, raw, timestamp) });
  };

  const succeeded = (id: string, paymentId = "pay_1", amount = 2400) => ({
    id,
    type: "payment_intent.succeeded",
    data: { object: { id: paymentId, amount } },
  });

  it("prices the order itself and creates the payment with the secret key and an idempotency key", async () => {
    const { response, body } = await order([{ productId: "mug", quantity: 2 }, { productId: "tote", quantity: 1 }]);
    expect(response.status).toBe(201);
    expect(body).toMatchObject({ amount: 4250, currency: "EUR", status: "pending", clientSecret: "pay_1_secret_x" });

    expect(cutpayCalls).toHaveLength(1);
    const call = cutpayCalls[0]!;
    expect(call.url).toBe("http://cutpay.test/v1/payment_intents");
    expect(call.headers.Authorization).toBe(`Bearer ${config.secretKey}`);
    expect(call.headers["Idempotency-Key"]).toBe(body.id);
    expect(call.body).toMatchObject({ amount: 4250, currency: "EUR", metadata: { order_id: body.id } });
    expect(String(call.body.return_url)).toBe(`http://shop.test/thanks?order=${String(body.id)}`);
  });

  it("ignores prices sent by the browser and rejects unknown products and silly quantities", async () => {
    const tampered = await post("/api/orders", { items: [{ productId: "mug", quantity: 1, unitAmount: 1 }], mode: "embedded" });
    expect(((await tampered.json()) as { amount: number }).amount).toBe(1200);
    expect((await order([{ productId: "yacht", quantity: 1 }])).response.status).toBe(400);
    expect((await order([{ productId: "mug", quantity: 0 }])).response.status).toBe(400);
    expect((await order([{ productId: "mug", quantity: 11 }])).response.status).toBe(400);
    expect((await order([])).response.status).toBe(400);
    expect((await order([{ productId: "mug", quantity: 1 }], "paypal")).response.status).toBe(400);
  });

  it("uses a checkout session for the hosted flow and returns its url", async () => {
    cutpayAnswer.body = { payment_id: "pay_2", client_secret: "pay_2_secret_y", url: "http://checkout.test/c/pay_2_secret_y" };
    const { body } = await order([{ productId: "print", quantity: 1 }], "hosted");
    expect(cutpayCalls[0]!.url).toBe("http://cutpay.test/v1/checkout_sessions");
    expect(body.url).toBe("http://checkout.test/c/pay_2_secret_y");
    expect(body.clientSecret).toBeUndefined(); // hosted orders never show the secret
  });

  it("explains itself when it has no keys, and when Cutpay refuses the payment", async () => {
    delete config.secretKey;
    expect((await order()).response.status).toBe(409);
    config.secretKey = "sk_test_" + "s".repeat(32);
    cutpayAnswer = { ok: false, status: 401, body: { error: { message: "Invalid API key" } } };
    const { response, body } = await order();
    expect(response.status).toBe(502);
    expect((body.error as { message: string }).message).toBe("Invalid API key");
    expect(app.orders.size).toBe(0); // no half-created orders
  });

  it("marks an order paid only when a correctly signed, matching webhook arrives", async () => {
    const { body } = await order();
    const id = String(body.id);
    expect(((await (await fetch(`${base}/api/orders/${id}`)).json()) as { status: string }).status).toBe("pending");

    const response = await deliver(succeeded("evt_1"));
    expect(response.status).toBe(200);
    const paid = (await (await fetch(`${base}/api/orders/${id}`)).json()) as Record<string, unknown>;
    expect(paid.status).toBe("paid");
    expect(paid.paidAt).toBe(new Date(NOW).toISOString());
    expect(paid.clientSecret).toBeUndefined(); // nothing left to pay
  });

  it("rejects webhooks with a bad signature, a tampered body or an old timestamp", async () => {
    const { body } = await order();
    const raw = JSON.stringify(succeeded("evt_1"));
    const now = Math.floor(clock / 1000);

    expect((await post("/webhooks/cutpay", raw)).status).toBe(400);
    expect((await post("/webhooks/cutpay", raw, { "Cutpay-Signature": sign("whsec_wrong", raw, now) })).status).toBe(400);
    expect((await post("/webhooks/cutpay", raw.replace("2400", "1"), { "Cutpay-Signature": sign(SECRET, raw, now) })).status).toBe(400);
    expect((await deliver(succeeded("evt_1"), now - 600)).status).toBe(400); // replay of an old capture

    expect(((await (await fetch(`${base}/api/orders/${String(body.id)}`)).json()) as { status: string }).status).toBe("pending");
  });

  it("handles a repeated event once", async () => {
    await order();
    expect(((await (await deliver(succeeded("evt_dup"))).json()) as Record<string, unknown>).duplicate).toBeUndefined();
    expect(((await (await deliver(succeeded("evt_dup"))).json()) as Record<string, unknown>).duplicate).toBe(true);
  });

  it("does not mark an order paid when the amount is wrong", async () => {
    const { body } = await order();
    await deliver(succeeded("evt_1", "pay_1", 1));
    const result = (await (await fetch(`${base}/api/orders/${String(body.id)}`)).json()) as Record<string, unknown>;
    expect(result.status).toBe("failed");
    expect(result.failureReason).toMatch(/amount/);
  });

  it("marks a failed payment and ignores events for payments it does not know", async () => {
    const { body } = await order();
    await deliver({ id: "evt_f", type: "payment_intent.payment_failed", data: { object: { id: "pay_1", failure_code: "card_declined" } } });
    const result = (await (await fetch(`${base}/api/orders/${String(body.id)}`)).json()) as Record<string, unknown>;
    expect(result).toMatchObject({ status: "failed", failureReason: "card_declined" });

    const unknown = await deliver(succeeded("evt_u", "pay_unknown"));
    expect(((await unknown.json()) as Record<string, unknown>).ignored).toBe(true);
  });

  it("refuses webhooks when no secret is configured", async () => {
    delete config.webhookSecret;
    expect((await deliver(succeeded("evt_1"))).status).toBe(503);
  });

  it("lists orders newest first", async () => {
    await order([{ productId: "mug", quantity: 1 }]);
    cutpayAnswer.body = { id: "pay_9", client_secret: "pay_9_secret_z" };
    await order([{ productId: "tote", quantity: 1 }]);
    const list = (await (await fetch(`${base}/api/orders`)).json()) as { data: { amount: number }[] };
    expect(list.data.map((o) => o.amount)).toEqual([1850, 1200]);
  });

  it("only accepts keys of the right kind on the setup endpoint, and can be switched off", async () => {
    expect((await post("/api/setup", { secretKey: "pk_test_x" })).status).toBe(400);
    expect((await post("/api/setup", { publishableKey: "sk_test_x" })).status).toBe(400);
    const ok = await post("/api/setup", { secretKey: "sk_test_new", publishableKey: "pk_test_new", webhookSecret: "whsec_new" });
    expect(ok.status).toBe(200);
    expect(config.webhookSecret).toBe("whsec_new");

    config.allowSetup = false;
    expect((await post("/api/setup", { secretKey: "sk_test_new" })).status).toBe(404);
  });

  it("never exposes the secret key or webhook secret", async () => {
    const response = await fetch(`${base}/api/config`);
    const text = await response.text();
    expect(text).not.toContain("sk_test_");
    expect(text).not.toContain(SECRET);
    expect(JSON.parse(text)).toMatchObject({ configured: true, publishableKey: config.publishableKey });
  });

  it("returns JSON errors for bad input and unknown routes", async () => {
    expect((await post("/api/orders", "{nope")).status).toBe(400);
    expect((await fetch(`${base}/api/nothing`)).status).toBe(404);
    expect((await fetch(`${base}/api/orders/ord_missing`)).status).toBe(404);
  });
});
