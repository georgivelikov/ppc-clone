// The team of a business, invitations and the audit trail (backend/services/identity).

/** The roles that can be given to someone. The owner role belongs to whoever created the business. */
export type AssignableRole = "admin" | "developer" | "viewer" | "support";

export interface TeamMember {
  object: "team_member";
  user_id: string;
  email: string;
  full_name: string;
  role: "owner" | AssignableRole;
  joined_at: string;
  last_login_at: string | null;
}

export interface TeamInvite {
  object: "invitation";
  id: string;
  email: string;
  role: AssignableRole;
  invited_by: string;
  created_at: string;
  expires_at: string;
  status: "pending" | "accepted" | "revoked" | "expired";
  /** Only on the response that created it: the page the invited person opens. */
  accept_path: string | null;
}

export interface InvitePreview {
  merchant_name: string;
  email: string;
  role: AssignableRole;
  /** An existing account joins with its own password instead of making a new one. */
  account_exists: boolean;
}

export interface AcceptInviteInput {
  full_name?: string;
  password: string;
}

export interface AuditEvent {
  object: "audit_event";
  id: number;
  actor_id: string | null;
  actor_email: string | null;
  action: string;
  target_type: string | null;
  target_id: string | null;
  detail: string | null;
  created_at: string;
}

export interface ListOf<T> {
  object: "list";
  data: T[];
}
