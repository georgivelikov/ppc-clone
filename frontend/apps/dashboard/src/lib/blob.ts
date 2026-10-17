import { useEffect, useState } from "react";

/** Where the checkout app lives: link addresses, QR codes and the card element all point there. */
export const CHECKOUT_URL: string = import.meta.env.VITE_CHECKOUT_URL ?? "http://localhost:5174";

/**
 * Loads an authenticated image (the API needs the access token, so a plain `<img src>` cannot fetch it) and
 * returns an object URL for it. The URL is released when the inputs change or the component goes away.
 */
export function useBlobUrl(load: (() => Promise<Blob>) | null, deps: readonly unknown[]): { url: string | null; error: boolean } {
  const [state, setState] = useState<{ url: string | null; error: boolean }>({ url: null, error: false });
  useEffect(() => {
    if (!load) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    load()
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setState({ url: objectUrl, error: false });
      })
      .catch(() => {
        if (!cancelled) setState({ url: null, error: true });
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
    // The caller lists exactly what the loader depends on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

/** Saves a blob as a file, the way a download link would. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
