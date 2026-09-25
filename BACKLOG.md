# BACKLOG.md – Core Banking MCP

**This file is the only backlog.** GitHub issues mirror it once created and approved by the owner.

**Legend**: Statuses are `todo | in-progress | in-review | done`. Path is `critical-path` or `stretch`. Merged to `main` = done.

---

## E0: Foundation

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-01 | Scaffolding | `docker compose up`, `sbt run`, Claude connects over stdio (MCP protocol). Repo structure in place: `src/main/scala/`, `src/test/scala/`, `src/main/resources/db/migration/`, `docs/`. | critical-path | done |
| CB-02 | Env guard | `CORE_ENV` must be `mock` or `sandbox`. Server refuses to start if env is missing or set to production. Every tool response includes the current env in a consistent field. | critical-path | done |

## E1: Domain & DB

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-03 | Schema + Flyway migrations | Tables: `clients`, `products`, `accounts`, `loans`, `installments`, `transactions` (append-only, `booking_date`, `value_date`, `reverses_id`, `idempotency_key`), `accruals`, `system_clock`, `accounting_periods`, `audit_log`. No UPDATE/DELETE on `transactions`. `V1__schema.sql` creates all tables. Foreign key constraints enforce referential integrity. | critical-path | done |
| CB-04 | Seed products | Flyway `V2__seed_products.sql` inserts: Savings product (annual interest, no term). 12-month Loan product (8% annual, annuity schedule, daily accrual). Both use `NUMERIC(18,2)` for money fields. | critical-path | done |

## E2: Read Tools

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-05 | Read tools: get_client, list_accounts, get_transactions, get_loan_schedule | `get_client(client_id)` returns client details. `list_accounts(client_id)` returns all accounts + balances. `get_transactions(account_id, start_date?, end_date?)` filters by booking/value date. `get_loan_schedule(loan_id)` shows installment schedule with dates, amounts, interest, principal breakdown. All read from `system_clock`, not server clock. | critical-path | todo |

## E3: Writes & Safety

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-06 | Write tools: create_client, open_account | `create_client(name, email?)` inserts client + audit log entry. `open_account(client_id, product_id, currency)` creates account + initial balance transaction with `booking_date` = `value_date` = system clock time. Both tools accept `idempotency_key`, `dry_run`. | critical-path | todo |
| CB-07 | Write tool: disburse_loan | `disburse_loan(account_id, principal, annual_rate, term_months)` creates loan, generates installment schedule, posts disbursement transaction. Installments scheduled using system date. Accepts `idempotency_key`, `dry_run`. | critical-path | todo |
| CB-08 | Write tool: make_repayment | `make_repayment(account_id, amount)` applies repayment with allocation order: fees (if any) → interest (accrued) → principal. Writes reversal if overpayment + returns amount applied + excess. Accepts `idempotency_key`, `dry_run`. | critical-path | todo |
| CB-09 | Idempotency keys | All write tools enforce: repeated `idempotency_key` returns exact same result (same transaction IDs, same state, no duplicates). Idempotency stored in `transactions.idempotency_key` column. | critical-path | todo |
| CB-10 | Audit log | Every tool call (read + write) logged to `audit_log(id, tool_name, params, result, timestamp, dry_run_flag)`. `get_audit_log(start_time?, end_time?)` read tool to retrieve history. | critical-path | todo |
| CB-11 | Dry-run on all writes | All write tools accept `dry_run: bool`. When true: preview all side effects (returned in `preview` field) without committing. DB state unchanged. Tool returns proposed transactions. | critical-path | todo |

## E4: Time Travel

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-12 | system_clock + advance_date | `system_clock` table holds single row: `current_date`. `advance_date(days)` increments it. All subsequent reads use this clock, not server clock. `get_system_date()` read tool. | critical-path | in-review |
| CB-13 | End-of-day job: accrual, arrears, late fees | `run_eod()` tool: (1) accrues interest for all active loans at daily rate (actual/365) into `accruals` table; (2) checks unpaid installments: if past due date + grace period, flag as arrears, post fixed late fee transaction; (3) returns summary of state changes. Advance clock by 45 days and verify unpaid loan is in arrears with correct accumulated fees. | critical-path | todo |
| CB-14 | close_accounting_period | `close_accounting_period(end_date)` marks period as closed in `accounting_periods` table. Prevents new transactions with `value_date` in closed period (return error). | critical-path | todo |

