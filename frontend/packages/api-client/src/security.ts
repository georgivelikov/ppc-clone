// A person's own sign-in security and email choices (backend/services/identity, backend/services/notifications).

export interface SecurityOverview {
  object: "security";
  email_verified: boolean;
  totp_enabled: boolean;
  recovery_codes_left: number;
}

export interface TotpSetup {
  /** The key to type into an authenticator app. */
  secret: string;
  /** The same key as an address an app can open. */
  otpauth_uri: string;
}

export interface Session {
  object: "session";
  id: string;
  user_agent: string | null;
  started_at: string;
  last_active: string;
  current: boolean;
}

export interface NotificationPreference {
  template: string;
  enabled: boolean;
}

export interface NotificationPreferences {
  object: "notification_preferences";
  email: string;
  data: NotificationPreference[];
}

export interface NotificationEntry {
  object: "notification";
  id: string;
  recipient: string;
  template: string;
  subject: string;
  status: "queued" | "sent" | "failed" | "dead" | "skipped";
  attempts: number;
  last_error: string | null;
  created_at: string;
  sent_at: string | null;
}
