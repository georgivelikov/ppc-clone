import { useQuery } from "@tanstack/react-query";
import { Smartphone } from "lucide-react";
import { useEffect } from "react";
import { getSession, type Session } from "./api";

/**
 * Shown while the customer confirms the BLIK payment in their banking app. The answer reaches the payment from the bank,
 * not this page, so the page asks the server every second until the payment has moved on.
 */
export function BlikWaiting({ secret, onDone }: { secret: string; onDone: (session: Session) => void }) {
  const session = useQuery({
    queryKey: ["blik-wait", secret],
    queryFn: () => getSession(secret),
    refetchInterval: 1000,
    retry: true,
    gcTime: 0,
  });
  const status = session.data?.status;
  useEffect(() => {
    if (session.data && status !== "requires_action") onDone(session.data);
  }, [session.data, status, onDone]);

  return (
    <div className="text-center" role="status">
      <Smartphone className="mx-auto size-12 text-brand-700" aria-hidden />
      <h1 className="mt-4 text-xl font-semibold">Confirm in your banking app</h1>
      <p className="mt-2 text-sm text-muted">
        We sent a request to your bank. Open your banking app and approve the payment. This page updates by itself.
      </p>
      <p className="mt-4 text-xs text-muted">
        Trying it out?{" "}
        <a href="/vault/sim/blik-app" target="_blank" rel="noreferrer" className="font-medium text-brand-600 hover:underline">
          Open the pretend bank app
        </a>{" "}
        to approve or reject it. If you do nothing, it is approved after a few seconds.
      </p>
    </div>
  );
}
