import {
  parseFrameMessage,
  wrap,
  type ChangeState,
  type ConfirmResult,
  type ParentMessage,
} from "./protocol";

type Listener<T> = (event: T) => void;

export interface CardElementEvents {
  ready: undefined;
  change: ChangeState;
  focus: undefined;
  blur: undefined;
}

const CONFIRM_TIMEOUT_MS = 60_000;
const DEFAULT_HEIGHT = 44;

/**
 * The card input. It is an iframe served from our origin, so the card number is typed into our page and never
 * touches the merchant's. This class only mounts the frame and talks to it with postMessage.
 */
export class CardElement {
  private iframe: HTMLIFrameElement | null = null;
  private listeners = new Map<keyof CardElementEvents, Listener<never>[]>();
  private pending = new Map<string, (result: ConfirmResult) => void>();
  private state: ChangeState = { complete: false, empty: true, brand: "unknown", error: null };
  private ready = false;

  constructor(
    private readonly publishableKey: string,
    private readonly checkoutUrl: string,
  ) {}

  get isMounted(): boolean {
    return this.iframe !== null;
  }

  get currentState(): ChangeState {
    return this.state;
  }

  mount(target: string | HTMLElement): this {
    if (this.iframe) throw new Error("This element is already mounted. Call unmount() first.");
    const host = typeof target === "string" ? document.querySelector<HTMLElement>(target) : target;
    if (!host) throw new Error(`Cutpay: no element matches ${String(target)}`);

    const url = new URL("/elements/card", this.checkoutUrl);
    url.searchParams.set("key", this.publishableKey);
    // The frame only talks to this origin, and only accepts messages from it.
    url.searchParams.set("origin", window.location.origin);

    const iframe = document.createElement("iframe");
    iframe.src = url.toString();
    iframe.title = "Secure card payment input";
    iframe.setAttribute("allow", "payment");
    iframe.setAttribute("sandbox", "allow-scripts allow-same-origin");
    iframe.setAttribute("scrolling", "no");
    Object.assign(iframe.style, { border: "0", width: "100%", height: `${DEFAULT_HEIGHT}px`, display: "block" });
    host.appendChild(iframe);
    this.iframe = iframe;
    window.addEventListener("message", this.onMessage);
    return this;
  }

  unmount(): void {
    window.removeEventListener("message", this.onMessage);
    this.iframe?.remove();
    this.iframe = null;
    this.ready = false;
    this.pending.forEach((resolve) =>
      resolve({ ok: false, error: { type: "validation_error", code: "element_unmounted", message: "The card input was removed." } }),
    );
    this.pending.clear();
  }

  on<K extends keyof CardElementEvents>(event: K, listener: Listener<CardElementEvents[K]>): this {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener as Listener<never>]);
    return this;
  }

  focus(): void {
    this.post({ type: "focus" });
  }

  clear(): void {
    this.post({ type: "clear" });
  }

  /** Asks the frame to pay. Used by {@link Cutpay.confirmPayment}; the card never leaves the frame. */
  confirm(clientSecret: string, email: string | undefined, timeoutMs = CONFIRM_TIMEOUT_MS): Promise<ConfirmResult> {
    if (!this.iframe) {
      return Promise.resolve({
        ok: false,
        error: { type: "validation_error", code: "element_not_mounted", message: "Mount the card element first." },
      });
    }
    const requestId = crypto.randomUUID();
    return new Promise<ConfirmResult>((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        resolve({
          ok: false,
          error: { type: "api_error", code: "timeout", message: "The payment took too long. Please try again." },
        });
      }, timeoutMs);
      this.pending.set(requestId, (result) => {
        clearTimeout(timer);
        resolve(result);
      });
      this.post({ type: "confirm", requestId, clientSecret, ...(email ? { email } : {}) });
    });
  }

  private post(message: ParentMessage): void {
    // The target origin is named explicitly, so a frame that was navigated elsewhere receives nothing.
    this.iframe?.contentWindow?.postMessage(wrap(message), new URL(this.checkoutUrl).origin);
  }

  private emit<K extends keyof CardElementEvents>(event: K, payload: CardElementEvents[K]): void {
    this.listeners.get(event)?.forEach((listener) => (listener as Listener<CardElementEvents[K]>)(payload));
  }

  private onMessage = (event: MessageEvent): void => {
    // Who sent it is decided by the browser (origin and source window), never by what the message says.
    if (!this.iframe || event.source !== this.iframe.contentWindow) return;
    if (event.origin !== new URL(this.checkoutUrl).origin) return;
    const message = parseFrameMessage(event.data);
    if (!message) return;

    switch (message.type) {
      case "ready":
        this.ready = true;
        this.emit("ready", undefined);
        break;
      case "change":
        this.state = message.state;
        this.emit("change", message.state);
        break;
      case "height":
        if (this.iframe) this.iframe.style.height = `${Math.max(DEFAULT_HEIGHT, Math.min(message.height, 200))}px`;
        break;
      case "focus":
        this.emit("focus", undefined);
        break;
      case "blur":
        this.emit("blur", undefined);
        break;
      case "confirm_result": {
        const resolve = this.pending.get(message.requestId);
        if (resolve) {
          this.pending.delete(message.requestId);
          resolve(message.result);
        }
        break;
      }
    }
  };

  /** True once the frame has loaded and announced itself. */
  get isReady(): boolean {
    return this.ready;
  }
}

export interface ElementsOptions {
  clientSecret: string;
}

/** A group of elements that belong to one payment. */
export class Elements {
  private card: CardElement | null = null;

  constructor(
    readonly clientSecret: string,
    private readonly publishableKey: string,
    private readonly checkoutUrl: string,
  ) {}

  create(type: "card"): CardElement {
    if (type !== "card") throw new Error(`Cutpay: unknown element type "${String(type)}"`);
    if (this.card) throw new Error("Cutpay: a card element was already created for these elements.");
    this.card = new CardElement(this.publishableKey, this.checkoutUrl);
    return this.card;
  }

  getElement(type: "card"): CardElement | null {
    return type === "card" ? this.card : null;
  }
}