## E5: Backdating ⭐ (Core Differentiator)

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-15 | Recalculation engine: replay from value_date via reverse-and-repost | See CB-15a/b/c below. | critical-path | todo |
| CB-15a | RecalculationStrategy trait + FullReplay | `RecalculationStrategy` trait in `engine/` with `FullReplay` as the default implementation: rebuilds loan state from loan start by replaying all domain events. Pure over domain events (no ZIO, no DB). Backdating tools accept `strategy: "full" \| "incremental"` (default "full") and the response states which strategy actually ran. | critical-path | done |
| CB-15b | loan_snapshots table + EOD month-end snapshot writing | Flyway migration adds `loan_snapshots`; the EOD job writes a snapshot per loan at month end; any posting with a value_date on or before a snapshot's date invalidates that snapshot and all later ones. | stretch | todo |
| CB-15c | SnapshotReplay with fallback to FullReplay | `SnapshotReplay` (opt-in via `strategy: "incremental"`) starts from the latest valid `loan_snapshots` row before value_date and replays forward; falls back to `FullReplay` when no valid snapshot exists; the tool response states which strategy actually ran. Pure over domain events so it is directly comparable with FullReplay. | stretch | todo |
| CB-16 | preview_backdated_transaction | Tool: accept `account_id`, `amount`, `type`, `value_date` (in past). Return before/after diff: current balance + interest breakdown vs. what it would be with this txn. No DB write. | critical-path | todo |
| CB-17 | post_backdated_transaction | Tool: accept `account_id`, `amount`, `type`, `value_date`. Calls recalculation engine. Posts reversal + new txn + repost chain to DB. Returns full chain of transaction IDs. Accepts `idempotency_key`, `dry_run`. | critical-path | todo |
| CB-18 | explain_recalculation | Read tool: given transaction ID, return plain-English narrative: "This repayment on 2025-09-15 reduced interest accrual from X to Y because…" Uses `audit_log` + domain model. | critical-path | todo |
| CB-19 | Validations | Reject if: (1) value_date before account open date; (2) value_date in closed period; (3) overpayment (amount > outstanding). Return specific error codes; error frames must also carry the env field (decision from CB-02 review). | critical-path | todo |
| CB-20 | Property test: order independence | Verify: post 3 backdated txns in random order (e.g., Day 5, Day 3, Day 7) → final balance identical regardless of order. Use zio-test + Gen. | critical-path | done |
| CB-20b | Differential property test: FullReplay vs SnapshotReplay | For random backdated sequences (zio-test Gen), `FullReplay` and `SnapshotReplay` yield identical final loan state. | stretch | todo |
| CB-20c | Property test: value-date tie order rule | `FullReplay.scala:136-137` documents "on a value-date tie, the backdated transaction is applied after the existing ones" — a money-visible allocation rule (fees→interest→principal split, and which fee gets settled, both change with tie order). Zero test coverage repo-wide. Verify: two backdated repayments sharing one value date, posted in both orders, produce the documented tie-break result (not order independence — the opposite: pin the documented order-*dependent* behavior). Found during CB-20's final review (#23); CB-20 itself deliberately scopes to distinct value dates, per its acceptance criteria's own example. | stretch | todo |

### Design note — recalculation strategies (CB-15 refinement)

- `RecalculationStrategy` trait with two implementations: `FullReplay` (default) rebuilds loan state from loan start, replaying all events; `SnapshotReplay` (opt-in) starts from the latest `loan_snapshots` row before value_date and replays forward, falling back to FullReplay if no valid snapshot exists; the tool response states which strategy actually ran.
- Backdating tools accept `strategy: "full" | "incremental"` (default "full").
- Snapshots are written by the EOD job at month end; any posting with a value_date on or before a snapshot's date invalidates that snapshot and all later ones.
- Keep both strategies pure over domain events so they are directly comparable.

## E6: QA Tooling

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-21 | generate_scenario | Tool: accept declarative spec (e.g., "10 clients, 30% loans late by 10–30 days, 10% late by 60+, rest current"). Returns list of generated transactions to set up the portfolio. | stretch | todo |
| CB-22 | assert_invariants | Tool: verify invariants for all loans: sum(installments) = principal + total_interest; current balance = disbursed principal - repaid principal - early repayment surplus. Return pass/fail per loan + violations. | stretch | todo |
| CB-23 | Export scenario | Tool: given a set of transaction IDs + clock state, export as a runnable test in Scala (Gen-based). | stretch | todo |

## E7: Demo & Design

| ID | Story | Acceptance Criteria | Path | Status |
|---|---|---|---|---|
| CB-24 | Design mockups | Claude Design artifacts (outside repo): Timeline UI, Recalculation Diff viewer, Scenario Builder, Audit Log. Screenshots/links in README. | stretch | done |
| CB-25 | README + docs/demo-script.md | `README.md` with quick start, connection, safety model. `docs/demo-script.md` with grouped prompts (scenario, time travel, backdating, edge cases, QA, safety) ready to paste into Claude. | stretch | todo |
| CB-26 | Web console | (Stretch) Simple web UI: list clients, view transactions, trigger tools. Connects to same DB. | stretch | todo |
| CB-27 | Adapter + Bancolombia sandbox stub | (Stretch) Abstract DB layer + adapter for real Bancolombia sandbox API. | stretch | todo |

---

**Notes**:
- CB-01, CB-02 are in-progress (scaffolding phase).
- Critical path: CB-01 → CB-13, CB-15 → CB-17, CB-19, CB-24, CB-25. Everything else is stretch.
- One story = one `gh stack`; no PR over 300 changed lines; each story in its own git worktree.
- All times from `system_clock` table, never server time.
- Money as `NUMERIC(18,2)`, never Double.
- Test with property tests (zio-test + Gen).

**Updated**: 2026-09-25
