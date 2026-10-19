import { ApiError } from "@cutpay/api-client";
import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
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
  const { t } = useTranslation();
  const { number } = useLocaleFormat();
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
        <h2 className="font-semibold">{t("fx.settings.title")}</h2>
        <p className="mt-2 text-sm text-danger">{t("fx.settings.loadFailed")}</p>
      </Card>
    );
  }

  return (
    <Card>
      <h2 className="font-semibold">{t("fx.settings.title")}</h2>
      <p className="mt-1 text-sm text-muted">{t("fx.settings.intro")}</p>
      <form
        className="mt-4 space-y-4"
        aria-label={t("fx.settings.formLabel")}
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <fieldset>
          <legend className="text-sm font-medium">{t("fx.settings.keepIn")}</legend>
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
            {t("fx.settings.auto")}
          </label>
          {auto && held.length > 0 && (
            <label className="flex items-center gap-2 text-sm">
              {t("fx.settings.into")}
              <select
                value={target}
                onChange={(event) => (setSaved(false), setTarget(event.target.value))}
                aria-label={t("fx.settings.convertInto")}
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
          {t("fx.settings.markup", { percent: settings.data ? number(settings.data.fx_markup_bps / 100) : "…" })}
        </p>
        {save.isError && <Alert>{save.error instanceof ApiError ? save.error.message : t("fx.settings.saveFailed")}</Alert>}
        <div className="flex items-center gap-3">
          <Button type="submit" loading={save.isPending} disabled={held.length === 0}>
            {t("fx.settings.save")}
          </Button>
          {saved && (
            <span className="text-sm text-success" role="status">
              {t("fx.settings.saved")}
            </span>
          )}
        </div>
      </form>
    </Card>
  );
}
