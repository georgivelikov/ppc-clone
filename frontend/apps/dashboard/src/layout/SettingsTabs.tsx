import { cn } from "@cutpay/ui";
import { NavLink } from "react-router";

const TABS = [
  { label: "Business profile", to: "/settings", end: true },
  { label: "Payouts", to: "/settings/payouts", end: false },
];

/** Switches between the settings pages. */
export function SettingsTabs() {
  return (
    <nav aria-label="Settings" className="flex gap-1 border-b border-border">
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
          {tab.label}
        </NavLink>
      ))}
    </nav>
  );
}
