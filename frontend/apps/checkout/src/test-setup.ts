import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { checkoutResources, setupI18n } from "@cutpay/i18n";
import { afterEach, beforeEach } from "vitest";

// Every test starts in English, whatever an earlier one switched to.
beforeEach(() => {
  setupI18n({ resources: checkoutResources, locale: "en", defaultNamespace: "checkout" });
});

// Testing Library only unmounts automatically when Vitest globals are on; we keep them off.
afterEach(cleanup);
