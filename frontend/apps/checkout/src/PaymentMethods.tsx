import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { getMethods, type Session } from "./api";
import { BlikPanel } from "./BlikPanel";
import { BnplPanel } from "./BnplPanel";
import { getBnplOffers } from "./bnplApi";
import { PaymentForm } from "./PaymentForm";
import { WalletPanel } from "./WalletPanel";

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

type Tab = "card" | "wallet" | "blik" | "instalments";

const LABEL: Record<Tab, string> = {
  card: "Card",
  wallet: "Wallet",
  blik: "BLIK",
  instalments: "Pay in instalments",
};

/**
 * The card form, and next to it the other ways of paying this payment allows: a wallet, BLIK for złoty payments and
 * instalments when a provider has something to offer. What is offered is fetched in the background: while it loads,
 * or when it fails, the checkout looks exactly as it did before there were other methods.
 */
export function PaymentMethods({ secret, session, onUpdate }: Props) {
  const methods = useQuery({
    queryKey: ["methods", secret],
    queryFn: () => getMethods(secret),
    retry: false,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
  const offers = useQuery({
    queryKey: ["bnpl-offers", secret],
    queryFn: () => getBnplOffers(secret),
    retry: false,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
  const [tab, setTab] = useState<Tab>("card");
  const offered = methods.data?.methods ?? [];
  const tabs: Tab[] = ["card"];
  if (offered.includes("apple_pay") || offered.includes("google_pay")) tabs.push("wallet");
  if (offered.includes("blik")) tabs.push("blik");
  const instalments = Array.isArray(offers.data?.offers) && offers.data.offers.length > 0;
  if (instalments) tabs.push("instalments");

  if (tabs.length === 1) {
    return <PaymentForm secret={secret} session={session} onUpdate={onUpdate} />;
  }
  return (
    <div className="space-y-4">
      <div
        role="tablist"
        aria-label="Payment method"
        className="grid gap-1 rounded-lg bg-canvas p-1 text-sm"
        style={{ gridTemplateColumns: `repeat(${tabs.length}, minmax(0, 1fr))` }}
      >
        {tabs.map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            id={`tab-${id}`}
            aria-selected={tab === id}
            aria-controls={`panel-${id}`}
            onClick={() => setTab(id)}
            className={`rounded-md px-3 py-2 font-medium ${tab === id ? "bg-surface shadow-sm" : "text-muted"}`}
          >
            {LABEL[id]}
          </button>
        ))}
      </div>
      {/* All stay mounted, so an application in progress is not lost when the customer looks at the card form. */}
      <div role="tabpanel" id="panel-card" aria-labelledby="tab-card" hidden={tab !== "card"}>
        <PaymentForm secret={secret} session={session} onUpdate={onUpdate} />
      </div>
      {tabs.includes("wallet") && (
        <div role="tabpanel" id="panel-wallet" aria-labelledby="tab-wallet" hidden={tab !== "wallet"}>
          <WalletPanel secret={secret} session={session} onUpdate={onUpdate} />
        </div>
      )}
      {tabs.includes("blik") && (
        <div role="tabpanel" id="panel-blik" aria-labelledby="tab-blik" hidden={tab !== "blik"}>
          <BlikPanel secret={secret} session={session} onUpdate={onUpdate} />
        </div>
      )}
      {instalments && (
        <div role="tabpanel" id="panel-instalments" aria-labelledby="tab-instalments" hidden={tab !== "instalments"}>
          <BnplPanel secret={secret} session={session} offers={offers.data!} />
        </div>
      )}
    </div>
  );
}
