import { Alert, Button, Card, formatMoney } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { getConfig, getOrder } from "../api";
import { loadCutpay, type CutpayApi, type CutpayElementsApi } from "../cutpay-loader";

/** Pay without leaving the shop: the card input is Cutpay's iframe, mounted into this page by the SDK. */
export function PayPage() {
  const { orderId = "" } = useParams();
  const navigate = useNavigate();
  const { data: config } = useQuery({ queryKey: ["config"], queryFn: getConfig });
  const { data: order, error } = useQuery({ queryKey: ["order", orderId], queryFn: () => getOrder(orderId) });

  const mountPoint = useRef<HTMLDivElement>(null);
  const sdk = useRef<{ cutpay: CutpayApi; elements: CutpayElementsApi } | null>(null);
  const [ready, setReady] = useState(false);
  const [complete, setComplete] = useState(false);
  const [cardError, setCardError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [paying, setPaying] = useState(false);

  const clientSecret = order?.clientSecret;
  const publishableKey = config?.publishableKey;
  const checkoutUrl = config?.checkoutUrl;

  useEffect(() => {
    if (!clientSecret || !publishableKey || !checkoutUrl || !mountPoint.current) return;
    let element: ReturnType<CutpayElementsApi["create"]> | null = null;
    let cancelled = false;
    loadCutpay(checkoutUrl)
      .then((Cutpay) => {
        if (cancelled || !mountPoint.current) return;
        const cutpay = Cutpay(publishableKey, { checkoutUrl });
        const elements = cutpay.elements({ clientSecret });
        element = elements.create("card");
        element.on("ready", () => setReady(true));
        element.on("change", (state) => {
          setComplete(state.complete);
          setCardError(state.error);
        });
        element.mount(mountPoint.current);
        sdk.current = { cutpay, elements };
      })
      .catch((e: unknown) => setProblem(e instanceof Error ? e.message : "Could not load the payment form."));
    return () => {
      cancelled = true;
      element?.unmount();
      sdk.current = null;
    };
  }, [clientSecret, publishableKey, checkoutUrl]);

  async function pay() {
    if (!sdk.current || !order) return;
    setPaying(true);
    setProblem(null);
    const { error: payError } = await sdk.current.cutpay.confirmPayment({
      elements: sdk.current.elements,
      returnUrl: `${window.location.origin}/thanks?order=${order.id}`,
      receiptEmail: order.email ?? undefined,
    });
    if (payError) {
      setProblem(payError.message);
      setPaying(false);
      return;
    }
    // The browser's word is not proof of payment: the thanks page waits for the webhook to confirm.
    navigate(`/thanks?order=${order.id}`);
  }

  if (error) return <Alert>We could not find that order.</Alert>;
  if (!order) return <p className="text-sm text-muted">Loading…</p>;
  if (order.status !== "pending" || !order.clientSecret) {
    return (
      <p className="text-sm">
        This order is {order.status}. <Link to={`/thanks?order=${order.id}`} className="text-brand-600 hover:underline">See its status</Link>.
      </p>
    );
  }

  return (
    <section aria-labelledby="pay-heading" className="mx-auto max-w-lg">
      <h1 id="pay-heading" className="mb-2 text-2xl font-semibold">
        Pay by card
      </h1>
      <p className="mb-6 text-sm text-muted">
        Order {order.id} · {formatMoney(order.amount, order.currency)}
      </p>
      <Card>
        <p className="mb-2 text-sm font-medium">Card details</p>
        <div ref={mountPoint} data-testid="card-mount" />
        {cardError && (
          <p role="alert" className="mt-2 text-sm text-danger">
            {cardError}
          </p>
        )}
        {problem && <Alert className="mt-4">{problem}</Alert>}
        <Button className="mt-4 w-full" onClick={pay} loading={paying} disabled={!ready || !complete || paying}>
          Pay {formatMoney(order.amount, order.currency)}
        </Button>
        <p className="mt-3 text-center text-xs text-muted">Test card: 4242 4242 4242 4242, any future date, any CVC.</p>
      </Card>
    </section>
  );
}
