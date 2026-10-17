import { Card, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useState } from "react";
import { PageHeader, Section } from "../components";
import { cardFee, FEE_PLANS, instantPayoutFee, type CardKind } from "../lib/pricing";

function FeeCalculator() {
  const [amount, setAmount] = useState("100.00");
  const [kind, setKind] = useState<CardKind>("eea_consumer");
  const minor = toMinorUnits(amount, "EUR");
  const valid = minor !== null && minor > 0;
  const fee = valid ? cardFee(minor, kind) : 0;
  const net = valid ? minor - fee : 0;

  return (
    <Card className="max-w-xl">
      <h3 className="font-semibold">Fee calculator</h3>
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <div>
          <Label htmlFor="calc-amount">Payment amount (EUR)</Label>
          <Input id="calc-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} aria-invalid={!valid} />
        </div>
        <div>
          <Label htmlFor="calc-kind">Card</Label>
          <select
            id="calc-kind"
            value={kind}
            onChange={(e) => setKind(e.target.value as CardKind)}
            className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm"
          >
            {(Object.keys(FEE_PLANS) as CardKind[]).map((key) => (
              <option key={key} value={key}>
                {FEE_PLANS[key].label}
              </option>
            ))}
          </select>
        </div>
      </div>
      {valid ? (
        <dl className="mt-5 divide-y divide-border text-sm">
          <div className="flex justify-between py-2">
            <dt className="text-muted">Customer pays</dt>
            <dd>{formatMoney(minor, "EUR")}</dd>
          </div>
          <div className="flex justify-between py-2">
            <dt className="text-muted">Cutpay fee</dt>
            <dd data-testid="calc-fee">−{formatMoney(fee, "EUR")}</dd>
          </div>
          <div className="flex justify-between py-2 font-semibold">
            <dt>You receive</dt>
            <dd data-testid="calc-net">{formatMoney(net, "EUR")}</dd>
          </div>
          <div className="flex justify-between py-2 text-muted">
            <dt>Instant payout of that, if you cannot wait</dt>
            <dd>−{formatMoney(instantPayoutFee(net), "EUR")}</dd>
          </div>
        </dl>
      ) : (
        <p role="alert" className="mt-4 text-sm text-danger">
          Enter an amount such as 25.00.
        </p>
      )}
    </Card>
  );
}

export function PricingPage() {
  return (
    <>
      <PageHeader title="Simple pricing" intro="One fee per successful card payment. No monthly fee, no setup fee, no minimum." />
      <Section title="Card payments">
        <div className="overflow-x-auto rounded-card border border-border bg-surface">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-muted uppercase">
              <tr>
                <th className="px-4 py-3 font-medium">Card</th>
                <th className="px-4 py-3 font-medium">Fee per successful payment</th>
              </tr>
            </thead>
            <tbody>
              {(Object.keys(FEE_PLANS) as CardKind[]).map((key) => (
                <tr key={key} className="border-b border-border last:border-0">
                  <td className="px-4 py-3">{FEE_PLANS[key].label}</td>
                  <td className="px-4 py-3 font-medium">
                    {(FEE_PLANS[key].bps / 100).toFixed(2)}% + €{(FEE_PLANS[key].fixed / 100).toFixed(2)}
                  </td>
                </tr>
              ))}
              <tr>
                <td className="px-4 py-3">Refunds</td>
                <td className="px-4 py-3">The fee on the original payment is not returned</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="mt-3 max-w-2xl text-sm text-muted">
          Fees are worked out in cents and rounded once, to the nearest cent, with exact halves going to the even cent. A fee
          is never more than the payment itself.
        </p>
      </Section>

      <Section title="Payouts" className="border-t border-border">
        <ul className="max-w-2xl list-disc space-y-2 pl-5 text-sm">
          <li>
            <strong>Scheduled payouts are free.</strong> Choose daily, weekly or manual. Payments wait a few days (two by
            default) before they become available.
          </li>
          <li>
            <strong>Pay out now costs 1%</strong> of the amount, if you do not want to wait for the next payout.
          </li>
          <li>If a refund arrives after a payout, your balance can go below zero and the next payout is reduced to cover it.</li>
        </ul>
      </Section>

      <Section title="Try it" className="border-t border-border">
        <FeeCalculator />
      </Section>
    </>
  );
}
