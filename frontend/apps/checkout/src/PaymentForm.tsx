import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { CreditCard } from "lucide-react";
import { useMemo, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";
import { CheckoutApiError, confirm, getSession, tokenize, type Session } from "./api";
import { detectBrand, formatCardNumber, formatExpiry, isExpired, luhnValid, parseExpiry } from "./card";

/** The rules for the card form; the messages are in the language being shown. */
function makeSchema(t: (key: string) => string) {
  return z.object({
    email: z.string().trim().min(1, t("card.errors.emailRequired")).pipe(z.email(t("card.errors.emailInvalid"))),
    number: z.string().refine(luhnValid, t("card.errors.number")),
    expiry: z.string().refine((value) => {
      const parsed = parseExpiry(value);
      return parsed !== null && !isExpired(parsed.month, parsed.year);
    }, t("card.errors.expiry")),
    cvc: z.string().regex(/^\d{3,4}$/, t("card.errors.cvc")),
  });
}
type Values = z.infer<ReturnType<typeof makeSchema>>;

const BRAND_LABEL = { visa: "Visa", mastercard: "Mastercard", amex: "Amex", unknown: "" } as const;

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

export function PaymentForm({ secret, session, onUpdate }: Props) {
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
  const [formError, setFormError] = useState<string | null>(null);
  // `t` is a new function when the language changes, so the messages are not left in the old language.
  const schema = useMemo(() => makeSchema(t), [t]);
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
      try {
        onUpdate(await confirm(secret, token, values.email));
      } catch (error) {
        // A server error or a lost connection does not mean the payment failed: the answer may have been lost on its
        // way back after the card was charged. Look at the payment before telling the customer anything, so they are
        // not invited to pay twice for something that went through.
        if (!(error instanceof CheckoutApiError) || error.status >= 500) {
          const current = await getSession(secret).catch(() => null);
          if (current && current.status !== "requires_payment_method") {
            onUpdate(current);
            return;
          }
        }
        throw error;
      }
    } catch (error) {
      setFormError(error instanceof CheckoutApiError && error.status < 500 ? error.message : t("card.failed"));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4" aria-label={t("card.formLabel")}>
      <h1 className="text-xl font-semibold">{t("card.title")}</h1>
      {formError && <Alert>{formError}</Alert>}
      <div>
        <Label htmlFor="email">{t("card.email")}</Label>
        <Input id="email" type="email" autoComplete="email" aria-invalid={!!errors.email} {...register("email")} />
        <FieldError>{errors.email?.message}</FieldError>
      </div>
      <div>
        <Label htmlFor="number">{t("card.number")}</Label>
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
          <Label htmlFor="expiry">{t("card.expiry")}</Label>
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
          <Label htmlFor="cvc">{t("card.cvc")}</Label>
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
        {t("card.pay", { amount: money(session.amount, session.currency) })}
      </Button>
      {session.save_card && (
        <p className="text-center text-xs text-muted" data-testid="save-card-notice">
          {t("card.saveNotice", { merchant: session.merchant_name ?? t("card.thisMerchant") })}
        </p>
      )}
      <p className="text-center text-xs text-muted">{t("card.testCard")}</p>
    </form>
  );
}
