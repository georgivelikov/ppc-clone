import { describe, expect, it } from "vitest";
import { detectBrand, formatCardNumber, formatExpiry, isExpired, luhnValid, parseExpiry } from "./card";

describe("card helpers", () => {
  it("accepts the documented test cards and rejects typos", () => {
    for (const pan of ["4242424242424242", "5555555555554444", "4000000000003220", "378282246310005"]) {
      expect(luhnValid(pan)).toBe(true);
    }
    expect(luhnValid("4242424242424241")).toBe(false);
    expect(luhnValid("1234")).toBe(false);
  });

  it("detects the card brand from the first digits", () => {
    expect(detectBrand("4242 4242")).toBe("visa");
    expect(detectBrand("5555")).toBe("mastercard");
    expect(detectBrand("2221")).toBe("mastercard");
    expect(detectBrand("3782")).toBe("amex");
    expect(detectBrand("9999")).toBe("unknown");
  });

  it("groups the number as it is typed", () => {
    expect(formatCardNumber("4242424242424242")).toBe("4242 4242 4242 4242");
    expect(formatCardNumber("42-42 x")).toBe("4242");
    expect(formatCardNumber("378282246310005")).toBe("3782 822463 10005");
  });

  it("formats and parses the expiry", () => {
    expect(formatExpiry("1")).toBe("1");
    expect(formatExpiry("123")).toBe("12 / 3");
    expect(formatExpiry("1230")).toBe("12 / 30");
    expect(parseExpiry("12 / 30")).toEqual({ month: 12, year: 2030 });
    expect(parseExpiry("13 / 30")).toBeNull();
    expect(parseExpiry("12 / 3")).toBeNull();
  });

  it("knows when a card has expired", () => {
    const now = new Date("2026-10-17");
    expect(isExpired(9, 2026, now)).toBe(true);
    expect(isExpired(10, 2026, now)).toBe(false);
    expect(isExpired(1, 2027, now)).toBe(false);
    expect(isExpired(12, 2025, now)).toBe(true);
  });
});
