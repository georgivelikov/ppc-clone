import { ApiError, type ApiKey, type CutpayEvent, type RequestLogEntry, type WebhookDelivery, type WebhookEndpoint } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiKeysPage } from "./ApiKeysPage";
import { EventsPage } from "./EventsPage";
import { LogsPage } from "./LogsPage";
import { WebhookDetailPage } from "./WebhookDetailPage";
import { WebhooksPage } from "./WebhooksPage";

vi.mock("../../api", () => ({
  api: {
    listApiKeys: vi.fn(),
    createApiKey: vi.fn(),
    rollApiKey: vi.fn(),
    revokeApiKey: vi.fn(),
    listWebhookEndpoints: vi.fn(),
    getWebhookEndpoint: vi.fn(),
    createWebhookEndpoint: vi.fn(),
    updateWebhookEndpoint: vi.fn(),
    deleteWebhookEndpoint: vi.fn(),
    testWebhookEndpoint: vi.fn(),
    listWebhookDeliveries: vi.fn(),
    getWebhookDelivery: vi.fn(),
    retryWebhookDelivery: vi.fn(),
    listEvents: vi.fn(),
    listRequestLogs: vi.fn(),
  },
}));

const session = { role: "owner", merchant: { display_name: "Rose Cafe" } };
vi.mock("../../auth/AuthProvider", () => ({ useSession: () => session }));

let testMode = true;
vi.mock("../../layout/testMode", () => ({ useTestMode: () => ({ testMode, setTestMode: vi.fn() }) }));

const { api } = await import("../../api");

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/developers/api-keys" element={<ApiKeysPage />} />
          <Route path="/developers/webhooks" element={<WebhooksPage />} />
          <Route path="/developers/webhooks/:id" element={<WebhookDetailPage />} />
          <Route path="/developers/events" element={<EventsPage />} />
          <Route path="/developers/logs" element={<LogsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  testMode = true;
  session.role = "owner";
});

// --- API keys ----------------------------------------------------------------------------------------

const key = (over: Partial<ApiKey> = {}): ApiKey => ({
  id: "key_1",
  object: "api_key",
  name: "Server",
  type: "secret",
  livemode: false,
  prefix: "sk_test_Ab3x",
  last4: "9zQ1",
  scopes: ["*"],
  status: "active",
  created_at: "2026-10-17T10:00:00Z",
  last_used_at: null,
  expires_at: null,
  revoked_at: null,
  ...over,
});

describe("API keys", () => {
  it("lists only the keys of the current mode, without ever showing a secret", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({
      object: "list",
      data: [key(), key({ id: "key_2", name: "Live server", livemode: true, prefix: "sk_live_Zz9y", last4: "AAAA" })],
    });
    renderAt("/developers/api-keys");
    expect(await screen.findByText("Server")).toBeInTheDocument();
    expect(screen.getByText("sk_test_Ab3x…9zQ1")).toBeInTheDocument();
    expect(screen.queryByText("Live server")).not.toBeInTheDocument();
  });

  it("shows live keys when test mode is off", async () => {
    testMode = false;
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [key(), key({ id: "key_2", name: "Live server", livemode: true })] });
    renderAt("/developers/api-keys");
    expect(await screen.findByText("Live server")).toBeInTheDocument();
    expect(screen.queryByText("Server")).not.toBeInTheDocument();
  });

  it("creates a key in the current mode and reveals its secret once", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.createApiKey).mockResolvedValue(key({ secret: "sk_test_" + "x".repeat(32) }));
    renderAt("/developers/api-keys");
    await userEvent.click(await screen.findByRole("button", { name: "Create API key" }));
    await userEvent.type(screen.getByLabelText("Name"), "Production server");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));

    expect(api.createApiKey).toHaveBeenCalledWith({ name: "Production server", type: "secret", livemode: false, scopes: undefined });
    expect(await screen.findByTestId("revealed-secret")).toHaveTextContent("sk_test_" + "x".repeat(32));
    expect(screen.getByText(/will not be shown again/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "I have saved it" }));
    expect(screen.queryByTestId("revealed-secret")).not.toBeInTheDocument();
  });

  it("creates a restricted key with the chosen permissions and needs at least one", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.createApiKey).mockResolvedValue(key({ type: "restricted", secret: "rk_test_" + "y".repeat(32) }));
    renderAt("/developers/api-keys");
    await userEvent.click(await screen.findByRole("button", { name: "Create API key" }));
    await userEvent.type(screen.getByLabelText("Name"), "Reporting");
    await userEvent.click(screen.getByRole("radio", { name: /Restricted/ }));
    expect(screen.getByRole("button", { name: "Create key" })).toBeDisabled();

    await userEvent.selectOptions(screen.getByLabelText("Payments"), "read");
    await userEvent.selectOptions(screen.getByLabelText("Refunds"), "write");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    expect(api.createApiKey).toHaveBeenCalledWith({
      name: "Reporting",
      type: "restricted",
      livemode: false,
      scopes: ["payments:read", "refunds:write"],
    });
  });

  it("rolls a key with a grace period and reveals the replacement", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [key()] });
    vi.mocked(api.rollApiKey).mockResolvedValue(key({ id: "key_3", secret: "sk_test_" + "n".repeat(32) }));
    renderAt("/developers/api-keys");
    await userEvent.click(await screen.findByRole("button", { name: "Roll Server" }));
    await userEvent.click(screen.getByRole("radio", { name: /24 hours/ }));
    await userEvent.click(screen.getByRole("button", { name: "Roll key" }));

    expect(api.rollApiKey).toHaveBeenCalledWith("key_1", 24);
    expect(await screen.findByTestId("revealed-secret")).toHaveTextContent("n".repeat(32));
  });

  it("revokes a key after confirmation", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [key()] });
    vi.mocked(api.revokeApiKey).mockResolvedValue(undefined);
    renderAt("/developers/api-keys");
    await userEvent.click(await screen.findByRole("button", { name: "Revoke Server" }));
    expect(api.revokeApiKey).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Revoke key" }));
    await waitFor(() => expect(api.revokeApiKey).toHaveBeenCalledWith("key_1"));
  });

  it("offers no actions on revoked keys, and none at all to viewers", async () => {
    vi.mocked(api.listApiKeys).mockResolvedValue({ object: "list", data: [key({ status: "revoked" }), key({ id: "key_9", name: "Other" })] });
    renderAt("/developers/api-keys");
    await screen.findByText("Other");
    expect(screen.getAllByRole("button", { name: /^Roll / })).toHaveLength(1);

    session.role = "viewer";
    renderAt("/developers/api-keys");
    await waitFor(() => expect(screen.getAllByRole("button", { name: "Create API key" }).at(-1)).toBeDisabled());
  });
});

