import { cn } from "@cutpay/ui";
import { NavLink, Outlet, useLocation } from "react-router";

const PAGES = [
  { label: "Getting started", to: "/docs", end: true },
  { label: "Test cards", to: "/docs/test-cards", end: false },
  { label: "Payment links", to: "/docs/payment-links", end: false },
  { label: "Webhooks", to: "/docs/webhooks", end: false },
  { label: "API reference", to: "/docs/api", end: false },
];

/** Side navigation shared by the docs pages. The API reference uses the full width. */
export function DocsLayout() {
  const { pathname } = useLocation();
  const full = pathname === "/docs/api" || pathname.startsWith("/docs/api/");
  return (
    <div className={cn("mx-auto grid gap-8 px-4 py-10", full ? "max-w-none" : "max-w-6xl md:grid-cols-[13rem_1fr]")}>
      <nav aria-label="Docs" className={cn("flex gap-1 md:flex-col", full && "md:flex-row md:justify-center")}>
        {PAGES.map((page) => (
          <NavLink
            key={page.to}
            to={page.to}
            end={page.end}
            className={({ isActive }) =>
              cn("rounded-lg px-3 py-2 text-sm font-medium", isActive ? "bg-brand-50 text-brand-700" : "text-muted hover:text-ink")
            }
          >
            {page.label}
          </NavLink>
        ))}
      </nav>
      <article className={cn("min-w-0", !full && "max-w-3xl")}>
        <Outlet />
      </article>
    </div>
  );
}
