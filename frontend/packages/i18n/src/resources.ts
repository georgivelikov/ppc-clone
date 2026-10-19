import type { Messages } from "./check";
import type { Locale } from "./locale";
import bgCheckout from "./locales/bg/checkout.json" with { type: "json" };
import elCheckout from "./locales/el/checkout.json" with { type: "json" };
import bgDashboard from "./locales/bg/dashboard.json" with { type: "json" };
import enCheckout from "./locales/en/checkout.json" with { type: "json" };
import enDashboard from "./locales/en/dashboard.json" with { type: "json" };
import huCheckout from "./locales/hu/checkout.json" with { type: "json" };
import plCheckout from "./locales/pl/checkout.json" with { type: "json" };
import roCheckout from "./locales/ro/checkout.json" with { type: "json" };

/** The hosted checkout is translated into every language. */
export const CHECKOUT: Record<Locale, Messages> = {
  en: enCheckout,
  bg: bgCheckout,
  ro: roCheckout,
  pl: plCheckout,
  hu: huCheckout,
  el: elCheckout,
};

/** What an app hands to {@link setupI18n}: `{ locale: { namespace: messages } }`. */
export const checkoutResources = Object.fromEntries(
  Object.entries(CHECKOUT).map(([locale, messages]) => [locale, { checkout: messages }]),
);

/** The dashboard is translated into English and Bulgarian; the other languages show English. */
export const DASHBOARD: Partial<Record<Locale, Messages>> & { en: Messages } = {
  en: enDashboard,
  bg: bgDashboard,
};

export const dashboardResources = Object.fromEntries(
  Object.entries(DASHBOARD).map(([locale, messages]) => [locale, { dashboard: messages }]),
);

/** The languages the dashboard can be shown in. */
export const DASHBOARD_LOCALES = Object.keys(DASHBOARD) as Locale[];
