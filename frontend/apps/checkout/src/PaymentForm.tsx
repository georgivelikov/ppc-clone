import { Alert, Button, FieldError, formatMoney, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { CreditCard } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";
import { CheckoutApiError, confirm, tokenize, type Session } from "./api";
import { detectBrand, formatCardNumber, formatExpiry, isExpired, luhnValid, parseExpiry } from "./card";

const schema = z.object({
  email: z.string().trim().min(1, "Enter your email").pipe(z.email("Enter a valid email")),
  number: z.string().refine(luhnValid, "Enter a valid card number"),
  expiry: z.string().refine((value) => {
    const parsed = parseExpiry(value);
    return parsed !== null && !isExpired(parsed.month, parsed.year);
  }, "Enter a valid expiry date"),
  cvc: z.string().regex(/^\d{3,4}$/, "Enter the security code"),
});
type Values = z.infer<typeof schema>;

const BRAND_LABEL = { visa: "Visa", mastercard: "Mastercard", amex: "Amex", unknown: "" } as const;

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

export function PaymentForm({ secret, session, onUpdate }: Props) {
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: "", number: "", expiry: "", cvc: "" },
  });
  const brand = detectBrand(watch("number"));

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const expiry = parseExpiry(values.expiry)!;
    try {
      // 1. The card number goes only to the vault, which returns a token.
      const token = await tokenize({
        number: values.number,
        exp_month: expiry.month,
        exp_year: expiry.year,
        cvc: values.cvc,
      });
      // 2. Our payments service only ever sees the token.
      onUpdate(await confirm(secret, token, values.email));
    } catch (error) {
      setFormError(
        error instanceof CheckoutApiError && error.status < 500
          ? error.message
          : "We could not process your payment. Please try again.",
      );
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4" aria-label="Payment details">
      <h1 className="text-xl font-semibold">Pay by card</h1>
      {formError && <Alert>{formError}</Alert>}
      <div>
        <Label htmlFor="email">Email</Label>
        <Input id="email" type="email" autoComplete="email" aria-invalid={!!errors.email} {...register("email")} />
        <FieldError>{errors.email?.message}</FieldError>
      </div>
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
          <Input
            id="cvc"
            inputMode="numeric"
            autoComplete="cc-csc"
            maxLength={4}
            placeholder="123"
            aria-invalid={!!errors.cvc}
            {...register("cvc")}
          />
          <FieldError>{errors.cvc?.message}</FieldError>
        </div>
      </div>
      <Button type="submit" className="w-full" loading={isSubmitting}>
        Pay {formatMoney(session.amount, session.currency)}
      </Button>
      <p className="text-center text-xs text-muted">
        Test card: 4242 4242 4242 4242, any future date, any CVC.
      </p>
    </form>
  );
}
