// Which ways of paying a merchant offers at its checkout (backend/services/payments).

export type PaymentMethodId = "card" | "apple_pay" | "google_pay" | "blik";

export interface PaymentMethodSetting {
  method: PaymentMethodId;
  enabled: boolean;
  /** Cards cannot be switched off. */
  locked: boolean;
}

export interface PaymentMethodSettings {
  object: "payment_method_settings";
  data: PaymentMethodSetting[];
}

export interface UpdatePaymentMethodSettingsInput {
  methods: { method: PaymentMethodId; enabled: boolean }[];
}
