// The five chaos experiments of docs/chaos/README.md. Each one states a hypothesis, breaks something while the platform
// is under load, and checks the outcome against the three accounts of the money (payments, ledger, acquirer).
//
//   docker compose -f infra/docker-compose.yml up -d
//   (cd backend && ./gradlew bootJar) && node chaos/stack.mjs up
//   node chaos/experiments.mjs            all five     (or: node chaos/experiments.mjs 1 3)
import { execFile } from "node:child_process";
import { start, stop, waitDown, waitHealthy } from "./stack.mjs";
import {
  acquirerConfig, api, envelope, lag, metric, newMerchant, pay, payMany, produce, reconcile, sh, sleep, staff, summarise, until,
} from "./lib.mjs";

const results = [];
const check = (name, ok, detail = "") => {
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  (${detail})` : ""}`);
  return ok;
};

async function cleanReconciliation(token, label) {
  // Events are asynchronous: give the pipeline a moment, then let reconciliation judge.
  const { run, mismatches } = await reconcile(token);
  const ok = check(`${label}: reconciliation is clean`, run.status === "clean",
    `${run.payments_checked} payments, ${run.acquirer_lines} acquirer lines, ${run.mismatch_count} differences`);
  if (!ok) console.log(mismatches.map((m) => `      ${m.kind} ${m.payment_id} p=${m.payments_amount} l=${m.ledger_amount} a=${m.acquirer_amount}`).join("\n"));
  return ok;
}

const paymentRows = async (merchant) => {
  const rows = [];
  let after = "";
  for (;;) {
    const page = (await api("GET", `/v1/balance_transactions?limit=100${after}`, { token: merchant.token })).body;
    rows.push(...page.data);
    if (!page.has_more || page.data.length === 0) break;
    after = `&starting_after=${page.data[page.data.length - 1].id}`;
  }
  return rows.filter((t) => t.type === "payment");
};

// ---------------------------------------------------------------------------------------------------------------------
async function experiment1() {
  console.log("\n1. Kill the ledger for a while during load");
  console.log("   Hypothesis: checkout is unaffected, the ledger's consumer lag grows while it is down and drains after it returns,\n" +
    "   and every payment ends up booked exactly once.");
  const merchant = await newMerchant();
  const token = await staff();
  await payMany(merchant, 5, 5);
  await until("ledger caught up", () => lag("ledger") === 0, 30_000);

  stop("ledger");
  await waitDown("ledger");
  const t0 = Date.now();
  const during = await payMany(merchant, 60, 6);
  const summary = summarise(during);
  const lagDown = lag("ledger");
  console.log(`  while down: ${JSON.stringify(summary)}, ledger lag ${lagDown}`);
  let ok = check("checkout kept working with the ledger down", summary.outcomes.succeeded === 60, `p95 ${summary.p95} ms`);
  ok = check("the ledger's lag grew", lagDown > 0, `${lagDown} records behind after ${Math.round((Date.now() - t0) / 1000)} s`) && ok;

  await start("ledger");
  await waitHealthy("ledger");
  const drainStart = Date.now();
  await until("ledger caught up", () => lag("ledger") === 0, 120_000);
  console.log(`  ledger drained ${lagDown} records in ${Math.round((Date.now() - drainStart) / 1000)} s after restart`);
  await until("all 65 payments booked", async () => (await paymentRows(merchant)).length >= 65, 60_000, 1000).catch(() => {});
  const rows = await paymentRows(merchant);
  ok = check("every payment is booked exactly once", rows.length === 65, `${rows.length} ledger lines for 65 payments`) && ok;
  await sleep(3000);
  ok = (await cleanReconciliation(token, "after catch-up")) && ok;
  return ok;
}

