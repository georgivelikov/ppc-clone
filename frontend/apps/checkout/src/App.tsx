import { Route, Routes } from "react-router";
import { CardElementFrame } from "./CardElementFrame";
import { CheckoutPage } from "./CheckoutPage";
import { LinkPage, LinkThanksPage } from "./LinkPage";
import { SetupPage } from "./SetupPage";
import { Shell } from "./Shell";

export function App() {
  return (
    <Routes>
      <Route path="/c/:secret" element={<CheckoutPage />} />
      <Route path="/s/:secret" element={<SetupPage />} />
      <Route path="/l/:slug" element={<LinkPage />} />
      <Route path="/l/:slug/thanks" element={<LinkThanksPage />} />
      <Route path="/elements/card" element={<CardElementFrame />} />
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
