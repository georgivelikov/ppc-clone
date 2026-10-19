import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Card } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "../../api";
import { CURRENCIES } from "./fx";

/**
 * Which currencies the merchant holds money in, and whether money in any other currency is converted by itself when it
 * becomes available. Converting costs a markup over the market rate, which the platform sets.
 */
export function CurrencySettingsCard() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ["currency-settings"], queryFn: () => api.getCurrencySettings() });
  const [held, setHeld] = useState<string[]>([]);
  const [target, setTarget] = useState<string>("");
  const [auto, setAuto] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (settings.data) {
      setHeld(settings.data.settlement_currencies);
      setTarget(settings.data.settlement_currencies[0] ?? "");
      setAuto(settings.data.auto_convert);
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: () => {
      // The first currency is where converted money goes, so the chosen target goes first.
      const ordered = [target, ...held.filter((code) => code !== target)];
      return api.updateCurrencySettings({ settlement_currencies: ordered, auto_convert: auto });
    },
    onSuccess: () => {
      setSaved(true);
      void queryClient.invalidateQueries({ queryKey: ["currency-settings"] });
    },
  });

  function toggle(code: string, on: boolean) {
    setSaved(false);
    const next = on ? [...held, code] : held.filter((c) => c !== code);
    setHeld(next);
    if (!next.includes(target)) setTarget(next[0] ?? "");
  }

  if (settings.isError) {
    return (
      <Card>
        <h2 className="font-semibold">Currencies</h2>
        <p className="mt-2 text-sm text-danger">Could not load your currency settings.</p>
      </Card>
    );
  }

  return (
    <Card>
      <h2 className="font-semibold">Currencies</h2>
      <p className="mt-1 text-sm text-muted">
        Money arrives in the currency the customer paid in. Choose the currencies you want to keep, and what happens to the rest.
      </p>
      <form
        className="mt-4 space-y-4"
        aria-label="Currency settings"
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <fieldset>
          <legend className="text-sm font-medium">Keep money in</legend>
          <div className="mt-2 flex flex-wrap gap-2">
            {CURRENCIES.map((code) => (
              <label key={code} className="flex cursor-pointer items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-sm">
                <input type="checkbox" checked={held.includes(code)} onChange={(event) => toggle(code, event.target.checked)} />
                {code}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="flex flex-wrap items-center gap-4">
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={auto} onChange={(event) => (setSaved(false), setAuto(event.target.checked))} />
            Convert other currencies automatically
          </label>
          {auto && held.length > 0 && (
            <label className="flex items-center gap-2 text-sm">
              into
              <select
                value={target}
                onChange={(event) => (setSaved(false), setTarget(event.target.value))}
                aria-label="Convert into"
                className="h-9 rounded-lg border border-border bg-surface px-2 text-sm"
              >
                {held.map((code) => (
                  <option key={code}>{code}</option>
                ))}
              </select>
            </label>
          )}
        </div>
        <p className="text-xs text-muted" data-testid="fx-markup">
          A conversion costs {settings.data ? settings.data.fx_markup_bps / 100 : "…"}% over the market rate. Money is converted when it
          becomes available, not while it is pending.
        </p>
        {save.isError && <Alert>{save.error instanceof ApiError ? save.error.message : "Could not save."}</Alert>}
        <div className="flex items-center gap-3">
          <Button type="submit" loading={save.isPending} disabled={held.length === 0}>
            Save currencies
          </Button>
          {saved && (
            <span className="text-sm text-success" role="status">
              Saved
            </span>
          )}
        </div>
      </form>
    </Card>
  );
}
