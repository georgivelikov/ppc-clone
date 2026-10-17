import { ApiError, type PayoutScheduleInterval } from "@cutpay/api-client";
import { Alert, Button, Card, FieldError, Input, Label } from "@cutpay/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api } from "../api";
import { useSession } from "../auth/AuthProvider";
import { SettingsTabs } from "../layout/SettingsTabs";
import { useTestMode } from "../layout/testMode";
import { normaliseIban } from "../lib/iban";

const CURRENCIES = ["EUR", "RON", "PLN", "HUF", "CZK", "GBP", "USD", "CHF"];
const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const dayLabel = (day: string) => day.charAt(0) + day.slice(1).toLowerCase();

const bankSchema = z.object({
  iban: z
    .string()
    .trim()
    .min(1, "Enter an IBAN")
    .refine((value) => normaliseIban(value) !== null, "This IBAN is not valid. Check the number for typos."),
  holder_name: z.string().trim().min(1, "Enter the account holder's name").max(120),
  currency: z.string().length(3),
});
type BankValues = z.infer<typeof bankSchema>;

const scheduleSchema = z.object({
  schedule: z.enum(["daily", "weekly", "manual"]),
  weekly_anchor: z.string(),
  delay_days: z.number({ error: "Enter a number of days" }).int("Use whole days").min(0, "At least 0").max(30, "At most 30"),
});
type ScheduleValues = z.infer<typeof scheduleSchema>;

const selectClass = "h-10 w-full rounded-lg border border-border bg-surface px-3 text-sm";

