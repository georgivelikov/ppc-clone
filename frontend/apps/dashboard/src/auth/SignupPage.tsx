import { ApiError } from "@cutpay/api-client";
import { Alert, Button, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router";
import { z } from "zod";
import { AuthLayout } from "./AuthLayout";
import { useAuth } from "./AuthProvider";

export const COUNTRIES = [
  { code: "BG", name: "Bulgaria" },
  { code: "GR", name: "Greece" },
  { code: "RO", name: "Romania" },
  { code: "PL", name: "Poland" },
  { code: "HU", name: "Hungary" },
  { code: "CZ", name: "Czechia" },
  { code: "DE", name: "Germany" },
  { code: "IE", name: "Ireland" },
  { code: "GB", name: "United Kingdom" },
] as const;

const schema = z.object({
  full_name: z.string().trim().min(1, "Enter your name").max(120),
  business_name: z.string().trim().min(1, "Enter your business name").max(160),
  country: z.string().length(2, "Choose a country"),
  email: z.string().min(1, "Enter your email").email("Enter a valid email"),
  password: z.string().min(10, "Use at least 10 characters").max(128),
});
type Values = z.infer<typeof schema>;

export function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { country: "BG" } });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await signup(values);
      void navigate("/", { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.param && error.param in schema.shape) {
        setError(error.param as keyof Values, { message: error.message });
      } else {
        setFormError(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
      }
    }
  });

  return (
    <AuthLayout title="Create your account" subtitle="Start accepting (pretend) payments in minutes.">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        {formError && <Alert>{formError}</Alert>}
        <div>
          <Label htmlFor="full_name">Your name</Label>
          <Input id="full_name" autoComplete="name" aria-invalid={!!errors.full_name} {...register("full_name")} />
          <FieldError>{errors.full_name?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="business_name">Business name</Label>
          <Input
            id="business_name"
            autoComplete="organization"
            aria-invalid={!!errors.business_name}
            {...register("business_name")}
          />
          <FieldError>{errors.business_name?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="country">Country</Label>
          <select
            id="country"
            className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm"
            {...register("country")}
          >
            {COUNTRIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
          <FieldError>{errors.country?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="email">Work email</Label>
          <Input id="email" type="email" autoComplete="email" aria-invalid={!!errors.email} {...register("email")} />
          <FieldError>{errors.email?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            aria-invalid={!!errors.password}
            {...register("password")}
          />
          <FieldError>{errors.password?.message}</FieldError>
        </div>
        <Button type="submit" className="w-full" loading={isSubmitting}>
          Create account
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-muted">
        Already have an account?{" "}
        <Link to="/login" className="font-medium text-brand-600 hover:underline">
          Sign in
        </Link>
      </p>
    </AuthLayout>
  );
}
