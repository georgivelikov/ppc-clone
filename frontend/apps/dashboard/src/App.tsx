import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router";
import { GuestOnly, RequireAuth } from "./auth/guards";
import { LoginPage } from "./auth/LoginPage";
import { SignupPage } from "./auth/SignupPage";
import { AppShell } from "./layout/AppShell";
import { HomePage } from "./pages/HomePage";
import { ApiKeysPage } from "./pages/developers/ApiKeysPage";
import { EventsPage } from "./pages/developers/EventsPage";
import { LogsPage } from "./pages/developers/LogsPage";
import { WebhookDetailPage } from "./pages/developers/WebhookDetailPage";
import { WebhooksPage } from "./pages/developers/WebhooksPage";
import { InvoiceDetailPage } from "./pages/billing/InvoiceDetailPage";
import { InvoiceEditorPage } from "./pages/billing/InvoiceEditorPage";
import { InvoicesPage } from "./pages/billing/InvoicesPage";
import { NewSubscriptionPage } from "./pages/billing/NewSubscriptionPage";
import { ProductsPage } from "./pages/billing/ProductsPage";
import { SubscriptionDetailPage } from "./pages/billing/SubscriptionDetailPage";
import { SubscriptionsPage } from "./pages/billing/SubscriptionsPage";
import { ConnectedAccountDetailPage } from "./pages/connected/ConnectedAccountDetailPage";
import { ConnectedAccountsPage } from "./pages/connected/ConnectedAccountsPage";
import { CustomerDetailPage } from "./pages/customers/CustomerDetailPage";
import { CustomersPage } from "./pages/customers/CustomersPage";
import { CreateLinkPage } from "./pages/links/CreateLinkPage";
import { LinkDetailPage } from "./pages/links/LinkDetailPage";
import { LinksPage } from "./pages/links/LinksPage";
import { ChargeQrPage } from "./pages/terminal/ChargeQrPage";
import { ReceiptPage } from "./pages/terminal/ReceiptPage";
import { TerminalPage } from "./pages/terminal/TerminalPage";
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
            <Route path="customers" element={<CustomersPage />} />
            <Route path="connected-accounts" element={<ConnectedAccountsPage />} />
            <Route path="connected-accounts/:id" element={<ConnectedAccountDetailPage />} />
            <Route path="products" element={<ProductsPage />} />
            <Route path="subscriptions" element={<SubscriptionsPage />} />
            <Route path="subscriptions/new" element={<NewSubscriptionPage />} />
            <Route path="subscriptions/:id" element={<SubscriptionDetailPage />} />
            <Route path="invoices" element={<InvoicesPage />} />
            <Route path="invoices/new" element={<InvoiceEditorPage />} />
            <Route path="invoices/:id" element={<InvoiceDetailPage />} />
            <Route path="customers/:id" element={<CustomerDetailPage />} />
            <Route path="links" element={<LinksPage />} />
            <Route path="links/new" element={<CreateLinkPage />} />
            <Route path="links/:id" element={<LinkDetailPage />} />
            <Route path="terminal" element={<TerminalPage />} />
            <Route path="terminal/qr" element={<ChargeQrPage />} />
            <Route path="terminal/receipt/:id" element={<ReceiptPage />} />
            <Route path="balances" element={<BalancesPage />} />
            <Route path="payouts" element={<PayoutsPage />} />
            <Route path="payouts/:id" element={<PayoutDetailPage />} />
            <Route path="developers" element={<Navigate to="/developers/api-keys" replace />} />
            <Route path="developers/api-keys" element={<ApiKeysPage />} />
            <Route path="developers/webhooks" element={<WebhooksPage />} />
            <Route path="developers/webhooks/:id" element={<WebhookDetailPage />} />
            <Route path="developers/events" element={<EventsPage />} />
            <Route path="developers/logs" element={<LogsPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="settings/payouts" element={<PayoutSettingsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
