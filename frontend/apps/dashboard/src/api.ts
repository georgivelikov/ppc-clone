import { ApiClient } from "@cutpay/api-client";

// The auth provider registers a handler here so the client can tell React when the session is gone.
let sessionExpiredHandler: (() => void) | undefined;

export function onSessionExpired(handler: (() => void) | undefined): void {
  sessionExpiredHandler = handler;
}

export const api = new ApiClient({
  baseUrl: import.meta.env.VITE_API_URL ?? "",
  onSessionExpired: () => sessionExpiredHandler?.(),
});
