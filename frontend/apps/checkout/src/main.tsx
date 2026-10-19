import { browserLanguages, checkoutResources, detectLocale, setupI18n, storedLocale } from "@cutpay/i18n";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import "./styles.css";

// ?locale=pl in the link, or the language the customer picked last time, then the browser's languages. The language the
// merchant set for a payment is applied once the payment has loaded (see CheckoutPage).
setupI18n({
  resources: checkoutResources,
  defaultNamespace: "checkout",
  locale: detectLocale({
    chosen: new URLSearchParams(window.location.search).get("locale") ?? storedLocale(),
    browser: browserLanguages(),
  }),
});

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
