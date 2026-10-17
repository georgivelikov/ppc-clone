// Loads the Cutpay SDK the way a merchant would: with a script tag from Cutpay's own domain.
// (Never bundle or self-host it: card entry must always run the code Cutpay serves.)

export interface CutpayElementsApi {
  create(type: "card"): {
    mount(target: string | HTMLElement): unknown;
    unmount(): void;
    on(event: "change", listener: (state: { complete: boolean; error: string | null }) => void): unknown;
    on(event: "ready", listener: () => void): unknown;
  };
}

export interface CutpayApi {
  elements(options: { clientSecret: string }): CutpayElementsApi;
  confirmPayment(options: {
    elements: CutpayElementsApi;
    returnUrl?: string;
    redirect?: "if_required" | "always";
    receiptEmail?: string;
  }): Promise<{ paymentIntent?: { id: string; status: string }; error?: { code: string; message: string } }>;
}

declare global {
  interface Window {
    Cutpay?: (publishableKey: string, options?: { checkoutUrl?: string }) => CutpayApi;
  }
}

let loading: Promise<NonNullable<Window["Cutpay"]>> | null = null;

export function loadCutpay(checkoutUrl: string): Promise<NonNullable<Window["Cutpay"]>> {
  if (window.Cutpay) return Promise.resolve(window.Cutpay);
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = `${checkoutUrl}/v1/cutpay.js`;
    script.async = true;
    script.onload = () => (window.Cutpay ? resolve(window.Cutpay) : reject(new Error("The Cutpay SDK did not initialise.")));
    script.onerror = () => {
      loading = null;
      reject(new Error("Could not load the Cutpay SDK. Is the checkout running?"));
    };
    document.head.appendChild(script);
  });
  return loading;
}
