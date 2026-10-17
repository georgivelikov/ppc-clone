import { Route, Routes } from "react-router";
import { CheckoutPage } from "./CheckoutPage";
import { Shell } from "./Shell";

export function App() {
  return (
    <Routes>
      <Route path="/c/:secret" element={<CheckoutPage />} />
      <Route
        path="*"
        element={
          <Shell>
            <h1 className="text-xl font-semibold">Payment link not found</h1>
            <p className="mt-2 text-sm text-muted">Check the link you were sent and try again.</p>
          </Shell>
        }
      />
    </Routes>
  );
}
