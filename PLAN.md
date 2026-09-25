# PLAN.md – Core Banking MCP Timeline

One Innovation Day (09:00–17:00). Optimise for a working demo.

## Timeline

| Time | Epic | Stories | Critical? |
|------|------|---------|-----------|
| 09:00 | **E0: Foundation** | CB-01 Scaffolding, CB-02 Env guard | ✓ |
| 09:30 | **E1: Domain & DB** | CB-03 Schema + Flyway, CB-04 Seed products | ✓ |
| 10:00 | **E2: Read tools** | CB-05 get_client, list_accounts, get_transactions, get_loan_schedule | ✓ |
| 11:00 | **E3: Writes & safety** | CB-06 create_client/open_account, CB-07 disburse_loan, CB-08 make_repayment, CB-09 Idempotency keys, CB-10 Audit log, CB-11 dry_run | ✓ |
| 13:30 | **E4: Time travel** | CB-12 system_clock + advance_date, CB-13 EOD job (accrual, arrears, fees), CB-14 close_accounting_period | ✓ |
| 15:00 | **E5: Backdating ⭐** | CB-15 Recalculation engine, CB-16 preview_backdated_transaction, CB-17 post_backdated_transaction, CB-18 explain_recalculation, CB-19 Validations, CB-20 Property test | ✓ |
| 16:00 | **E6: QA tooling** | CB-21 generate_scenario, CB-22 assert_invariants, CB-23 Export | – |
| 17:00 | **E7: Demo & docs** | CB-24 Design mockups, CB-25 README + demo script, CB-26 Web console, CB-27 Adapter | – |

## Lanes (parallel workstreams)

Rewired 2026-09-25 (owner decision) to the real data/code dependencies. Each lane is worked in its own git worktree by its own session; merge conflicts are resolved in the branch before the PR, as in a normal SDLC. GitHub carries these as native blocked-by edges; an issue is startable when every blocker is closed.

| Lane | Stories in order | Starts when |
|---|---|---|
| **D — Pure engine** | CB-15a → CB-20 | now (no blockers; pure `domain/` + `engine/`, no DB) |
| **A — Data** | CB-03 → CB-04 → CB-05 | now |
| **B — Writes** | CB-06 → CB-07 → CB-08 → CB-09, CB-11 | CB-03 merged (CB-07 also needs CB-04 and CB-15a) |
| **C — Platform** | CB-10, CB-12 → CB-14 | CB-03 merged |
| **Joins** | CB-13 ← CB-08, CB-12, CB-15a · CB-16/CB-17 ← CB-13, CB-10, CB-15a · CB-18/19/20 ← CB-17 (CB-19 also ← CB-14) · CB-15b ← CB-13 · CB-15c ← CB-15a, CB-15b · CB-20b ← CB-15c | as blockers close |
| **QA & demo** | CB-21 ← CB-08, CB-12 · CB-22 ← CB-08 · CB-23 ← CB-21 · CB-25 ← CB-19 · CB-26 ← CB-17, CB-25 · CB-27 ← CB-11 | as blockers close |

CB-15a owns the pure domain types (`LoanTerms`, `UserEvent`, `Allocation`, `LoanState`, `ChainStep`, `RecalcResult`, `RecalcError`, `Schedule`, accrual and allocation rules); CB-07 and CB-13 consume them.

## Critical Path

Longest chain of blockers to the demo (six stories after CB-03):

```
CB-03 → CB-06 → CB-07 → CB-08 → CB-13 → CB-17 → CB-19 → CB-25
          (CB-07 also waits for CB-04, CB-15a; CB-13 for CB-12, CB-15a; CB-17 for CB-10)
```

Up to four sessions can run at once after CB-03 merges (CB-04, CB-06, CB-10, CB-12) with CB-15a already in flight.

**Must-complete by 17:00**: CB-01 through CB-20 (backdating engine and validation). Everything else is stretch.

## Stretch Goals

- CB-15b, CB-15c: Snapshot-based recalculation (loan_snapshots table, SnapshotReplay strategy with fallback)
- CB-20b: Differential property test: FullReplay vs SnapshotReplay (stretch, but demo gold)
- CB-21, CB-22, CB-23: QA tooling (scenario builder, assertions, test export)
- CB-24: Design mockups (timeline, diff UI, scenario builder, audit log)
- CB-26: Web console for manual testing
- CB-27: Adapter interface + Bancolombia sandbox stub

## Acceptance Criteria Per Story

See `BACKLOG.md` for full detail. Key checkpoints:

- **CB-01**: `docker compose up`, `sbt run`, Claude connects over stdio.
- **CB-03**: No UPDATE path on `transactions`; `reverses_id` for corrections.
- **CB-05**: Filters by booking/value date; loan schedule is readable.
- **CB-08**: Allocation order enforced: fees → interest → principal.
- **CB-11**: All write tools accept `dry_run`; no commit on dry run.
- **CB-13**: +45 days → unpaid loan moves to arrears with correct fees.
- **CB-15**: Replay from value_date via reverse-and-repost.
- **CB-17**: Returns reversal/repost chain.
- **CB-20**: Property test: same backdated txns in any order → same final state.
- **CB-25**: Demo script with all prompt categories.

---

Updated: 2026-09-25. See `BACKLOG.md` for current status.
