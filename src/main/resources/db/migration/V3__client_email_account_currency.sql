-- V3__client_email_account_currency.sql
-- CB-06: create_client's `email?` parameter and open_account's `currency` parameter have nowhere
-- to persist in V1__schema.sql. Both are additive, nullable-or-defaulted columns on existing
-- tables -- no rename, no backfill, safe regardless of whether CB-04's V2 has landed yet.

ALTER TABLE clients ADD COLUMN email TEXT;

-- clients has no natural transaction row (unlike open_account, which posts the opening
-- transaction and can reuse transactions.idempotency_key), so create_client needs its own
-- repeat-detection column. Nullable: most callers won't pass an idempotency_key, and Postgres
-- treats multiple NULLs in a UNIQUE column as distinct (same reasoning as
-- transactions.reverses_id in V1__schema.sql).
ALTER TABLE clients ADD COLUMN idempotency_key TEXT;
ALTER TABLE clients ADD CONSTRAINT clients_idempotency_key_unique UNIQUE (idempotency_key);

-- No CHECK constraint: the allowed-currency list is a tool-level validation (CreateClient.scala's
-- sibling, OpenAccount.scala), not a schema-level vocabulary, matching how V1__schema.sql left
-- transactions.type unconstrained for the same reason.
ALTER TABLE accounts ADD COLUMN currency TEXT NOT NULL DEFAULT 'COP';
ALTER TABLE accounts ALTER COLUMN currency DROP DEFAULT;
