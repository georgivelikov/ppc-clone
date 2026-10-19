import { ApiError, type Session } from "@cutpay/api-client";
import { Alert, Badge, Button, Card, Input, Label } from "@cutpay/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api } from "../api";
import { SettingsTabs } from "../layout/SettingsTabs";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong.");

/** What the signed-in person controls about their own sign-in: email, two-factor codes, password and sessions. */
export function AccountSecurityPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <EmailCard />
      <TwoFactorCard />
      <PasswordCard />
      <SessionsCard />
    </div>
  );
}

function useOverview() {
  return useQuery({ queryKey: ["security"], queryFn: () => api.getSecurity() });
}

function EmailCard() {
  const overview = useOverview();
  const resend = useMutation({ mutationFn: () => api.resendVerification() });
  return (
    <Card>
      <h2 className="font-semibold">Email address</h2>
      {overview.data?.email_verified ? (
        <p className="mt-2 text-sm text-muted" data-testid="email-status">
          Confirmed <Badge>Verified</Badge>
        </p>
      ) : (
        <div className="mt-2 flex flex-wrap items-center gap-3 text-sm">
          <span data-testid="email-status">Not confirmed yet. We sent you a link.</span>
          <Button type="button" variant="secondary" loading={resend.isPending} onClick={() => resend.mutate()}>
            Send it again
          </Button>
          {resend.isSuccess && <span role="status" className="text-muted">Sent.</span>}
          {resend.isError && <Alert>{message(resend.error)}</Alert>}
        </div>
      )}
    </Card>
  );
}

function TwoFactorCard() {
  const queryClient = useQueryClient();
  const overview = useOverview();
  const [setup, setSetup] = useState<{ secret: string; otpauth_uri: string } | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["security"] });

  const start = useMutation({ mutationFn: () => api.startTotp(), onSuccess: setSetup, onError: (e) => setProblem(message(e)) });
  const enable = useMutation({
    mutationFn: () => api.enableTotp(code.trim()),
    onSuccess: (recovery) => {
      setCodes(recovery);
      setSetup(null);
      setCode("");
      refresh();
    },
    onError: (e) => setProblem(message(e)),
  });
  const disable = useMutation({
    mutationFn: () => api.disableTotp(password, code.trim()),
    onSuccess: () => {
      setPassword("");
      setCode("");
      refresh();
    },
    onError: (e) => setProblem(message(e)),
  });

  const on = overview.data?.totp_enabled ?? false;
  const submit = (action: () => void) => (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    action();
  };

  return (
    <Card>
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="font-semibold">Two-factor sign-in</h2>
          <p className="mt-1 text-sm text-muted">
            A code from an authenticator app, asked for after your password. Owners and admins need it before creating live API keys.
          </p>
        </div>
        <Badge data-testid="totp-status">{on ? "On" : "Off"}</Badge>
      </div>
      {problem && <Alert className="mt-3">{problem}</Alert>}

      {codes && (
        <div className="mt-4 rounded-md border border-border bg-canvas p-4" role="status">
          <p className="text-sm font-medium">Save these recovery codes now. Each works once, and they are not shown again.</p>
          <ul className="mt-2 grid grid-cols-2 gap-1 font-mono text-sm" aria-label="Recovery codes">
            {codes.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
          <Button type="button" className="mt-3" onClick={() => setCodes(null)}>
            I have saved them
          </Button>
        </div>
      )}

      {!on && !setup && !codes && (
        <Button type="button" className="mt-4" loading={start.isPending} onClick={() => { setProblem(null); start.mutate(); }}>
          Set up
        </Button>
      )}

      {!on && setup && (
        <form onSubmit={submit(() => enable.mutate())} noValidate className="mt-4 space-y-3">
          <p className="text-sm">
            Add this key to your authenticator app (choose "enter a setup key"), then type the six-digit code it shows.
          </p>
          <p className="rounded-md bg-canvas px-3 py-2 font-mono text-sm break-all" data-testid="totp-secret">
            {setup.secret}
          </p>
          <a href={setup.otpauth_uri} className="text-sm text-brand-700 hover:underline">
            Open in an authenticator app on this device
          </a>
          <div>
            <Label htmlFor="totp-code">Code from the app</Label>
            <Input id="totp-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
          </div>
          <Button type="submit" loading={enable.isPending}>
            Turn on
          </Button>
        </form>
      )}

      {on && !codes && (
        <form onSubmit={submit(() => disable.mutate())} noValidate className="mt-4 space-y-3">
          <p className="text-sm text-muted">{overview.data?.recovery_codes_left} recovery codes left.</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <Label htmlFor="off-password">Password</Label>
              <Input id="off-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="off-code">Code or recovery code</Label>
              <Input id="off-code" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
            </div>
          </div>
          <Button type="submit" variant="secondary" loading={disable.isPending}>
            Turn off
          </Button>
        </form>
      )}
    </Card>
  );
}

function PasswordCard() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const change = useMutation({
    mutationFn: () => api.changePassword(current, next),
    onSuccess: () => {
      setCurrent("");
      setNext("");
    },
    onError: (e) => setProblem(message(e)),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    if (next.length < 10) return setProblem("New password must be at least 10 characters");
    change.mutate();
  };
  return (
    <Card>
      <h2 className="font-semibold">Password</h2>
      <p className="mt-1 text-sm text-muted">Changing it signs you out everywhere else.</p>
      <form onSubmit={submit} noValidate className="mt-3 grid gap-3 sm:grid-cols-2" aria-label="Change password">
        {problem && <Alert className="sm:col-span-2">{problem}</Alert>}
        <div>
          <Label htmlFor="pw-current">Current password</Label>
          <Input id="pw-current" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="pw-new">New password</Label>
          <Input id="pw-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
        </div>
        <div className="sm:col-span-2">
          <Button type="submit" loading={change.isPending}>
            Change password
          </Button>
          {change.isSuccess && <span role="status" className="ml-3 text-sm text-muted">Password changed.</span>}
        </div>
      </form>
    </Card>
  );
}

function SessionsCard() {
  const queryClient = useQueryClient();
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => api.listSessions() });
  const end = useMutation({
    mutationFn: (id: string) => api.endSession(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["sessions"] }),
  });
  return (
    <Card>
      <h2 className="font-semibold">Where you are signed in</h2>
      {end.isError && <Alert className="mt-3">{message(end.error)}</Alert>}
      <ul className="mt-3 divide-y divide-border" aria-label="Sessions">
        {sessions.data?.map((s: Session) => (
          <li key={s.id} className="flex items-center justify-between gap-3 py-3" data-testid="session">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{s.user_agent ?? "Unknown device"}</p>
              <p className="text-xs text-muted">
                Started {new Date(s.started_at).toLocaleString()} · last active {new Date(s.last_active).toLocaleString()}
              </p>
            </div>
            {s.current ? (
              <Badge>This device</Badge>
            ) : (
              <Button type="button" variant="secondary" onClick={() => end.mutate(s.id)}>
                Sign out
              </Button>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}
