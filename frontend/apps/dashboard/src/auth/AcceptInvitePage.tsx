import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Input, Label } from "@cutpay/ui";
import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api } from "../api";
import { AuthLayout } from "./AuthLayout";
import { useAuth } from "./AuthProvider";

/** Where an invitation link leads: the person sees who invited them and joins, with a new password or their own. */
export function AcceptInvitePage() {
  const token = useSearchParams()[0].get("token") ?? "";
  const { acceptInvite } = useAuth();
  const navigate = useNavigate();
  const preview = useQuery({ queryKey: ["invite", token], queryFn: () => api.previewInvite(token), enabled: token !== "", retry: false });
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (token === "" || preview.isError) {
    return (
      <AuthLayout title="Invitation" subtitle="This link cannot be used.">
        <Alert>
          {preview.error instanceof ApiError ? preview.error.message : "The link is incomplete."} Ask the person who invited you for a new one.
        </Alert>
        <p className="mt-6 text-center text-sm text-muted">
          <Link to="/login" className="font-medium text-brand-600 hover:underline">
            Sign in
          </Link>
        </p>
      </AuthLayout>
    );
  }
  if (!preview.data) return <AuthLayout title="Invitation" subtitle="Checking the link…">{null}</AuthLayout>;

  const invite = preview.data;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    if (!invite.account_exists && fullName.trim() === "") return setProblem("Enter your name");
    if (!invite.account_exists && password.length < 10) return setProblem("Password must be at least 10 characters");
    setBusy(true);
    try {
      await acceptInvite(token, { full_name: fullName.trim() || undefined, password });
      void navigate("/", { replace: true });
    } catch (error) {
      setProblem(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={`Join ${invite.merchant_name}`} subtitle={`You were invited as ${invite.role}.`}>
      <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4">
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="invite-email">Email</Label>
          <Input id="invite-email" value={invite.email} readOnly />
        </div>
        {!invite.account_exists && (
          <div>
            <Label htmlFor="invite-name">Your name</Label>
            <Input id="invite-name" autoComplete="name" value={fullName} onChange={(e) => setFullName(e.target.value)} />
          </div>
        )}
        <div>
          <Label htmlFor="invite-password">{invite.account_exists ? "Your password" : "Choose a password"}</Label>
          <Input
            id="invite-password"
            type="password"
            autoComplete={invite.account_exists ? "current-password" : "new-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {invite.account_exists && <p className="mt-1 text-xs text-muted">You already have a Cutpay account with this email.</p>}
        </div>
        <Button type="submit" className="w-full" loading={busy}>
          Join the team
        </Button>
      </form>
    </AuthLayout>
  );
}
