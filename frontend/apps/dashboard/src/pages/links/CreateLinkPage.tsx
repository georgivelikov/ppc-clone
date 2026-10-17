import { ApiError, type CollectField, type CreatePaymentLinkInput, type PaymentLinkType } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, formatMoney, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { useTestMode } from "../../layout/testMode";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];
const COLLECT: [CollectField, string][] = [
  ["email", "Email"],
  ["name", "Full name"],
  ["phone", "Phone number"],
  ["address", "Address"],
];

export interface LinkForm {
  type: PaymentLinkType;
  title: string;
  description: string;
  currency: string;
  amount: string;
  minAmount: string;
  maxAmount: string;
  items: { name: string; price: string; quantity: string }[];
  quantityAdjustable: boolean;
  collect: CollectField[];
  maxUses: string;
  expires: string;
  after: "message" | "redirect";
  redirectUrl: string;
  successMessage: string;
}

export const emptyForm = (currency: string): LinkForm => ({
  type: "fixed",
  title: "",
  description: "",
  currency,
  amount: "",
  minAmount: "",
  maxAmount: "",
  items: [{ name: "", price: "", quantity: "1" }],
  quantityAdjustable: false,
  collect: [],
  maxUses: "",
  expires: "",
  after: "message",
  redirectUrl: "",
  successMessage: "",
});

/** Turns the form into an API request, or says what is wrong with it field by field. */
export function buildLinkInput(
  form: LinkForm,
  merchantName: string,
): { input: CreatePaymentLinkInput } | { errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  if (!form.title.trim()) errors.title = "Give the link a name";
  const input: CreatePaymentLinkInput = {
    type: form.type,
    currency: form.currency,
    title: form.title.trim(),
    merchant_name: merchantName,
  };
  if (form.description.trim()) input.description = form.description.trim();

  if (form.type === "fixed") {
    const amount = toMinorUnits(form.amount, form.currency);
    if (amount === null) errors.amount = "Enter an amount, like 12.50";
    else input.amount = amount;
  } else if (form.type === "customer_chooses") {
    const min = form.minAmount.trim() ? toMinorUnits(form.minAmount, form.currency) : undefined;
    const max = form.maxAmount.trim() ? toMinorUnits(form.maxAmount, form.currency) : undefined;
    if (min === null) errors.minAmount = "Enter a valid minimum";
    if (max === null) errors.maxAmount = "Enter a valid maximum";
    if (typeof min === "number" && typeof max === "number" && min > max) errors.minAmount = "The minimum is above the maximum";
    if (typeof min === "number") input.min_amount = min;
    if (typeof max === "number") input.max_amount = max;
  } else {
    const items = form.items.filter((item) => item.name.trim() || item.price.trim());
    const parsed = items.map((item) => ({
      product_name: item.name.trim(),
      unit_amount: toMinorUnits(item.price, form.currency),
      quantity: Number(item.quantity),
    }));
    if (parsed.length === 0 || parsed.some((p) => !p.product_name || p.unit_amount === null || !Number.isInteger(p.quantity) || p.quantity < 1)) {
      errors.items = "Each item needs a name, a price and a quantity of at least 1";
    } else {
      input.line_items = parsed.map((p) => ({ ...p, unit_amount: p.unit_amount as number }));
    }
  }
  if (form.type !== "customer_chooses" && form.quantityAdjustable) input.quantity_adjustable = true;
  if (form.collect.length > 0) input.collect = form.collect;

  if (form.maxUses.trim()) {
    const uses = Number(form.maxUses);
    if (!Number.isInteger(uses) || uses < 1) errors.maxUses = "Enter a whole number, 1 or more";
    else input.max_uses = uses;
  }
  if (form.expires) {
    const when = new Date(form.expires);
    if (Number.isNaN(when.getTime()) || when.getTime() <= Date.now()) errors.expires = "Pick a time in the future";
    else input.expires_at = when.toISOString();
  }

  input.after_completion = form.after;
  if (form.after === "redirect") {
    if (!/^https?:\/\/\S+$/.test(form.redirectUrl.trim())) errors.redirectUrl = "Enter a full address starting with http:// or https://";
    else input.redirect_url = form.redirectUrl.trim();
  } else if (form.successMessage.trim()) {
    input.success_message = form.successMessage.trim();
  }
  return Object.keys(errors).length > 0 ? { errors } : { input };
}

