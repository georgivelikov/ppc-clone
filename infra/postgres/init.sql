-- One database per service (see docs/adr/0002-service-granularity.md).
-- Runs only on the first start of an empty volume; add new databases here
-- and recreate the volume (`docker compose down -v`) or create them manually.
CREATE DATABASE identity;
CREATE DATABASE payments;
CREATE DATABASE ledger;
CREATE DATABASE payouts;
CREATE DATABASE links;
CREATE DATABASE billing;
CREATE DATABASE bnpl;
CREATE DATABASE webhooks;
CREATE DATABASE notifications;
