/** The test cards the simulated card network understands. Keep in step with acquirer-sim's TestCards and BinTable. */
export const TEST_CARDS = [
  { number: "4242 4242 4242 4242", result: "Approved", note: "Any other valid card number is approved too." },
  { number: "4000 0000 0000 0002", result: "Declined", note: "card_declined: Your card was declined." },
  { number: "4000 0000 0000 9995", result: "Declined", note: "insufficient_funds: Your card has insufficient funds." },
  { number: "4000 0000 0000 0069", result: "Declined", note: "expired_card: Your card has expired." },
  { number: "4000 0000 0000 3220", result: "3-D Secure", note: "The customer is asked for a one-time code. Enter 1234 to approve; any other code fails the payment. A card saved after that can be charged later without the customer." },
  { number: "4000 0027 6000 3184", result: "3-D Secure every time", note: "Asks for authentication on every charge. Saved for later, it cannot be charged without the customer: an off-session payment fails with authentication_required (a subscription goes past due)." },
  { number: "4000 0000 0000 0341", result: "Declined when charged", note: "Can be saved to a customer, but every charge is declined. Use it to see a subscription go past due and be retried." },
  { number: "4000 0000 0000 0119", result: "Processing error", note: "The card network answers with an error, and the payment fails with a retry message." },
  { number: "4000 0000 0000 7775", result: "Timeout", note: "The card network answers only after ten seconds, far too late, so the payment fails with a retry message." },
] as const;

export const FEE_CARDS = [
  { number: "4242 4242 4242 4242", kind: "European consumer", fee: "1.29% + €0.10" },
  { number: "4000 0100 0000 0001", kind: "European business", fee: "2.69% + €0.10" },
  { number: "4000 0200 0000 0000", kind: "Non-European consumer", fee: "2.69% + €0.10" },
  { number: "4000 0300 0000 0009", kind: "Non-European business", fee: "2.69% + €0.10" },
] as const;

export function TestCardsPage() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Test cards</h1>
      <p className="mt-3 text-muted">
        In test mode, use these numbers with any future expiry date and any CVC (3 digits, or 4 for American Express). Numbers must
        pass the usual card checksum, so a random number will be rejected before it reaches the card network.
      </p>

      <h2 className="mt-10 text-xl font-semibold">Payment outcomes</h2>
      <div className="mt-3 overflow-x-auto rounded-card border border-border bg-surface">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Number</th>
              <th className="px-4 py-3 font-medium">Result</th>
              <th className="px-4 py-3 font-medium">What happens</th>
            </tr>
          </thead>
          <tbody>
            {TEST_CARDS.map((card) => (
              <tr key={card.number} className="border-b border-border last:border-0">
                <td className="px-4 py-3 font-mono whitespace-nowrap">{card.number}</td>
                <td className="px-4 py-3 font-medium">{card.result}</td>
                <td className="px-4 py-3 text-muted">{card.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 className="mt-10 text-xl font-semibold">Fee tiers</h2>
      <p className="mt-2 text-sm text-muted">
        The first six digits decide which fee a card pays. All of these are approved; use them to see the different fees in your
        balance.
      </p>
      <div className="mt-3 overflow-x-auto rounded-card border border-border bg-surface">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="px-4 py-3 font-medium">Number</th>
              <th className="px-4 py-3 font-medium">Card</th>
              <th className="px-4 py-3 font-medium">Fee</th>
            </tr>
          </thead>
          <tbody>
            {FEE_CARDS.map((card) => (
              <tr key={card.number} className="border-b border-border last:border-0">
                <td className="px-4 py-3 font-mono whitespace-nowrap">{card.number}</td>
                <td className="px-4 py-3">{card.kind}</td>
                <td className="px-4 py-3 text-muted">{card.fee}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 className="mt-10 text-xl font-semibold">Test bank accounts</h2>
      <p className="mt-2 text-sm text-muted">
        Payouts go to a pretend bank. Any valid IBAN works, for example <code>BG80 BNBG 9661 1020 3456 78</code>. An IBAN ending in{" "}
        <code>0000</code>, such as <code>DE94 3704 0044 0532 0100 00</code>, is accepted but its payouts fail with{" "}
        <code>account_closed</code>, so you can test failed payouts.
      </p>
    </>
  );
}