/** What the customer will see, updated as the merchant types. */
function Preview({ form }: { form: LinkForm }) {
  const total =
    form.type === "fixed"
      ? toMinorUnits(form.amount, form.currency)
      : form.type === "products"
        ? form.items.reduce((sum, item) => sum + (toMinorUnits(item.price, form.currency) ?? 0) * (Number(item.quantity) || 0), 0)
        : null;
  return (
    <Card aria-label="Preview" className="sticky top-6">
      <p className="text-xs font-medium uppercase text-muted">What your customer sees</p>
      <p className="mt-4 text-sm text-muted">Your business</p>
      <h2 className="mt-1 text-xl font-semibold">{form.title || "Link name"}</h2>
      {form.description && <p className="mt-2 text-sm text-muted">{form.description}</p>}
      {form.type === "products" && (
        <ul className="mt-3 divide-y divide-border text-sm">
          {form.items
            .filter((item) => item.name.trim())
            .map((item, index) => (
              <li key={index} className="flex justify-between py-1.5">
                <span>
                  {item.name}
                  {Number(item.quantity) > 1 && <span className="text-muted"> × {item.quantity}</span>}
                </span>
                <span>{formatMoney((toMinorUnits(item.price, form.currency) ?? 0) * (Number(item.quantity) || 1), form.currency)}</span>
              </li>
            ))}
        </ul>
      )}
      <p className="mt-3 text-3xl font-semibold" data-testid="preview-amount">
        {form.type === "customer_chooses" ? "Customer enters the amount" : total === null ? "—" : formatMoney(total, form.currency)}
      </p>
      {form.collect.length > 0 && <p className="mt-3 text-xs text-muted">Asks for: {form.collect.join(", ")}</p>}
      {form.maxUses.trim() && <p className="mt-1 text-xs text-muted">Closes after {form.maxUses} payment(s)</p>}
    </Card>
  );
}

