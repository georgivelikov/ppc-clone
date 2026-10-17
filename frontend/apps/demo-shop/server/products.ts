export interface Product {
  id: string;
  name: string;
  description: string;
  /** Minor units (cents). The server prices every order from this list; it never trusts a price from the browser. */
  unitAmount: number;
  currency: "EUR";
}

export const PRODUCTS: readonly Product[] = [
  { id: "mug", name: "Ceramic mug", description: "Hand-glazed, holds 350 ml of anything hot.", unitAmount: 1200, currency: "EUR" },
  { id: "tote", name: "Canvas tote", description: "Heavy cotton, fits a laptop and lunch.", unitAmount: 1850, currency: "EUR" },
  { id: "print", name: "Screen print", description: "A3, two colours, numbered edition.", unitAmount: 4500, currency: "EUR" },
];
