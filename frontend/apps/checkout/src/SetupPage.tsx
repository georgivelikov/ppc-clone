import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, CreditCard, XCircle } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { useParams } from "react-router";
import { z } from "zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckoutApiError, confirmSetup, getSetupSession, tokenize, type SetupSession } from "./api";
import { detectBrand, formatCardNumber, formatExpiry, isExpired, luhnValid, parseExpiry } from "./card";
import { Shell } from "./Shell";
import { ThreeDsChallenge } from "./ThreeDsChallenge";

const schema = z.object({
  number: z.string().refine(luhnValid, "Enter a valid card number"),
  expiry: z.string().refine((value) => {
    const parsed = parseExpiry(value);
    return parsed !== null && !isExpired(parsed.month, parsed.year);
  }, "Enter a valid expiry date"),
  cvc: z.string().regex(/^\d{3,4}$/, "Enter the security code"),
});
type Values = z.infer<typeof schema>;

const BRAND_LABEL = { visa: "Visa", mastercard: "Mastercard", amex: "Amex", unknown: "" } as const;

function CardForm({ secret, onUpdate }: { secret: string; onUpdate: (session: SetupSession) => void }) {
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { number: "", expiry: "", cvc: "" } });
  const brand = detectBrand(watch("number"));

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const expiry = parseExpiry(values.expiry)!;
    try {
      // The card number goes only to the vault, our own service sees the token.
      const token = await tokenize({ number: values.number, exp_month: expiry.month, exp_year: expiry.year, cvc: values.cvc });
      onUpdate(await confirmSetup(secret, token));
    } catch (error) {
      setFormError(
        error instanceof CheckoutApiError && error.status < 500
          ? error.message
          : "We could not save your card. Please try again.",
      );
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4" aria-label="Card details">
      <h1 className="text-xl font-semibold">Save your card</h1>
      <p className="text-sm text-muted">You will not be charged now. Your card is kept for future payments.</p>
      {formError && <Alert>{formError}</Alert>}
      <div>
        <Label htmlFor="number">Card number</Label>
        <div className="relative">
          <Controller
            control={control}
            name="number"
            render={({ field }) => (
              <Input
                id="number"
                inputMode="numeric"
                autoComplete="cc-number"
                placeholder="1234 1234 1234 1234"
                aria-invalid={!!errors.number}
                className="pr-24"
                {...field}
                onChange={(event) => field.onChange(formatCardNumber(event.target.value))}
              />
            )}
          />
          <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center gap-1 text-xs text-muted">
            <CreditCard className="size-4" aria-hidden />
            {BRAND_LABEL[brand]}
          </span>
        </div>
        <FieldError>{errors.number?.message}</FieldError>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <Label htmlFor="expiry">Expiry</Label>
          <Controller
            control={control}
            name="expiry"
            render={({ field }) => (
              <Input
                id="expiry"
                inputMode="numeric"
                autoComplete="cc-exp"
                placeholder="MM / YY"
                aria-invalid={!!errors.expiry}
                {...field}
                onChange={(event) => field.onChange(formatExpiry(event.target.value))}
              />
            )}
          />
          <FieldError>{errors.expiry?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="cvc">CVC</Label>
          <Input id="cvc" inputMode="numeric" autoComplete="cc-csc" maxLength={4} placeholder="123" aria-invalid={!!errors.cvc} {...register("cvc")} />
          <FieldError>{errors.cvc?.message}</FieldError>
        </div>
      </div>
      <Button type="submit" className="w-full" loading={isSubmitting}>
        Save card
      </Button>
      <p className="text-center text-xs text-muted">Test card: 4242 4242 4242 4242, any future date, any CVC.</p>
    </form>
  );
}

function Outcome({ session }: { session: SetupSession }) {
  const saved = session.status === "succeeded";
  return (
    <div className="text-center" role="status">
      {saved ? (
        <CheckCircle2 className="mx-auto size-12 text-success" aria-hidden />
      ) : (
        <XCircle className="mx-auto size-12 text-danger" aria-hidden />
      )}
      <h1 className="mt-4 text-xl font-semibold">
        {saved ? "Card saved" : session.status === "canceled" ? "Link canceled" : "Card not saved"}
      </h1>
      <p className="mt-2 text-sm text-muted">
        {saved
          ? `${session.merchant_name ?? "The merchant"} can now charge this card for future payments.`
          : session.status === "canceled"
            ? "This link was canceled by the merchant."
            : (session.failure_message ?? "We could not save your card.")}
      </p>
      {session.return_url && (
        <a href={session.return_url} className="mt-6 inline-block text-sm font-medium text-brand-600 hover:underline">
          Return to {session.merchant_name ?? "merchant"}
        </a>
      )}
    </div>
  );
}

/** The hosted page where a customer saves a card for a merchant, without being charged. */
export function SetupPage() {
  const { secret = "" } = useParams();
  const queryClient = useQueryClient();
  const key = ["setup-session", secret];
  const { data: session, error, isPending } = useQuery({ queryKey: key, queryFn: () => getSetupSession(secret) });

  if (isPending) {
    return (
      <Shell>
        <p className="text-sm text-muted" role="status">
          Loading…
        </p>
      </Shell>
    );
  }
  if (error || !session) {
    const notFound = error instanceof CheckoutApiError && error.status === 404;
    return (
      <Shell>
        <h1 className="text-xl font-semibold">{notFound ? "Link not found" : "Something went wrong"}</h1>
        <Alert className="mt-4">{notFound ? "This link is not valid." : "We could not load this page. Please try again."}</Alert>
      </Shell>
    );
  }

  const header = session.merchant_name ? (
    <p className="mb-4 text-sm text-muted">
      {session.merchant_name}
      {session.description ? `: ${session.description}` : ""}
    </p>
  ) : null;
  switch (session.status) {
    case "requires_payment_method":
      return (
        <Shell>
          {header}
          <CardForm secret={secret} onUpdate={(next) => queryClient.setQueryData(key, next)} />
        </Shell>
      );
    case "requires_action":
      return (
        <Shell>
          <ThreeDsChallenge session={session} onDone={() => queryClient.invalidateQueries({ queryKey: key })} />
        </Shell>
      );
    default:
      return (
        <Shell>
          <Outcome session={session} />
        </Shell>
      );
  }
}
