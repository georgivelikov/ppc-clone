import { cn } from "@cutpay/ui";
import { NavLink } from "react-router";

const TABS = [
  { label: "API keys", to: "/developers/api-keys" },
  { label: "Webhooks", to: "/developers/webhooks" },
  { label: "Events", to: "/developers/events" },
  { label: "Logs", to: "/developers/logs" },
];

/** Switches between the pages of the Developers section. */
export function DevelopersTabs() {
  return (
    <nav aria-label="Developers" className="flex gap-1 border-b border-border">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
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
