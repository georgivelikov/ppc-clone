import { Badge, cn } from "@cutpay/ui";
import {
  Banknote,
  Code2,
  CreditCard,
  Home,
  Link2,
  LogOut,
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
  label: string;
  to: string;
  icon: LucideIcon;
  /** The phase in which the page gets built; until then the item is shown disabled. */
  phase?: number;
}

const NAV: NavItem[] = [
  { label: "Home", to: "/", icon: Home },
  { label: "Payments", to: "/payments", icon: CreditCard, phase: 1 },
  { label: "Balances", to: "/balances", icon: Wallet, phase: 2 },
  { label: "Payouts", to: "/payouts", icon: Banknote, phase: 2 },
  { label: "Links", to: "/links", icon: Link2, phase: 4 },
  { label: "Customers", to: "/customers", icon: Users, phase: 5 },
  { label: "Subscriptions", to: "/subscriptions", icon: Repeat, phase: 5 },
  { label: "Developers", to: "/developers", icon: Code2, phase: 3 },
  { label: "Settings", to: "/settings", icon: Settings },
];

function NavEntry({ item }: { item: NavItem }) {
  const Icon = item.icon;
  const base = "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap";
  if (item.phase) {
    return (
      <span className={cn(base, "cursor-not-allowed text-muted opacity-60")} aria-disabled="true">
        <Icon className="size-4" aria-hidden />
        {item.label}
        <Badge className="ml-auto hidden md:inline-flex">Soon</Badge>
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
      {item.label}
    </NavLink>
  );
}

function TestModeToggle() {
  const { testMode, setTestMode } = useTestMode();
  return (
    <label className="flex cursor-pointer items-center gap-2 text-sm">
      <span className={cn("font-medium", testMode ? "text-warning" : "text-muted")}>Test mode</span>
      <input
        type="checkbox"
        role="switch"
        checked={testMode}
        onChange={(event) => setTestMode(event.target.checked)}
        className="size-4 accent-brand-600"
        aria-label="Test mode"
      />
    </label>
  );
}

function Shell() {
  const { merchant, user } = useSession();
  const { logout } = useAuth();
  const { testMode } = useTestMode();
  const navigate = useNavigate();

  return (
    <div className="min-h-screen md:grid md:grid-cols-[15rem_1fr]">
      <aside className="border-b border-border bg-surface md:min-h-screen md:border-r md:border-b-0">
        <div className="flex items-center gap-2 px-4 py-4 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
          Cutpay
        </div>
        <nav aria-label="Main" className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-col md:overflow-visible">
          {NAV.map((item) => (
            <NavEntry key={item.to} item={item} />
          ))}
        </nav>
      </aside>

      <div className="flex min-w-0 flex-col">
        {testMode && (
          <div className="bg-warning-bg px-4 py-1.5 text-center text-sm text-warning" role="status">
            You are viewing test data. No real money is involved.
          </div>
        )}
        <header className="flex items-center justify-between gap-4 border-b border-border bg-surface px-4 py-3 md:px-8">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold" data-testid="merchant-name">
              {merchant.display_name}
            </p>
            <p className="truncate text-xs text-muted">{user.email}</p>
          </div>
          <div className="flex items-center gap-4">
            <TestModeToggle />
            <button
              type="button"
              onClick={() => {
                void logout().then(() => navigate("/login", { replace: true }));
              }}
              className="flex items-center gap-1.5 text-sm text-muted hover:text-ink"
            >
              <LogOut className="size-4" aria-hidden />
              Sign out
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
