// The postMessage protocol between the SDK (on the merchant page) and the card iframe (on our origin).
//
// Rules both sides follow:
//  - every message carries SOURCE and VERSION, and anything else is ignored;
//  - the receiver checks event.origin AND event.source, never the message content, to decide who sent it;
//  - the sender always names the target origin, never "*";
//  - card details never appear in any message. The iframe turns them into a payment result itself.

export const SOURCE = "cutpay-elements";
export const VERSION = 1;

export type CardBrand = "visa" | "mastercard" | "amex" | "unknown";

export interface ChangeState {
  /** Number, expiry and CVC are all present and valid. */
  complete: boolean;
  empty: boolean;
  brand: CardBrand;
  /** What to show the customer, or null. Never contains card data. */
  error: string | null;
}

export interface ElementError {
  type: "validation_error" | "card_error" | "api_error";
  code: string;
  message: string;
}

export interface SessionSummary {
  id: string;
  status: string;
  amount: number;
  currency: string;
  failure_code: string | null;
  failure_message: string | null;
  next_action: { type: "three_ds" | "blik"; challenge_id: string } | null;
  return_url: string | null;
}

export type ConfirmResult = { ok: true; session: SessionSummary } | { ok: false; error: ElementError };

/** Sent by the iframe. */
export type FrameMessage =
  | { type: "ready" }
  | { type: "change"; state: ChangeState }
  | { type: "height"; height: number }
  | { type: "focus" }
  | { type: "blur" }
  | { type: "confirm_result"; requestId: string; result: ConfirmResult };

/** Sent by the SDK. */
export type ParentMessage =
  | { type: "confirm"; requestId: string; clientSecret: string; email?: string }
  | { type: "focus" }
  | { type: "clear" };

export type Envelope<T> = T & { source: typeof SOURCE; version: typeof VERSION };

export function wrap<T extends { type: string }>(message: T): Envelope<T> {
  return { ...message, source: SOURCE, version: VERSION };
}

const FRAME_TYPES = new Set(["ready", "change", "height", "focus", "blur", "confirm_result"]);
const PARENT_TYPES = new Set(["confirm", "focus", "clear"]);

function isEnvelope(data: unknown): data is Record<string, unknown> & { type: string } {
  if (typeof data !== "object" || data === null) return false;
  const record = data as Record<string, unknown>;
  return record.source === SOURCE && record.version === VERSION && typeof record.type === "string";
}

/** Returns the message when it is a well-formed one from the iframe, otherwise null. */
export function parseFrameMessage(data: unknown): FrameMessage | null {
  if (!isEnvelope(data) || !FRAME_TYPES.has(data.type)) return null;
  switch (data.type) {
    case "change":
      return typeof data.state === "object" && data.state !== null ? (data as unknown as FrameMessage) : null;
    case "height":
      return typeof data.height === "number" && Number.isFinite(data.height) ? (data as unknown as FrameMessage) : null;
    case "confirm_result":
      return typeof data.requestId === "string" && typeof data.result === "object" && data.result !== null
        ? (data as unknown as FrameMessage)
        : null;
    default:
      return data as unknown as FrameMessage;
  }
}

/** Returns the message when it is a well-formed one from the SDK, otherwise null. */
export function parseParentMessage(data: unknown): ParentMessage | null {
  if (!isEnvelope(data) || !PARENT_TYPES.has(data.type)) return null;
  if (data.type === "confirm") {
    return typeof data.requestId === "string" && typeof data.clientSecret === "string"
      ? (data as unknown as ParentMessage)
      : null;
  }
  return data as unknown as ParentMessage;
}
