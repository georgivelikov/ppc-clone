import { ApiError, type PaymentMethodId } from "@cutpay/api-client";
import { Alert, Card } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";

const METHODS: Record<PaymentMethodId, { name: string; about: string }> = {
  card: { name: "Cards", about: "Visa, Mastercard and Amex. Always on, so a checkout always has a way to pay." },
  apple_pay: { name: "Apple Pay", about: "A pretend wallet. The customer confirms on their device, so there is no 3-D Secure step. Priced like the card behind it." },
  google_pay: { name: "Google Pay", about: "A pretend wallet. The customer confirms on their device, so there is no 3-D Secure step. Priced like the card behind it." },
  blik: { name: "BLIK", about: "Polish bank-app payments, in złoty only. The customer confirms in their banking app. 1.29% + PLN 0.25." },
};

/** Which ways of paying the checkout offers. A method that does not fit a payment (BLIK for euros) is hidden anyway. */
export function PaymentMethodsPage() {
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
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <Card>
        <h2 className="font-semibold">Payment methods</h2>
        <p className="mt-1 text-sm text-muted">Choose what your customers can pay with at the checkout.</p>
        {settings.isError && <Alert className="mt-3">Could not load your payment methods.</Alert>}
        {toggle.isError && (
          <Alert className="mt-3">{toggle.error instanceof ApiError ? toggle.error.message : "Could not save that change."}</Alert>
        )}
        <ul className="mt-4 divide-y divide-border" aria-label="Payment methods">
          {settings.data?.data.map((setting) => {
            const info = METHODS[setting.method];
            return (
              <li key={setting.method} className="flex items-start justify-between gap-4 py-3" data-testid={`method-${setting.method}`}>
                <div>
                  <p className="text-sm font-medium">{info.name}</p>
                  <p className="text-xs text-muted">{info.about}</p>
                </div>
                <label className="flex shrink-0 items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={setting.enabled}
                    disabled={setting.locked || !canEdit || toggle.isPending}
                    aria-label={`Accept ${info.name}`}
                    onChange={(event) => toggle.mutate({ method: setting.method, enabled: event.target.checked })}
                  />
                  {setting.enabled ? "On" : "Off"}
                </label>
              </li>
            );
          })}
        </ul>
        {!canEdit && <p className="mt-3 text-sm text-muted">Only owners and admins can change these.</p>}
        <p className="mt-4 text-sm text-muted">
          Pay in instalments is set up on the <Link to="/instalments" className="text-brand-700 hover:underline">Instalments</Link> page.
        </p>
      </Card>
    </div>
  );
}
