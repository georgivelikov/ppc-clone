import { createServer } from "node:http";
import { createApp } from "./app.ts";

// Configuration comes from the environment like a real shop's would. The keys can also be pasted into the
// Settings page while developing (set DEMO_SHOP_ALLOW_SETUP=0 to switch that off).
const port = Number(process.env.PORT ?? 4000);
const app = createApp({
  apiUrl: process.env.CUTPAY_API_URL ?? "http://localhost:8080",
  checkoutUrl: process.env.CUTPAY_CHECKOUT_URL ?? "http://localhost:5174",
  shopUrl: process.env.SHOP_URL ?? "http://localhost:5175",
  webhookUrl: process.env.WEBHOOK_URL ?? `http://localhost:${port}/webhooks/cutpay`,
  secretKey: process.env.CUTPAY_SECRET_KEY,
  publishableKey: process.env.CUTPAY_PUBLISHABLE_KEY,
  webhookSecret: process.env.CUTPAY_WEBHOOK_SECRET,
  allowSetup: process.env.DEMO_SHOP_ALLOW_SETUP !== "0",
});

createServer(app.handler).listen(port, () => {
  console.log(`Demo shop backend on http://localhost:${port}`);
  console.log(`Register this webhook URL in Cutpay: http://localhost:${port}/webhooks/cutpay`);
});
