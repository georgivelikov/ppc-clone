import { ApiError, type AssignableRole, type TeamInvite, type TeamMember } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { Dialog } from "../components/Dialog";
import { SettingsTabs } from "../layout/SettingsTabs";

const ROLE_ABOUT: Record<string, string> = {
  owner: "Created the business. Cannot be changed or removed.",
  admin: "Everything, including the team and payouts.",
  developer: "API keys, webhooks, links and logs. Cannot refund or pay out.",
  viewer: "Reads payments, balances and payouts. Changes nothing.",
  support: "Reads payments and customers, and can refund.",
};

/** What the signed-in role may give: owners any role, admins every role except admin. */
function grantable(role: string): AssignableRole[] {
  return role === "owner" ? ["admin", "developer", "viewer", "support"] : ["developer", "viewer", "support"];
}

function canManage(actorRole: string, targetRole: string): boolean {
  if (targetRole === "owner") return false;
  return actorRole === "owner" || (actorRole === "admin" && targetRole !== "admin");
}

const label = (role: string) => role.charAt(0).toUpperCase() + role.slice(1);

/** The people who can sign in to the business, what each may do, and the invitations waiting for an answer. */
export function TeamPage() {
  const { role, user } = useSession();
  const queryClient = useQueryClient();
  const [inviting, setInviting] = useState(false);
  const [sent, setSent] = useState<TeamInvite | null>(null);
  const allowed = role === "owner" || role === "admin";
  const members = useQuery({ queryKey: ["team-members"], queryFn: () => api.listTeamMembers(), enabled: allowed });
  const invites = useQuery({ queryKey: ["team-invites"], queryFn: () => api.listTeamInvites(), enabled: allowed });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["team-members"] });
    void queryClient.invalidateQueries({ queryKey: ["team-invites"] });
  };
  const change = useMutation({
    mutationFn: (v: { userId: string; role: AssignableRole }) => api.changeTeamMemberRole(v.userId, v.role),
    onSuccess: refresh,
  });
  const remove = useMutation({ mutationFn: (userId: string) => api.removeTeamMember(userId), onSuccess: refresh });
  const revoke = useMutation({ mutationFn: (id: string) => api.revokeTeamInvite(id), onSuccess: refresh });
  const error = [change, remove, revoke].find((m) => m.isError)?.error;

  if (!allowed) {
    return (
      <div className="mx-auto max-w-3xl space-y-6">
        <h1 className="text-2xl font-semibold">Settings</h1>
        <SettingsTabs />
        <Alert>Only owners and admins can see the team.</Alert>
      </div>
    );
  }

  const pending = invites.data?.data.filter((i) => i.status === "pending") ?? [];
  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <Card>
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="font-semibold">Team</h2>
            <p className="mt-1 text-sm text-muted">Everyone who can sign in to this business, and what they may do.</p>
          </div>
          <Button type="button" onClick={() => setInviting(true)}>
            Invite someone
          </Button>
        </div>
        {error && <Alert className="mt-3">{error instanceof ApiError ? error.message : "Something went wrong."}</Alert>}
        {members.isError && <Alert className="mt-3">Could not load the team.</Alert>}
        <ul className="mt-4 divide-y divide-border" aria-label="Team members">
          {members.data?.data.map((m: TeamMember) => (
            <li key={m.user_id} className="flex flex-wrap items-center justify-between gap-3 py-3" data-testid={`member-${m.email}`}>
              <div>
                <p className="text-sm font-medium">
                  {m.full_name} {m.user_id === user.id && <span className="text-muted">(you)</span>}
                </p>
                <p className="text-xs text-muted">{m.email}</p>
              </div>
              <div className="flex items-center gap-2">
                {canManage(role, m.role) && m.user_id !== user.id ? (
                  <>
                    <select
                      aria-label={`Role of ${m.full_name}`}
                      className="rounded-md border border-border bg-surface px-2 py-1 text-sm"
                      value={m.role}
                      onChange={(event) => change.mutate({ userId: m.user_id, role: event.target.value as AssignableRole })}
                    >
                      {grantable(role)
                        .concat(grantable(role).includes(m.role as AssignableRole) ? [] : [m.role as AssignableRole])
                        .map((r) => (
                          <option key={r} value={r}>
                            {label(r)}
                          </option>
                        ))}
                    </select>
                    <Button
                      type="button"
                      variant="secondary"
                      aria-label={`Remove ${m.full_name}`}
                      onClick={() => remove.mutate(m.user_id)}
                    >
                      Remove
                    </Button>
                  </>
                ) : (
                  <Badge>{label(m.role)}</Badge>
                )}
              </div>
            </li>
          ))}
        </ul>
      </Card>

      {pending.length > 0 && (
        <Card>
          <h2 className="font-semibold">Waiting for an answer</h2>
          <ul className="mt-3 divide-y divide-border" aria-label="Pending invitations">
            {pending.map((i) => (
              <li key={i.id} className="flex items-center justify-between gap-3 py-3" data-testid={`invite-${i.email}`}>
                <div>
                  <p className="text-sm font-medium">{i.email}</p>
                  <p className="text-xs text-muted">
                    {label(i.role)}, invited by {i.invited_by}
                  </p>
                </div>
                <Button type="button" variant="secondary" onClick={() => revoke.mutate(i.id)}>
                  Cancel invitation
                </Button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card>
        <h2 className="font-semibold">What each role can do</h2>
        <dl className="mt-3 space-y-2 text-sm">
          {Object.entries(ROLE_ABOUT).map(([r, about]) => (
            <div key={r} className="flex gap-3">
              <dt className="w-24 shrink-0 font-medium">{label(r)}</dt>
              <dd className="text-muted">{about}</dd>
            </div>
          ))}
        </dl>
      </Card>

      {inviting && (
        <InviteDialog
          role={role}
          onClose={() => setInviting(false)}
          onSent={(invite) => {
            setInviting(false);
            setSent(invite);
            refresh();
          }}
        />
      )}
      {sent && <LinkDialog invite={sent} onClose={() => setSent(null)} />}
    </div>
  );
}

function InviteDialog({ role, onClose, onSent }: { role: string; onClose: () => void; onSent: (i: TeamInvite) => void }) {
  const [email, setEmail] = useState("");
  const [chosen, setChosen] = useState<AssignableRole>("developer");
  const [problem, setProblem] = useState<string | null>(null);
  const send = useMutation({
    mutationFn: () => api.inviteTeamMember(email.trim(), chosen),
    onSuccess: onSent,
    onError: (e) => setProblem(e instanceof ApiError ? e.message : "Something went wrong."),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return setProblem("Enter a valid email");
    send.mutate();
  };
  return (
    <Dialog title="Invite someone" onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="invite-email">Email</Label>
          <Input id="invite-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="invite-role">Role</Label>
          <select
            id="invite-role"
            className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            value={chosen}
            onChange={(e) => setChosen(e.target.value as AssignableRole)}
          >
            {grantable(role).map((r) => (
              <option key={r} value={r}>
                {label(r)}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-muted">{ROLE_ABOUT[chosen]}</p>
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={send.isPending}>
            Send invitation
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** Email delivery arrives with the notifications service; until then the link is handed over here, once. */
function LinkDialog({ invite, onClose }: { invite: TeamInvite; onClose: () => void }) {
  const link = `${window.location.origin}${invite.accept_path ?? ""}`;
  return (
    <Dialog title="Invitation ready" onClose={onClose}>
      <p className="text-sm">
        Send this link to {invite.email}. It works once, for 7 days, and is not shown again.
      </p>
      <input
        readOnly
        aria-label="Invitation link"
        className="mt-3 w-full rounded-md border border-border bg-surface px-3 py-2 font-mono text-xs"
        value={link}
        onFocus={(e) => e.currentTarget.select()}
      />
      <div className="mt-4 flex justify-end">
        <Button type="button" onClick={onClose}>
          Done
        </Button>
      </div>
    </Dialog>
  );
}