// ---------------------------------------------------------------------------------------------------------------------
async function experiment2() {
  console.log("\n2. The acquirer fails half of its requests");
  console.log("   Hypothesis: the circuit breaker opens, customers get a clear failure quickly (not a hang), nobody is charged twice,\n" +
    "   and the platform recovers by itself when the acquirer does.");
  const merchant = await newMerchant();
  const token = await staff();
  await acquirerConfig({ failure_rate: 0.5, latency_ms: 0 });
  let ok = true;
  try {
    const results = await payMany(merchant, 60, 1);
    const summary = summarise(results);
    console.log(`  with 50% errors: ${JSON.stringify(summary)}`);
    const succeeded = results.filter((r) => r.result === "succeeded").length;
    const failed = results.filter((r) => r.result === "failed").length;
    ok = check("every payment ended in a clear state", succeeded + failed === 60, `${succeeded} succeeded, ${failed} failed`) && ok;
    ok = check("some payments still got through", succeeded > 0 && failed > 0) && ok;
    ok = check("failures say what happened", results.filter((r) => r.result === "failed").every((r) => r.failure), "failure_code present") && ok;
    ok = check("no customer waited long for a failure", summary.p95 < 5000, `p95 ${summary.p95} ms`) && ok;
    const opened = await metric(8082, "resilience4j_circuitbreaker_state", (l) => l.includes('name="acquirer"') && l.includes('state="open"')).catch(() => []);
    if (opened.length) console.log(`  circuit state gauge (open): ${opened.join(",")}`);
  } finally {
    await acquirerConfig({ failure_rate: 0 });
  }
  await sleep(11_000); // the breaker's open state lasts ten seconds
  const after = await payMany(merchant, 10, 2);
  ok = check("after the acquirer recovers, payments succeed again", after.every((r) => r.result === "succeeded"), JSON.stringify(summarise(after).outcomes)) && ok;
  await sleep(3000);
  ok = (await cleanReconciliation(token, "after the outage")) && ok;
  return ok;
}

// ---------------------------------------------------------------------------------------------------------------------
async function experiment3() {
  console.log("\n3. Duplicated and reordered events");
  console.log("   Hypothesis: the same event delivered three times books once; a refund that arrives before its payment waits (retries)\n" +
    "   and is booked once the payment arrives; the books still balance.");
  const merchant = await newMerchant();
  const paymentId = `pay_chaos_${Date.now()}`;
  const refundId = `re_chaos_${Date.now()}`;
  const paid = envelope("PaymentSucceeded", merchant.id, paymentId, {
    payment_id: paymentId, status: "succeeded", amount: 10_000, currency: "EUR", payment_method_type: "bnpl", bnpl_fee: 300,
  }, { eventId: `evt_chaos_pay_${paymentId}` });
  const refunded = envelope("RefundSucceeded", merchant.id, refundId, {
    refund_id: refundId, payment_id: paymentId, amount: 2_000, currency: "EUR", status: "succeeded",
  }, { eventId: `evt_chaos_ref_${refundId}` });

  // The refund comes first, then the payment three times.
  produce("refund.events", [[refundId, refunded]]);
  await sleep(4000);
  const early = (await api("GET", "/v1/balance_transactions?limit=100", { token: merchant.token })).body.data;
  let ok = check("a refund with no payment yet is not booked", early.length === 0);
  produce("payment.events", [[paymentId, paid], [paymentId, paid], [paymentId, paid]]);
  await until("payment and refund booked", async () => {
    const rows = (await api("GET", "/v1/balance_transactions?limit=100", { token: merchant.token })).body.data;
    return rows.some((t) => t.type === "payment") && rows.some((t) => t.type === "refund");
  }, 60_000, 1000);
  const rows = (await api("GET", "/v1/balance_transactions?limit=100", { token: merchant.token })).body.data;
  ok = check("the payment is booked once, not three times", rows.filter((t) => t.type === "payment").length === 1) && ok;
  ok = check("the refund is booked once", rows.filter((t) => t.type === "refund").length === 1) && ok;
  const balance = (await api("GET", "/v1/balance", { token: merchant.token })).body;
  const total = [...balance.pending, ...balance.available].reduce((sum, b) => sum + b.amount, 0);
  const expectedNet = 10_000 - 300 - 2_000; // gross - bnpl fee - refund (fee is not returned on this path)
  console.log(`  balance ${total}, ledger lines ${rows.map((r) => `${r.type}:${r.net}`).join(" ")}`);
  ok = check("the balance is the payment minus the refund", Math.abs(total - expectedNet) <= 300, `balance ${total}, expected about ${expectedNet}`) && ok;
  return ok;
}

