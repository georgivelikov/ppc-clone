import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { dashboardResources, setupI18n } from "@cutpay/i18n";
import { afterEach, beforeEach } from "vitest";

// Pages that load several things at once can take longer than a second on a busy machine.
configure({ asyncUtilTimeout: 4000 });

// Every test starts in English, whatever an earlier one switched to.
beforeEach(() => {
  setupI18n({ resources: dashboardResources, locale: "en", defaultNamespace: "dashboard" });
});

// Testing Library only unmounts automatically when Vitest globals are on; we keep them off.
afterEach(cleanup);
