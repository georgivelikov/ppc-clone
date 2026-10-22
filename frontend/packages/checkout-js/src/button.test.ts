import { afterEach, describe, expect, it, vi } from "vitest";
import { mount, mountAll } from "./button";

function host(attrs: Record<string, string>) {
  const el = document.createElement("div");
  el.setAttribute("data-cutpay-button", "");
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(`data-${k}`, v);
  document.body.append(el);
  return el;
}

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("pay button", () => {
  it("shows a button with the default or the given label", () => {
    const a = host({ link: "https://checkout.test/l/mug" });
    const b = host({ link: "https://checkout.test/l/mug", label: "Buy now" });
    mountAll();
    expect(a.querySelector("button")?.textContent).toBe("Pay with Cutpay");
    expect(b.querySelector("button")?.textContent).toBe("Buy now");
  });

  it("does nothing without a link or an endpoint, and says why", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const el = host({});
    expect(mount(el)).toBeNull();
    expect(warn).toHaveBeenCalled();
  });

  it("refuses an address that is not a web address", () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(mount(host({ link: "javascript:alert(1)" }))).toBeNull();
    expect(mount(host({ endpoint: "data:text/html,x" }))).toBeNull();
  });

  it("mounts once even if asked twice", () => {
    const el = host({ link: "https://checkout.test/l/mug" });
    mountAll();
    mountAll();
    expect(el.querySelectorAll("button")).toHaveLength(1);
  });

  it("opens the payment link in a new tab when asked", () => {
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    const el = host({ link: "https://checkout.test/l/mug", "new-tab": "true" });
    mountAll();
    el.querySelector("button")!.click();
    return vi.waitFor(() => expect(open).toHaveBeenCalledWith("https://checkout.test/l/mug", "_blank", "noopener"));
  });

  it("asks the shop's server for a session and goes to the page it returns", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ url: "https://checkout.test/c/pay_1_secret" }) });
    vi.stubGlobal("fetch", fetchMock);
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    const el = host({ endpoint: "https://shop.test/api/pay", "new-tab": "true", body: '{"cart":"7"}' });
    mountAll();
    el.querySelector("button")!.click();
    await vi.waitFor(() => expect(open).toHaveBeenCalledWith("https://checkout.test/c/pay_1_secret", "_blank", "noopener"));
    expect(fetchMock).toHaveBeenCalledWith("https://shop.test/api/pay", expect.objectContaining({ method: "POST", body: '{"cart":"7"}' }));
  });

  it("shows a message and stays on the page when the shop fails or answers something unsafe", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ url: "javascript:alert(1)" }) }));
    const el = host({ endpoint: "https://shop.test/api/pay" });
    mountAll();
    el.querySelector("button")!.click();
    await vi.waitFor(() => expect(el.querySelector("[role=alert]")?.textContent).toContain("did not return a payment page"));
    expect(el.querySelector("button")!.disabled).toBe(false);
  });
});
