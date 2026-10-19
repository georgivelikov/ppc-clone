import { DASHBOARD_LOCALES, LanguageSwitcher, useTranslation } from "@cutpay/i18n";
import { Badge, cn } from "@cutpay/ui";
import {
  Banknote,
  CalendarClock,
  Code2,
  CreditCard,
  Home,
  FileText,
  Keyboard,
  Link2,
  LogOut,
  Network,
  Package,
  Repeat,
  Settings,
  Users,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { NavLink, Outlet, useNavigate } from "react-router";
import { useAuth, useSession } from "../auth/AuthProvider";
import { TestModeProvider, useTestMode } from "./testMode";

interface NavItem {
  /** The key of the label in the dashboard messages: `nav.<key>`. */
  key: string;
  to: string;
  icon: LucideIcon;
  /** The phase in which the page gets built; until then the item is shown disabled. */
  phase?: number;
}

const NAV: NavItem[] = [
  { key: "home", to: "/", icon: Home },
  { key: "payments", to: "/payments", icon: CreditCard },
  { key: "balances", to: "/balances", icon: Wallet },
  { key: "payouts", to: "/payouts", icon: Banknote },
  { key: "links", to: "/links", icon: Link2 },
  { key: "terminal", to: "/terminal", icon: Keyboard },
  { key: "customers", to: "/customers", icon: Users },
  { key: "products", to: "/products", icon: Package },
  { key: "subscriptions", to: "/subscriptions", icon: Repeat },
  { key: "instalments", to: "/instalments", icon: CalendarClock },
  { key: "invoices", to: "/invoices", icon: FileText },
  { key: "connectedAccounts", to: "/connected-accounts", icon: Network },
  { key: "developers", to: "/developers", icon: Code2 },
  { key: "settings", to: "/settings", icon: Settings },
];

function NavEntry({ item }: { item: NavItem }) {
  const { t } = useTranslation();
  const Icon = item.icon;
  const label = t(`nav.${item.key}`);
  const base = "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap";
  if (item.phase) {
    return (
      <span className={cn(base, "cursor-not-allowed text-muted opacity-60")} aria-disabled="true">
        <Icon className="size-4" aria-hidden />
        {label}
        <Badge className="ml-auto hidden md:inline-flex">{t("nav.soon")}</Badge>
      </span>
    );
  }
  return (
    <NavLink
      to={item.to}
      end={item.to === "/"}
      className={({ isActive }) => cn(base, isActive ? "bg-brand-50 text-brand-700" : "text-ink hover:bg-canvas")}
    >
      <Icon className="size-4" aria-hidden />
      {label}
    </NavLink>
  );
}

function TestModeToggle() {
  const { t } = useTranslation();
  const { testMode, setTestMode } = useTestMode();
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm">
      <span className={cn("font-medium", testMode ? "text-warning" : "text-muted")}>{t("shell.testMode")}</span>
      <input
        type="checkbox"
        role="switch"
        checked={testMode}
        onChange={(event) => setTestMode(event.target.checked)}
        className="size-4 accent-brand-600"
        aria-label={t("shell.testMode")}
      />
    </label>
  );
}

function Shell() {
  const { t } = useTranslation();
  const { merchant, user } = useSession();
  const { logout } = useAuth();
  const { testMode } = useTestMode();
  const navigate = useNavigate();

  return (
    <div className="min-h-screen md:grid md:grid-cols-[15rem_1fr]">
      <aside className="border-b border-border bg-surface print:hidden md:min-h-screen md:border-r md:border-b-0">
        <div className="flex items-center gap-2 px-4 py-4 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
          Cutpay
        </div>
        <nav aria-label={t("nav.label")} className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-col md:overflow-visible">
          {NAV.map((item) => (
            <NavEntry key={item.to} item={item} />
          ))}
        </nav>
      </aside>

      <div className="flex min-w-0 flex-col">
        {testMode && (
          <div className="bg-warning-bg px-4 py-1.5 text-center text-sm text-warning print:hidden" role="status">
            {t("shell.testBanner")}
          </div>
        )}
        <header className="flex items-center justify-between gap-4 border-b border-border bg-surface px-4 py-3 print:hidden md:px-8">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold" data-testid="merchant-name">
              {merchant.display_name}
            </p>
            <p className="truncate text-xs text-muted">{user.email}</p>
          </div>
          <div className="flex items-center gap-4">
            <LanguageSwitcher label={t("shell.language")} locales={DASHBOARD_LOCALES} />
            <TestModeToggle />
            <button
              type="button"
              onClick={() => {
                void logout().then(() => navigate("/login", { replace: true }));
              }}
              className="flex items-center gap-1.5 text-sm text-muted hover:text-ink"
            >
              <LogOut className="size-4" aria-hidden />
              {t("shell.signOut")}
            </button>
          </div>
        </header>
        <main className="flex-1 px-4 py-6 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function AppShell() {
  return (
    <TestModeProvider>
      <Shell />
    </TestModeProvider>
  );
}