// --- webhooks ----------------------------------------------------------------------------------------

const endpoint = (over: Partial<WebhookEndpoint> = {}): WebhookEndpoint => ({
  id: "we_1",
  object: "webhook_endpoint",
  url: "https://shop.example.com/hooks",
  description: "Main shop",
  enabled_events: ["*"],
  status: "enabled",
  disabled_reason: null,
  livemode: false,
  created_at: "2026-10-17T10:00:00Z",
  ...over,
});

describe("webhook endpoints", () => {
  it("lists endpoints with what they listen to", async () => {
    vi.mocked(api.listWebhookEndpoints).mockResolvedValue({
      object: "list",
      data: [endpoint(), endpoint({ id: "we_2", url: "https://b.example.com", enabled_events: ["payout.paid", "payout.failed", "charge.refunded"], status: "disabled" })],
    });
    renderAt("/developers/webhooks");
    expect(await screen.findByText("https://shop.example.com/hooks")).toBeInTheDocument();
    expect(screen.getByText("All events")).toBeInTheDocument();
    expect(screen.getByText("payout.paid, payout.failed +1 more")).toBeInTheDocument();
    expect(screen.getByText("Disabled")).toBeInTheDocument();
    expect(api.listWebhookEndpoints).toHaveBeenCalledWith(false);
  });

  it("adds an endpoint for selected events and reveals the signing secret once", async () => {
    vi.mocked(api.listWebhookEndpoints).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.createWebhookEndpoint).mockResolvedValue(endpoint({ secret: "whsec_" + "k".repeat(32) }));
    renderAt("/developers/webhooks");
    await userEvent.click(await screen.findByRole("button", { name: "Add endpoint" }));
    await userEvent.type(screen.getByLabelText("Endpoint URL"), "https://shop.example.com/hooks");
    await userEvent.click(screen.getByRole("checkbox", { name: "All events" })); // untick, show the list
    await userEvent.click(screen.getByRole("checkbox", { name: "payout.paid" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "payment_intent.succeeded" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add endpoint" }));

    expect(api.createWebhookEndpoint).toHaveBeenCalledWith(
      { url: "https://shop.example.com/hooks", enabled_events: ["payout.paid", "payment_intent.succeeded"], description: undefined },
      false,
    );
    expect(await screen.findByTestId("revealed-secret")).toHaveTextContent("whsec_" + "k".repeat(32));
  });

  it("cannot add an endpoint without choosing any event", async () => {
    vi.mocked(api.listWebhookEndpoints).mockResolvedValue({ object: "list", data: [] });
    renderAt("/developers/webhooks");
    await userEvent.click(await screen.findByRole("button", { name: "Add endpoint" }));
    await userEvent.type(screen.getByLabelText("Endpoint URL"), "https://shop.example.com/hooks");
    await userEvent.click(screen.getByRole("checkbox", { name: "All events" }));
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Add endpoint" })).toBeDisabled();
  });

  it("shows the server's complaint next to the URL field", async () => {
    testMode = false;
    vi.mocked(api.listWebhookEndpoints).mockResolvedValue({ object: "list", data: [] });
    vi.mocked(api.createWebhookEndpoint).mockRejectedValue(new ApiError(400, "invalid_request_error", "invalid_url", "Live mode endpoints must use https", "url"));
    renderAt("/developers/webhooks");
    await userEvent.click(await screen.findByRole("button", { name: "Add endpoint" }));
    await userEvent.type(screen.getByLabelText("Endpoint URL"), "http://shop.example.com");
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add endpoint" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("must use https");
    expect(api.createWebhookEndpoint).toHaveBeenCalledWith(expect.anything(), true);
  });
});

const delivery = (over: Partial<WebhookDelivery> = {}): WebhookDelivery => ({
  id: "whd_1",
  object: "webhook_delivery",
  event_id: "evt_1",
  event_type: "payment_intent.succeeded",
  endpoint_id: "we_1",
  status: "failed",
  attempts: 2,
  next_attempt_at: null,
  last_response_code: 500,
  livemode: false,
  created_at: "2026-10-17T10:00:00Z",
  attempt_log: null,
  ...over,
});

describe("webhook endpoint detail", () => {
  beforeEach(() => {
    vi.mocked(api.getWebhookEndpoint).mockResolvedValue(endpoint());
    vi.mocked(api.listWebhookDeliveries).mockResolvedValue({ object: "list", data: [delivery()], has_more: false });
  });

  it("shows deliveries and, on request, every attempt with the response", async () => {
    vi.mocked(api.getWebhookDelivery).mockResolvedValue(
      delivery({
        attempt_log: [
          { attempt: 1, response_code: 500, response_body_snippet: "broken", error: null, duration_ms: 31, created_at: "2026-10-17T10:00:01Z" },
          { attempt: 2, response_code: null, response_body_snippet: null, error: "ConnectException", duration_ms: 5, created_at: "2026-10-17T10:01:01Z" },
        ],
      }),
    );
    renderAt("/developers/webhooks/we_1");
    await userEvent.click(await screen.findByRole("button", { name: "payment_intent.succeeded" }));
    const dialog = await screen.findByRole("dialog", { name: "Delivery" });
    expect(await within(dialog).findByText("#1 HTTP 500")).toBeInTheDocument();
    expect(within(dialog).getByText("broken")).toBeInTheDocument();
    expect(within(dialog).getByText("#2 No response")).toBeInTheDocument();
    expect(within(dialog).getByText("ConnectException")).toBeInTheDocument();
  });

  it("resends a delivery", async () => {
    vi.mocked(api.getWebhookDelivery).mockResolvedValue(delivery({ attempt_log: [] }));
    vi.mocked(api.retryWebhookDelivery).mockResolvedValue(delivery({ status: "pending" }));
    renderAt("/developers/webhooks/we_1");
    await userEvent.click(await screen.findByRole("button", { name: "payment_intent.succeeded" }));
    await userEvent.click(await screen.findByRole("button", { name: "Resend" }));
    await waitFor(() => expect(api.retryWebhookDelivery).toHaveBeenCalledWith("whd_1"));
  });

  it("sends a test event and can disable the endpoint", async () => {
    vi.mocked(api.testWebhookEndpoint).mockResolvedValue({ delivery_id: "whd_9", endpoint: "we_1" });
    vi.mocked(api.updateWebhookEndpoint).mockResolvedValue(endpoint({ status: "disabled" }));
    renderAt("/developers/webhooks/we_1");
    await userEvent.click(await screen.findByRole("button", { name: "Send test event" }));
    expect(await screen.findByText(/Test event queued/)).toBeInTheDocument();
    expect(api.testWebhookEndpoint).toHaveBeenCalledWith("we_1");

    await userEvent.click(screen.getByRole("button", { name: "Disable" }));
    await waitFor(() => expect(api.updateWebhookEndpoint).toHaveBeenCalledWith("we_1", { status: "disabled" }));
  });

  it("explains why an endpoint was disabled and blocks test events", async () => {
    vi.mocked(api.getWebhookEndpoint).mockResolvedValue(
      endpoint({ status: "disabled", disabled_reason: "Disabled automatically after failing for 3 days" }),
    );
    renderAt("/developers/webhooks/we_1");
    expect(await screen.findByRole("alert")).toHaveTextContent("Disabled automatically after failing for 3 days");
    expect(screen.getByRole("button", { name: "Send test event" })).toBeDisabled();
  });

  it("changes the events after confirmation of the picker", async () => {
    vi.mocked(api.updateWebhookEndpoint).mockResolvedValue(endpoint({ enabled_events: ["payout.paid"] }));
    renderAt("/developers/webhooks/we_1");
    await userEvent.click(await screen.findByRole("button", { name: "Change" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "All events" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "payout.paid" }));
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.updateWebhookEndpoint).toHaveBeenCalledWith("we_1", { enabled_events: ["payout.paid"] }));
  });

  it("reports an unknown endpoint", async () => {
    vi.mocked(api.getWebhookEndpoint).mockRejectedValue(new ApiError(404, "invalid_request_error", "webhook_endpoint_not_found", "No such webhook endpoint"));
    renderAt("/developers/webhooks/we_x");
    expect(await screen.findByRole("alert")).toHaveTextContent("could not find that endpoint");
  });
});

