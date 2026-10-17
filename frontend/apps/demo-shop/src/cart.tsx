import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import type { Product } from "./api";

export interface CartLine {
  product: Product;
  quantity: number;
}

interface Cart {
  lines: CartLine[];
  count: number;
  total: number;
  add(product: Product): void;
  setQuantity(productId: string, quantity: number): void;
  clear(): void;
}

const CartContext = createContext<Cart | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>([]);
  const cart = useMemo<Cart>(
    () => ({
      lines,
      count: lines.reduce((sum, line) => sum + line.quantity, 0),
      total: lines.reduce((sum, line) => sum + line.product.unitAmount * line.quantity, 0),
      add: (product) =>
        setLines((current) =>
          current.some((line) => line.product.id === product.id)
            ? current.map((line) => (line.product.id === product.id ? { ...line, quantity: Math.min(line.quantity + 1, 10) } : line))
            : [...current, { product, quantity: 1 }],
        ),
      setQuantity: (productId, quantity) =>
        setLines((current) =>
          quantity <= 0
            ? current.filter((line) => line.product.id !== productId)
            : current.map((line) => (line.product.id === productId ? { ...line, quantity: Math.min(quantity, 10) } : line)),
        ),
      clear: () => setLines([]),
    }),
    [lines],
  );
  return <CartContext.Provider value={cart}>{children}</CartContext.Provider>;
}

export function useCart(): Cart {
  const cart = useContext(CartContext);
  if (!cart) throw new Error("useCart must be used inside CartProvider");
  return cart;
}
