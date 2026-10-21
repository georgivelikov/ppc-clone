import { Alert, Button, Input, Label, cn } from "@cutpay/ui";
import { LogOut } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Navigate, NavLink, Outlet, Route, Routes } from "react-router";
import { ApiError } from "./api";
import { useAuth, useStaff } from "./auth";
import { AdjustmentsPage } from "./pages/AdjustmentsPage";
import { AuditPage, DisputesPage, SystemPage } from "./pages/OtherPages";
import { MerchantPage, MerchantsPage } from "./pages/MerchantsPage";
import { OperationsPage } from "./pages/OperationsPage";
import { ReviewPage, ReviewQueuePage } from "./pages/ReviewPages";

const NAV = [
  { to: "/merchants", label: "Businesses" },
  { to: "/review", label: "Review queue" },
  { to: "/adjustments", label: "Adjustments" },
  { to: "/disputes", label: "Disputes" },
  { to: "/audit", label: "Audit" },
  { to: "/operations", label: "Operations" },
  { to: "/system", label: "System" },
];

function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    setBusy(true);
    try {
      await login(email, password);
    } catch (error) {
      setProblem(error instanceof ApiError ? error.message : "Something went wrong.");
      setBusy(false);
    }
  };
  return (
    <main className="grid min-h-screen place-items-center px-4">
      <form onSubmit={(e) => void submit(e)} className="w-full max-w-sm space-y-4 rounded-card border border-border bg-surface p-6" aria-label="Staff sign-in">
        <h1 className="text-xl font-semibold">Cutpay back-office</h1>
        <p className="text-sm text-muted">For Cutpay staff only. Every action here is recorded.</p>
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="staff-email">Email</Label>
          <Input id="staff-email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="staff-password">Password</Label>
          <Input id="staff-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" loading={busy}>
          Sign in
        </Button>
      </form>
    </main>
  );
}

function Shell() {
  const staff = useStaff();
  const { logout } = useAuth();
  return (
    <div className="min-h-screen md:grid md:grid-cols-[14rem_1fr]">
      <aside className="border-b border-border bg-surface md:min-h-screen md:border-r md:border-b-0">
        <div className="px-4 py-4 text-lg font-semibold">
          Cutpay <span className="text-danger">back-office</span>
        </div>
        <nav aria-label="Main" className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-col">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn("rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap", isActive ? "bg-brand-50 text-brand-700" : "text-muted hover:text-ink")
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-col">
        <header className="flex items-center justify-between border-b border-border bg-surface px-4 py-3 md:px-8">
          <p className="text-sm" data-testid="staff-name">
            {staff.full_name} <span className="text-muted">· {staff.role}</span>
          </p>
          <button type="button" onClick={logout} className="flex items-center gap-1.5 text-sm text-muted hover:text-ink">
            <LogOut className="size-4" aria-hidden /> Sign out
          </button>
        </header>
        <main className="flex-1 px-4 py-6 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function App() {
  const { staff } = useAuth();
  if (!staff) return <LoginPage />;
  return (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<Navigate to="/review" replace />} />
        <Route path="merchants" element={<MerchantsPage />} />
        <Route path="merchants/:id" element={<MerchantPage />} />
        <Route path="review" element={<ReviewQueuePage />} />
        <Route path="review/:id" element={<ReviewPage />} />
        <Route path="adjustments" element={<AdjustmentsPage />} />
        <Route path="disputes" element={<DisputesPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="system" element={<SystemPage />} />
        <Route path="*" element={<Navigate to="/review" replace />} />
      </Route>
    </Routes>
  );
}
