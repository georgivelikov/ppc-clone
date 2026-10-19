import type { AcceptInviteInput, AuthResponse, LoginInput, Merchant, Role, SignupInput, User } from "@cutpay/api-client";
import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, onSessionExpired } from "../api";

type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; user: User; merchant: Merchant; role: Role };

interface AuthContextValue {
  state: AuthState;
  login(input: LoginInput): Promise<void>;
  signup(input: SignupInput): Promise<void>;
  /** Joins a team from an invitation link and signs in. */
  acceptInvite(token: string, input: AcceptInviteInput): Promise<void>;
  logout(): Promise<void>;
  /** Replaces the cached merchant after the profile was edited. */
  setMerchant(merchant: Merchant): void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

const toAuthenticated = (r: AuthResponse): AuthState => ({
  status: "authenticated",
  user: r.user,
  merchant: r.merchant,
  role: r.role,
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });
  const queryClient = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    api
      .restoreSession()
      .then((session) => {
        if (!cancelled) setState(session ? toAuthenticated(session) : { status: "anonymous" });
      })
      .catch(() => {
        if (!cancelled) setState({ status: "anonymous" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    onSessionExpired(() => {
      queryClient.clear();
      setState({ status: "anonymous" });
    });
    return () => onSessionExpired(undefined);
  }, [queryClient]);

  const login = useCallback(async (input: LoginInput) => {
    setState(toAuthenticated(await api.login(input)));
  }, []);

  const signup = useCallback(async (input: SignupInput) => {
    setState(toAuthenticated(await api.signup(input)));
  }, []);

  const acceptInvite = useCallback(async (token: string, input: AcceptInviteInput) => {
    setState(toAuthenticated(await api.acceptInvite(token, input)));
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      queryClient.clear();
      setState({ status: "anonymous" });
    }
  }, [queryClient]);

  const setMerchant = useCallback((merchant: Merchant) => {
    setState((current) => (current.status === "authenticated" ? { ...current, merchant } : current));
  }, []);

  const value = useMemo(
    () => ({ state, login, signup, acceptInvite, logout, setMerchant }),
    [state, login, signup, acceptInvite, logout, setMerchant],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside <AuthProvider>");
  return context;
}

/** For pages rendered behind <RequireAuth>, where the user is guaranteed to exist. */
export function useSession(): Extract<AuthState, { status: "authenticated" }> {
  const { state } = useAuth();
  if (state.status !== "authenticated") throw new Error("useSession used outside an authenticated route");
  return state;
}
