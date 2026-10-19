import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Badge, Card } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { useTestMode } from "../layout/testMode";
import { Insights } from "./reports/Insights";

const STEPS = ["first", "balance", "connect"] as const;

export function HomePage() {
  const { t } = useTranslation();
  const { money } = useLocaleFormat();
  const { merchant, user } = useSession();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const firstName = user.full_name.split(" ")[0];
  const summary = useQuery({ queryKey: ["payment-summary", livemode], queryFn: () => api.paymentSummary(livemode) });
  const today = summary.data?.today.find((row) => row.currency === merchant.default_currency);
  const subscriptions = useQuery({ queryKey: ["subscription-stats", livemode], queryFn: () => api.subscriptionStats(livemode) });
  const mrr = subscriptions.data?.mrr[merchant.default_currency] ?? 0;
  const subscribers = (subscriptions.data?.by_status.active ?? 0) + (subscriptions.data?.by_status.past_due ?? 0);
  const left = subscriptions.data?.ended_last_30_days ?? 0;
  const churn = subscribers + left > 0 ? (left / (subscribers + left)) * 100 : 0;

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{t("home.welcome", { name: firstName })}</h1>
        <p className="mt-1 text-sm text-muted">
          {t("home.intro", { business: merchant.display_name, country: merchant.country, currency: merchant.default_currency })}{" "}
          <Badge tone="warning">{merchant.status.replace("_", " ")}</Badge>
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <Card>
          <p className="text-sm text-muted">{t("home.todayVolume")}</p>
          <p className="mt-2 text-2xl font-semibold" data-testid="today-volume">
            {money(today?.volume ?? 0, merchant.default_currency)}
          </p>
        </Card>
        <Card>
          <p className="text-sm text-muted">{t("home.paymentsToday")}</p>
          <p className="mt-2 text-2xl font-semibold" data-testid="today-count">
            {today?.count ?? 0}
          </p>
        </Card>
        <Card>
          <p className="text-sm text-muted">{t("home.nextPayout")}</p>
          <p className="mt-2 text-2xl font-semibold">{t("home.none")}</p>
        </Card>
      </div>

      <Insights days={30} />

      {subscribers + left > 0 && (
        <div className="grid gap-4 sm:grid-cols-3" aria-label={t("home.recurring")}>
          <Card>
            <p className="text-sm text-muted">{t("home.mrr")}</p>
            <p className="mt-2 text-2xl font-semibold" data-testid="home-mrr">
              {money(mrr, merchant.default_currency)}
            </p>
          </Card>
          <Card>
            <p className="text-sm text-muted">{t("home.subscribers")}</p>
            <p className="mt-2 text-2xl font-semibold" data-testid="home-subscribers">
              {subscribers}
            </p>
          </Card>
          <Card>
            <p className="text-sm text-muted">{t("home.churn")}</p>
            <p className="mt-2 text-2xl font-semibold" data-testid="home-churn">
              {churn.toFixed(1)}%
            </p>
          </Card>
        </div>
      )}

      <Card>
        <h2 className="font-semibold">{t("home.gettingStarted")}</h2>
        <ol className="mt-4 space-y-4">
          {STEPS.map((step, index) => (
            <li key={step} className="flex gap-3">
              <span className="grid size-6 shrink-0 place-items-center rounded-full bg-canvas text-xs font-medium text-muted">
                {index + 1}
              </span>
              <div>
                <p className="text-sm font-medium">{t(`home.steps.${step}.title`)}</p>
                <p className="text-sm text-muted">{t(`home.steps.${step}.text`)}</p>
              </div>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}
