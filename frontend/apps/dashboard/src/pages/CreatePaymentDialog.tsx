import { ApiError, type CheckoutSessionCreated } from "@cutpay/api-client";
import { Alert, Button, FieldError, Input, Label, toMinorUnits } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router";
import { z } from "zod";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { Dialog } from "../components/Dialog";
import { useTestMode } from "../layout/testMode";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

const schema = z.object({
  amount: z.string().min(1, "Enter an amount"),
  currency: z.string().length(3),
  description: z.string().max(500),
});
type Values = z.infer<typeof schema>;

/** Creates a payment and gives the merchant the hosted checkout link to open (a stand-in for a real storefront). */
export function CreatePaymentDialog({ onClose }: { onClose: () => void }) {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const queryClient = useQueryClient();
  const [created, setCreated] = useState<CheckoutSessionCreated | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [splitTo, setSplitTo] = useState("");
  const [splitAmount, setSplitAmount] = useState("");
  const accounts = useQuery({ queryKey: ["connected-accounts", { livemode: !testMode }], queryFn: () => api.listConnectedAccounts({ limit: 100, livemode: !testMode }) });
  const destinations = (accounts.data?.data ?? []).filter((account) => account.status === "active");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { amount: "25.00", currency: merchant.default_currency, description: "Test payment" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const minor = toMinorUnits(values.amount, values.currency);
    if (minor === null) {
      setError("amount", { message: "Enter a valid amount, e.g. 12.50" });
      return;
    }
    let transferData: { destination: string; amount: number }[] | undefined;
    if (splitTo) {
      const share = toMinorUnits(splitAmount, values.currency);
      if (share === null || share > minor) {
        setFormError(share === null ? "Enter how much the connected account receives, e.g. 10.00" : "The account cannot receive more than the payment");
        return;
      }
      transferData = [{ destination: splitTo, amount: share }];
    }
    try {
      setCreated(
        await api.createCheckoutSession(
          {
            amount: minor,
            currency: values.currency,
            description: values.description || undefined,
            merchant_name: merchant.display_name,
            transfer_data: transferData,
          },
          !testMode,
        ),
      );
      void queryClient.invalidateQueries({ queryKey: ["payments"] });
    } catch (error) {
      setFormError(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    }
  });

  if (created) {
    return (
      <Dialog title="Checkout is ready" onClose={onClose}>
        <p className="text-sm text-muted">
          Send this link to your customer, or open it yourself to pay with a test card.
        </p>
        <p className="mt-3 rounded-lg bg-canvas px-3 py-2 text-xs break-all">{created.url}</p>
        <div className="mt-4 flex gap-3">
          <a
            href={created.url}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-10 flex-1 items-center justify-center rounded-lg bg-brand-600 px-4 text-sm font-medium text-white hover:bg-brand-700"
          >
            Open checkout
          </a>
          <Link
            to={`/payments/${created.payment_id}`}
            onClick={onClose}
            className="inline-flex h-10 items-center justify-center rounded-lg border border-border px-4 text-sm font-medium hover:bg-canvas"
          >
            View payment
          </Link>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title="Create test payment" onClose={onClose}>
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert>{formError}</Alert>}
        <div className="grid grid-cols-[1fr_6rem] gap-3">
          <div>
            <Label htmlFor="amount">Amount</Label>
            <Input id="amount" inputMode="decimal" aria-invalid={!!errors.amount} {...register("amount")} />
            <FieldError>{errors.amount?.message}</FieldError>
          </div>
          <div>
            <Label htmlFor="currency">Currency</Label>
            <select
              id="currency"
              className="h-10 w-full rounded-lg border border-border bg-surface px-2 text-sm"
              {...register("currency")}
            >
              {CURRENCIES.map((code) => (
                <option key={code}>{code}</option>
              ))}
            </select>
          </div>
        </div>
        <div>
          <Label htmlFor="description">Description</Label>
          <Input id="description" {...register("description")} />
        </div>
        {destinations.length > 0 && (
          <fieldset className="space-y-3 rounded-lg border border-border p-3">
            <legend className="px-1 text-sm font-medium">Share the payment (optional)</legend>
            <div>
              <Label htmlFor="split-account">Send part of it to</Label>
              <select
                id="split-account"
                value={splitTo}
                onChange={(e) => setSplitTo(e.target.value)}
                className="h-10 w-full rounded-lg border border-border bg-surface px-2 text-sm"
              >
                <option value="">Nobody, keep it all</option>
                {destinations.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </div>
            {splitTo && (
              <div>
                <Label htmlFor="split-amount">The account receives</Label>
                <Input id="split-amount" inputMode="decimal" value={splitAmount} onChange={(e) => setSplitAmount(e.target.value)} placeholder="10.00" />
                <p className="mt-1 text-xs text-muted">You keep the rest, and pay the card fee from it.</p>
              </div>
            )}
          </fieldset>
        )}
        <Button type="submit" className="w-full" loading={isSubmitting}>
          Create checkout
        </Button>
      </form>
    </Dialog>
  );
}
