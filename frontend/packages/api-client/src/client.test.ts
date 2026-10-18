import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError } from "./client";
import type { AuthResponse } from "./types";

const session = (token: string): AuthResponse => ({
  access_token: token,
  token_type: "Bearer",
  expires_in: 900,
  user: { id: "usr_1", email: "a@b.c", full_name: "A B" },
  merchant: {
    id: "mer_1",
    display_name: "Shop",
    legal_name: "Shop",
    country: "BG",
    default_currency: "EUR",
    status: "pending_kyc",
  },
  role: "owner",
});

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("ApiClient", () => {
  it("sends the bearer token after login", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(200, session("tok-1")))
      .mockResolvedValueOnce(json(200, { user: {}, memberships: [] }));
    const client = new ApiClient({ fetch: fetchMock });

    await client.login({ email: "a@b.c", password: "x" });
    await client.me();

    const headers = fetchMock.mock.calls[1]?.[1]?.headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer tok-1");
  });

  it("refreshes once and retries when the access token has expired", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(401, { error: { code: "invalid_token", message: "expired" } })) // GET /me
      .mockResolvedValueOnce(json(200, session("tok-2"))) // POST /auth/refresh
      .mockResolvedValueOnce(json(200, { user: {}, memberships: [] })); // GET /me retry
    const client = new ApiClient({ fetch: fetchMock });

    await client.me();

    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(["/me", "/auth/refresh", "/me"]);
    const retryHeaders = fetchMock.mock.calls[2]?.[1]?.headers as Record<string, string>;
    expect(retryHeaders.Authorization).toBe("Bearer tok-2");
  });

  it("shares a single refresh request between concurrent callers", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => json(200, session("tok-3")));
    const client = new ApiClient({ fetch: fetchMock });

    const [a, b] = await Promise.all([client.restoreSession(), client.restoreSession()]);

    expect(a).toEqual(b);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reports an expired session when refresh fails", async () => {
    const onSessionExpired = vi.fn();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json(401, { error: { code: "invalid_token", message: "expired" } }))
      .mockResolvedValueOnce(json(401, { error: { code: "invalid_refresh_token", message: "sign in" } }));
    const client = new ApiClient({ fetch: fetchMock, onSessionExpired });

    await expect(client.me()).rejects.toBeInstanceOf(ApiError);
    expect(onSessionExpired).toHaveBeenCalledOnce();
  });

  it("maps error bodies to ApiError", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        json(409, { error: { type: "invalid_request_error", code: "email_taken", message: "taken", param: "email" } }),
      );
    const client = new ApiClient({ fetch: fetchMock });

    await expect(
      client.signup({ email: "a@b.c", password: "x", full_name: "A", business_name: "B", country: "BG" }),
    ).rejects.toMatchObject({ status: 409, code: "email_taken", param: "email" });
  });

  describe("balances and payouts", () => {
    const ok = (body: unknown) => vi.fn<typeof fetch>().mockResolvedValue(json(200, body));
    const urlOf = (fetchMock: ReturnType<typeof ok>) => String(fetchMock.mock.calls[0]?.[0]);

    it("leaves out empty filters and the default test mode", async () => {
      const fetchMock = ok({ object: "list", data: [], has_more: false });
      await new ApiClient({ fetch: fetchMock }).listBalanceTransactions({
        type: "",
        source: "pay_1",
        livemode: false,
        limit: 20,
      });
      expect(urlOf(fetchMock)).toBe("/v1/balance_transactions?source=pay_1&limit=20");
    });

    it("asks for live data only when told to", async () => {
      const fetchMock = ok({ object: "balance" });
      await new ApiClient({ fetch: fetchMock }).getBalance(true);
      expect(urlOf(fetchMock)).toBe("/v1/balance?livemode=true");
    });

    it("sends an idempotency key when paying out now, so a double click pays out once", async () => {
      const fetchMock = ok({ id: "po_1" });
      await new ApiClient({ fetch: fetchMock }).createPayout("EUR");
      const init = fetchMock.mock.calls[0]?.[1];
      expect(urlOf(fetchMock)).toBe("/v1/payouts");
      expect(init?.method).toBe("POST");
      expect(JSON.parse(String(init?.body))).toEqual({ currency: "EUR" });
      expect((init?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
    });

    it("encodes ids in paths and sends DELETE for bank accounts", async () => {
      const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(json(200, { id: "ba_1", deleted: true }));
      await new ApiClient({ fetch: fetchMock }).deleteBankAccount("ba_1");
      expect(urlOf(fetchMock)).toBe("/v1/bank_accounts/ba_1");
      expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("DELETE");
    });
  });

  describe("pay in instalments", () => {
    const urlAt = (fetchMock: ReturnType<typeof vi.fn<typeof fetch>>, call: number) => String(fetchMock.mock.calls[call]?.[0]);

    it("reads and writes the settings in the mode asked for", async () => {
      const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => json(200, { object: "bnpl_settings", providers: [] }));
      const client = new ApiClient({ fetch: fetchMock });

      await client.getBnplSettings();
      await client.updateBnplSettings({ routing_mode: "cheapest", providers: [{ code: "lendo", enabled: false }] }, true);

      expect(urlAt(fetchMock, 0)).toBe("/v1/bnpl/settings");
      expect(urlAt(fetchMock, 1)).toBe("/v1/bnpl/settings?livemode=true");
      expect(fetchMock.mock.calls[1]?.[1]?.method).toBe("PUT");
      expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
        routing_mode: "cheapest",
        providers: [{ code: "lendo", enabled: false }],
      });
    });

    it("lists applications with filters, and asks for analytics over a period", async () => {
      const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => json(200, { object: "list", data: [], has_more: false }));
      const client = new ApiClient({ fetch: fetchMock });

      await client.listBnplApplications({ status: "declined", provider: "lendo", limit: 5 });
      await client.getBnplAnalytics(90);
      await client.getBnplApplication("bnpl_1");

      expect(urlAt(fetchMock, 0)).toBe("/v1/bnpl/applications?status=declined&provider=lendo&limit=5");
      expect(urlAt(fetchMock, 1)).toBe("/v1/bnpl/analytics?days=90");
      expect(urlAt(fetchMock, 2)).toBe("/v1/bnpl/applications/bnpl_1");
    });

    it("asks why a provider was chosen, and a payment without a decision is a 404", async () => {
      const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(json(404, { error: { type: "invalid_request_error", code: "routing_decision_not_found", message: "none" } }));
      const client = new ApiClient({ fetch: fetchMock });

      await expect(client.getRoutingDecision("pay_1")).rejects.toMatchObject({ status: 404, code: "routing_decision_not_found" });
      expect(urlAt(fetchMock, 0)).toBe("/v1/bnpl/routing_decisions/pay_1");
    });
  });
});
