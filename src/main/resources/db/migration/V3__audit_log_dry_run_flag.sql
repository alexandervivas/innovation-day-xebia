-- V3__audit_log_dry_run_flag.sql
-- Distinguishes a dry-run call from a real one in the audit trail. Defaults to false so every
-- row has an unambiguous value, including from tools that never pass dry_run at all.
ALTER TABLE audit_log ADD COLUMN dry_run_flag BOOLEAN NOT NULL DEFAULT false;