export function CreateLinkPage() {
  const { merchant } = useSession();
  const { testMode } = useTestMode();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<LinkForm>(() => emptyForm(merchant.default_currency));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const patch = (change: Partial<LinkForm>) => setForm((current) => ({ ...current, ...change }));
  const text = (key: keyof LinkForm) => (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    patch({ [key]: event.target.value } as Partial<LinkForm>);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const built = buildLinkInput(form, merchant.display_name);
    if ("errors" in built) {
      setErrors(built.errors);
      return;
    }
    setErrors({});
    setSaving(true);
    try {
      const link = await api.createPaymentLink(built.input, !testMode);
      await queryClient.invalidateQueries({ queryKey: ["links"] });
      navigate(`/links/${link.id}`);
    } catch (error) {
      setFormError(error instanceof ApiError && error.status < 500 ? error.message : "Could not create the link. Please try again.");
      setSaving(false);
    }
  }

  function toggleCollect(field: CollectField) {
    patch({ collect: form.collect.includes(field) ? form.collect.filter((f) => f !== field) : [...form.collect, field] });
  }

  const updateItem = (index: number, change: Partial<LinkForm["items"][number]>) =>
    patch({ items: form.items.map((item, i) => (i === index ? { ...item, ...change } : item)) });

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <Link to="/links" className="text-sm text-brand-700 hover:underline">
          ← Payment links
        </Link>
        <h1 className="mt-1 text-2xl font-semibold">Create a payment link</h1>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
        <form onSubmit={submit} noValidate className="space-y-6" aria-label="New payment link">
          <Card className="space-y-4">
            <div>
              <Label htmlFor="title">Name</Label>
              <Input id="title" value={form.title} onChange={text("title")} placeholder="Table 4, Haircut, Donation…" aria-invalid={Boolean(errors.title)} />
              <FieldError>{errors.title}</FieldError>
            </div>
            <div>
              <Label htmlFor="description">Description (optional)</Label>
              <Input id="description" value={form.description} onChange={text("description")} />
            </div>

            <fieldset>
              <legend className="mb-1.5 text-sm font-medium">Price</legend>
              <div className="flex flex-wrap gap-4 text-sm">
                {(
                  [
                    ["fixed", "Fixed price"],
                    ["customer_chooses", "Customer chooses"],
                    ["products", "Products"],
                  ] as const
                ).map(([value, label]) => (
                  <label key={value} className="flex items-center gap-2">
                    <input type="radio" name="type" checked={form.type === value} onChange={() => patch({ type: value })} className="accent-brand-600" />
                    {label}
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="grid gap-4 sm:grid-cols-[8rem_1fr]">
              <div>
                <Label htmlFor="currency">Currency</Label>
                <select
                  id="currency"
                  value={form.currency}
                  onChange={text("currency")}
                  className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm"
                >
                  {CURRENCIES.map((c) => (
                    <option key={c}>{c}</option>
                  ))}
                </select>
              </div>
              {form.type === "fixed" && (
                <div>
                  <Label htmlFor="amount">Amount</Label>
                  <Input id="amount" inputMode="decimal" value={form.amount} onChange={text("amount")} placeholder="25.00" aria-invalid={Boolean(errors.amount)} />
                  <FieldError>{errors.amount}</FieldError>
                </div>
              )}
              {form.type === "customer_chooses" && (
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label htmlFor="min">Minimum (optional)</Label>
                    <Input id="min" inputMode="decimal" value={form.minAmount} onChange={text("minAmount")} placeholder="1.00" />
                    <FieldError>{errors.minAmount}</FieldError>
                  </div>
                  <div>
                    <Label htmlFor="max">Maximum (optional)</Label>
                    <Input id="max" inputMode="decimal" value={form.maxAmount} onChange={text("maxAmount")} />
                    <FieldError>{errors.maxAmount}</FieldError>
                  </div>
                </div>
              )}
            </div>

            {form.type === "products" && (
              <div className="space-y-2">
                <p className="text-sm font-medium">Items</p>
                {form.items.map((item, index) => (
                  <div key={index} className="grid grid-cols-[1fr_6rem_4.5rem_2rem] items-center gap-2">
                    <Input aria-label={`Item ${index + 1} name`} placeholder="Name" value={item.name} onChange={(e) => updateItem(index, { name: e.target.value })} />
                    <Input aria-label={`Item ${index + 1} price`} placeholder="Price" inputMode="decimal" value={item.price} onChange={(e) => updateItem(index, { price: e.target.value })} />
                    <Input aria-label={`Item ${index + 1} quantity`} inputMode="numeric" value={item.quantity} onChange={(e) => updateItem(index, { quantity: e.target.value })} />
                    <button
                      type="button"
                      aria-label={`Remove item ${index + 1}`}
                      className="grid size-8 place-items-center rounded-lg text-muted hover:bg-canvas hover:text-danger disabled:opacity-40"
                      disabled={form.items.length === 1}
                      onClick={() => patch({ items: form.items.filter((_, i) => i !== index) })}
                    >
                      <Trash2 className="size-4" aria-hidden />
                    </button>
                  </div>
                ))}
                <FieldError>{errors.items}</FieldError>
                <Button type="button" variant="ghost" size="sm" onClick={() => patch({ items: [...form.items, { name: "", price: "", quantity: "1" }] })}>
                  <Plus className="size-4" aria-hidden />
                  Add item
                </Button>
              </div>
            )}

            {form.type !== "customer_chooses" && (
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={form.quantityAdjustable} onChange={(e) => patch({ quantityAdjustable: e.target.checked })} className="accent-brand-600" />
                Let the customer change the quantity
              </label>
            )}
          </Card>

          <Card className="space-y-4">
            <fieldset>
              <legend className="mb-1.5 text-sm font-medium">Ask the customer for</legend>
              <div className="flex flex-wrap gap-4 text-sm">
                {COLLECT.map(([field, label]) => (
                  <label key={field} className="flex items-center gap-2">
                    <input type="checkbox" checked={form.collect.includes(field)} onChange={() => toggleCollect(field)} className="accent-brand-600" />
                    {label}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label htmlFor="maxUses">Limit number of payments (optional)</Label>
                <Input id="maxUses" inputMode="numeric" value={form.maxUses} onChange={text("maxUses")} placeholder="Unlimited" aria-invalid={Boolean(errors.maxUses)} />
                <FieldError>{errors.maxUses}</FieldError>
              </div>
              <div>
                <Label htmlFor="expires">Expires (optional)</Label>
                <Input id="expires" type="datetime-local" value={form.expires} onChange={text("expires")} />
                <FieldError>{errors.expires}</FieldError>
              </div>
            </div>
          </Card>

          <Card className="space-y-4">
            <fieldset>
              <legend className="mb-1.5 text-sm font-medium">After the customer pays</legend>
              <div className="flex gap-4 text-sm">
                <label className="flex items-center gap-2">
                  <input type="radio" name="after" checked={form.after === "message"} onChange={() => patch({ after: "message" })} className="accent-brand-600" />
                  Show a message
                </label>
                <label className="flex items-center gap-2">
                  <input type="radio" name="after" checked={form.after === "redirect"} onChange={() => patch({ after: "redirect" })} className="accent-brand-600" />
                  Send them to my website
                </label>
              </div>
            </fieldset>
            {form.after === "message" ? (
              <div>
                <Label htmlFor="message">Message (optional)</Label>
                <Input id="message" value={form.successMessage} onChange={text("successMessage")} placeholder="Thank you! See you soon." />
              </div>
            ) : (
              <div>
                <Label htmlFor="redirect">Website address</Label>
                <Input id="redirect" value={form.redirectUrl} onChange={text("redirectUrl")} placeholder="https://example.com/thanks" aria-invalid={Boolean(errors.redirectUrl)} />
                <FieldError>{errors.redirectUrl}</FieldError>
              </div>
            )}
          </Card>

          {formError && <Alert>{formError}</Alert>}
          <div className="flex justify-end gap-2">
            <Link to="/links">
              <Button type="button" variant="secondary">
                Cancel
              </Button>
            </Link>
            <Button type="submit" loading={saving}>
              Create link
            </Button>
          </div>
        </form>

        <Preview form={form} />
      </div>
    </div>
  );
}
