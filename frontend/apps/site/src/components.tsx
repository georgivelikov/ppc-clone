import { cn } from "@cutpay/ui";
import { Check, Copy } from "lucide-react";
import { useState, type ReactNode } from "react";

/** A code sample with a copy button. The language is only a label; there is no syntax highlighting. */
export function CodeBlock({ code, language, label }: { code: string; language?: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be blocked; the text is selectable anyway.
    }
  }
  return (
    <figure className="my-4 overflow-hidden rounded-lg border border-border bg-canvas">
      <figcaption className="flex items-center justify-between border-b border-border px-3 py-1.5 text-xs text-muted">
        <span>{label ?? language ?? "Code"}</span>
        <button type="button" onClick={() => void copy()} className="flex items-center gap-1 hover:text-ink" aria-label={`Copy ${label ?? "code"}`}>
          {copied ? <Check className="size-3.5" aria-hidden /> : <Copy className="size-3.5" aria-hidden />}
          {copied ? "Copied" : "Copy"}
        </button>
      </figcaption>
      <pre className="overflow-x-auto p-3 text-[13px] leading-relaxed">
        <code>{code}</code>
      </pre>
    </figure>
  );
}

/** A section with a heading, used on the marketing pages. */
export function Section({
  title,
  intro,
  children,
  className,
}: {
  title: string;
  intro?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("mx-auto max-w-5xl px-4 py-12", className)}>
      <h2 className="text-2xl font-semibold tracking-tight">{title}</h2>
      {intro && <p className="mt-2 max-w-2xl text-muted">{intro}</p>}
      <div className="mt-8">{children}</div>
    </section>
  );
}

export function PageHeader({ title, intro }: { title: string; intro: string }) {
  return (
    <header className="mx-auto max-w-5xl px-4 pt-14 pb-4">
      <h1 className="text-4xl font-semibold tracking-tight">{title}</h1>
      <p className="mt-3 max-w-2xl text-lg text-muted">{intro}</p>
    </header>
  );
}
