import { ApiError, type CreatePriceInput, type Price, type PriceInterval, type Product } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, FieldError, Input, Label, toMinorUnits } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { api } from "../../api";
import { useSession } from "../../auth/AuthProvider";
import { Dialog } from "../../components/Dialog";
import { useTestMode } from "../../layout/testMode";
import { priceLabel } from "../../lib/billing";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

interface PriceDraft {
  amount: string;
  currency: string;
  recurring: boolean;
  interval: PriceInterval;
  intervalCount: string;
  trialDays: string;
  nickname: string;
}

function emptyDraft(currency: string): PriceDraft {
  return { amount: "", currency, recurring: true, interval: "month", intervalCount: "1", trialDays: "0", nickname: "" };
}

/** Turns what was typed into a price, or the problems with it. */
function toPrice(productId: string, draft: PriceDraft): { price?: CreatePriceInput; problems: Record<string, string> } {
  const problems: Record<string, string> = {};
  const unit = toMinorUnits(draft.amount, draft.currency);
  if (unit === null || unit < 1) problems.amount = "Enter a price, like 15.00";
  const count = Number(draft.intervalCount);
  if (draft.recurring && (!Number.isInteger(count) || count < 1 || count > 365)) problems.intervalCount = "Enter 1 to 365";
  const trial = Number(draft.trialDays || "0");
  if (draft.recurring && (!Number.isInteger(trial) || trial < 0 || trial > 730)) problems.trialDays = "Enter 0 to 730 days";
  if (Object.keys(problems).length > 0 || unit === null) return { problems };
  return {
    problems,
    price: {
      product: productId,
      unit_amount: unit,
      currency: draft.currency,
      ...(draft.recurring
        ? { interval: draft.interval, interval_count: count, trial_days: trial }
        : {}),
      nickname: draft.nickname.trim() || undefined,
    },
  };
}

function PriceFields({ draft, onChange, problems }: { draft: PriceDraft; onChange: (draft: PriceDraft) => void; problems: Record<string, string> }) {
  const set = (patch: Partial<PriceDraft>) => onChange({ ...draft, ...patch });
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-[1fr_7rem]">
        <div>
          <Label htmlFor="price-amount">Price</Label>
          <Input id="price-amount" inputMode="decimal" value={draft.amount} onChange={(e) => set({ amount: e.target.value })} placeholder="15.00" aria-invalid={Boolean(problems.amount)} />
          <FieldError>{problems.amount}</FieldError>
        </div>
        <div>
          <Label htmlFor="price-currency">Currency</Label>
          <select id="price-currency" value={draft.currency} onChange={(e) => set({ currency: e.target.value })} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
            {CURRENCIES.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </div>
      </div>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Billing</legend>
        <label className="flex items-center gap-2 text-sm">
          <input type="radio" name="billing" checked={draft.recurring} onChange={() => set({ recurring: true })} />
          Recurring
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="radio" name="billing" checked={!draft.recurring} onChange={() => set({ recurring: false })} />
          One time
        </label>
      </fieldset>
      {draft.recurring && (
        <div className="grid gap-4 sm:grid-cols-3">
          <div>
            <Label htmlFor="price-count">Every</Label>
            <Input id="price-count" inputMode="numeric" value={draft.intervalCount} onChange={(e) => set({ intervalCount: e.target.value })} aria-invalid={Boolean(problems.intervalCount)} />
            <FieldError>{problems.intervalCount}</FieldError>
          </div>
          <div>
            <Label htmlFor="price-interval">Period</Label>
            <select id="price-interval" value={draft.interval} onChange={(e) => set({ interval: e.target.value as PriceInterval })} className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm">
              <option value="day">days</option>
              <option value="week">weeks</option>
              <option value="month">months</option>
              <option value="year">years</option>
            </select>
          </div>
          <div>
            <Label htmlFor="price-trial">Free trial (days)</Label>
            <Input id="price-trial" inputMode="numeric" value={draft.trialDays} onChange={(e) => set({ trialDays: e.target.value })} aria-invalid={Boolean(problems.trialDays)} />
            <FieldError>{problems.trialDays}</FieldError>
          </div>
        </div>
      )}
      <div>
        <Label htmlFor="price-nickname">Label (optional)</Label>
        <Input id="price-nickname" value={draft.nickname} onChange={(e) => set({ nickname: e.target.value })} placeholder="Monthly" />
      </div>
    </div>
  );
}

