# CB-12 — system_clock + advance_date (design note)

Bounded story. Issue #12, blocked-by CB-03 (closed).

## Acceptance criteria (issue #12)

`system_clock` table holds single row: `current_date`. `advance_date(days)`
increments it. All subsequent reads use this clock, not server clock.
`get_system_date()` read tool.

`system_clock` and its no-delete trigger already exist from CB-03's
`V1__schema.sql`. No DB access layer exists yet on `main`; CB-06 and CB-10
have their own unmerged, in-flight versions in sibling worktrees — expected
rebase conflicts on `Server.scala` later are normal for this project.

## Scope

New files:

- `db/Entities.scala` — `SystemClockRow(currentDateValue: LocalDate)` mapped
  to `system_clock`, plus a `DbCodec[LocalDate]` given (magnum/magnumpg 1.3.1
  ship no `LocalDate` codec).
- `db/Db.scala` — magnum `Transactor` built from `DbConfig` via
  `PGSimpleDataSource` (no pooling; single-writer mock server).
- `db/DryRun.scala` — runs a block in one transaction; rolls it back when
  `dryRun` is true but still returns the computed result, so a dry run and a
  real run share one code path.
- `tools/AuditLog.scala` — inserts one `audit_log` row per tool call, in its
  own transaction, committed regardless of `dryRun` or an idempotent replay.
- `tools/GetSystemDate.scala` — read tool. `SELECT current_date_value FROM
  system_clock WHERE id = true`. Response: `{env, data:{currentDate}}`. Logs
  to `audit_log` (no `idempotency_key`/`dry_run` — it is a read).
- `tools/AdvanceDate.scala` — write tool. Params: `days: Int` (rejected if
  `<= 0`), `idempotencyKey: Option[String]`, `dryRun: Boolean`. Response:
  `{env, data:{previousDate, currentDate, daysAdvanced, dryRun}}`.

`Server.scala` wiring: `dbConfig`, `FlywayRunner.migrate`, `transactor` vals,
and two `@Tool` methods, `get_system_date` and `advance_date`.

## Idempotency design decision

`system_clock` is a singleton row with no `idempotency_key` column, so the
"look up an existing row by key on the entity's own table" pattern (as used
elsewhere for entities that carry their own `idempotency_key` column) does
not apply here. Instead, `advance_date` looks up the most recent `audit_log`
row for `tool_name = 'advance_date'` whose `request->>'idempotencyKey'`
matches the supplied key; if found, it replays that stored `response` JSON
verbatim instead of incrementing the clock again. Every call — including a
replayed one — still gets its own `audit_log` row (rule 6: every call is
logged).

## Out of scope

CB-13 (EOD job/accrual), CB-14 (close_accounting_period), any recalculation
triggered by advancing the clock, a generic `get_audit_log` read tool
(CB-10's job), backdating the clock itself (only forward advances, per the
acceptance criteria).

## Testing

zio-test against the real docker-compose Postgres, following
`ProductSeedSpec`'s pattern:

- `get_system_date` returns the seeded `CURRENT_DATE`.
- `advance_date` moves the clock forward by `days`; a subsequent
  `get_system_date` reflects it.
- A repeated `idempotency_key` returns the identical previous response
  without incrementing the clock again.
- `dry_run = true` leaves `current_date_value` unchanged but still returns
  the would-be result.
- Each tool call adds exactly one `audit_log` row.
