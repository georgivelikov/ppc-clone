import type { AuditEvent } from "@cutpay/api-client";
import { Alert, Button, Card } from "@cutpay/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";

const PAGE = 25;

const ACTIONS: Record<string, string> = {
  "team.invite_sent": "Invited someone to the team",
  "team.invite_revoked": "Cancelled an invitation",
  "team.invite_accepted": "Joined the team",
  "team.role_changed": "Changed a role",
  "team.member_removed": "Removed a team member",
  "api_key.created": "Created an API key",
  "api_key.rolled": "Rolled an API key",
  "api_key.revoked": "Revoked an API key",
  "merchant.updated": "Edited the business profile",
};

/** The record of who did what, newest first. */
export function SecurityPage() {
  const { role } = useSession();
  const allowed = role === "owner" || role === "admin";
  const events = useInfiniteQuery({
    queryKey: ["audit-events"],
    enabled: allowed,
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.listAuditEvents({ before: pageParam, limit: PAGE }),
    getNextPageParam: (last) => (last.data.length === PAGE ? last.data[last.data.length - 1]?.id : undefined),
  });
  const rows: AuditEvent[] = events.data?.pages.flatMap((p) => p.data) ?? [];

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      {!allowed ? (
        <Alert>Only owners and admins can see the activity log.</Alert>
      ) : (
        <Card>
          <h2 className="font-semibold">Activity</h2>
          <p className="mt-1 text-sm text-muted">Changes to the team, API keys and business profile. Entries are never edited.</p>
          {events.isError && <Alert className="mt-3">Could not load the activity.</Alert>}
          {events.isSuccess && rows.length === 0 && <p className="mt-4 text-sm text-muted">Nothing yet.</p>}
          <ul className="mt-4 divide-y divide-border" aria-label="Activity">
            {rows.map((e) => (
              <li key={e.id} className="py-3" data-testid="audit-event">
                <p className="text-sm font-medium">{ACTIONS[e.action] ?? e.action}</p>
                <p className="text-xs text-muted">
                  {e.actor_email ?? "System"} · {new Date(e.created_at).toLocaleString()}
                  {e.detail ? ` · ${e.detail}` : ""}
                </p>
              </li>
            ))}
          </ul>
          {events.hasNextPage && (
            <Button type="button" variant="secondary" className="mt-3" loading={events.isFetchingNextPage} onClick={() => void events.fetchNextPage()}>
              Show older
            </Button>
          )}
        </Card>
      )}
    </div>
  );
}
