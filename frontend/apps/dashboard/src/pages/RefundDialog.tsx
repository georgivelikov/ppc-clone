import { ApiError, type Payment } from "@cutpay/api-client";
import { Alert, Button, FieldError, formatMoney, Input, Label, toMajorString, toMinorUnits } from "@cutpay/ui";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { Dialog } from "../components/Dialog";

/** Full or partial refund. Amounts are typed in major units ("12.50") and sent as minor units. */
export function RefundDialog({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const queryClient = useQueryClient();
  const remaining = payment.amount - payment.amount_refunded;
  const [amount, setAmount] = useState(toMajorString(remaining, payment.currency));
  const [reason, setReason] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (minor: number) =>
      api.createRefund({ payment_intent: payment.id, amount: minor, reason: reason.trim() || undefined }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["payment", payment.id] });
      await queryClient.invalidateQueries({ queryKey: ["payments"] });
      onClose();
    },
  });

  function submit() {
    const minor = toMinorUnits(amount, payment.currency);
    if (minor === null) {
      setFieldError("Enter a valid amount, e.g. 12.50");
    } else if (minor > remaining) {
      setFieldError(`You can refund at most ${formatMoney(remaining, payment.currency)}`);
    } else {
      setFieldError(null);
      mutation.mutate(minor);
    }
  }

  return (
    <Dialog title="Refund payment" onClose={onClose}>
      <form
        noValidate
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <p className="text-sm text-muted">
          {formatMoney(remaining, payment.currency)} of {formatMoney(payment.amount, payment.currency)} can still be
          refunded.
        </p>
        {mutation.isError && (
          <Alert>{mutation.error instanceof ApiError ? mutation.error.message : "The refund could not be created."}</Alert>
        )}
        <div>
          <Label htmlFor="refund-amount">Refund amount ({payment.currency})</Label>
          <Input
            id="refund-amount"
            inputMode="decimal"
            value={amount}
            aria-invalid={!!fieldError}
            onChange={(event) => setAmount(event.target.value)}
          />
          <FieldError>{fieldError}</FieldError>
        </div>
        <div>
          <Label htmlFor="refund-reason">Reason (optional)</Label>
          <Input id="refund-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </div>
        <Button type="submit" className="w-full" loading={mutation.isPending}>
          Refund
        </Button>
      </form>
    </Dialog>
  );
}
