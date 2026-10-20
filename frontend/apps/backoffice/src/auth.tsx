import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, hasToken, onSessionExpired, setToken, type Session } from "./api";

interface Staff {
  id: string;
  email: string;
  full_name: string;
  role: "admin" | "reviewer";
}

interface AuthValue {
  staff: Staff | null;
  login(email: string, password: string): Promise<void>;
  logout(): void;
}

const AuthContext = createContext<AuthValue | null>(null);

const STAFF_KEY = "cutpay.staff.who";

function stored(): Staff | null {
  try {
    const raw = sessionStorage.getItem(STAFF_KEY);
    return raw && hasToken() ? (JSON.parse(raw) as Staff) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [staff, setStaff] = useState<Staff | null>(stored);

  const logout = useCallback(() => {
    setToken(null);
    try {
      sessionStorage.removeItem(STAFF_KEY);
    } catch {
      // ignore
    }
    setStaff(null);
  }, []);

  useEffect(() => {
    onSessionExpired(() => setStaff(null));
    return () => onSessionExpired(undefined);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const session: Session = await api.login(email, password);
    setToken(session.access_token);
    const who: Staff = { id: session.id, email: session.email, full_name: session.full_name, role: session.role.replace("staff_", "") as Staff["role"] };
    try {
      sessionStorage.setItem(STAFF_KEY, JSON.stringify(who));
    } catch {
      // ignore
    }
    setStaff(who);
  }, []);

  const value = useMemo(() => ({ staff, login, logout }), [staff, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}

export function useStaff(): Staff {
  const { staff } = useAuth();
  if (!staff) throw new Error("useStaff used outside a signed-in route");
  return staff;
}
