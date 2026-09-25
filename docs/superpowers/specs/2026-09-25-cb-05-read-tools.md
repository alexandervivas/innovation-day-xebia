# CB-05 Read tools — design note (bounded)

Issue: #5. Blocked-by: CB-04 (closed).

## Acceptance criteria (issue body)

`get_client(client_id)` returns client details. `list_accounts(client_id)` returns all accounts +
balances. `get_transactions(account_id, start_date?, end_date?)` filters by booking/value date.
`get_loan_schedule(loan_id)` shows installment schedule with dates, amounts, interest, principal
breakdown. All read from `system_clock`, not server clock.

## Scope decisions

- **First DB read layer on `main`**: no tool on `main` touches Postgres yet (only `ping`). This
  story adds it: `db/Db.scala` (magnum `Transactor`, `PGSimpleDataSource`, unpooled — single-reader
  mock server) and `db/Entities.scala` (magnum row types + the `LocalDate` codec).
- **Parallel-lane collision, expected**: CB-06 (unmerged, its own worktree, itself stale relative to
  `main`) independently built its own `db/Db.scala` and `db/Entities.scala` for its write path.
  `PLAN.md`'s Lane A (CB-03→04→05) and Lane B (CB-06→...) are explicitly meant to run concurrently;
  whichever story rebases onto `main` second reconciles the duplicate files as an ordinary conflict,
  same as `Server.scala`/`build.sbt`.
- **`loan_id` = `accounts.id`**: `V1__schema.sql`'s header explicitly leaves this open ("no column
  holds a human-facing external reference... whether an `external_ref` column is added belongs to
  CB-04 and CB-05"). CB-04 didn't add one. Adding a new column/migration for a display label is out
  of scope for a read-only story; `loans.account_id` is already the loan's real primary key, so
  `get_loan_schedule` takes it directly as `loan_id`.
- **`get_transactions` filters on `value_date`**: the signature only has one `start_date`/`end_date`
  pair, and `value_date` is the economically meaningful date for backdating work. Both
  `booking_date` and `value_date` are returned on every row either way, so nothing is hidden.
- **`list_accounts` balance**: there is no stored balance column — `transactions` is the ledger.
  Balance = `SUM(amount)` over transactions whose `value_date <= system_clock.current_date_value`
  (per "read from `system_clock`, not server clock" — an as-of-today figure, not a raw ledger sum
  that could include a not-yet-effective future-dated posting).
- **`get_loan_schedule` interest/principal breakdown**: `installments` only stores
  `(account_id, seq, due_date, amount_due)` — no split columns, and CB-03 deliberately left the
  split to a later story. Since no write tool exists yet to populate `installments`
  (CB-07 is later in Lane B), this story adds one pure domain function,
  `Schedule.amortizationBreakdown(principal, annualRate, installments)`, that replays the standard
  declining-balance split (`interest_i = round(balance * annualRate/12, 2)`,
  `principal_i = amount_due_i - interest_i`, `balance -= principal_i`) over whatever `amount_due`
  rows are actually in the DB, so it works whether `installments` has one row or none yet.
- **Excluded**: any write tool, `idempotency_key`/`dry_run`/`audit_log` plumbing (CB-06/CB-09/CB-10),
  connection pooling, and a validation/error-code framework for malformed ids or dates (CB-19's job
  — invalid input just fails naturally, surfaced as a tool error).

## Components

- **`domain/Schedule.scala`**: add `InstallmentBreakdown(seq, dueDate, amountDue, interest,
  principal)` and `amortizationBreakdown(...)` — pure, ZIO/DB-free (rule 9), unit-tested directly.
- **`db/Db.scala`**: `Db.transactor(config: DbConfig): Transactor`.
- **`db/Entities.scala`**: magnum row types for `clients`, `accounts`, `loans`, `installments`,
  `transactions`, `system_clock` (read-only projections — only the columns these tools need), plus
  the shared `LocalDate` codec.
- **`tools/GetClient.scala`, `tools/ListAccounts.scala`, `tools/GetTransactions.scala`,
  `tools/GetLoanSchedule.scala`**: each pairs a pure JSON-encodable `*Data` case class (unit-tested
  without a DB, like `PingData`) with a query function taking a `Transactor` and returning the
  `{env, data}` envelope. A missing client/account/loan raises `NoSuchElementException`, surfaced by
  fast-mcp-scala's `ZIO.attempt` handling as a tool error (confirmed by reading
  `ToolProcessor.scala`: `@Tool` methods may return a ZIO effect directly).
- **`Server.scala`**: adds `private val xa: Transactor = Db.transactor(DbConfig.fromEnv())` and
  `FlywayRunner.migrate` at startup (construction stays lazy-connect, so it can't break the existing
  `CORE_ENV` guard tests, which never reach a tool call); registers the four new `@Tool` methods as
  `readOnlyHint = Some(true)`.

## Test-plan note

Integration tests hit the real docker-compose Postgres via `connect(transactor)`, following
`SchemaMigrationSpec`'s convention: seed fixture rows with hand-picked UUIDv7-shaped literals inside
a rolled-back transaction. `ServerProcessSpec`'s test-process spawn needs
`DATABASE_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD` forwarded from the test JVM's `sys.env`, same gap
CB-10's design note already flags.

## Evidence commands

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```
