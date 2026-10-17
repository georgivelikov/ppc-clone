// Verifies Cutpay webhook signatures. This is everything a merchant needs; copy it into your own server.
//
//   Cutpay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>
//
// Always verify the RAW request body, exactly as received: parsing and re-serialising it changes the bytes.
import { createHmac, timingSafeEqual } from "node:crypto";

export type VerifyResult = "valid" | "malformed_header" | "timestamp_out_of_range" | "no_matching_signature";

export const DEFAULT_TOLERANCE_SECONDS = 300;

export function sign(secret: string, body: string, timestampSeconds: number): string {
  return `t=${timestampSeconds},v1=${hmac(secret, `${timestampSeconds}.${body}`)}`;
}

export function verify(
  secret: string,
  body: string,
  header: string | undefined,
  nowSeconds: number,
  toleranceSeconds = DEFAULT_TOLERANCE_SECONDS,
): VerifyResult {
  if (!header) return "malformed_header";
  let timestamp: number | null = null;
  const signatures: string[] = [];
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq <= 0) return "malformed_header";
    const key = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (key === "t") {
      if (!/^\d+$/.test(value)) return "malformed_header";
      timestamp = Number(value);
    } else if (key === "v1") {
      signatures.push(value);
    }
  }
  if (timestamp === null || signatures.length === 0) return "malformed_header";
  // Rejecting old timestamps is what stops someone replaying a request they captured.
  if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) return "timestamp_out_of_range";

  const expected = Buffer.from(hmac(secret, `${timestamp}.${body}`));
  const matches = signatures.some((candidate) => {
    const given = Buffer.from(candidate);
    return given.length === expected.length && timingSafeEqual(given, expected);
  });
  return matches ? "valid" : "no_matching_signature";
}

function hmac(secret: string, payload: string): string {
  return createHmac("sha256", secret).update(payload).digest("hex");
}
