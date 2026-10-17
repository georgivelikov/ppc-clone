// Types for payment links, QR codes and the virtual POS terminal (backend/services/links, payments).

export type PaymentLinkType = "fixed" | "customer_chooses" | "products";
export type PaymentLinkStatus = "active" | "inactive" | "expired" | "sold_out";
export type CollectField = "email" | "phone" | "name" | "address";

export interface LinkLineItem {
  product_name: string;
  unit_amount: number;
  quantity: number;
}

/** Colours of the printed code, as "#rrggbb". */
export interface QrStyle {
  fg?: string;
  bg?: string;
}

export interface PaymentLink {
  id: string;
  object: "payment_link";
  livemode: boolean;
  /** The address to share: the checkout app's /l/{slug}. */
  url: string;
  slug: string;
  status: PaymentLinkStatus;
  type: PaymentLinkType;
  amount: number | null;
  currency: string;
  min_amount: number | null;
  max_amount: number | null;
  title: string;
  description: string | null;
  image_url: string | null;
  collect: CollectField[];
  quantity_adjustable: boolean;
  max_uses: number | null;
  uses_count: number;
  /** Total paid through the link, in minor units. */
  amount_collected: number;
  expires_at: string | null;
  active: boolean;
  after_completion: "message" | "redirect";
  redirect_url: string | null;
  success_message: string | null;
  metadata: Record<string, string>;
  qr_style: QrStyle;
  line_items: LinkLineItem[];
  created_at: string;
}

export interface PaymentLinkList {
  object: "list";
  data: PaymentLink[];
  has_more: boolean;
}

export interface PaymentLinkListParams {
  status?: "active" | "inactive" | "";
  starting_after?: string;
  limit?: number;
  livemode?: boolean;
}

export interface CreatePaymentLinkInput {
  type: PaymentLinkType;
  currency: string;
  title: string;
  /** Minor units; for "fixed" links. */
  amount?: number;
  min_amount?: number;
  max_amount?: number;
  description?: string;
  image_url?: string;
  collect?: CollectField[];
  quantity_adjustable?: boolean;
  max_uses?: number;
  expires_at?: string;
  after_completion?: "message" | "redirect";
  redirect_url?: string;
  success_message?: string;
  merchant_name?: string;
  line_items?: LinkLineItem[];
  qr_style?: QrStyle;
}

export interface UpdatePaymentLinkInput {
  title?: string;
  description?: string;
  image_url?: string;
  collect?: CollectField[];
  max_uses?: number;
  expires_at?: string;
  active?: boolean;
  after_completion?: "message" | "redirect";
  redirect_url?: string;
  success_message?: string;
  qr_style?: QrStyle;
}

export interface PaymentLinkPayment {
  payment_id: string;
  reservation_id: string;
  status: string;
  amount: number;
  created_at: string;
}

export interface PaymentLinkPayments {
  object: "list";
  data: PaymentLinkPayment[];
}

/** What a payment looks like on the live status stream. */
export interface PaymentStatusEvent {
  status: string;
  failure_code?: string;
}
