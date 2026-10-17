import { Card } from "@cutpay/ui";
import type { ReactNode } from "react";

/** The page frame: narrow centred card with the educational-project disclaimer underneath. */
export function Shell({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <main className="grid min-h-screen place-items-center px-4 py-8">
      <div className="w-full max-w-3xl">
        <div className="mb-6 flex items-center justify-center gap-2 text-lg font-semibold">
          <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
          Cutpay
        </div>
        <div className={aside ? "grid gap-4 md:grid-cols-[1fr_1.2fr]" : "mx-auto max-w-md"}>
          {aside && <Card className="md:self-start">{aside}</Card>}
          <Card>{children}</Card>
        </div>
        <p className="mt-6 text-center text-xs text-muted">
          Educational project. No real payments are processed — never enter a real card number.
        </p>
      </div>
    </main>
  );
}