function BankAccounts({ livemode, canEdit, defaultCurrency }: { livemode: boolean; canEdit: boolean; defaultCurrency: string }) {
  const queryClient = useQueryClient();
  const accounts = useQuery({ queryKey: ["bank-accounts", livemode], queryFn: () => api.listBankAccounts(livemode) });
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<BankValues>({
    resolver: zodResolver(bankSchema),
    defaultValues: { iban: "", holder_name: "", currency: defaultCurrency },
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["bank-accounts"] });
  const create = useMutation({
    mutationFn: (values: BankValues) =>
      api.createBankAccount(
        { iban: values.iban, holder_name: values.holder_name, currency: values.currency },
        livemode,
      ),
    onSuccess: async () => {
      reset({ iban: "", holder_name: "", currency: defaultCurrency });
      await refresh();
      await queryClient.invalidateQueries({ queryKey: ["payout-preview"] });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteBankAccount(id),
    onSuccess: async () => {
      await refresh();
      await queryClient.invalidateQueries({ queryKey: ["payout-preview"] });
    },
  });

  return (
    <Card className="space-y-4">
      <h2 className="font-semibold">Bank account</h2>
      <p className="text-sm text-muted">Payouts go to the newest bank account in the payout currency.</p>

      {accounts.isError && <Alert>Could not load your bank accounts.</Alert>}
      {accounts.data && accounts.data.data.length > 0 && (
        <ul className="divide-y divide-border rounded-lg border border-border">
          {accounts.data.data.map((account) => (
            <li key={account.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
              <span>
                <span className="font-medium">{account.holder_name}</span>
                <span className="text-muted">
                  {" "}
                  · {account.country}•• •••• {account.iban_last4} · {account.currency}
                </span>
              </span>
              {canEdit && (
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  loading={remove.isPending && remove.variables === account.id}
                  aria-label={`Remove account ending ${account.iban_last4}`}
                  onClick={() => remove.mutate(account.id)}
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {accounts.isSuccess && accounts.data.data.length === 0 && (
        <p className="text-sm text-muted">No bank account yet. Add one to receive payouts.</p>
      )}
      {remove.isError && <Alert>Could not remove the bank account.</Alert>}

      {canEdit ? (
        <form noValidate className="space-y-4 border-t border-border pt-4" onSubmit={handleSubmit((v) => create.mutate(v))}>
          <h3 className="text-sm font-semibold">Add a bank account</h3>
          {create.isError && (
            <Alert>{create.error instanceof ApiError ? create.error.message : "Could not add the bank account."}</Alert>
          )}
          <div>
            <Label htmlFor="iban">IBAN</Label>
            <Input id="iban" autoComplete="off" aria-invalid={!!errors.iban} {...register("iban")} />
            <FieldError>{errors.iban?.message}</FieldError>
          </div>
          <div>
            <Label htmlFor="holder_name">Account holder</Label>
            <Input id="holder_name" autoComplete="off" aria-invalid={!!errors.holder_name} {...register("holder_name")} />
            <FieldError>{errors.holder_name?.message}</FieldError>
          </div>
          <div>
            <Label htmlFor="bank_currency">Currency</Label>
            <select id="bank_currency" className={selectClass} {...register("currency")}>
              {CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </div>
          <Button type="submit" loading={create.isPending}>
            Add bank account
          </Button>
        </form>
      ) : (
        <p className="text-sm text-muted">Only owners and admins can change bank accounts.</p>
      )}
    </Card>
  );
}

function Schedule({ canEdit }: { canEdit: boolean }) {
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState(false);
  const current = useQuery({ queryKey: ["payout-schedule"], queryFn: () => api.getPayoutSchedule() });

  const mutation = useMutation({
    mutationFn: (values: ScheduleValues) =>
      api.updatePayoutSchedule({
        schedule: values.schedule as PayoutScheduleInterval,
        weekly_anchor: values.weekly_anchor,
        delay_days: values.delay_days,
      }),
    onSuccess: async () => {
      setSaved(true);
      await queryClient.invalidateQueries({ queryKey: ["payout-schedule"] });
    },
  });

  if (current.isPending) return <p className="text-sm text-muted">Loading payout schedule…</p>;
  if (current.isError || !current.data) return <Alert>Could not load the payout schedule.</Alert>;

  return (
    <ScheduleForm
      key={`${current.data.schedule}-${current.data.delay_days}-${current.data.weekly_anchor}`}
      initial={{
        schedule: current.data.schedule,
        weekly_anchor: current.data.weekly_anchor,
        delay_days: current.data.delay_days,
      }}
      canEdit={canEdit}
      saving={mutation.isPending}
      saved={saved}
      error={mutation.error}
      onSubmit={(values) => {
        setSaved(false);
        mutation.mutate(values);
      }}
    />
  );
}

function ScheduleForm({ initial, canEdit, saving, saved, error, onSubmit }: {
  initial: ScheduleValues;
  canEdit: boolean;
  saving: boolean;
  saved: boolean;
  error: Error | null;
  onSubmit: (values: ScheduleValues) => void;
}) {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isDirty },
  } = useForm<ScheduleValues>({ resolver: zodResolver(scheduleSchema), defaultValues: initial });
  const schedule = watch("schedule");

  return (
    <Card>
      <form noValidate className="space-y-4" onSubmit={handleSubmit(onSubmit)}>
        <h2 className="font-semibold">Payout schedule</h2>
        {error && <Alert>{error instanceof ApiError ? error.message : "Could not save the schedule."}</Alert>}
        {saved && (
          <p role="status" className="rounded-lg bg-success-bg px-3 py-2 text-sm text-success">
            Schedule saved.
          </p>
        )}
        <div>
          <Label htmlFor="schedule">Pay out</Label>
          <select id="schedule" disabled={!canEdit} className={selectClass} {...register("schedule")}>
            <option value="daily">Every day</option>
            <option value="weekly">Once a week</option>
            <option value="manual">Only when I ask (manual)</option>
          </select>
        </div>
        {schedule === "weekly" && (
          <div>
            <Label htmlFor="weekly_anchor">Weekly payout day</Label>
            <select id="weekly_anchor" disabled={!canEdit} className={selectClass} {...register("weekly_anchor")}>
              {DAYS.map((day) => (
                <option key={day} value={day}>
                  {dayLabel(day)}
                </option>
              ))}
            </select>
          </div>
        )}
        <div>
          <Label htmlFor="delay_days">Funds become available after (days)</Label>
          <Input
            id="delay_days"
            type="number"
            min={0}
            max={30}
            disabled={!canEdit}
            aria-invalid={!!errors.delay_days}
            {...register("delay_days", { valueAsNumber: true })}
          />
          <FieldError>{errors.delay_days?.message}</FieldError>
          <p className="mt-1 text-xs text-muted">Applies to payments from now on.</p>
        </div>
        {canEdit && (
          <Button type="submit" loading={saving} disabled={!isDirty}>
            Save schedule
          </Button>
        )}
      </form>
    </Card>
  );
}

export function PayoutSettingsPage() {
  const { merchant, role } = useSession();
  const { testMode } = useTestMode();
  const canEdit = role === "owner" || role === "admin";

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-2xl font-semibold">Settings</h1>
      <SettingsTabs />
      <BankAccounts livemode={!testMode} canEdit={canEdit} defaultCurrency={merchant.default_currency} />
      <Schedule canEdit={canEdit} />
    </div>
  );
}
