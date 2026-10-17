import { Badge } from "@cutpay/ui";
import { Link, NavLink, Route, Routes } from "react-router";
import { useCart } from "./cart";
import { CartPage } from "./pages/CartPage";
import { OrdersPage } from "./pages/OrdersPage";
import { PayPage } from "./pages/PayPage";
import { SettingsPage } from "./pages/SettingsPage";
import { ShopPage } from "./pages/ShopPage";
import { ThanksPage } from "./pages/ThanksPage";

const navClass = ({ isActive }: { isActive: boolean }) =>
  `text-sm ${isActive ? "font-semibold text-ink" : "text-muted hover:text-ink"}`;

export function App() {
  const cart = useCart();
  return (
    <div className="mx-auto min-h-screen max-w-4xl px-4 py-6">
      <header className="mb-8 flex items-center justify-between border-b border-border pb-4">
        <Link to="/" className="text-lg font-semibold">
          Demo Shop
        </Link>
        <nav aria-label="Main" className="flex items-center gap-5">
          <NavLink to="/" end className={navClass}>
            Shop
          </NavLink>
          <NavLink to="/orders" className={navClass}>
            Orders
          </NavLink>
          <NavLink to="/settings" className={navClass}>
            Settings
          </NavLink>
          <NavLink to="/cart" className={navClass}>
            Cart {cart.count > 0 && <Badge>{cart.count}</Badge>}
          </NavLink>
        </nav>
      </header>
      <Routes>
        <Route path="/" element={<ShopPage />} />
        <Route path="/cart" element={<CartPage />} />
        <Route path="/pay/:orderId" element={<PayPage />} />
        <Route path="/thanks" element={<ThanksPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Routes>
      <p className="mt-12 text-center text-xs text-muted">
        A demo store built on the Cutpay API. Nothing here is real; use test cards only.
      </p>
    </div>
  );
}