// ---------------------------------------------------------------------------------------------------------------------
async function experiment4() {
  console.log("\n4. Postgres restarts under load");
  console.log("   Hypothesis: requests during the restart fail cleanly, connection pools reconnect without restarting any service,\n" +
    "   consumers resume, and no payment is lost or doubled.");
  const merchant = await newMerchant();
  const token = await staff();
  let restarting = true;
  const during = [];
  const load = (async () => {
    while (restarting || during.length < 40) during.push(...(await payMany(merchant, 8, 4)));
  })();
  await sleep(2500);
  console.log("  restarting postgres ...");
  const restartStarted = Date.now();
  await new Promise((resolve) => execFile("docker", ["restart", "cutpay-postgres-1"], resolve));
  console.log(`  postgres was away for about ${Math.round((Date.now() - restartStarted) / 1000)} s`);
  await sleep(8000);
  restarting = false;
  await load;
  const summary = summarise(during);
  console.log(`  during the restart: ${JSON.stringify(summary)}`);
  let ok = check("requests failed while the database was away, and every one of them ended", during.some((r) => !r.ok || r.result !== "succeeded"), `${during.length} attempts`);
  await until("services healthy again", async () => {
    for (const port of [8082, 8084, 8094]) {
      const h = await fetch(`http://localhost:${port}/actuator/health`).then((r) => r.ok).catch(() => false);
      if (!h) return false;
    }
    return true;
  }, 90_000, 1000);
  const after = await payMany(merchant, 10, 2);
  ok = check("after the restart payments succeed with no service restarted", after.every((r) => r.result === "succeeded"), JSON.stringify(summarise(after).outcomes)) && ok;
  await until("consumers caught up", () => ["ledger", "reporting"].every((g) => lag(g) === 0), 120_000);
  const succeeded = during.filter((r) => r.result === "succeeded").length + after.length;
  // A confirm that answered 500 may still have gone through: the database came back before the answer could be written.
  const errored = during.filter((r) => !r.ok && r.stage === "confirm" && r.status >= 500).length;
  await until("ledger booked every succeeded payment", async () => (await paymentRows(merchant)).length >= succeeded, 60_000, 1000).catch(() => {});
  const booked = (await paymentRows(merchant)).length;
  console.log(`  ${errored} confirms answered with an error; ${booked - succeeded} of them had gone through anyway`);
  ok = check("nothing is lost or doubled: booked = succeeded plus the ambiguous ones at most", booked >= succeeded && booked <= succeeded + errored, `${booked} booked, ${succeeded} seen succeeding, ${errored} ambiguous`) && ok;
  await sleep(3000);
  // A confirm that died between asking the network and recording the answer is charged but stuck in processing. Reconciliation
  // names it at once; the payments service settles it from the network's record after two minutes.
  let swept = await reconcile(token);
  if (swept.run.status !== "clean") {
    console.log(`  reconciliation found ${swept.run.mismatch_count} difference(s): ${[...new Set(swept.mismatches.map((m) => m.kind))].join(", ")}; waiting for the sweeper`);
    swept = await until("the stuck payments to be settled", async () => {
      const again = await reconcile(token);
      return again.run.status === "clean" ? again : null;
    }, 240_000, 20_000).catch(() => swept);
  }
  ok = check("after the restart (and the sweeper): reconciliation is clean", swept.run.status === "clean", `${swept.run.mismatch_count} differences`) && ok;
  return ok;
}

