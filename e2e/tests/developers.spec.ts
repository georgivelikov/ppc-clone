import { createHmac } from "node:crypto";
import { createServer, type Server } from "node:http";
import { expect, test, type Page } from "@playwright/test";

const GATEWAY = "http://localhost:8080";
const PASSWORD = "correct-horse-battery";
const uniqueEmail = () => `dev+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

async function signUp(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Dana Developer");
  await page.getByLabel("Business name").fill("Dev Cafe");
  await page.getByLabel("Country").selectOption("BG");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Dev Cafe");
}

/** Creates a key through the dashboard and returns the secret from the reveal-once dialog. */
async function createKey(page: Page, name: string, type: "Secret" | "Publishable" | "Restricted" = "Secret"): Promise<string> {
  await page.getByRole("button", { name: "Create API key" }).click();
  await page.getByLabel("Name").fill(name);
  if (type !== "Secret") await page.getByRole("radio", { name: new RegExp(type) }).check();
  if (type === "Restricted") await page.getByLabel("Payments", { exact: true }).selectOption("read");
  await page.getByRole("button", { name: "Create key" }).click();
  const secret = (await page.getByTestId("revealed-secret").textContent())!.trim();
  await page.getByRole("button", { name: "I have saved it" }).click();
  return secret;
}

test.describe("developers", () => {
  test("creates, uses, rolls and revokes API keys, and sees the calls in the log", async ({ page, request }) => {
    await signUp(page);
    await page.getByRole("link", { name: "Developers" }).click();
    await expect(page).toHaveURL(/\/developers\/api-keys/);
    await expect(page.getByText("You are viewing test data")).toBeVisible();

    // Reveal once: the secret is in the dialog, never in the list.
    const secret = await createKey(page, "Production server");
    expect(secret).toMatch(/^sk_test_[0-9A-Za-z]{32}$/);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    const row = page.getByRole("row", { name: /Production server/ });
    await expect(row).toContainText(secret.slice(0, 12));
    await expect(row).toContainText(secret.slice(-4));
    await expect(page.locator("body")).not.toContainText(secret);

    // The key works against the public API...
    const ok = await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer ${secret}` } });
    expect(ok.status()).toBe(200);
    expect(ok.headers()["cutpay-version"]).toBe("2026-10-01");
    // ...is held to its mode and cannot manage keys...
    expect((await request.get(`${GATEWAY}/v1/api_keys`, { headers: { Authorization: `Bearer ${secret}` } })).status()).toBe(403);
    // ...and a bogus one is refused.
    expect((await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer sk_test_${"x".repeat(32)}` } })).status()).toBe(401);

    // A restricted key only does what it was given.
    const restricted = await createKey(page, "Reporting", "Restricted");
    expect(restricted).toMatch(/^rk_test_/);
    expect((await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer ${restricted}` } })).status()).toBe(200);
    expect((await request.get(`${GATEWAY}/v1/payouts`, { headers: { Authorization: `Bearer ${restricted}` } })).status()).toBe(403);
    expect(
      (await request.post(`${GATEWAY}/v1/payment_intents`, {
        headers: { Authorization: `Bearer ${restricted}`, "Content-Type": "application/json" },
        data: { amount: 100, currency: "EUR" },
      })).status(),
    ).toBe(403);

    // A publishable key is refused by the API.
    const publishable = await createKey(page, "Website", "Publishable");
    expect((await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer ${publishable}` } })).status()).toBe(403);

    // Revoking a key that has not been used yet stops it at once (used keys are cached for up to 30 seconds).
    const spare = await createKey(page, "Spare");
    await page.getByRole("button", { name: "Revoke Spare" }).click();
    await page.getByRole("button", { name: "Revoke key" }).click();
    await expect(page.getByRole("row", { name: /Spare/ })).toContainText("revoked");
    expect((await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer ${spare}` } })).status()).toBe(401);

    // Rolling gives a new secret and leaves the old one working for the grace period.
    await page.getByRole("button", { name: "Roll Production server" }).click();
    await page.getByRole("radio", { name: /24 hours/ }).check();
    await page.getByRole("button", { name: "Roll key" }).click();
    const rolled = (await page.getByTestId("revealed-secret").textContent())!.trim();
    await page.getByRole("button", { name: "I have saved it" }).click();
    expect(rolled).not.toBe(secret);
    expect((await request.get(`${GATEWAY}/v1/payment_intents`, { headers: { Authorization: `Bearer ${rolled}` } })).status()).toBe(200);
    await expect(page.getByRole("row", { name: /Production server/ }).first()).toContainText(/Expires|active/);

    // Live mode is a separate world: no live keys, and the banner goes away.
    await page.getByRole("switch", { name: "Test mode" }).uncheck();
    await expect(page.getByText("You are viewing test data")).toHaveCount(0);
    await expect(page.getByText("No live API keys yet")).toBeVisible();
    await page.getByRole("switch", { name: "Test mode" }).check();

    // The API calls show up in the log (it travels through Kafka, so give it a moment).
    await page.getByRole("link", { name: "Logs" }).click();
    await expect(page.getByText("/v1/payment_intents").first()).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText("403").first()).toBeVisible();
    await page.getByLabel("Filter by status").selectOption("4xx");
    await expect(page.getByText("201").or(page.getByText("200", { exact: true }))).toHaveCount(0);
  });

  test("adds a webhook endpoint, receives a signed test event, and resends a failed delivery", async ({ page }) => {
    // The merchant's server: answers with whatever status the test says.
    let status = 200;
    const received: { body: string; signature: string }[] = [];
    const server: Server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (chunk: Buffer) => chunks.push(chunk));
      req.on("end", () => {
        received.push({ body: Buffer.concat(chunks).toString("utf8"), signature: String(req.headers["cutpay-signature"]) });
        res.writeHead(status);
        res.end(status === 200 ? "ok" : "broken");
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const port = (server.address() as { port: number }).port;

    try {
      await signUp(page);
      await page.goto("/developers/webhooks");
      await page.getByRole("button", { name: "Add endpoint" }).click();
      await page.getByLabel("Endpoint URL").fill(`http://localhost:${port}/hooks`);
      await page.getByRole("dialog").getByRole("button", { name: "Add endpoint" }).click();

      const secret = (await page.getByTestId("revealed-secret").textContent())!.trim();
      expect(secret).toMatch(/^whsec_/);
      await page.getByRole("button", { name: "I have saved it" }).click();

      await page.getByRole("link", { name: `http://localhost:${port}/hooks` }).click();
      await page.getByRole("button", { name: "Send test event" }).click();
      await expect(page.getByText(/Test event queued/)).toBeVisible();

      await expect.poll(() => received.length, { timeout: 20_000 }).toBe(1);
      const first = received[0]!;
      expect(JSON.parse(first.body).type).toBe("webhook_endpoint.test");
      // The signature is genuine: only someone holding the secret could have produced it.
      const [t, v1] = first.signature.split(",").map((part) => part.split("=")[1]!);
      expect(createHmac("sha256", secret).update(`${t}.${first.body}`).digest("hex")).toBe(v1);

      await expect(page.getByRole("row", { name: /webhook_endpoint.test/ })).toContainText("succeeded", { timeout: 15_000 });

      // A failing server: the delivery stays pending with its attempts recorded...
      status = 500;
      await page.getByRole("button", { name: "Send test event" }).click();
      await expect.poll(() => received.length, { timeout: 20_000 }).toBe(2);
      await expect(page.getByRole("row", { name: /webhook_endpoint.test.*pending/ })).toBeVisible({ timeout: 15_000 });
      await page.getByRole("row", { name: /webhook_endpoint.test.*pending/ }).getByRole("button").click();
      const dialog = page.getByRole("dialog", { name: "Delivery" });
      await expect(dialog.getByText("#1 HTTP 500")).toBeVisible();
      await expect(dialog.getByText("broken")).toBeVisible();

      // ...and once the server is fixed it can be resent by hand, without waiting for the retry schedule.
      status = 200;
      await dialog.getByRole("button", { name: "Resend" }).click();
      await expect.poll(() => received.length, { timeout: 20_000 }).toBe(3);
      await dialog.getByRole("button", { name: "Close" }).last().click();
      await expect(page.getByRole("row", { name: /webhook_endpoint.test.*succeeded/ })).toHaveCount(2, { timeout: 15_000 });

      // Events are listed exactly as sent.
      await page.goto("/developers/events");
      await page.getByRole("button", { name: "webhook_endpoint.test" }).first().click();
      await expect(page.getByTestId("event-json")).toContainText('"object": "event"');
      await expect(page.getByTestId("event-json")).toContainText('"api_version": "2026-10-01"');
    } finally {
      server.close();
    }
  });
});
