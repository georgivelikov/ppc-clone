import { ApiError, type Payment } from "@cutpay/api-client";
import { Alert, Button, FieldError, formatMoney, Input, Label, toMajorString, toMinorUnits } from "@cutpay/ui";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api";
import { Dialog } from "../components/Dialog";

/**
 * Takes money from a held (pre-authorised) payment. Taking less than was held releases the rest to the customer, so
 * a hotel can hold the full stay and charge only what was used.
 */
export function CaptureDialog({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const queryClient = useQueryClient();
  const held = payment.amount;
  const [amount, setAmount] = useState(toMajorString(held, payment.currency));
  const [fieldError, setFieldError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (minor: number) => api.capturePayment(payment.id, minor),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["payment", payment.id] });
      await queryClient.invalidateQueries({ queryKey: ["payments"] });
      onClose();
    },
  });

  function submit() {
    const minor = toMinorUnits(amount, payment.currency);
    if (minor === null || minor < 1) {
      setFieldError("Enter a valid amount, e.g. 150.00");
    } else if (minor > held) {
      setFieldError(`You can capture at most ${formatMoney(held, payment.currency)}`);
    } else {
      setFieldError(null);
      mutation.mutate(minor);
    }
  }

  const minor = toMinorUnits(amount, payment.currency);
  const released = minor !== null && minor >= 1 && minor < held ? held - minor : 0;

  return (
    <Dialog title="Capture payment" onClose={onClose}>
      <form
        noValidate
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <p className="text-sm text-muted">
          {formatMoney(held, payment.currency)} is held on the customer&apos;s card. Capture it all, or less to release the
          rest.
        </p>
        {mutation.isError && (
          <Alert>{mutation.error instanceof ApiError ? mutation.error.message : "The capture could not be completed."}</Alert>
        )}
        <div>
          <Label htmlFor="capture-amount">Amount to capture ({payment.currency})</Label>
          <Input
            id="capture-amount"
            inputMode="decimal"
            value={amount}
            aria-invalid={!!fieldError}
            onChange={(event) => setAmount(event.target.value)}
          />
          <FieldError>{fieldError}</FieldError>
        </div>
        {released > 0 && (
          <p className="text-sm text-muted" data-testid="capture-release-note">
            {formatMoney(released, payment.currency)} will be released back to the customer.
          </p>
        )}
        <Button type="submit" className="w-full" loading={mutation.isPending}>
          Capture
        </Button>
      </form>
    </Dialog>
  );
}