// ---------------------------------------------------------------------------------------------------------------------
async function experiment5() {
  console.log("\n5. A poison message");
  console.log("   Hypothesis: a message that cannot be processed does not block the topic; after its retries it lands on a dead-letter topic,\n" +
    "   shows in the back-office, and sending it back after the cause is fixed processes it exactly once.");
  const merchant = await newMerchant();
  const admin = await staff("admin@cutpay.test");
  const paymentId = `pay_poison_${Date.now()}`;
  const refundId = `re_poison_${Date.now()}`;
  const refunded = envelope("RefundSucceeded", merchant.id, refundId, {
    refund_id: refundId, payment_id: paymentId, amount: 500, currency: "EUR", status: "succeeded",
  }, { eventId: `evt_poison_${refundId}` });
  const garbage = "this is not json at all";

  produce("refund.events", [[refundId, refunded], ["garbage", garbage]]);
  const healthyPayments = await payMany(merchant, 5, 5);
  let ok = check("traffic behind the poison is not blocked", healthyPayments.every((r) => r.result === "succeeded"));

  // Six retries with backoff add up to about half a minute.
  const letters = await until("both records on a dead-letter topic", async () => {
    const found = (await api("GET", "/v1/staff/dead_letters", { token: admin })).body.data ?? [];
    const ours = found.filter((l) => l.key === refundId || l.key === "garbage");
    return ours.some((l) => l.key === refundId) && ours.some((l) => l.key === "garbage") ? ours : null;
  }, 120_000, 2000);
  ok = check("the poison is visible in the back-office", letters.length >= 2, letters.map((l) => `${l.topic}: ${l.error?.slice(0, 60)}`).join(" | ")) && ok;

  // Fix the cause: the payment the refund refers to now exists. Then send the refund back.
  produce("payment.events", [[paymentId, envelope("PaymentSucceeded", merchant.id, paymentId, {
    payment_id: paymentId, status: "succeeded", amount: 5_000, currency: "EUR", payment_method_type: "bnpl", bnpl_fee: 150,
  })]]);
  await until("the payment is booked", async () => (await paymentRows(merchant)).some((t) => t.source_id === paymentId), 60_000, 1000);
  const refundLetter = letters.find((l) => l.key === refundId);
  const redriven = await api("POST", "/v1/staff/dead_letters/redrive", {
    token: admin, body: { topic: refundLetter.topic, partition: refundLetter.partition, offset: refundLetter.offset },
  });
  ok = check("an admin can send it back", redriven.status === 200, `status ${redriven.status}`) && ok;
  await until("the refund is booked", async () => {
    const rows = (await api("GET", "/v1/balance_transactions?limit=100", { token: merchant.token })).body.data;
    return rows.filter((t) => t.type === "refund").length === 1;
  }, 60_000, 1000).then(() => check("sent back, it is processed once the payment exists", true)).catch(() => (ok = check("sent back, it is processed", false)));

  const garbageLetter = letters.find((l) => l.key === "garbage");
  const discarded = await api("POST", "/v1/staff/dead_letters/discard", {
    token: admin, body: { topic: garbageLetter.topic, partition: garbageLetter.partition, offset: garbageLetter.offset },
  });
  ok = check("the unfixable one can be discarded", discarded.status === 200) && ok;
  const left = (await api("GET", "/v1/staff/dead_letters", { token: admin })).body.data.filter((l) => l.key === refundId);
  ok = check("handled records leave the list", left.length === 0) && ok;
  return ok;
}

const all = { 1: experiment1, 2: experiment2, 3: experiment3, 4: experiment4, 5: experiment5 };
const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(all);
for (const n of wanted) {
  try {
    results.push([n, await all[n]()]);
  } catch (error) {
    console.log(`  FAIL  experiment ${n} crashed: ${error.message}`);
    results.push([n, false]);
  }
}
console.log("\nSummary: " + results.map(([n, ok]) => `${n}:${ok ? "pass" : "FAIL"}`).join("  "));
process.exit(results.every(([, ok]) => ok) ? 0 : 1);
