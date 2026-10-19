// Types for currencies: exchange rates, locked quotes, conversions between balances and the settlement settings
// (backend/services/ledger, package fx).

export interface FxRates {
  object: "fx_rates";
  base: string;
  as_of: string;
  /** What one unit of the base currency buys of each other currency. */
  rates: Record<string, number>;
}

export interface FxQuote {
  id: string;
  object: "fx_quote";
  from_currency: string;
  to_currency: string;
  from_amount: number;
  /** What the amount is worth at the market rate. */
  mid_amount: number;
  /** What you receive: the market value less the markup. */
  to_amount: number;
  markup_amount: number;
  rate: number;
  markup_bps: number;
  expires_at: string;
  livemode: boolean;
}

export interface CreateFxQuoteInput {
  from_currency: string;
  to_currency: string;
  amount: number;
}

export interface ConvertBalanceInput extends CreateFxQuoteInput {
  /** Converts at the quoted rate. A quote is for one conversion of exactly its amount. */
  quote_id?: string;
}

export interface FxConversion {
  id: string;
  object: "fx_conversion";
  from_currency: string;
  to_currency: string;
  from_amount: number;
  mid_amount: number;
  to_amount: number;
  markup_amount: number;
  rate: number;
  markup_bps: number;
  /** `auto` when funds were converted as they became available. */
  kind: "manual" | "auto";
  quote_id: string | null;
  /** The payment whose funds were converted (auto conversions). */
  source_id: string | null;
  created: string;
}

export interface FxConversionList {
  object: "list";
  data: FxConversion[];
  has_more: boolean;
}

export interface FxReportRow {
  /** The currency received. */
  currency: string;
  conversions: number;
  received: number;
  markup: number;
}

export interface FxReport {
  object: "fx_report";
  days: number;
  data: FxReportRow[];
}

export interface CurrencySettings {
  object: "currency_settings";
  /** The currencies you want to hold money in. The first is where converted money goes. */
  settlement_currencies: string[];
  /** Convert money in other currencies as it becomes available. */
  auto_convert: boolean;
  /** The platform's margin on a conversion, in basis points. */
  fx_markup_bps: number;
}

export interface UpdateCurrencySettingsInput {
  settlement_currencies: string[];
  auto_convert: boolean;
}