// --- events and logs ---------------------------------------------------------------------------------

const event = (over: Partial<CutpayEvent> = {}): CutpayEvent => ({
  id: "evt_1",
  object: "event",
  type: "payment_intent.succeeded",
  api_version: "2026-10-01",
  livemode: false,
  created_at: "2026-10-17T10:00:00Z",
  data: { object: { id: "pay_1", amount: 2500 } },
  ...over,
});

describe("events", () => {
  it("lists events, filters by type and shows the JSON as sent", async () => {
    vi.mocked(api.listEvents).mockResolvedValue({ object: "list", data: [event()], has_more: false });
    renderAt("/developers/events");
    await userEvent.click(await screen.findByRole("button", { name: "payment_intent.succeeded" }));
    expect(screen.getByTestId("event-json")).toHaveTextContent('"amount": 2500');
    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    await userEvent.selectOptions(screen.getByLabelText("Filter by event type"), "payout.paid");
    await waitFor(() => expect(api.listEvents).toHaveBeenLastCalledWith(expect.objectContaining({ type: "payout.paid", livemode: false })));
  });

  it("asks for the next page with the last id", async () => {
    vi.mocked(api.listEvents)
      .mockResolvedValueOnce({ object: "list", data: [event()], has_more: true })
      .mockResolvedValueOnce({ object: "list", data: [event({ id: "evt_2", type: "payout.paid" })], has_more: false });
    renderAt("/developers/events");
    await userEvent.click(await screen.findByRole("button", { name: "Load more" }));
    expect(await screen.findByText("payout.paid", { selector: "button" })).toBeInTheDocument();
    expect(api.listEvents).toHaveBeenLastCalledWith(expect.objectContaining({ starting_after: "evt_1" }));
  });
});

