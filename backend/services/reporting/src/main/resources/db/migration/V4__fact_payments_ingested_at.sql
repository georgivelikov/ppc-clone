-- When this service first heard of a payment, by the wall clock. Reconciliation without a date only compares payments seen since
-- the acquirer file begins (the simulator forgets everything when it restarts); a payment's own timestamps cannot say that,
-- because the services run on simulated clocks in tests. Rows from before this migration stay null and are left out.
ALTER TABLE fact_payments ADD COLUMN ingested_at TIMESTAMPTZ;
ALTER TABLE fact_payments ALTER COLUMN ingested_at SET DEFAULT now();
