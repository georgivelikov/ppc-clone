// Shared by the k6 scenarios. k6 runs in a container, so the host's services are at host.docker.internal.
// The gateway rate-limits the public checkout and the vault per address (10 a second, on purpose), so the checkout
// scenario talks to the vault (the acquirer simulator) and to payments directly; merchant calls go through the gateway.
import http from "k6/http";
import { check, fail } from "k6";

export const HOST = __ENV.HOST || "host.docker.internal";
export const GATEWAY = `http://${HOST}:8080`;
export const PAYMENTS = `http://${HOST}:8082`;
export const VAULT = `http://${HOST}:8083`;

const JSON_HEADERS = { "Content-Type": "application/json" };

export function merchant() {
  const email = `perf+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const signup = http.post(
    `${GATEWAY}/auth/signup`,
    JSON.stringify({ email, password: "correct-horse-battery", full_name: "Perf Test", business_name: "Perf Ltd", country: "BG" }),
    { headers: JSON_HEADERS },
  );
  if (signup.status >= 300) fail(`signup failed: ${signup.status} ${signup.body}`);
  return signup.json("access_token");
}

export function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

/** One customer paying with a test card, the same calls the hosted checkout makes. */
export function payOnce(token, amount = 2500) {
  const session = http.post(
    `${GATEWAY}/v1/checkout_sessions`,
    JSON.stringify({ amount, currency: "EUR", merchant_name: "Perf Ltd" }),
    { ...auth(token), tags: { step: "create" } },
  );
  if (!check(session, { "session created": (r) => r.status === 201 })) return null;
  const secret = session.json("client_secret") || session.json("url").split("/").pop();
  const tokenized = http.post(
    `${VAULT}/tokenize`,
    JSON.stringify({ number: "4242424242424242", exp_month: 12, exp_year: 2030, cvc: "123" }),
    { headers: JSON_HEADERS, tags: { step: "tokenize" } },
  );
  if (!check(tokenized, { "tokenized": (r) => r.status === 200 })) return null;
  const confirmed = http.post(
    `${PAYMENTS}/checkout/sessions/${encodeURIComponent(secret)}/confirm`,
    JSON.stringify({ token: tokenized.json("token"), email: "customer@example.com" }),
    { headers: JSON_HEADERS, tags: { step: "confirm" } },
  );
  check(confirmed, { "payment succeeded": (r) => r.status === 200 && r.json("status") === "succeeded" });
  return confirmed;
}
