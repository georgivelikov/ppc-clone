import { ApiError } from "@cutpay/api-client";
import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation, useNavigate } from "react-router";
import { z } from "zod";
import { AuthLayout } from "./AuthLayout";
import { useAuth } from "./AuthProvider";

const schema = z.object({
  email: z.string().min(1, "Enter your email").email("Enter a valid email"),
  password: z.string().min(1, "Enter your password"),
  totp_code: z.string().optional(),
});
type Values = z.infer<typeof schema>;

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [formError, setFormError] = useState<string | null>(null);
  const [needsCode, setNeedsCode] = useState(false);
  const notice = (location.state as { notice?: string } | null)?.notice;
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await login(values);
      const from = (location.state as { from?: string } | null)?.from;
      void navigate(from && from !== "/login" ? from : "/", { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.code === "totp_required") {
        setNeedsCode(true); // the password was right: now ask for the code
        return;
      }
      setFormError(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    }
  });

  return (
    <AuthLayout title="Sign in" subtitle="Welcome back to your Cutpay dashboard.">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {notice && <Alert tone="info" role="status">{notice}</Alert>}
        {formError && <Alert>{formError}</Alert>}
        <div>
          <Label htmlFor="email">Email</Label>
          <Input id="email" type="email" autoComplete="email" aria-invalid={!!errors.email} {...register("email")} />
          <FieldError>{errors.email?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            aria-invalid={!!errors.password}
            {...register("password")}
          />
          <FieldError>{errors.password?.message}</FieldError>
        </div>
        {needsCode && (
          <div>
            <Label htmlFor="totp_code">Code from your authenticator app</Label>
            <Input id="totp_code" inputMode="numeric" autoComplete="one-time-code" autoFocus {...register("totp_code")} />
            <p className="mt-1 text-xs text-muted">Lost your phone? Use one of your recovery codes instead.</p>
          </div>
        )}
        <Button type="submit" className="w-full" loading={isSubmitting}>
          Sign in
        </Button>
      </form>
      <p className="mt-4 text-center text-sm">
        <Link to="/forgot-password" className="text-brand-600 hover:underline">
          Forgot your password?
        </Link>
      </p>
      <p className="mt-6 text-center text-sm text-muted">
        New to Cutpay?{" "}
        <Link to="/signup" className="font-medium text-brand-600 hover:underline">
          Create an account
        </Link>
      </p>
    </AuthLayout>
  );
}
