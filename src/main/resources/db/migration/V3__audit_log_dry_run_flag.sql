-- V3__audit_log_dry_run_flag.sql
-- CB-10: distinguishes a dry-run call from a real one in the audit trail (rule 5: all write tools
-- accept dry_run). Defaults to false so every row — including ones from tools that never pass
-- dry_run at all, like ping — has an unambiguous value.
ALTER TABLE audit_log ADD COLUMN dry_run_flag BOOLEAN NOT NULL DEFAULT false;