const entry = (over: Partial<RequestLogEntry> = {}): RequestLogEntry => ({
  id: "rl_1",
  object: "api_request",
  method: "POST",
  path: "/v1/payment_intents",
  status: 201,
  latency_ms: 42,
  actor: "key_1",
  actor_type: "api_key",
  idempotency_key: "idem-1",
  request_id: "req_1",
  livemode: false,
  created_at: "2026-10-17T10:00:00Z",
  ...over,
});

describe("logs", () => {
  it("lists calls with status, caller and idempotency key", async () => {
    vi.mocked(api.listRequestLogs).mockResolvedValue({
      object: "list",
      data: [entry(), entry({ id: "rl_2", method: "GET", path: "/v1/balance", status: 500, actor_type: "user", actor: "usr_1", idempotency_key: null })],
      has_more: false,
    });
    renderAt("/developers/logs");
    expect(await screen.findByText("/v1/payment_intents", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("201")).toBeInTheDocument();
    expect(screen.getByText("500")).toBeInTheDocument();
    expect(screen.getByText("Idempotency-Key: idem-1")).toBeInTheDocument();
    expect(screen.getByText("API key")).toBeInTheDocument();
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
  });

  it("filters by status, method and path", async () => {
    vi.mocked(api.listRequestLogs).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderAt("/developers/logs");
    await screen.findByText(/No requests match/);
    await userEvent.selectOptions(screen.getByLabelText("Filter by status"), "4xx");
    await userEvent.selectOptions(screen.getByLabelText("Filter by method"), "POST");
    await userEvent.type(screen.getByLabelText("Filter by path"), "/v1/refunds");
    await waitFor(() =>
      expect(api.listRequestLogs).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: "4xx", method: "POST", path: "/v1/refunds", livemode: false }),
      ),
    );
  });

  it("asks for live data when test mode is off", async () => {
    testMode = false;
    vi.mocked(api.listRequestLogs).mockResolvedValue({ object: "list", data: [], has_more: false });
    renderAt("/developers/logs");
    await screen.findByText(/No requests match/);
    expect(api.listRequestLogs).toHaveBeenCalledWith(expect.objectContaining({ livemode: true }));
  });
});
