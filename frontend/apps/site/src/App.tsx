import { Button, cn } from "@cutpay/ui";
import { lazy, Suspense } from "react";
import { Link, NavLink, Outlet, Route, Routes, useLocation } from "react-router";
import { LOGIN_URL, SIGNUP_URL } from "./links";
import { BusinessTypesPage } from "./pages/BusinessTypesPage";
import { DocsLayout } from "./pages/docs/DocsLayout";
import { GettingStartedPage } from "./pages/docs/GettingStartedPage";
import { TestCardsPage } from "./pages/docs/TestCardsPage";
import { BillingPage } from "./pages/docs/BillingPage";
import { InstalmentsPage } from "./pages/docs/InstalmentsPage";
import { PaymentLinksPage } from "./pages/docs/PaymentLinksPage";
import { WebhooksGuidePage } from "./pages/docs/WebhooksGuidePage";
import { FaqPage } from "./pages/FaqPage";
import { HomePage } from "./pages/HomePage";
import { PricingPage } from "./pages/PricingPage";
import { ProductsPage } from "./pages/ProductsPage";

// Redoc is large; only load it when someone opens the API reference.
const ApiReferencePage = lazy(() => import("./pages/docs/ApiReferencePage").then((m) => ({ default: m.ApiReferencePage })));

const NAV = [
  { label: "Products", to: "/products" },
  { label: "Pricing", to: "/pricing" },
  { label: "Business types", to: "/business-types" },
  { label: "Docs", to: "/docs" },
  { label: "FAQ", to: "/faq" },
];

function Layout() {
  const { pathname } = useLocation();
  const wide = pathname.startsWith("/docs/api");
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-40 border-b border-border bg-surface/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Link to="/" className="flex items-center gap-2 text-lg font-semibold">
            <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
            Cutpay
          </Link>
          <nav aria-label="Main" className="hidden gap-6 md:flex">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => cn("text-sm font-medium", isActive ? "text-brand-700" : "text-muted hover:text-ink")}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center gap-2">
            <a href={LOGIN_URL} className="hidden text-sm font-medium text-muted hover:text-ink sm:block">
              Sign in
            </a>
            <a href={SIGNUP_URL}>
              <Button size="sm">Start for free</Button>
            </a>
          </div>
        </div>
        <nav aria-label="Mobile" className="flex gap-4 overflow-x-auto border-t border-border px-4 py-2 md:hidden">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} className="text-sm font-medium whitespace-nowrap text-muted">
              {item.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className={cn("flex-1", wide && "bg-surface")}>
        <Outlet />
      </main>
      <footer className="border-t border-border bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-6 text-sm text-muted">
          <p>Cutpay is an educational project. It moves no real money, so never enter a real card number.</p>
          <p>
            <Link to="/docs" className="hover:text-ink">
              Docs
            </Link>{" "}
            ·{" "}
            <Link to="/faq" className="hover:text-ink">
              FAQ
            </Link>
          </p>
        </div>
      </footer>
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="products" element={<ProductsPage />} />
        <Route path="pricing" element={<PricingPage />} />
        <Route path="business-types" element={<BusinessTypesPage />} />
        <Route path="faq" element={<FaqPage />} />
        <Route path="docs" element={<DocsLayout />}>
          <Route index element={<GettingStartedPage />} />
          <Route path="test-cards" element={<TestCardsPage />} />
          <Route path="payment-links" element={<PaymentLinksPage />} />
          <Route path="billing" element={<BillingPage />} />
          <Route path="instalments" element={<InstalmentsPage />} />
          <Route path="webhooks" element={<WebhooksGuidePage />} />
          <Route
            path="api/*"
            element={
              <Suspense fallback={<p className="p-8 text-sm text-muted">Loading the API reference…</p>}>
                <ApiReferencePage />
              </Suspense>
            }
          />
        </Route>
        <Route
          path="*"
          element={
            <div className="mx-auto max-w-xl px-4 py-24 text-center">
              <h1 className="text-3xl font-semibold">Page not found</h1>
              <p className="mt-2 text-muted">
                Try the <Link to="/" className="text-brand-700 hover:underline">home page</Link> or the{" "}
                <Link to="/docs" className="text-brand-700 hover:underline">docs</Link>.
              </p>
            </div>
          }
        />
      </Route>
    </Routes>
  );
}
