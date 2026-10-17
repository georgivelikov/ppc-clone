import { describe, expect, it } from "vitest";
import { formatIban, normaliseIban } from "./iban";

describe("normaliseIban", () => {
  it.each(["BG80BNBG96611020345678", "DE89370400440532013000", "GB82WEST12345698765432", "DE94370400440532010000"])(
    "accepts %s",
    (iban) => {
      expect(normaliseIban(iban)).toBe(iban);
    },
  );

  it("ignores spaces and case", () => {
    expect(normaliseIban("de89 3704 0044 0532 0130 00")).toBe("DE89370400440532013000");
  });

  it.each([
    ["a wrong last digit", "BG80BNBG96611020345679"],
    ["wrong check digits", "BG81BNBG96611020345678"],
    ["a truncated number", "DE8937040044053201300"],
    ["no country", "12345678901234567890"],
    ["an illegal character", "BG80BNBG9661102034567!"],
    ["an empty string", ""],
  ])("rejects %s", (_, iban) => {
    expect(normaliseIban(iban)).toBeNull();
  });
});

describe("formatIban", () => {
  it("groups in blocks of four", () => {
    expect(formatIban("BG80BNBG96611020345678")).toBe("BG80 BNBG 9661 1020 3456 78");
  });
});
