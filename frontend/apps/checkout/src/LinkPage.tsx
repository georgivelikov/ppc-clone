import { Alert, Button, FieldError, formatMoney, Input, Label, toMajorString, toMinorUnits } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router";
import { CheckoutApiError, createLinkSession, getLink, type PublicLink } from "./api";
import { Shell } from "./Shell";

const UNAVAILABLE: Record<string, string> = {
  inactive: "This payment link is no longer active.",
  expired: "This payment link has expired.",
  sold_out: "This payment link is no longer available.",
};

/** What the customer will be charged, in minor units; null while the amount they typed is not valid yet. */
export function totalFor(link: PublicLink, chosen: string, quantity: number): number | null {
  const units = link.quantity_adjustable ? quantity : 1;
  switch (link.type) {
    case "fixed":
      return (link.amount ?? 0) * units;
    case "products":
      return link.line_items.reduce((sum, item) => sum + item.unit_amount * item.quantity, 0) * units;
    default: {
      const minor = toMinorUnits(chosen, link.currency);
      return minor === null ? null : minor;
    }
  }
}

function Summary({ link }: { link: PublicLink }) {
  return (
    <div>
      <p className="text-sm text-muted">{link.merchant_name ?? "Merchant"}</p>
      {link.image_url && (
        <img src={link.image_url} alt="" referrerPolicy="no-referrer" className="mt-3 max-h-48 w-full rounded-lg object-cover" />
      )}
      <h1 className="mt-3 text-xl font-semibold">{link.title}</h1>
      {link.description && <p className="mt-2 text-sm text-muted">{link.description}</p>}
      {link.type === "products" && (
        <ul className="mt-4 divide-y divide-border text-sm" aria-label="Items">
          {link.line_items.map((item) => (
            <li key={item.product_name} className="flex justify-between gap-4 py-2">
              <span>
                {item.product_name}
                {item.quantity > 1 && <span className="text-muted"> × {item.quantity}</span>}
              </span>
              <span>{formatMoney(item.unit_amount * item.quantity, link.currency)}</span>
            </li>
          ))}
        </ul>
      )}
      {link.type === "fixed" && link.amount !== null && (
        <p className="mt-3 text-3xl font-semibold" data-testid="amount">
          {formatMoney(link.amount, link.currency)}
        </p>
      )}
      {!link.livemode && <p className="mt-4 text-xs font-medium text-warning">Test mode — no real money moves.</p>}
    </div>
  );
}

