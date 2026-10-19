import { expect, test } from "@playwright/test";
import { inbox, linkIn, nav, PASSWORD, signUp, totpCode } from "./support";

test.describe("sign-in security and email", () => {
  test("the email address is confirmed from a link in the inbox", async ({ page, request }) => {
    const email = await signUp(page);
    await expect(page.getByTestId("verify-banner")).toBeVisible();

    const mail = await inbox(request, email, /Confirm your email/);
    await page.goto(linkIn(mail.html, "Confirm my email"));
    await expect(page.getByText("Your email address is confirmed")).toBeVisible();

    await page.getByRole("link", { name: "Go to the dashboard" }).click();
    await expect(page.getByTestId("merchant-name")).toBeVisible();
    await expect(page.getByTestId("verify-banner")).toHaveCount(0);
  });

  test("a forgotten password is reset from an emailed link", async ({ page, request }) => {
    const email = await signUp(page);
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.getByRole("link", { name: "Forgot your password?" }).click();
    await page.getByLabel("Email").fill(email);
    await page.getByRole("button", { name: "Send the link" }).click();
    await expect(page.getByRole("status")).toContainText("an email with a link is on its way");

    const mail = await inbox(request, email, /Reset your Cutpay password/);
    await page.goto(linkIn(mail.html, "Choose a new password"));
    await page.getByLabel("New password").fill("a-brand-new-password");
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page.getByRole("status")).toContainText("Your password was changed");

    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert")).toContainText("Incorrect email or password");
    await page.getByLabel("Password").fill("a-brand-new-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("merchant-name")).toBeVisible();
  });

  test("two-factor sign-in: set up with a code, asked for at sign-in, recovery code works once", async ({ page }) => {
    const email = await signUp(page);
    await nav(page).getByRole("link", { name: "Settings" }).click();
    await page.getByRole("link", { name: "Security", exact: true }).click();
    await expect(page.getByTestId("totp-status")).toHaveText("Off");

    await page.getByRole("button", { name: "Set up" }).click();
    const secret = (await page.getByTestId("totp-secret").textContent())!.trim();
    await page.getByLabel("Code from the app").fill(totpCode(secret));
    await page.getByRole("button", { name: "Turn on" }).click();
    const recovery = page.getByRole("list", { name: "Recovery codes" }).getByRole("listitem");
    await expect(recovery).toHaveCount(8);
    const codes = await recovery.allTextContents();
    expect(codes).toHaveLength(8);
    await page.getByRole("button", { name: "I have saved them" }).click();
    await expect(page.getByTestId("totp-status")).toHaveText("On");

    // Sign out and back in: the password alone is no longer enough.
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByLabel("Code from your authenticator app")).toBeVisible();
    await page.getByLabel("Code from your authenticator app").fill("000000");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByRole("alert")).toContainText("That code is not right");
    await page.getByLabel("Code from your authenticator app").fill(totpCode(secret));
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("merchant-name")).toBeVisible();

    // A recovery code replaces the app once.
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.getByLabel("Email").fill(email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.getByLabel("Code from your authenticator app").fill(codes[0]);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("merchant-name")).toBeVisible();
  });

  test("sessions are listed and another device can be signed out", async ({ page, browser }) => {
    const email = await signUp(page);
    const other = await browser.newContext({ baseURL: "http://localhost:5173", userAgent: "Pretend Phone Browser" });
    const phone = await other.newPage();
    await phone.goto("/login");
    await phone.getByLabel("Email").fill(email);
    await phone.getByLabel("Password").fill(PASSWORD);
    await phone.getByRole("button", { name: "Sign in" }).click();
    await expect(phone.getByTestId("merchant-name")).toBeVisible();

    await nav(page).getByRole("link", { name: "Settings" }).click();
    await page.getByRole("link", { name: "Security", exact: true }).click();
    const sessions = page.getByTestId("session");
    await expect(sessions).toHaveCount(2);
    await expect(sessions.filter({ hasText: "This device" })).toHaveCount(1);
    await sessions.filter({ hasText: "Pretend Phone Browser" }).getByRole("button", { name: "Sign out" }).click();
    await expect(sessions).toHaveCount(1);

    // The phone loses its session the next time it needs a new token.
    await phone.reload();
    await expect(phone.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await other.close();
  });

  test("an invitation reaches the inbox and the emails page lists what was sent", async ({ page, request }) => {
    await signUp(page);
    await nav(page).getByRole("link", { name: "Settings" }).click();
    await page.getByRole("link", { name: "Team", exact: true }).click();
    const invitee = `guest+${Date.now()}@example.com`;
    await page.getByRole("button", { name: "Invite someone" }).click();
    await page.getByRole("dialog", { name: "Invite someone" }).getByLabel("Email").fill(invitee);
    await page.getByRole("dialog", { name: "Invite someone" }).getByRole("button", { name: "Send invitation" }).click();
    await page.getByRole("dialog", { name: "Invitation ready" }).getByRole("button", { name: "Done" }).click();

    const mail = await inbox(request, invitee, /invited you to Sofa Studio/);
    expect(linkIn(mail.html, "Accept the invitation")).toContain("/accept-invite?token=");

    await page.getByRole("link", { name: "Emails", exact: true }).click();
    await expect(page.getByRole("list", { name: "Emails" }).getByTestId("pref-payout_paid")).toBeVisible();
    await page.getByRole("checkbox", { name: "New payment" }).click();
    await expect(page.getByRole("checkbox", { name: "New payment" })).toBeChecked();
  });
});
