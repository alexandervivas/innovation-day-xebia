-- V1__schema.sql
-- CB-03: core schema for the mock banking ledger.
--
-- Column names and shapes follow docs/handoff/V3__seed_demo_ln0042.sql and
-- docs/handoff/RecalculationSpec.scala exactly, so CB-04's seed migration and CB-15a's engine
-- need no renames.
--
-- transactions is append-only (CLAUDE.md rule 2): corrections are reversal (reverses_id) plus
-- repost, never UPDATE/DELETE. That is enforced below with triggers, not just app discipline.
--
-- Money a client can see or be charged is NUMERIC(18,2), i.e. cents. accruals.amount is the one
-- deliberate exception, at NUMERIC(18,8): daily actual/365 interest is an internal running figure,
-- and docs/handoff/RecalculationSpec.scala requires it be "kept at full precision and rounded
-- HALF_UP to cents when allocated or reported". Storing it at 2dp would round every single day and
-- let a month of rows drift by real cents from the exact figure replay has to reproduce, so it is
-- stored unrounded; rounding happens when the engine allocates or reports it, never on write.
--
-- Known open questions, deliberately left unanswered here because each needs an engine or
-- write-tool spec that does not exist yet. These are NOT TODOs for CB-03:
--   * transactions.type has no CHECK constraint: the allowed vocabulary belongs to the write
--     tools, so it gets pinned when those stories define it.
--   * nothing links a repost back to the transaction it re-applies (only a reversal links back,
--     via reverses_id); whether that link is needed depends on the correction flow those stories
--     define.

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

-- A transaction may be reversed at most once, and may never reverse itself. UNIQUE treats
-- multiple NULLs as distinct in Postgres, so ordinary non-reversing transactions are unaffected.
ALTER TABLE transactions
  ADD CONSTRAINT transactions_reverses_id_unique UNIQUE (reverses_id);
ALTER TABLE transactions
  ADD CONSTRAINT transactions_no_self_reversal CHECK (reverses_id IS NULL OR reverses_id <> id);

CREATE FUNCTION forbid_transactions_mutation() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'transactions is append-only: % not allowed on id=%', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

-- Statement-level counterpart: TRUNCATE has no OLD row, so it needs its own function. Without
-- this, a row-level trigger alone leaves TRUNCATE as an open hole in the append-only guarantee.
CREATE FUNCTION forbid_transactions_truncate() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'transactions is append-only: % not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactions_append_only
  BEFORE UPDATE OR DELETE ON transactions
  FOR EACH ROW EXECUTE FUNCTION forbid_transactions_mutation();

CREATE TRIGGER transactions_no_truncate
  BEFORE TRUNCATE ON transactions
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_transactions_truncate();

-- ENABLE ALWAYS, not merely enabled: a session that sets session_replication_role = 'replica'
-- would otherwise silently skip both triggers and mutate the ledger freely.
ALTER TABLE transactions ENABLE ALWAYS TRIGGER transactions_append_only;
ALTER TABLE transactions ENABLE ALWAYS TRIGGER transactions_no_truncate;

CREATE TABLE accruals (
  id           BIGSERIAL PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id),
  accrual_date DATE NOT NULL,
  -- Full-precision internal figure, not a cent amount; see the precision note in the header.
  amount       NUMERIC(18,8) NOT NULL,
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

-- The clock row must never be removed: rule 4 makes it the only time source, so an empty table
-- leaves every date-sensitive read with nothing to read. Statement-level, so a DELETE is refused
-- whether or not its WHERE clause matches anything, and TRUNCATE is refused too.
CREATE FUNCTION forbid_system_clock_removal() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'system_clock is a permanent single row: % not allowed, use UPDATE', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER system_clock_no_delete
  BEFORE DELETE OR TRUNCATE ON system_clock
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_system_clock_removal();

ALTER TABLE system_clock ENABLE ALWAYS TRIGGER system_clock_no_delete;

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
