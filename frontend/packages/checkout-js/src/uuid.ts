/**
 * A random version 4 UUID. {@code crypto.randomUUID} exists only in secure contexts (https, or localhost), so on a plain
 * http address - a staging server, a cluster behind a name that is not localhost - it is undefined and calling it throws.
 * {@code getRandomValues} works everywhere, so use it as the fallback. Found by running the dashboard on kind.
 */
export function randomUUID(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0"));
  return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10).join("")}`;
}
