import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Input, Label } from "@cutpay/ui";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api } from "../api";
import { AuthLayout } from "./AuthLayout";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Something went wrong. Please try again.");

/** Asks for the email address; the answer is the same whether or not there is an account. */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) return setProblem("Enter a valid email");
    setBusy(true);
    try {
      await api.forgotPassword(email.trim());
      setSent(true);
    } catch (error) {
      setProblem(message(error));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Forgot your password?" subtitle="We will email you a link to choose a new one.">
      {sent ? (
        <Alert tone="info" role="status">
          If there is an account for {email.trim()}, an email with a link is on its way. The link works for one hour.
        </Alert>
      ) : (
        <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4">
          {problem && <Alert>{problem}</Alert>}
          <div>
            <Label htmlFor="forgot-email">Email</Label>
            <Input id="forgot-email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <Button type="submit" className="w-full" loading={busy}>
            Send the link
          </Button>
        </form>
      )}
      <p className="mt-6 text-center text-sm text-muted">
        <Link to="/login" className="font-medium text-brand-600 hover:underline">
          Back to sign in
        </Link>
      </p>
    </AuthLayout>
  );
}

/** Where the emailed link leads. */
export function ResetPasswordPage() {
  const token = useSearchParams()[0].get("token") ?? "";
  const navigate = useNavigate();
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    if (password.length < 10) return setProblem("Password must be at least 10 characters");
    setBusy(true);
    try {
      await api.resetPassword(token, password);
      void navigate("/login", { replace: true, state: { notice: "Your password was changed. Sign in with the new one." } });
    } catch (error) {
      setProblem(message(error));
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Choose a new password" subtitle="You will be signed out everywhere else.">
      <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4">
        {problem && <Alert>{problem}</Alert>}
        <div>
          <Label htmlFor="reset-password">New password</Label>
          <Input id="reset-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <Button type="submit" className="w-full" loading={busy}>
          Change password
        </Button>
      </form>
    </AuthLayout>
  );
}

/** Confirms the address as soon as it opens. */
export function VerifyEmailPage() {
  const token = useSearchParams()[0].get("token") ?? "";
  const [state, setState] = useState<"working" | "done" | "failed">("working");
  const [problem, setProblem] = useState("");
  const started = useRef(false);

  useEffect(() => {
    // The link works once, so a second request (React runs effects twice in development) must not be sent.
    if (started.current) return;
    started.current = true;
    api
      .verifyEmail(token)
      .then(() => setState("done"))
      .catch((error: unknown) => {
        setProblem(message(error));
        setState("failed");
      });
  }, [token]);

  return (
    <AuthLayout title="Confirm your email" subtitle={state === "working" ? "One moment…" : ""}>
      {state === "done" && <Alert tone="info" role="status">Your email address is confirmed. Thank you.</Alert>}
      {state === "failed" && <Alert>{problem}</Alert>}
      <p className="mt-6 text-center text-sm text-muted">
        <Link to="/" className="font-medium text-brand-600 hover:underline">
          Go to the dashboard
        </Link>
      </p>
    </AuthLayout>
  );
}
