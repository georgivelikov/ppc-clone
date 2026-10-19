import { ApiError, type NotificationEntry } from "@cutpay/api-client";
import { Alert, Badge, Card } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";

const NAMES: Record<string, { name: string; about: string }> = {
  payment_new: { name: "New payment", about: "An email for every payment you receive." },
  payout_paid: { name: "Payout sent", about: "When a payout leaves for your bank account." },
  dispute_opened: { name: "Dispute opened", about: "When a customer disputes a payment, with the deadline to answer." },
  dispute_closed: { name: "Dispute decided", about: "When a dispute is won or lost." },
  kyc_approved: { name: "Application approved", about: "When your business is approved." },
  kyc_rejected: { name: "Application declined", about: "When your business could not be approved." },
  kyc_needs_info: { name: "More information needed", about: "When we need something else to finish checking your business." },
  report_ready: { name: "Report ready", about: "When a report you asked for can be downloaded." },
};

/** Which emails you get, and (for owners and admins) what was sent. Password and invitation emails always go. */
export function NotificationsPage() {
  const { role } = useSession();
  const queryClient = useQueryClient();
  const preferences = useQuery({ queryKey: ["notification-preferences"], queryFn: () => api.getNotificationPreferences() });
  const toggle = useMutation({
    mutationFn: (v: { template: string; enabled: boolean }) => api.setNotificationPreference(v.template, v.enabled),
    onSuccess: (updated) => queryClient.setQueryData(["notification-preferences"], updated),
  });
  const canSeeLog = role === "owner" || role === "admin";
  const log = useQuery({ queryKey: ["notification-log"], queryFn: () => api.listNotifications(20), enabled: canSeeLog });

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <Card>
        <h2 className="font-semibold">Emails to {preferences.data?.email ?? "you"}</h2>
        <p className="mt-1 text-sm text-muted">These choices are yours alone. Emails about your password or an invitation always go.</p>
        {toggle.isError && <Alert className="mt-3">{toggle.error instanceof ApiError ? toggle.error.message : "Could not save."}</Alert>}
        <ul className="mt-3 divide-y divide-border" aria-label="Emails">
          {preferences.data?.data.map((p) => {
            const info = NAMES[p.template] ?? { name: p.template, about: "" };
            return (
              <li key={p.template} className="flex items-start justify-between gap-4 py-3" data-testid={`pref-${p.template}`}>
                <div>
                  <p className="text-sm font-medium">{info.name}</p>
                  <p className="text-xs text-muted">{info.about}</p>
                </div>
                <label className="flex shrink-0 items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={p.enabled}
                    disabled={toggle.isPending}
                    aria-label={info.name}
                    onChange={(e) => toggle.mutate({ template: p.template, enabled: e.target.checked })}
                  />
                  {p.enabled ? "On" : "Off"}
                </label>
              </li>
            );
          })}
        </ul>
      </Card>

      {canSeeLog && (
        <Card>
          <h2 className="font-semibold">Recently sent</h2>
          {log.data?.length === 0 && <p className="mt-3 text-sm text-muted">Nothing yet.</p>}
          <ul className="mt-3 divide-y divide-border" aria-label="Sent emails">
            {log.data?.map((n: NotificationEntry) => (
              <li key={n.id} className="flex items-center justify-between gap-3 py-2" data-testid="sent-email">
                <div className="min-w-0">
                  <p className="truncate text-sm">{n.subject}</p>
                  <p className="text-xs text-muted">
                    {n.recipient} · {new Date(n.created_at).toLocaleString()}
                  </p>
                </div>
                <Badge>{n.status}</Badge>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
