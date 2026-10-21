import { staff, reconcile } from "./lib.mjs";
const r = await reconcile(await staff());
console.log(JSON.stringify(r.run), "\n", r.mismatches.map((m) => `${m.kind} ${m.payment_id} p=${m.payments_amount} l=${m.ledger_amount} a=${m.acquirer_amount}`).join("\n"));
