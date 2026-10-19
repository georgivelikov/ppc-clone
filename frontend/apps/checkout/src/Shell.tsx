import { LanguageSwitcher, useTranslation } from "@cutpay/i18n";
import { Card } from "@cutpay/ui";
import type { ReactNode } from "react";

/** The page frame: narrow centred card with the educational-project disclaimer underneath. */
/** {@code languages}: show the language switcher, on pages that are translated. */
export function Shell({ children, aside, languages = false }: { children: ReactNode; aside?: ReactNode; languages?: boolean }) {
  const { t } = useTranslation();
  return (
    <main className="grid min-h-screen place-items-center px-4 py-8">
      <div className="w-full max-w-3xl">
        <div className="mb-6 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-lg font-semibold">
            <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-white">C</span>
            Cutpay
          </div>
          {languages && <LanguageSwitcher label={t("shell.language")} />}
        </div>
        <div className={aside ? "grid gap-4 md:grid-cols-[1fr_1.2fr]" : "mx-auto max-w-md"}>
          {aside && <Card className="md:self-start">{aside}</Card>}
          <Card>{children}</Card>
        </div>
        <p className="mt-6 text-center text-xs text-muted">{t("shell.disclaimer")}</p>
      </div>
    </main>
  );
}