function NewProductDialog({ livemode, onClose }: { livemode: boolean; onClose: () => void }) {
  const { merchant } = useSession();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [draft, setDraft] = useState(emptyDraft(merchant.default_currency));
  const [problems, setProblems] = useState<Record<string, string>>({});

  const create = useMutation({
    mutationFn: async () => {
      const product = await api.createProduct({ name: name.trim(), description: description.trim() || undefined }, livemode);
      const { price } = toPrice(product.id, draft);
      if (price) await api.createPrice(price, livemode);
      return product;
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["products"] });
      await queryClient.invalidateQueries({ queryKey: ["prices"] });
      onClose();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!name.trim()) found.name = "Give the product a name";
    Object.assign(found, toPrice("x", draft).problems);
    setProblems(found);
    if (Object.keys(found).length === 0) create.mutate();
  }

  return (
    <Dialog title="New product" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not create the product."}</Alert>}
        <div>
          <Label htmlFor="product-name">Name</Label>
          <Input id="product-name" autoFocus value={name} onChange={(e) => setName(e.target.value)} aria-invalid={Boolean(problems.name)} />
          <FieldError>{problems.name}</FieldError>
        </div>
        <div>
          <Label htmlFor="product-description">Description (optional)</Label>
          <Input id="product-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <PriceFields draft={draft} onChange={setDraft} problems={problems} />
        <Button type="submit" className="w-full" loading={create.isPending}>
          Create product
        </Button>
      </form>
    </Dialog>
  );
}

function NewPriceDialog({ product, livemode, onClose }: { product: Product; livemode: boolean; onClose: () => void }) {
  const { merchant } = useSession();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(emptyDraft(merchant.default_currency));
  const [problems, setProblems] = useState<Record<string, string>>({});
  const create = useMutation({
    mutationFn: (price: CreatePriceInput) => api.createPrice(price, livemode),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["prices"] });
      onClose();
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const { price, problems: found } = toPrice(product.id, draft);
    setProblems(found);
    if (price) create.mutate(price);
  }

  return (
    <Dialog title={`Add a price to ${product.name}`} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {create.isError && <Alert>{create.error instanceof ApiError ? create.error.message : "Could not add the price."}</Alert>}
        <PriceFields draft={draft} onChange={setDraft} problems={problems} />
        <Button type="submit" className="w-full" loading={create.isPending}>
          Add price
        </Button>
      </form>
    </Dialog>
  );
}

function PriceRow({ price }: { price: Price }) {
  const queryClient = useQueryClient();
  const toggle = useMutation({
    mutationFn: () => api.updatePrice(price.id, { active: !price.active }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["prices"] }),
  });
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-2 text-sm">
      <span>
        <span className="font-medium" data-testid="price-label">
          {priceLabel(price)}
        </span>
        {price.nickname && <span className="ml-2 text-muted">{price.nickname}</span>}
        {price.trial_days > 0 && <Badge className="ml-2">{price.trial_days}-day trial</Badge>}
        {!price.active && <Badge className="ml-2">Archived</Badge>}
      </span>
      <Button size="sm" variant="secondary" onClick={() => toggle.mutate()} loading={toggle.isPending}>
        {price.active ? "Archive" : "Restore"}
      </Button>
    </li>
  );
}

export function ProductsPage() {
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [pricing, setPricing] = useState<Product | null>(null);
  const products = useQuery({ queryKey: ["products", { livemode }], queryFn: () => api.listProducts({ limit: 100, livemode }) });
  const prices = useQuery({ queryKey: ["prices", { livemode }], queryFn: () => api.listPrices({ limit: 100, livemode }) });
  const archive = useMutation({
    mutationFn: (product: Product) => api.updateProduct(product.id, { active: !product.active }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["products"] }),
  });

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Products</h1>
          <p className="mt-1 text-sm text-muted">What you sell, and the prices it can be bought at.</p>
        </div>
        <Button onClick={() => setCreating(true)}>
          <Plus className="size-4" aria-hidden />
          New product
        </Button>
      </div>

      {products.isPending && <p className="text-sm text-muted">Loading…</p>}
      {products.isError && <Alert>Could not load your products.</Alert>}
      {products.isSuccess && products.data.data.length === 0 && (
        <Card className="p-10 text-center">
          <p className="font-medium">No products yet</p>
          <p className="mt-1 text-sm text-muted">Create a product with a monthly price to start your first subscription.</p>
        </Card>
      )}
      {(products.data?.data ?? []).map((product) => {
        const own = (prices.data?.data ?? []).filter((price) => price.product === product.id);
        return (
          <Card key={product.id} aria-label={product.name}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="font-semibold">
                  {product.name}
                  {!product.active && <Badge className="ml-2">Archived</Badge>}
                </h2>
                {product.description && <p className="text-sm text-muted">{product.description}</p>}
                <p className="mt-1 text-xs text-muted">{product.id}</p>
              </div>
              <div className="flex gap-2">
                {product.active && (
                  <Button size="sm" variant="secondary" onClick={() => setPricing(product)}>
                    Add price
                  </Button>
                )}
                <Button size="sm" variant="secondary" onClick={() => archive.mutate(product)}>
                  {product.active ? "Archive" : "Restore"}
                </Button>
              </div>
            </div>
            {own.length === 0 ? (
              <p className="mt-3 text-sm text-muted">No prices yet.</p>
            ) : (
              <ul className="mt-3 divide-y divide-border" aria-label={`Prices of ${product.name}`}>
                {own.map((price) => (
                  <PriceRow key={price.id} price={price} />
                ))}
              </ul>
            )}
          </Card>
        );
      })}
      {creating && <NewProductDialog livemode={livemode} onClose={() => setCreating(false)} />}
      {pricing && <NewPriceDialog product={pricing} livemode={livemode} onClose={() => setPricing(null)} />}
    </div>
  );
}
