import type {
  AuthResponse,
  LoginInput,
  MeResponse,
  Merchant,
  SignupInput,
  UpdateMerchantInput,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly type: string,
    readonly code: string,
    message: string,
    readonly param?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface ApiClientOptions {
  /** Base URL of the gateway. Empty means same origin (the Vite dev server proxies to the gateway). */
  baseUrl?: string;
  fetch?: typeof fetch;
  /** Called when the session can no longer be refreshed, so the app can send the user to the login page. */
  onSessionExpired?: () => void;
}

/**
 * Thin fetch wrapper that keeps the access token in memory (never localStorage, so XSS cannot read it)
 * and transparently renews it using the HttpOnly refresh cookie.
 */
export class ApiClient {
  private accessToken: string | null = null;
  private refreshing: Promise<AuthResponse | null> | null = null;
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;
  private readonly onSessionExpired?: () => void;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? "";
    this.fetchImpl = options.fetch ?? ((...args) => fetch(...args));
    this.onSessionExpired = options.onSessionExpired;
  }

  // --- auth ---------------------------------------------------------------

  async signup(input: SignupInput): Promise<AuthResponse> {
    return this.storeSession(await this.request<AuthResponse>("POST", "/auth/signup", input, { auth: false }));
  }

  async login(input: LoginInput): Promise<AuthResponse> {
    return this.storeSession(await this.request<AuthResponse>("POST", "/auth/login", input, { auth: false }));
  }

  /** Restores a session from the refresh cookie, e.g. after a page reload. Resolves null if there is none. */
  restoreSession(): Promise<AuthResponse | null> {
    // Single-flight: concurrent callers (React StrictMode, parallel 401s) share one request, because
    // refresh tokens are single-use and a second concurrent call would look like token theft.
    this.refreshing ??= this.doRefresh().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try {
      await this.request<void>("POST", "/auth/logout", undefined, { auth: false });
    } finally {
      this.accessToken = null;
    }
  }

  // --- resources ----------------------------------------------------------

  me(): Promise<MeResponse> {
    return this.request("GET", "/me");
  }

  getMerchant(): Promise<Merchant> {
    return this.request("GET", "/v1/merchant");
  }

  updateMerchant(input: UpdateMerchantInput): Promise<Merchant> {
    return this.request("PATCH", "/v1/merchant", input);
  }

  // --- internals ----------------------------------------------------------

  private storeSession(session: AuthResponse): AuthResponse {
    this.accessToken = session.access_token;
    return session;
  }

  private async doRefresh(): Promise<AuthResponse | null> {
    try {
      return this.storeSession(await this.request<AuthResponse>("POST", "/auth/refresh", undefined, { auth: false }));
    } catch (error) {
      this.accessToken = null;
      if (error instanceof ApiError && error.status === 401) {
        return null;
      }
      throw error;
    }
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    options: { auth?: boolean; retried?: boolean } = {},
  ): Promise<T> {
    const useAuth = options.auth ?? true;
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (useAuth && this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;

    const response = await this.fetchImpl(this.baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "include",
    });

    if (response.status === 401 && useAuth && !options.retried) {
      const session = await this.restoreSession();
      if (session) return this.request<T>(method, path, body, { ...options, retried: true });
      this.onSessionExpired?.();
    }

    if (!response.ok) throw await toApiError(response);
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const payload = (await response.json()) as {
      error?: { type?: string; code?: string; message?: string; param?: string };
    };
    const e = payload.error;
    return new ApiError(
      response.status,
      e?.type ?? "api_error",
      e?.code ?? "unknown",
      e?.message ?? response.statusText,
      e?.param,
    );
  } catch {
    return new ApiError(response.status, "api_error", "unknown", response.statusText || "Request failed");
  }
}
