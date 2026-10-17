// Types for the Developers area: API keys, webhook endpoints, events and the request log.

export type ApiKeyType = "secret" | "publishable" | "restricted";
export type ApiKeyStatus = "active" | "expiring" | "expired" | "revoked";

export interface ApiKey {
  id: string;
  object: "api_key";
  name: string;
  type: ApiKeyType;
  livemode: boolean;
  /** The first characters of the key, safe to show (for example "sk_test_Ab3x"). */
  prefix: string;
  last4: string;
  scopes: string[];
  status: ApiKeyStatus;
  created_at: string;
  last_used_at: string | null;
  expires_at: string | null;
  revoked_at: string | null;
  /** The full key. Only present on the response that created it; it cannot be shown again. */
  secret?: string | null;
}

export interface ApiKeyList {
  object: "list";
  data: ApiKey[];
}

export interface CreateApiKeyInput {
  name: string;
  type: ApiKeyType;
  livemode: boolean;
  /** Only for restricted keys, such as "payments:read". */
  scopes?: string[];
}

export interface WebhookEndpoint {
  id: string;
  object: "webhook_endpoint";
  url: string;
  description: string | null;
  enabled_events: string[];
  status: "enabled" | "disabled";
  disabled_reason: string | null;
  livemode: boolean;
  created_at: string;
  /** The signing secret. Only present on the response that created the endpoint. */
  secret?: string | null;
}

export interface WebhookEndpointList {
  object: "list";
  data: WebhookEndpoint[];
}

export interface CreateWebhookEndpointInput {
  url: string;
  enabled_events: string[];
  description?: string;
}

export interface UpdateWebhookEndpointInput {
  url?: string;
  enabled_events?: string[];
  description?: string;
  status?: "enabled" | "disabled";
}

export interface DeliveryAttempt {
  attempt: number;
  response_code: number | null;
  response_body_snippet: string | null;
  error: string | null;
  duration_ms: number;
  created_at: string;
}

export interface WebhookDelivery {
  id: string;
  object: "webhook_delivery";
  event_id: string;
  event_type: string;
  endpoint_id: string;
  status: "pending" | "succeeded" | "failed";
  attempts: number;
  next_attempt_at: string | null;
  last_response_code: number | null;
  livemode: boolean;
  created_at: string;
  /** Only on a single delivery, not in lists. */
  attempt_log: DeliveryAttempt[] | null;
}

export interface WebhookDeliveryList {
  object: "list";
  data: WebhookDelivery[];
  has_more: boolean;
}

export interface WebhookDeliveryListParams {
  endpoint?: string;
  event?: string;
  status?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

/** An event exactly as it is sent to endpoints. */
export interface CutpayEvent {
  id: string;
  object: "event";
  type: string;
  api_version: string;
  livemode: boolean;
  created_at: string;
  data: { object: Record<string, unknown> };
}

export interface CutpayEventList {
  object: "list";
  data: CutpayEvent[];
  has_more: boolean;
}

export interface CutpayEventListParams {
  type?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface RequestLogEntry {
  id: string;
  object: "api_request";
  method: string;
  path: string;
  status: number;
  latency_ms: number;
  actor: string | null;
  actor_type: "api_key" | "user" | null;
  idempotency_key: string | null;
  request_id: string | null;
  livemode: boolean;
  created_at: string;
}

export interface RequestLogList {
  object: "list";
  data: RequestLogEntry[];
  has_more: boolean;
}

export interface RequestLogParams {
  method?: string;
  /** An exact code ("404") or a class ("4xx"). */
  status?: string;
  /** Matches the start of the path. */
  path?: string;
  actor?: string;
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}
