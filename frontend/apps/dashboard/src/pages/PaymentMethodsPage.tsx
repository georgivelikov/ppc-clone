import { ApiError, type PaymentMethodId } from "@cutpay/api-client";
import { Trans, useTranslation } from "@cutpay/i18n";
import { Alert, Card } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";

/** Which ways of paying the checkout offers. A method that does not fit a payment (BLIK for euros) is hidden anyway. */
export function PaymentMethodsPage() {
  const { t } = useTranslation();
  const { role } = useSession();
  const canEdit = role === "owner" || role === "admin";
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ["payment-method-settings"], queryFn: () => api.getPaymentMethodSettings() });
  const toggle = useMutation({
    mutationFn: (change: { method: PaymentMethodId; enabled: boolean }) => api.updatePaymentMethodSettings({ methods: [change] }),
    onSuccess: (updated) => queryClient.setQueryData(["payment-method-settings"], updated),
  });

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-2xl font-semibold">{t("settingsTabs.label")}</h1>
      <SettingsTabs />
      <Card>
        <h2 className="font-semibold">{t("methods.title")}</h2>
        <p className="mt-1 text-sm text-muted">{t("methods.intro")}</p>
        {settings.isError && <Alert className="mt-3">{t("methods.loadFailed")}</Alert>}
        {toggle.isError && (
          <Alert className="mt-3">{toggle.error instanceof ApiError ? toggle.error.message : t("methods.saveFailed")}</Alert>
        )}
        <ul className="mt-4 divide-y divide-border" aria-label={t("methods.title")}>
          {settings.data?.data.map((setting) => {
            const name = t(`methods.${setting.method}.name`);
            return (
              <li key={setting.method} className="flex items-start justify-between gap-4 py-3" data-testid={`method-${setting.method}`}>
                <div>
                  <p className="text-sm font-medium">{name}</p>
                  <p className="text-xs text-muted">{t(`methods.${setting.method}.about`)}</p>
                </div>
                <label className="flex shrink-0 items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={setting.enabled}
                    disabled={setting.locked || !canEdit || toggle.isPending}
                    aria-label={t("methods.accept", { method: name })}
                    onChange={(event) => toggle.mutate({ method: setting.method, enabled: event.target.checked })}
                  />
                  {setting.enabled ? t("methods.on") : t("methods.off")}
                </label>
              </li>
            );
          })}
        </ul>
        {!canEdit && <p className="mt-3 text-sm text-muted">{t("methods.ownersOnly")}</p>}
        <p className="mt-4 text-sm text-muted">
          <Trans
            i18nKey="methods.instalments"
            components={{ instalmentsLink: <Link to="/instalments" className="text-brand-700 hover:underline" /> }}
          />
        </p>
      </Card>
    </div>
  );
}
