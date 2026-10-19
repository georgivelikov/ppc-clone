import { useTranslation } from "@cutpay/i18n";
import { cn } from "@cutpay/ui";
import { NavLink } from "react-router";

const TABS = [
  { key: "profile", to: "/settings", end: true },
  { key: "payouts", to: "/settings/payouts", end: false },
  { key: "methods", to: "/settings/methods", end: false },
];

/** Switches between the settings pages. */
export function SettingsTabs() {
  const { t } = useTranslation();
  return (
    <nav aria-label={t("settingsTabs.label")} className="flex gap-1 border-b border-border">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) =>
            cn(
              "-mb-px border-b-2 px-3 py-2 text-sm font-medium",
              isActive ? "border-brand-600 text-brand-700" : "border-transparent text-muted hover:text-ink",
            )
          }
        >
          {t(`settingsTabs.${tab.key}`)}
        </NavLink>
      ))}
    </nav>
  );
}