/** The page a payment link opens: shows what is being sold and collects what the merchant asked for. */
export function LinkPage() {
  const { slug = "" } = useParams();
  const navigate = useNavigate();
  const { data: link, error, isPending } = useQuery({ queryKey: ["link", slug], queryFn: () => getLink(slug) });

  const [chosen, setChosen] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [fields, setFields] = useState({ email: "", name: "", phone: "", address: "" });
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (isPending) {
    return (
      <Shell>
        <p className="text-sm text-muted" role="status">
          Loading…
        </p>
      </Shell>
    );
  }
  if (error || !link) {
    const notFound = error instanceof CheckoutApiError && error.status === 404;
    return (
      <Shell>
        <h1 className="text-xl font-semibold">{notFound ? "Payment link not found" : "Something went wrong"}</h1>
        <Alert className="mt-4">
          {notFound ? "Check the link you were sent and try again." : "We could not load this payment link. Please try again."}
        </Alert>
      </Shell>
    );
  }
  if (!link.available) {
    return (
      <Shell aside={<Summary link={link} />}>
        <h2 className="text-lg font-semibold">Unavailable</h2>
        <Alert className="mt-4" role="alert">
          {UNAVAILABLE[link.unavailable_reason ?? "sold_out"]}
        </Alert>
        <p className="mt-4 text-sm text-muted">Ask {link.merchant_name ?? "the merchant"} for a new link if you still want to pay.</p>
      </Shell>
    );
  }

  const total = totalFor(link, chosen, quantity);

  function validate(): Record<string, string> {
    const found: Record<string, string> = {};
    if (link!.type === "customer_chooses") {
      const minor = toMinorUnits(chosen, link!.currency);
      if (minor === null) found.amount = "Enter an amount, like 12.50";
      else if (link!.min_amount !== null && minor < link!.min_amount)
        found.amount = `The minimum is ${formatMoney(link!.min_amount, link!.currency)}`;
      else if (link!.max_amount !== null && minor > link!.max_amount)
        found.amount = `The maximum is ${formatMoney(link!.max_amount, link!.currency)}`;
    }
    if (link!.collect.includes("email") && !/^\S+@\S+\.\S+$/.test(fields.email.trim())) found.email = "Enter a valid email";
    if (link!.collect.includes("name") && !fields.name.trim()) found.name = "Enter your name";
    if (link!.collect.includes("phone") && !fields.phone.trim()) found.phone = "Enter your phone number";
    if (link!.collect.includes("address") && !fields.address.trim()) found.address = "Enter your address";
    return found;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setProblems(found);
    if (Object.keys(found).length > 0) return;
    setSubmitting(true);
    try {
      const session = await createLinkSession(slug, {
        ...(link!.type === "customer_chooses" ? { amount: toMinorUnits(chosen, link!.currency)! } : {}),
        ...(link!.quantity_adjustable ? { quantity } : {}),
        ...(link!.collect.includes("email") ? { email: fields.email.trim() } : {}),
        ...(link!.collect.includes("name") ? { name: fields.name.trim() } : {}),
        ...(link!.collect.includes("phone") ? { phone: fields.phone.trim() } : {}),
        ...(link!.collect.includes("address") ? { address: fields.address.trim() } : {}),
      });
      navigate(`/c/${encodeURIComponent(session.client_secret)}`);
    } catch (e) {
      // Somebody else may have taken the last use while this page was open.
      setFormError(
        e instanceof CheckoutApiError && e.code === "link_unavailable"
          ? "This payment link is no longer available."
          : e instanceof CheckoutApiError && e.status < 500
            ? e.message
            : "We could not start your payment. Please try again.",
      );
      setSubmitting(false);
    }
  }

  const set = (key: keyof typeof fields) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setFields((current) => ({ ...current, [key]: event.target.value }));

  return (
    <Shell aside={<Summary link={link} />}>
      <form onSubmit={onSubmit} noValidate className="space-y-4" aria-label="Payment details">
        {link.type === "customer_chooses" && (
          <div>
            <Label htmlFor="amount">Amount ({link.currency})</Label>
            <Input
              id="amount"
              inputMode="decimal"
              autoComplete="off"
              placeholder={link.min_amount ? toMajorString(link.min_amount, link.currency) : "0.00"}
              value={chosen}
              onChange={(event) => setChosen(event.target.value)}
              aria-invalid={Boolean(problems.amount)}
            />
            <FieldError>{problems.amount}</FieldError>
          </div>
        )}
        {link.quantity_adjustable && (
          <div>
            <Label htmlFor="quantity">Quantity</Label>
            <div className="flex items-center gap-2">
              <Button type="button" variant="secondary" aria-label="Fewer" onClick={() => setQuantity((q) => Math.max(1, q - 1))}>
                −
              </Button>
              <Input
                id="quantity"
                type="number"
                min={1}
                max={99}
                className="w-20 text-center"
                value={quantity}
                onChange={(event) => setQuantity(Math.min(99, Math.max(1, Number(event.target.value) || 1)))}
              />
              <Button type="button" variant="secondary" aria-label="More" onClick={() => setQuantity((q) => Math.min(99, q + 1))}>
                +
              </Button>
            </div>
          </div>
        )}
        {link.collect.includes("name") && (
          <div>
            <Label htmlFor="name">Full name</Label>
            <Input id="name" autoComplete="name" value={fields.name} onChange={set("name")} />
            <FieldError>{problems.name}</FieldError>
          </div>
        )}
        {link.collect.includes("email") && (
          <div>
            <Label htmlFor="email">Email</Label>
            <Input id="email" type="email" autoComplete="email" value={fields.email} onChange={set("email")} />
            <FieldError>{problems.email}</FieldError>
          </div>
        )}
        {link.collect.includes("phone") && (
          <div>
            <Label htmlFor="phone">Phone</Label>
            <Input id="phone" type="tel" autoComplete="tel" value={fields.phone} onChange={set("phone")} />
            <FieldError>{problems.phone}</FieldError>
          </div>
        )}
        {link.collect.includes("address") && (
          <div>
            <Label htmlFor="address">Address</Label>
            <Input id="address" autoComplete="street-address" value={fields.address} onChange={set("address")} />
            <FieldError>{problems.address}</FieldError>
          </div>
        )}

        <div className="flex items-baseline justify-between border-t border-border pt-4">
          <span className="text-sm text-muted">Total</span>
          <span className="text-lg font-semibold" data-testid="total">
            {total === null ? "—" : formatMoney(total, link.currency)}
          </span>
        </div>
        {formError && <Alert role="alert">{formError}</Alert>}
        <Button type="submit" className="w-full" loading={submitting}>
          Continue to payment
        </Button>
      </form>
    </Shell>
  );
}

/** Where a customer lands after paying a link that shows a message instead of redirecting. */
export function LinkThanksPage() {
  const { slug = "" } = useParams();
  const { data: link } = useQuery({ queryKey: ["link", slug], queryFn: () => getLink(slug) });
  return (
    <Shell>
      <div className="text-center" role="status">
        <h1 className="text-xl font-semibold">Thank you!</h1>
        <p className="mt-2 text-sm text-muted">
          {link?.success_message || `Your payment to ${link?.merchant_name ?? "the merchant"} was received.`}
        </p>
      </div>
    </Shell>
  );
}
