import { Alert, Button } from "@cutpay/ui";
import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Dialog } from "./Dialog";

/**
 * Shows a secret exactly once. The server keeps only a hash (API keys) or never shows it again (webhook
 * secrets), so closing this dialog is the point of no return, and the dialog says so.
 */
export function RevealSecretDialog({
  title,
  label,
  secret,
  onClose,
}: {
  title: string;
  label: string;
  secret: string;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
    } catch {
      // Clipboard access can be blocked; the secret is selectable in the box instead.
    }
  }

  return (
    <Dialog title={title} onClose={onClose}>
      <div className="space-y-4">
        <Alert>Copy it now. For your security it will not be shown again.</Alert>
        <div>
          <p className="mb-1.5 text-sm font-medium">{label}</p>
          <code
            data-testid="revealed-secret"
            className="block rounded-lg border border-border bg-canvas px-3 py-2 font-mono text-xs break-all select-all"
          >
            {secret}
          </code>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={() => void copy()}>
            {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
            {copied ? "Copied" : "Copy"}
          </Button>
          <Button type="button" onClick={onClose}>
            I have saved it
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
