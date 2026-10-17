import { createHmac } from "node:crypto";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const GATEWAY = "http://localhost:8080";
const SHOP = "http://localhost:5175";
const SHOP_API = "http://localhost:4000";
const PASSWORD = "correct-horse-battery";

/**
 * A developer's first hour with the platform: sign up, create keys and a webhook endpoint, paste them into
 * the shop, and sell something. Nothing here uses the dashboard UI; it is all the public API plus the SDK.
 */
async function connectShop(request: APIRequestContext) {
  const email = `dev+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const signup = await request.post(`${GATEWAY}/auth/signup`, {
    data: { email, password: PASSWORD, full_name: "Dana Developer", business_name: "Demo Shop Ltd", country: "BG" },
  });
  expect(signup.ok()).toBeTruthy();
  const token = ((await signup.json()) as { access_token: string }).access_token;
  const headers = { Authorization: `Bearer ${token}` };

  const key = async (type: string) => {
    const response = await request.post(`${GATEWAY}/v1/api_keys`, { headers, data: { name: `shop ${type}`, type } });
    expect(response.status()).toBe(201);
    return ((await response.json()) as { secret: string }).secret;
  };
  const secretKey = await key("secret");
  const publishableKey = await key("publishable");

  const endpoint = await request.post(`${GATEWAY}/v1/webhook_endpoints`, {
    headers,
    data: { url: `${SHOP_API}/webhooks/cutpay`, enabled_events: ["payment_intent.*"] },
  });
  expect(endpoint.status()).toBe(201);
  const webhookSecret = ((await endpoint.json()) as { secret: string }).secret;

  const setup = await request.post(`${SHOP_API}/api/setup`, { data: { secretKey, publishableKey, webhookSecret } });
  expect(setup.ok()).toBeTruthy();
  return { token, secretKey, publishableKey, webhookSecret };
}

async function addToCart(page: Page, product = "Ceramic mug") {
  await page.goto(SHOP);
  await page.getByRole("button", { name: `Add ${product} to cart` }).click();
  await page.getByRole("link", { name: /Cart/ }).click();
}

async function typeCardInFrame(page: Page, number: string) {
  // The card inputs live in Cutpay's iframe, on Cutpay's origin.
  const card = page.frameLocator('iframe[title="Secure card payment input"]');
  await card.getByLabel("Card number").fill(number);
  await card.getByLabel("Expiry date").fill("1299");
  await card.getByLabel("Security code").fill("123");
}

test.describe("demo shop", () => {
  test("sells a product with the embedded card element and learns it was paid from the webhook", async ({ page, request }) => {
    await connectShop(request);
    await addToCart(page);
    await expect(page.getByTestId("cart-total")).toHaveText("€12.00");
    await page.getByLabel("Email for your receipt").fill("anna@example.com");
    await page.getByRole("button", { name: "Pay on this site" }).click();

    await expect(page.getByRole("heading", { name: "Pay by card" })).toBeVisible();
    await typeCardInFrame(page, "4242 4242 4242 4242");
    await page.getByRole("button", { name: /^Pay €12.00/ }).click();

    // The shop page never held the card number, and "paid" appears only after the signed webhook reaches
    // the shop backend.
    await expect(page.getByRole("heading", { name: /Your order is paid/ })).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("body")).not.toContainText("4242");

    await page.getByRole("link", { name: "View all orders" }).click();
    // The shop keeps its orders for as long as it runs, newest first (row 0 is the header).
    const newest = page.getByRole("row").nth(1);
    await expect(newest).toContainText("1× Ceramic mug");
    await expect(newest).toContainText("paid");
  });

  test("sells a product through the hosted checkout and returns to the shop", async ({ page, request }) => {
    await connectShop(request);
    await addToCart(page, "Canvas tote");
    await page.getByRole("button", { name: "Pay on Cutpay's page" }).click();

    await expect(page).toHaveURL(/localhost:5174\/c\//);
    await expect(page.getByTestId("amount")).toHaveText("€18.50");
    await page.getByLabel("Email").fill("anna@example.com");
    await page.getByLabel("Card number").fill("4242 4242 4242 4242");
    await page.getByLabel("Expiry").fill("1299");
    await page.getByLabel("CVC").fill("123");
    await page.getByRole("button", { name: /^Pay/ }).click();
    await expect(page.getByRole("heading", { name: "Payment successful" })).toBeVisible();

    await page.getByRole("link", { name: /Return to Demo Shop/ }).click();
    await expect(page).toHaveURL(/localhost:5175\/thanks/);
    await expect(page.getByRole("heading", { name: /Your order is paid/ })).toBeVisible({ timeout: 30_000 });
  });

  test("a declined card is explained and the order stays unpaid", async ({ page, request }) => {
    await connectShop(request);
    await addToCart(page);
    await page.getByRole("button", { name: "Pay on this site" }).click();
    await typeCardInFrame(page, "4000 0000 0000 0002");
    await page.getByRole("button", { name: /^Pay €12.00/ }).click();

    await expect(page.getByText("Your card was declined.")).toBeVisible();
    await page.getByRole("link", { name: "Orders" }).click();
    const newest = page.getByRole("row").nth(1);
    await expect(newest).toContainText("1× Ceramic mug");
    await expect(newest).not.toContainText("paid");
  });

  test("the shop rejects webhooks that are forged, tampered with or replayed", async ({ request }) => {
    const { webhookSecret } = await connectShop(request);
    const body = JSON.stringify({ id: "evt_forged", type: "payment_intent.succeeded", data: { object: { id: "pay_x", amount: 1 } } });
    const signed = (timestamp: number, secret = webhookSecret, payload = body) =>
      `t=${timestamp},v1=${createHmac("sha256", secret).update(`${timestamp}.${payload}`).digest("hex")}`;
    const now = Math.floor(Date.now() / 1000);
    const send = (signature: string | undefined, payload = body) =>
      request.post(`${SHOP_API}/webhooks/cutpay`, {
        data: payload,
        headers: { "Content-Type": "application/json", ...(signature ? { "Cutpay-Signature": signature } : {}) },
      });

    expect((await send(undefined)).status()).toBe(400);
    expect((await send(signed(now, "whsec_attacker"))).status()).toBe(400);
    expect((await send(signed(now), body.replace("evt_forged", "evt_other"))).status()).toBe(400);
    expect((await send(signed(now - 600))).status()).toBe(400);
    expect((await send(signed(now))).status()).toBe(200); // the genuine article
  });

  test("the secret key never reaches the browser", async ({ page, request }) => {
    const { secretKey } = await connectShop(request);
    const seen: string[] = [];
    page.on("response", async (response) => {
      if (response.url().startsWith(SHOP) || response.url().startsWith(SHOP_API)) {
        seen.push(await response.text().catch(() => ""));
      }
    });
    await addToCart(page);
    await page.getByRole("button", { name: "Pay on this site" }).click();
    await expect(page.getByRole("heading", { name: "Pay by card" })).toBeVisible();
    expect(seen.join("\n")).not.toContain(secretKey);
    expect(await page.content()).not.toContain(secretKey);
  });
});
