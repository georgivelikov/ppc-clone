/**
 * A pay button for any web page, no build step and no framework:
 *
 *   <div data-cutpay-button data-link="https://checkout.example.com/l/mug-club"></div>
 *   <script src="https://checkout.example.com/v1/cutpay-button.js" async></script>
 *
 * Two ways to start a payment, both ending on Cutpay's hosted page (the card is never typed on your site):
 *   data-link      a payment link (made in the dashboard): the button opens it. Needs no server of your own.
 *   data-endpoint  your own server URL that creates a checkout session with your secret key and answers {"url": "..."}:
 *                  the button posts to it and redirects. For a price that depends on the cart.
 * Optional: data-label (default "Pay with Cutpay"), data-new-tab="true", data-body='{"cart":"123"}' (JSON sent to the endpoint).
 *
 * The secret key must never be in a page, so there is deliberately no way to make a session from the browser alone.
 */

const LABEL = "Pay with Cutpay";

function safeUrl(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value, window.location.href);
    // Only web addresses: a javascript: or data: URL in an attribute must not become a click action.
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

async function sessionUrl(endpoint: string, body: string | undefined): Promise<string> {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body ?? "{}",
    credentials: "same-origin",
  });
  if (!response.ok) throw new Error(`The shop could not start the payment (${response.status})`);
  const data = (await response.json()) as { url?: string };
  const url = safeUrl(data.url);
  if (!url) throw new Error("The shop did not return a payment page");
  return url;
}

export function mount(host: HTMLElement): HTMLButtonElement | null {
  if (host.dataset.cutpayMounted) return null;
  const link = safeUrl(host.dataset.link);
  const endpoint = safeUrl(host.dataset.endpoint);
  if (!link && !endpoint) {
    console.warn("Cutpay button: set data-link or data-endpoint", host);
    return null;
  }
  host.dataset.cutpayMounted = "true";

  const button = document.createElement("button");
  button.type = "button";
  button.textContent = host.dataset.label || LABEL;
  button.setAttribute("style", "font:600 16px system-ui,sans-serif;padding:12px 22px;border:0;border-radius:8px;background:#1f55e0;color:#fff;cursor:pointer");
  const message = document.createElement("p");
  message.setAttribute("role", "alert");
  message.setAttribute("style", "font:14px system-ui,sans-serif;color:#b42318;margin:8px 0 0");
  message.hidden = true;

  button.addEventListener("click", async () => {
    message.hidden = true;
    button.disabled = true;
    try {
      const target = endpoint ? await sessionUrl(endpoint, host.dataset.body) : link!;
      if (host.dataset.newTab === "true") window.open(target, "_blank", "noopener");
      else window.location.assign(target);
    } catch (error) {
      message.textContent = error instanceof Error ? error.message : "Something went wrong";
      message.hidden = false;
    } finally {
      button.disabled = false;
    }
  });

  host.append(button, message);
  return button;
}

export function mountAll(root: ParentNode = document): void {
  root.querySelectorAll<HTMLElement>("[data-cutpay-button]").forEach((host) => mount(host));
}

if (typeof document !== "undefined") {
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => mountAll());
  else mountAll();
}
