import { ApiError } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api } from "../api";
import { useAuth, useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];

const schema = z.object({
  display_name: z.string().trim().min(1, "Enter a business name").max(160),
  default_currency: z.string().length(3),
});
type Values = z.infer<typeof schema>;

export function SettingsPage() {
  const { merchant, role } = useSession();
  const { setMerchant } = useAuth();
  const [saved, setSaved] = useState(false);
  const canEdit = role === "owner" || role === "admin";

  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
    reset,
  } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { display_name: merchant.display_name, default_currency: merchant.default_currency },
  });

  const mutation = useMutation({
    mutationFn: (values: Values) => api.updateMerchant(values),
    onSuccess: (updated, values) => {
      setMerchant(updated);
      reset(values);
      setSaved(true);
    },
  });

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <Card>
        <form
          noValidate
          className="space-y-4"
          onSubmit={handleSubmit((values) => {
            setSaved(false);
            mutation.mutate(values);
          })}
        >
          {mutation.error && (
            <Alert>{mutation.error instanceof ApiError ? mutation.error.message : "Could not save changes."}</Alert>
          )}
          {saved && (
            <p role="status" className="rounded-lg bg-success-bg px-3 py-2 text-sm text-success">
              Changes saved.
            </p>
          )}
          <div>
            <Label htmlFor="display_name">Business name</Label>
            <Input
              id="display_name"
              disabled={!canEdit}
              aria-invalid={!!errors.display_name}
              {...register("display_name")}
            />
            <FieldError>{errors.display_name?.message}</FieldError>
          </div>
          <div>
            <Label htmlFor="default_currency">Settlement currency</Label>
            <select
              id="default_currency"
              disabled={!canEdit}
              className="h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm"
              {...register("default_currency")}
            >
              {CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Country</Label>
            <p className="text-sm text-muted">{merchant.country} (set at sign-up)</p>
          </div>
          {canEdit ? (
            <Button type="submit" loading={mutation.isPending} disabled={!isDirty}>
              Save changes
            </Button>
          ) : (
            <p className="text-sm text-muted">Only owners and admins can edit the business profile.</p>
          )}
        </form>
      </Card>
    </div>
  );
}
