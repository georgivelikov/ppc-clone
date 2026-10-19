import { useLocaleFormat, useTranslation } from "@cutpay/i18n";
import { Card } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { api } from "../../api";
import { useTestMode } from "../../layout/testMode";
import { formatRate } from "./fx";

const DAYS = 30;

/** What conversions brought in over the last month, how much of it the markup took, and the latest conversions. */
export function FxReportCard() {
  const { t } = useTranslation();
  const { money: formatMoney, dateTime, locale } = useLocaleFormat();
  const { testMode } = useTestMode();
  const livemode = !testMode;
  const report = useQuery({ queryKey: ["fx-report", livemode], queryFn: () => api.getFxReport(DAYS, livemode) });
  const conversions = useQuery({
    queryKey: ["fx-conversions", livemode],
    queryFn: () => api.listFxConversions({ limit: 5, livemode }),
  });
  const rows = report.data?.data ?? [];
  const recent = conversions.data?.data ?? [];
  if (report.isSuccess && conversions.isSuccess && rows.length === 0 && recent.length === 0) {
    return null; // nothing was converted, so there is nothing to report
  }

  return (
    <Card>
      <h2 className="font-semibold">{t("fx.report.title", { days: DAYS })}</h2>
      {report.isError && <p className="mt-2 text-sm text-danger">{t("fx.report.loadFailed")}</p>}
      {rows.length > 0 && (
        <table className="mt-3 w-full text-left text-sm" aria-label={t("fx.report.totals")}>
          <thead className="border-b border-border text-xs text-muted uppercase">
            <tr>
              <th className="py-2 font-medium">{t("fx.report.receivedIn")}</th>
              <th className="py-2 text-right font-medium">{t("fx.report.conversions")}</th>
              <th className="py-2 text-right font-medium">{t("fx.report.received")}</th>
              <th className="py-2 text-right font-medium">{t("fx.report.markupPaid")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.currency} className="border-b border-border last:border-0" data-testid={`fx-report-${row.currency}`}>
                <td className="py-2">{row.currency}</td>
                <td className="py-2 text-right">{row.conversions}</td>
                <td className="py-2 text-right">{formatMoney(row.received, row.currency)}</td>
                <td className="py-2 text-right text-muted">{formatMoney(row.markup, row.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {recent.length > 0 && (
        <ul className="mt-4 divide-y divide-border text-sm" aria-label={t("fx.report.latest")}>
          {recent.map((c) => (
            <li key={c.id} className="flex flex-wrap items-baseline justify-between gap-2 py-2" data-testid="fx-conversion">
              <span>
                {formatMoney(c.from_amount, c.from_currency)} → {formatMoney(c.to_amount, c.to_currency)}
                <span className="ml-2 text-xs text-muted">
                  {t("fx.report.at", { rate: formatRate(c.rate, locale) })}
                  {c.kind === "auto" ? ` · ${t("fx.report.automatic")}` : ""}
                </span>
              </span>
              <span className="text-xs text-muted">
                {c.source_id && (
                  <Link to={`/payments/${c.source_id}`} className="mr-3 hover:underline">
                    {c.source_id}
                  </Link>
                )}
                {dateTime(c.created)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
