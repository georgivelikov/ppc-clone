import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";

const STORAGE_KEY = "cutpay.testMode";

interface TestModeValue {
  testMode: boolean;
  setTestMode(value: boolean): void;
}

const TestModeContext = createContext<TestModeValue | null>(null);

function readStored(): boolean {
  try {
    // Default to test mode: a new merchant has no live access until onboarding (phase 8) is complete.
    return localStorage.getItem(STORAGE_KEY) !== "false";
  } catch {
    return true;
  }
}

/**
 * The Test/Live switch. For now it only changes what the UI shows; from phase 1 it decides which
 * data the dashboard requests (test and live data are stored separately via a `livemode` flag).
 */
export function TestModeProvider({ children }: { children: ReactNode }) {
  const [testMode, setState] = useState(readStored);

  const setTestMode = useCallback((value: boolean) => {
    setState(value);
    try {
      localStorage.setItem(STORAGE_KEY, String(value));
    } catch {
      // Storage can be blocked (private windows); the toggle then lasts until reload.
    }
  }, []);

  const value = useMemo(() => ({ testMode, setTestMode }), [testMode, setTestMode]);
  return <TestModeContext.Provider value={value}>{children}</TestModeContext.Provider>;
}

export function useTestMode(): TestModeValue {
  const context = useContext(TestModeContext);
  if (!context) throw new Error("useTestMode must be used inside <TestModeProvider>");
  return context;
}
