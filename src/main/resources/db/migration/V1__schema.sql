-- V1__schema.sql
-- CB-03: core schema for the mock banking ledger.
--
-- Column names and shapes follow docs/handoff/V3__seed_demo_ln0042.sql and
-- docs/handoff/RecalculationSpec.scala exactly, so CB-04's seed migration and CB-15a's engine
-- need no renames.
--
-- transactions is append-only (CLAUDE.md rule 2): corrections are reversal (reverses_id) plus
-- repost, never UPDATE/DELETE. That is enforced below with a trigger, not just app discipline.

CREATE TABLE clients (
  id           TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  opened_on    DATE NOT NULL
);

CREATE TABLE products (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('savings', 'loan')),
  annual_rate   NUMERIC(6,4) NOT NULL,
  term_months   INT,
  accrual_basis TEXT NOT NULL DEFAULT 'actual/365'
);

CREATE TABLE accounts (
  id         TEXT PRIMARY KEY,
  client_id  TEXT NOT NULL REFERENCES clients(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  kind       TEXT NOT NULL CHECK (kind IN ('savings', 'loan')),
  opened_on  DATE NOT NULL
);

CREATE TABLE loans (
  account_id         TEXT PRIMARY KEY REFERENCES accounts(id),
  principal          NUMERIC(18,2) NOT NULL,
  annual_rate        NUMERIC(6,4) NOT NULL,
  term_months        INT NOT NULL,
  disbursement_date  DATE NOT NULL,
  installment_amount NUMERIC(18,2) NOT NULL,
  grace_days         INT NOT NULL,
  late_fee           NUMERIC(18,2) NOT NULL
);

CREATE TABLE installments (
  account_id TEXT NOT NULL REFERENCES accounts(id),
  seq        INT NOT NULL,
  due_date   DATE NOT NULL,
  amount_due NUMERIC(18,2) NOT NULL,
  PRIMARY KEY (account_id, seq)
);

CREATE TABLE transactions (
  id              TEXT PRIMARY KEY,
  account_id      TEXT NOT NULL REFERENCES accounts(id),
  type            TEXT NOT NULL,
  amount          NUMERIC(18,2) NOT NULL,
  booking_date    DATE NOT NULL,
  value_date      DATE NOT NULL,
  reverses_id     TEXT REFERENCES transactions(id),
  idempotency_key TEXT NOT NULL UNIQUE
);

CREATE FUNCTION forbid_transactions_mutation() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'transactions is append-only: % not allowed on id=%', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactions_append_only
  BEFORE UPDATE OR DELETE ON transactions
  FOR EACH ROW EXECUTE FUNCTION forbid_transactions_mutation();

CREATE TABLE accruals (
  id           BIGSERIAL PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id),
  accrual_date DATE NOT NULL,
  amount       NUMERIC(18,2) NOT NULL,
  UNIQUE (account_id, accrual_date)
);

-- Single-row table: id is BOOLEAN and can only ever be TRUE, so the primary key alone forbids a
-- second row. Seeded with one row below so callers can UPDATE it without an INSERT ever being
-- needed later.
CREATE TABLE system_clock (
  id                 BOOLEAN PRIMARY KEY DEFAULT TRUE,
  current_date_value DATE NOT NULL,
  CONSTRAINT system_clock_is_singleton CHECK (id)
);

INSERT INTO system_clock (current_date_value) VALUES (CURRENT_DATE);

CREATE TABLE accounting_periods (
  start_date DATE PRIMARY KEY,
  end_date   DATE NOT NULL,
  closed     BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK (end_date > start_date)
);

CREATE TABLE audit_log (
  id        BIGSERIAL PRIMARY KEY,
  tool_name TEXT NOT NULL,
  called_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  env       TEXT NOT NULL,
  request   JSONB,
  response  JSONB
);
