import { describe, expect, it } from "vitest";
import { cardFee, instantPayoutFee, percentageHalfEven } from "./pricing";

describe("card fees", () => {
  it("charges 1.29% + €0.10 on a European consumer card", () => {
    expect(cardFee(10_000, "eea_consumer")).toBe(139); // the example from the docs: €100.00 pays €1.39
    expect(cardFee(2_500, "eea_consumer")).toBe(42); // 32.25 -> 32, plus 10
  });

  it("charges 2.69% + €0.10 on business and non-European cards", () => {
    expect(cardFee(10_000, "other")).toBe(279);
  });

  it("rounds exact halves to the even cent", () => {
    // 1.29% of 3 876 is 50.0004: not a tie. 1.29% of 5 000 is 64.5: a tie that goes to 64 (even).
    expect(percentageHalfEven(5_000, 129)).toBe(64);
    // 1.29% of 7 000 is 90.3. 1.29% of 1 000 is 12.9.
    expect(percentageHalfEven(7_000, 129)).toBe(90);
    expect(percentageHalfEven(1_000, 129)).toBe(13);
    // 100 bps of 150 is 1.5, a tie that goes to 2 (even); of 250 is 2.5, which goes to 2.
    expect(percentageHalfEven(150, 100)).toBe(2);
    expect(percentageHalfEven(250, 100)).toBe(2);
  });

  it("never charges more than the payment", () => {
    expect(cardFee(5, "eea_consumer")).toBe(5);
    expect(cardFee(10, "other")).toBe(10);
    expect(cardFee(11, "other")).toBe(10); // the percentage rounds to 0, so only the fixed 10 remains
  });

  it("charges nothing for nothing", () => {
    expect(cardFee(0, "eea_consumer")).toBe(0);
    expect(cardFee(-5, "other")).toBe(0);
  });

  it("charges 1% for an instant payout", () => {
    expect(instantPayoutFee(9_861)).toBe(99); // 98.61 -> 99
    expect(instantPayoutFee(10_000)).toBe(100);
    expect(instantPayoutFee(0)).toBe(0);
  });
});
