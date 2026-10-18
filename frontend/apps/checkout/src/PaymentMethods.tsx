import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { Session } from "./api";
import { BnplPanel } from "./BnplPanel";
import { getBnplOffers } from "./bnplApi";
import { PaymentForm } from "./PaymentForm";

interface Props {
  secret: string;
  session: Session;
  onUpdate: (session: Session) => void;
}

/**
 * The card form, and next to it "Pay in instalments" when a provider has something to offer. The offers are fetched in
 * the background: while they load, or when none come, the checkout looks exactly as it did.
 */
export function PaymentMethods({ secret, session, onUpdate }: Props) {
  const offers = useQuery({
    queryKey: ["bnpl-offers", secret],
    queryFn: () => getBnplOffers(secret),
    retry: false,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
  const [tab, setTab] = useState<"card" | "instalments">("card");
  const available = Array.isArray(offers.data?.offers) && offers.data.offers.length > 0;

  if (!available) {
    return <PaymentForm secret={secret} session={session} onUpdate={onUpdate} />;
  }
  return (
    <div className="space-y-4">
      <div role="tablist" aria-label="Payment method" className="grid grid-cols-2 gap-1 rounded-lg bg-canvas p-1 text-sm">
        {(
          [
            ["card", "Card"],
            ["instalments", "Pay in instalments"],
          ] as const
        ).map(([id, label]) => (
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
            {label}
          </button>
        ))}
      </div>
      {/* Both stay mounted, so an application in progress is not lost when the customer looks at the card form. */}
      <div role="tabpanel" id="panel-card" aria-labelledby="tab-card" hidden={tab !== "card"}>
        <PaymentForm secret={secret} session={session} onUpdate={onUpdate} />
      </div>
      <div role="tabpanel" id="panel-instalments" aria-labelledby="tab-instalments" hidden={tab !== "instalments"}>
        <BnplPanel secret={secret} session={session} offers={offers.data!} />
      </div>
    </div>
  );
}
