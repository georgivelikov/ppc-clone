import { Card } from "@cutpay/ui";
import type { ReactNode } from "react";

/** Centered card used by the login and signup pages. */
export function AuthLayout({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <main className="grid min-h-screen place-items-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center justify-center gap-2 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
          Cutpay
        </div>
        <Card>
          <h1 className="text-xl font-semibold">{title}</h1>
          <p className="mt-1 mb-6 text-sm text-muted">{subtitle}</p>
          {children}
        </Card>
        <p className="mt-6 text-center text-xs text-muted">
          Educational project. No real payments are processed.
        </p>
      </div>
    </main>
  );
}
