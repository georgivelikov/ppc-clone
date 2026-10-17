import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router";
import { GuestOnly, RequireAuth } from "./auth/guards";
import { LoginPage } from "./auth/LoginPage";
import { SignupPage } from "./auth/SignupPage";
import { AppShell } from "./layout/AppShell";
import { HomePage } from "./pages/HomePage";
import { PaymentDetailPage } from "./pages/PaymentDetailPage";
import { PaymentsPage } from "./pages/PaymentsPage";
import { PayoutDetailPage } from "./pages/PayoutDetailPage";
import { PayoutSettingsPage } from "./pages/PayoutSettingsPage";
import { PayoutsPage } from "./pages/PayoutsPage";
import { SettingsPage } from "./pages/SettingsPage";

// The chart library is large; load it only when someone opens Balances.
const BalancesPage = lazy(() => import("./pages/BalancesPage").then((module) => ({ default: module.BalancesPage })));

export function App() {
  return (
    <Suspense fallback={<p className="p-8 text-sm text-muted">Loading…</p>}>
      <Routes>
        <Route element={<GuestOnly />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route index element={<HomePage />} />
            <Route path="payments" element={<PaymentsPage />} />
            <Route path="payments/:id" element={<PaymentDetailPage />} />
            <Route path="balances" element={<BalancesPage />} />
            <Route path="payouts" element={<PayoutsPage />} />
            <Route path="payouts/:id" element={<PayoutDetailPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="settings/payouts" element={<PayoutSettingsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
