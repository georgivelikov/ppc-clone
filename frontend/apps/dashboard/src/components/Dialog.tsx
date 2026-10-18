import { X } from "lucide-react";
import { useEffect, useId, type ReactNode } from "react";

/** A modal dialog: dims the page, closes on Escape or a click outside, and is announced as a dialog. */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    // A dialog taller than the window scrolls as a whole, so its buttons can always be reached.
    <div className="fixed inset-0 z-50 overflow-y-auto bg-black/40">
      <div
        className="flex min-h-full items-center justify-center px-4 py-8"
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) onClose();
        }}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          className="w-full max-w-md rounded-card border border-border bg-surface p-6 shadow-xl"
        >
          <div className="mb-4 flex items-start justify-between gap-4">
            <h2 id={titleId} className="text-lg font-semibold">
              {title}
            </h2>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X className="size-5" aria-hidden />
            </button>
          </div>
          {children}
        </div>
      </div>
    </div>
  );
}
