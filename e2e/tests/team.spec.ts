import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { nav, PASSWORD, signUp, startCheckout } from "./support";

/** Invites someone from Settings, Team and returns the link the dialog shows. */
async function invite(page: Page, email: string, role: string): Promise<string> {
  await nav(page).getByRole("link", { name: "Settings" }).click();
  await page.getByRole("link", { name: "Team", exact: true }).click();
  await page.getByRole("button", { name: "Invite someone" }).click();
  const dialog = page.getByRole("dialog", { name: "Invite someone" });
  await dialog.getByLabel("Email").fill(email);
  await dialog.getByLabel("Role").selectOption(role);
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  const ready = page.getByRole("dialog", { name: "Invitation ready" });
  const link = await ready.getByLabel("Invitation link").inputValue();
  await ready.getByRole("button", { name: "Done" }).click();
  return link;
}

/** A second person in a browser of their own, so the owner's session is not shared. */
async function join(browser: Browser, link: string, name: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: "http://localhost:5173" });
  const page = await context.newPage();
  await page.goto(link);
  await expect(page.getByRole("heading", { name: /^Join / })).toBeVisible();
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Choose a password").fill(PASSWORD);
  await page.getByRole("button", { name: "Join the team" }).click();
  await expect(page.getByTestId("merchant-name")).toBeVisible();
  return page;
}

async function accessToken(request: APIRequestContext, email: string): Promise<string> {
  const res = await request.post("/auth/login", { data: { email, password: PASSWORD } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).access_token;
}

test.describe("team and roles", () => {
  test("a viewer reads but cannot refund, support can, and the owner sees it all in the activity log", async ({
    page,
    context,
    browser,
    request,
  }) => {
    test.setTimeout(180_000);
    await signUp(page);

    // A paid payment to look at.
    const checkout = await startCheckout(page, context, "30.00");
    const form = checkout.getByRole("form", { name: "Payment details" });
    await form.getByLabel("Email").fill("anna@example.com");
    await form.getByLabel("Card number").fill("4242 4242 4242 4242");
    await form.getByLabel("Expiry").fill("1299");
    await form.getByLabel("CVC").fill("123");
    await form.getByRole("button", { name: /^Pay/ }).click();
    await expect(checkout.getByRole("heading", { name: "Payment successful" })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("link", { name: "View payment" }).click();
    await expect(page.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Refund" })).toBeVisible();
    const paymentUrl = page.url();

    // Two people join: a viewer and a support agent.
    const viewerEmail = `viewer+${Date.now()}@example.com`;
    const supportEmail = `support+${Date.now()}@example.com`;
    const viewerLink = await invite(page, viewerEmail, "viewer");
    const supportLink = await invite(page, supportEmail, "support");
    await expect(page.getByTestId(`invite-${viewerEmail}`)).toBeVisible();

    const viewer = await join(browser, viewerLink, "Vera Viewer");
    await viewer.goto(paymentUrl);
    await expect(viewer.getByText("Succeeded", { exact: true })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Refund" })).toHaveCount(0);

    const support = await join(browser, supportLink, "Sam Support");
    await support.goto(paymentUrl);
    await expect(support.getByRole("button", { name: "Refund" })).toBeVisible();

    // The edge says no to a viewer, whatever the screen shows; support gets past it.
    const viewerToken = await accessToken(request, viewerEmail);
    const refused = await request.post("/v1/refunds", {
      headers: { Authorization: `Bearer ${viewerToken}` },
      data: { payment_intent: "pay_none", amount: 100 },
    });
    expect(refused.status()).toBe(403);
    expect((await refused.json()).error.code).toBe("insufficient_role");
    const supportToken = await accessToken(request, supportEmail);
    const allowed = await request.post("/v1/refunds", {
      headers: { Authorization: `Bearer ${supportToken}` },
      data: { payment_intent: "pay_none", amount: 100 },
    });
    expect(allowed.status()).not.toBe(403);
    // Neither sees the team.
    const team = await request.get("/v1/team/members", { headers: { Authorization: `Bearer ${supportToken}` } });
    expect(team.status()).toBe(403);

    // The owner changes the viewer's role and later removes the support agent.
    await page.goto("/settings/team");
    await expect(page.getByTestId(`member-${viewerEmail}`)).toBeVisible();
    await page.getByLabel("Role of Vera Viewer").selectOption("developer");
    await expect(page.getByLabel("Role of Vera Viewer")).toHaveValue("developer");
    await page.getByRole("button", { name: "Remove Sam Support" }).click();
    await expect(page.getByTestId(`member-${supportEmail}`)).toHaveCount(0);

    await page.getByRole("link", { name: "Activity" }).click();
    const events = page.getByTestId("audit-event");
    await expect(events.filter({ hasText: "Removed a team member" })).toHaveCount(1);
    await expect(events.filter({ hasText: "Changed a role" })).toHaveCount(1);
    await expect(events.filter({ hasText: "Joined the team" })).toHaveCount(2);
    await expect(events.filter({ hasText: "Invited someone to the team" })).toHaveCount(2);

    await viewer.context().close();
    await support.context().close();
  });

  test("an invitation that was cancelled shows a plain message", async ({ page, browser }) => {
    await signUp(page);
    const email = `late+${Date.now()}@example.com`;
    const link = await invite(page, email, "developer");
    await page.getByTestId(`invite-${email}`).getByRole("button", { name: "Cancel invitation" }).click();
    await expect(page.getByTestId(`invite-${email}`)).toHaveCount(0);

    const context = await browser.newContext({ baseURL: "http://localhost:5173" });
    const stranger = await context.newPage();
    await stranger.goto(link);
    await expect(stranger.getByText(/not valid any more/)).toBeVisible();
    await context.close();
  });
});
