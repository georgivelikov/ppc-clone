import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError, parseSse } from "./client";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const urlOf = (mock: ReturnType<typeof vi.fn<typeof fetch>>, call = 0) => String(mock.mock.calls[call]?.[0]);

function streamOf(...chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

describe("parseSse", () => {
  it("reads complete status events and keeps a partial one for the next chunk", () => {
    const first = parseSse('event:status\ndata:{"status":"requires_payment_method"}\n\nevent:status\ndata:{"sta');
    expect(first.events).toEqual([{ status: "requires_payment_method" }]);
    const second = parseSse(first.rest + 'tus":"succeeded"}\n\n');
    expect(second.events).toEqual([{ status: "succeeded" }]);
    expect(second.rest).toBe("");
  });

  it("skips comments, other event names and broken JSON", () => {
    const { events } = parseSse(
      ': keep-alive\n\nevent:other\ndata:{"status":"x"}\n\nevent:status\ndata:not json\n\nevent:status\ndata:{"status":"failed","failure_code":"card_declined"}\n\n',
    );
    expect(events).toEqual([{ status: "failed", failure_code: "card_declined" }]);
  });

  it("understands Windows line endings", () => {
    expect(parseSse('event:status\r\ndata:{"status":"succeeded"}\r\n\r\n').events).toEqual([{ status: "succeeded" }]);
  });
});

describe("payment links", () => {
  it("creates a link with an idempotency key and live mode only when asked", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => json(201, { id: "plink_1" }));
    const client = new ApiClient({ fetch: fetchMock });
    await client.createPaymentLink({ type: "fixed", amount: 1500, currency: "EUR", title: "Table 4" });
    await client.createPaymentLink({ type: "fixed", amount: 1500, currency: "EUR", title: "Table 4" }, true);

    expect(urlOf(fetchMock, 0)).toBe("/v1/payment_links");
    expect(urlOf(fetchMock, 1)).toBe("/v1/payment_links?livemode=true");
    const init = fetchMock.mock.calls[0]?.[1];
    expect(init?.method).toBe("POST");
    expect((init?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  });

  it("lists, reads, updates and lists payments of a link", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => json(200, { data: [] }));
    const client = new ApiClient({ fetch: fetchMock });
    await client.listPaymentLinks({ status: "active", limit: 10, livemode: true });
    await client.getPaymentLink("plink_1");
    await client.updatePaymentLink("plink_1", { active: false });
    await client.paymentLinkPayments("plink_1");

    expect(urlOf(fetchMock, 0)).toBe("/v1/payment_links?status=active&limit=10&livemode=true");
    expect(urlOf(fetchMock, 1)).toBe("/v1/payment_links/plink_1");
    expect(urlOf(fetchMock, 2)).toBe("/v1/payment_links/plink_1");
    expect(fetchMock.mock.calls[2]?.[1]?.method).toBe("PATCH");
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({ active: false });
    expect(urlOf(fetchMock, 3)).toBe("/v1/payment_links/plink_1/payments");
  });

  it("downloads the QR code as an image with preview colours", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response("<svg/>", { status: 200, headers: { "Content-Type": "image/svg+xml" } }));
    const blob = await new ApiClient({ fetch: fetchMock }).paymentLinkQr("plink_1", { fg: "#112233", size: 300 });

    expect(urlOf(fetchMock)).toBe("/v1/payment_links/plink_1/qr?format=svg&fg=%23112233&size=300");
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>).Accept).toBe("*/*");
    expect(await blob.text()).toBe("<svg/>");
  });

  it("fetches the table card and the checkout QR", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => new Response("x", { status: 200 }));
    const client = new ApiClient({ fetch: fetchMock });
    await client.paymentLinkCard("plink_1");
    await client.checkoutQr("http://localhost:5174/c/pay_1_secret_a");
    expect(urlOf(fetchMock, 0)).toBe("/v1/payment_links/plink_1/card.pdf");
    expect(urlOf(fetchMock, 1)).toBe("/v1/qr?data=http%3A%2F%2Flocalhost%3A5174%2Fc%2Fpay_1_secret_a&format=svg");
  });

  it("turns an error response into an ApiError even for a download", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json(404, { error: { type: "invalid_request_error", code: "link_not_found", message: "No such payment link" } }));
    await expect(new ApiClient({ fetch: fetchMock }).paymentLinkQr("nope")).rejects.toMatchObject({
      status: 404,
      code: "link_not_found",
    });
    expect(ApiError).toBeDefined();
  });
});

describe("terminal and live status", () => {
  it("creates a terminal charge with an idempotency key", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(json(201, { id: "pay_1", channel: "moto" }));
    const payment = await new ApiClient({ fetch: fetchMock }).createTerminalCharge({ amount: 4200, currency: "EUR" });
    expect(payment.channel).toBe("moto");
    expect(urlOf(fetchMock)).toBe("/v1/terminal/charges");
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  });

  it("follows a payment until it ends, even when an event is split across chunks", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(streamOf('event:status\ndata:{"status":"requires_payment_method"}\n\nevent:sta', 'tus\ndata:{"status":"succeeded"}\n\n'));
    const seen: string[] = [];
    await new ApiClient({ fetch: fetchMock }).watchPayment("pay_1", (event) => seen.push(event.status));

    expect(seen).toEqual(["requires_payment_method", "succeeded"]);
    expect(urlOf(fetchMock)).toBe("/v1/payment_intents/pay_1/stream");
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>).Accept).toBe("text/event-stream");
  });

  it("stops quietly when the caller aborts", async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_url, init) => {
      const body = new ReadableStream<Uint8Array>({
        start(c) {
          init?.signal?.addEventListener("abort", () => c.error(new DOMException("aborted", "AbortError")));
        },
      });
      return new Response(body, { status: 200 });
    });
    const done = new ApiClient({ fetch: fetchMock }).watchPayment("pay_1", () => undefined, controller.signal);
    controller.abort();
    await expect(done).resolves.toBeUndefined();
  });

  it("renews the session once if the stream is refused as unauthorised", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(401, { error: { code: "invalid_token", message: "expired" } }))
      .mockResolvedValueOnce(
        json(200, {
          access_token: "tok-2",
          token_type: "Bearer",
          expires_in: 900,
          user: { id: "u", email: "a@b.c", full_name: "A" },
          merchant: {},
          role: "owner",
        }),
      )
      .mockResolvedValueOnce(streamOf('event:status\ndata:{"status":"succeeded"}\n\n'));
    const seen: string[] = [];
    await new ApiClient({ fetch: fetchMock }).watchPayment("pay_1", (e) => seen.push(e.status));
    expect(seen).toEqual(["succeeded"]);
    expect(fetchMock.mock.calls.map((c) => String(c[0]))).toEqual([
      "/v1/payment_intents/pay_1/stream",
      "/auth/refresh",
      "/v1/payment_intents/pay_1/stream",
    ]);
  });
});
