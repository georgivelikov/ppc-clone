// Helpers shared by the chaos experiments: a merchant, payments through the public API, the staff back-office
// API, Kafka through the broker's own command-line tools, and the acquirer simulator's chaos knobs.
import { execFileSync } from "node:child_process";

export const GATEWAY = "http://localhost:8080";
export const ACQUIRER = "http://localhost:8083";
export const PAYMENTS = "http://localhost:8082";
const PASSWORD = "correct-horse-battery";

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function until(what, check, timeoutMs = 60_000, everyMs = 500) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await check();
    if (last) return last;
    await sleep(everyMs);
  }
  throw new Error(`Timed out waiting for ${what}`);
}

async function json(response) {
  const text = await response.text();
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return { raw: text };
  }
}

export async function api(method, path, { token, body, headers = {} } = {}) {
  const response = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: response.status, body: await json(response) };
}

export async function newMerchant() {
  const email = `chaos+${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const signup = await api("POST", "/auth/signup", {
    body: { email, password: PASSWORD, full_name: "Chaos Monkey", business_name: "Chaos Ltd", country: "BG" },
  });
  if (signup.status >= 300) throw new Error(`signup failed: ${JSON.stringify(signup)}`);
  const token = signup.body.access_token;
  const me = await api("GET", "/me", { token });
  return { token, email, id: me.body.memberships[0].merchant_id };
}

/** One customer paying with a test card through the same calls the hosted checkout makes. Returns what the customer would see. */
export async function pay(merchant, { amount = 2500, card = "4242424242424242", currency = "EUR" } = {}) {
  const started = Date.now();
  const created = await api("POST", "/v1/checkout_sessions", {
    token: merchant.token,
    body: { amount, currency, merchant_name: "Chaos Ltd" },
  });
  if (created.status !== 201) return { ok: false, stage: "create", status: created.status, ms: Date.now() - started };
  const secret = created.body.client_secret ?? created.body.url.split("/").pop();
  // The gateway rate-limits the public checkout and vault per client address (as it should); a load test goes around it.
  const tokenized = await fetch(`${ACQUIRER}/tokenize`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ number: card, exp_month: 12, exp_year: 2030, cvc: "123" }),
  }).then(async (r) => ({ status: r.status, body: await r.json() }));
  if (tokenized.status !== 200) return { ok: false, stage: "tokenize", status: tokenized.status, ms: Date.now() - started };
  const confirmed = await fetch(`${PAYMENTS}/checkout/sessions/${encodeURIComponent(secret)}/confirm`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: tokenized.body.token, email: "customer@example.com" }),
  }).then(async (r) => ({ status: r.status, body: await json(r) }));
  return {
    ok: confirmed.status === 200,
    stage: "confirm",
    status: confirmed.status,
    paymentId: created.body.payment_id ?? created.body.id,
    result: confirmed.body.status,
    failure: confirmed.body.failure_code ?? confirmed.body.error?.code,
    message: confirmed.body.failure_message ?? confirmed.body.error?.message,
    ms: Date.now() - started,
  };
}

/** Runs {@code count} payments, {@code parallel} at a time. */
export async function payMany(merchant, count, parallel = 4, options = {}) {
  const results = [];
  let next = 0;
  await Promise.all(
    Array.from({ length: parallel }, async () => {
      while (next < count) {
        next += 1;
        results.push(await pay(merchant, options));
      }
    }),
  );
  return results;
}

export function summarise(results) {
  const by = {};
  for (const r of results) {
    const key = r.ok ? r.result : `${r.stage}:${r.status}:${r.failure ?? ""}`;
    by[key] = (by[key] ?? 0) + 1;
  }
  const times = results.map((r) => r.ms).sort((a, b) => a - b);
  return { total: results.length, outcomes: by, p50: times[Math.floor(times.length * 0.5)], p95: times[Math.floor(times.length * 0.95)] };
}

// --- staff ------------------------------------------------------------------------------------------------------

export async function staff(email = "admin@cutpay.test") {
  const login = await api("POST", "/auth/staff/login", { body: { email, password: "staff-password-123" } });
  if (login.status !== 200) throw new Error(`staff login failed: ${JSON.stringify(login)}`);
  return login.body.access_token;
}

export async function reconcile(token) {
  const run = await api("POST", "/v1/staff/reconciliation/runs", { token });
  if (run.status !== 200) throw new Error(`reconciliation failed: ${JSON.stringify(run)}`);
  const mismatches = run.body.mismatch_count
    ? (await api("GET", `/v1/staff/reconciliation/runs/${run.body.id}/mismatches`, { token })).body.data
    : [];
  return { run: run.body, mismatches };
}

// --- Kafka and Docker ------------------------------------------------------------------------------------------

const kafkaBin = "/opt/kafka/bin";

function docker(...args) {
  return execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

/** Sends key:value records; a value must not contain a newline. */
export function produce(topic, records) {
  const input = records.map(([key, value]) => `${key}\t${value}`).join("\n") + "\n";
  execFileSync(
    "docker",
    ["exec", "-i", "cutpay-kafka-1", `${kafkaBin}/kafka-console-producer.sh`, "--bootstrap-server", "localhost:9092", "--topic", topic,
      "--property", "parse.key=true", "--property", "key.separator=\t"],
    { input, stdio: ["pipe", "ignore", "ignore"] },
  );
}

/** Total records a consumer group is behind on, or null when the group is unknown. */
export function lag(group) {
  try {
    const out = docker("exec", "cutpay-kafka-1", `${kafkaBin}/kafka-consumer-groups.sh`, "--bootstrap-server", "localhost:9092",
      "--describe", "--group", group);
    let total = 0;
    for (const line of out.split("\n")) {
      const cols = line.trim().split(/\s+/);
      if (cols[0] === group && cols.length >= 6 && /^\d+$/.test(cols[5])) total += Number(cols[5]);
    }
    return total;
  } catch {
    return null;
  }
}

export function sh(...args) {
  return docker(...args);
}

export function envelope(type, merchant, aggregate, payload, { eventId, at = new Date().toISOString(), livemode = false } = {}) {
  return JSON.stringify({
    event_id: eventId ?? `evt_chaos_${Date.now()}_${Math.floor(Math.random() * 1e9)}`,
    event_type: type,
    occurred_at: at,
    aggregate_id: aggregate,
    merchant_id: merchant,
    livemode,
    version: 1,
    payload,
  });
}

export async function acquirerConfig(config) {
  const response = await fetch(`${ACQUIRER}/admin/config`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  });
  return json(response);
}

export async function metric(port, name, filter = () => true) {
  const text = await (await fetch(`http://localhost:${port}/actuator/prometheus`)).text();
  return text
    .split("\n")
    .filter((line) => line.startsWith(name) && filter(line))
    .map((line) => Number(line.split(" ").pop()));
}
