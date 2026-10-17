// The same arithmetic the ledger uses (docs/plans/phase-02-ledger-payouts.md): a percentage in basis points plus a
// fixed amount, rounded once to the nearest cent with ties going to the even cent (banker's rounding), and never
// more than the payment itself.

export type CardKind = "eea_consumer" | "other";

export interface FeePlan {
  label: string;
  /** Basis points: 129 means 1.29%. */
  bps: number;
  /** Minor units (cents). */
  fixed: number;
}

export const FEE_PLANS: Record<CardKind, FeePlan> = {
  eea_consumer: { label: "European consumer card", bps: 129, fixed: 10 },
  other: { label: "Business or non-European card", bps: 269, fixed: 10 },
};

/** Instant payouts cost 1% on top of the amount; waiting for the next scheduled payout is free. */
export const INSTANT_PAYOUT_BPS = 100;

/** amount × bps / 10 000, rounded half to even, using integers only so no cent is ever lost to floating point. */
export function percentageHalfEven(amount: number, bps: number): number {
  const numerator = amount * bps;
  const quotient = Math.floor(numerator / 10_000);
  const remainder = numerator - quotient * 10_000;
  if (remainder * 2 > 10_000) return quotient + 1;
  if (remainder * 2 === 10_000) return quotient % 2 === 0 ? quotient : quotient + 1;
  return quotient;
}

export function cardFee(amount: number, kind: CardKind): number {
  if (amount <= 0) return 0;
  const plan = FEE_PLANS[kind];
  return Math.min(percentageHalfEven(amount, plan.bps) + plan.fixed, amount);
}

export function instantPayoutFee(amount: number): number {
  return amount <= 0 ? 0 : percentageHalfEven(amount, INSTANT_PAYOUT_BPS);
}
