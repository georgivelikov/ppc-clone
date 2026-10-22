import { afterEach, describe, expect, it, vi } from "vitest";
import { call, newTraceparent } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("trace context", () => {
  it("is a valid W3C traceparent, new every time", () => {
    const a = newTraceparent();
    expect(a).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    expect(newTraceparent()).not.toBe(a);
  });

  it("is sent with every call, so the backend continues the browser's trace", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);
    await call("GET", "/checkout/sessions/x");
    const headers = (fetchMock.mock.calls[0]?.[1] as { headers: Record<string, string> }).headers;
    expect(headers.traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
  });
});
