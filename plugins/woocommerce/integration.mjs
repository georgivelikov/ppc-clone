// End-to-end check of the WooCommerce plugin against a real WordPress and the real Cutpay stack:
//   an order is placed and a Cutpay checkout session starts; the customer pays on Cutpay; the signed webhook marks the
//   order paid; a forged webhook is refused; a partial refund from the order screen reaches Cutpay; a declined card
//   marks another order failed.
//
//   docker compose -f plugins/woocommerce/docker-compose.yml up -d     # and wp core install, see the README
//   node e2e/chaos/stack.mjs up
//   node plugins/woocommerce/integration.mjs
import { execFileSync } from "node:child_process";
import { api, newMerchant, sleep, until } from "../../e2e/chaos/lib.mjs";

const COMPOSE = ["compose", "-f", new URL("./docker-compose.yml", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")];
const ACQUIRER = "http://localhost:8083";
const PAYMENTS = "http://localhost:8082";
const SHOP = "http://localhost:8099";

function wp(...args) {
  return execFileSync("docker", [...COMPOSE, "run", "--rm", "-T", "wpcli", "wp", ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
    env: { ...process.env, MSYS_NO_PATHCONV: "1" },
  }).trim();
}
const pluginTest = "/var/www/html/wp-content/plugins/cutpay-for-woocommerce/tests";

let failures = 0;
function check(name, ok, detail = "") {
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
  if (!ok) failures += 1;
}

// --- Cutpay side: a merchant, a secret key, a webhook endpoint pointing at the shop ---------------------------------
const merchant = await newMerchant();
const key = await api("POST", "/v1/api_keys", { token: merchant.token, body: { name: "woocommerce", type: "secret" } });
const endpoint = await api("POST", "/v1/webhook_endpoints", {
  token: merchant.token,
  body: { url: `${SHOP}/?wc-api=wc_gateway_cutpay`, enabled_events: ["payment_intent.*"] },
});
if (key.status !== 201 || endpoint.status !== 201) throw new Error(`setup failed: ${JSON.stringify([key, endpoint])}`);
const secretKey = key.body.secret;
const webhookSecret = endpoint.body.secret;

// --- WordPress side: configure the gateway the way an admin would -------------------------------------------------------
wp("option", "update", "woocommerce_cutpay_settings", JSON.stringify({
  enabled: "yes", title: "Pay by card", description: "", testmode: "yes",
  api_url: "http://host.docker.internal:8080", test_secret_key: secretKey, test_webhook_secret: webhookSecret,
  live_secret_key: "", live_webhook_secret: "",
}), "--format=json");
wp("option", "update", "woocommerce_currency", "EUR");
wp("option", "update", "siteurl", SHOP);
wp("option", "update", "home", SHOP);

const status = (id) => wp("eval", `echo wc_get_order(${id})->get_status();`);
const place = (total, email) => JSON.parse(wp("eval-file", `${pluginTest}/place-order.php`, total, email).split("\n").pop());

async function payOnCutpay(url, card = "4242424242424242") {
  const secret = url.split("/").pop();
  const token = await fetch(`${ACQUIRER}/tokenize`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ number: card, exp_month: 12, exp_year: 2030, cvc: "123" }),
  }).then((r) => r.json());
  const confirmed = await fetch(`${PAYMENTS}/checkout/sessions/${encodeURIComponent(secret)}/confirm`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: token.token, email: "customer@example.com" }),
  });
  return { status: confirmed.status, body: await confirmed.json().catch(() => ({})) };
}

// 1. The order starts a payment ---------------------------------------------------------------------------------------
console.log("1. An order starts a Cutpay checkout");
const first = place("25.00", "dana@example.com");
check("the gateway accepted the order", first.result === "success", first.result);
check("the customer is sent to Cutpay's hosted checkout", /\/c\/pay_/.test(first.redirect ?? ""), first.redirect);
check("the order waits for payment (on-hold)", first.status === "on-hold", first.status);

// 2. Paying on Cutpay marks the order paid via the signed webhook -----------------------------------------------------
console.log("2. Paying on Cutpay marks the order paid");
const paid = await payOnCutpay(first.redirect);
check("the payment succeeded on Cutpay", paid.status === 200 && paid.body.status === "succeeded", paid.body.status);
const processing = await until("the order to be paid", () => ["processing", "completed"].includes(status(first.order_id)), 60_000, 1500).catch(() => false);
check("the webhook marked the order paid (processing)", processing === true, status(first.order_id));

// 3. A forged webhook is refused ----------------------------------------------------------------------------------------
console.log("3. A forged webhook is refused");
const forged = await fetch(`${SHOP}/?wc-api=wc_gateway_cutpay`, {
  method: "POST", headers: { "Content-Type": "application/json", "Cutpay-Signature": "t=1760000000,v1=" + "0".repeat(64) },
  body: JSON.stringify({ id: "evt_forged", type: "payment_intent.succeeded", data: { object: { id: "pay_x", amount: 1, currency: "eur" } } }),
});
check("answered 400", forged.status === 400, String(forged.status));
const unsigned = await fetch(`${SHOP}/?wc-api=wc_gateway_cutpay`, { method: "POST", body: "{}" });
check("a request with no signature is refused", unsigned.status === 400, String(unsigned.status));

// 4. A partial refund from the order screen reaches Cutpay -------------------------------------------------------------
console.log("4. A partial refund reaches Cutpay");
const refundOutput = wp("eval", `
  $r = wc_create_refund(array('order_id' => ${first.order_id}, 'amount' => '10.00', 'reason' => 'Damaged', 'refund_payment' => true));
  echo is_wp_error($r) ? 'ERROR ' . $r->get_error_message() : 'refunded ' . $r->get_amount();`);
check("WooCommerce accepted the refund", refundOutput.startsWith("refunded 10"), refundOutput);
const intents = await api("GET", "/v1/payment_intents?limit=5", { token: merchant.token });
const payment = intents.body.data.find((p) => p.status === "succeeded" || p.status === "partially_refunded");
const refunded = payment ? (await api("GET", `/v1/payment_intents/${payment.id}`, { token: merchant.token })).body.amount_refunded : null;
check("Cutpay shows 10.00 refunded", refunded === 1000, String(refunded));

// 5. A declined card marks the order failed -------------------------------------------------------------------------------
console.log("5. A declined card");
const second = place("40.00", "lee@example.com");
const declined = await payOnCutpay(second.redirect, "4000000000000002");
check("Cutpay declined the payment", declined.body.status !== "succeeded", `${declined.status} ${declined.body.status ?? ""}`);
await sleep(4000);
const secondStatus = status(second.order_id);
check("the order is not paid", !["processing", "completed"].includes(secondStatus), secondStatus);

console.log(failures ? `\n${failures} check(s) failed` : "\nAll checks passed");
process.exit(failures ? 1 : 0);
