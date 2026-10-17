import { useEffect, useState } from "react";
import { api } from "../../api";

const FINAL = new Set(["succeeded", "failed", "canceled", "refunded", "partially_refunded"]);

export interface LiveStatus {
  status: string | null;
  failureCode: string | null;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Follows one payment until it ends. The live stream (Server-Sent Events) is the fast path, so a screen can flip
 * within a second of the payment; if the connection drops, the payment is read directly and the stream reopened, so
 * a flaky network never leaves the screen stuck on "waiting".
 */
export function usePaymentStatus(paymentId: string | null): LiveStatus {
  const [live, setLive] = useState<LiveStatus>({ status: null, failureCode: null });

  useEffect(() => {
    setLive({ status: null, failureCode: null });
    if (!paymentId) return;
    const controller = new AbortController();
    let done = false;

    const apply = (status: string, failureCode: string | null) => {
      if (controller.signal.aborted) return;
      setLive({ status, failureCode });
      if (FINAL.has(status)) done = true;
    };

    (async () => {
      while (!done && !controller.signal.aborted) {
        try {
          await api.watchPayment(paymentId, (event) => apply(event.status, event.failure_code ?? null), controller.signal);
        } catch {
          // fall through to the direct read below
        }
        if (done || controller.signal.aborted) return;
        await sleep(1500);
        try {
          const payment = await api.getPayment(paymentId);
          apply(payment.status, payment.failure_code);
        } catch {
          // keep trying
        }
      }
    })();

    return () => controller.abort();
  }, [paymentId]);

  return live;
}
