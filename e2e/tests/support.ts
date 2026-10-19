import { expect, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";
import { createHmac } from "node:crypto";

export const PASSWORD = "correct-horse-battery";
export const uniqueEmail = () => `merchant+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
export const nav = (page: Page) => page.getByRole("navigation", { name: "Main" });

export async function signUp(page: Page, country = "BG"): Promise<string> {
  const email = uniqueEmail();
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Ivan Petrov");
  await page.getByLabel("Business name").fill("Sofa Studio");
  await page.getByLabel("Country").selectOption(country);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Sofa Studio");
  return email;
}

/** Creates a test payment in the dashboard and returns the hosted checkout opened in a new tab. */
export async function startCheckout(page: Page, context: BrowserContext, amount: string, currency?: string): Promise<Page> {
  // The dialog of an earlier checkout would cover the navigation.
  const open = page.getByRole("dialog");
  if (await open.count()) await open.getByRole("button", { name: "Close" }).click();
  await nav(page).getByRole("link", { name: "Payments" }).click();
  await page.getByRole("button", { name: "Create test payment" }).click();
  await page.getByLabel("Amount").fill(amount);
  if (currency) await page.getByLabel("Currency").selectOption(currency);
  await page.getByRole("button", { name: "Create checkout" }).click();
  await expect(page.getByRole("dialog", { name: "Checkout is ready" })).toBeVisible();

  const [checkout] = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Open checkout" }).click(),
  ]);
  await expect(checkout.getByRole("tab", { name: "Card" })).toBeVisible({ timeout: 20_000 });
  return checkout;
}

/** The inbox the notifications service writes to (Mailpit's web API). */
export async function inbox(request: APIRequestContext, to: string, subject: RegExp, timeout = 30_000): Promise<{ subject: string; html: string }> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = await request.get(`http://localhost:8025/api/v1/search?query=${encodeURIComponent(`to:${to}`)}`);
    if (found.ok()) {
      for (const message of (await found.json()).messages ?? []) {
        if (subject.test(message.Subject)) {
          const full = await (await request.get(`http://localhost:8025/api/v1/message/${message.ID}`)).json();
          return { subject: message.Subject, html: full.HTML };
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`No email to ${to} matching ${subject} arrived within ${timeout} ms`);
}

/** The first link of an email, with the page address mapped to the dashboard the tests use. */
export function linkIn(html: string, text: string): string {
  const match = new RegExp(`<a href="([^"]+)">${text}</a>`).exec(html);
  if (!match) throw new Error(`No link "${text}" in the email`);
  return match[1].replaceAll("&amp;", "&");
}

/** A six-digit code from a Base32 secret, as an authenticator app would show (RFC 6238, 30-second steps). */
export function totpCode(secret: string, at = Date.now()): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/[ =]/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary = ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
  return String(binary % 1_000_000).padStart(6, "0");
}
