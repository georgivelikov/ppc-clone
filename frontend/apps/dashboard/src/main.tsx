import { browserLanguages, dashboardResources, DASHBOARD_LOCALES, detectLocale, setupI18n, storedLocale } from "@cutpay/i18n";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthProvider";
import "./styles.css";

// The language the person picked last time, then the browser's. The dashboard is translated into English and Bulgarian;
// any other language shows English.
const wanted = detectLocale({ chosen: storedLocale(), browser: browserLanguages() });
setupI18n({
  resources: dashboardResources,
  defaultNamespace: "dashboard",
  locale: DASHBOARD_LOCALES.includes(wanted) ? wanted : "en",
});

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
