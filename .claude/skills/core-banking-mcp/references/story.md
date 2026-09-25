# Story

Deliver one `CB-NN` story as one small PR.

## Qualify

1. Read the story in `BACKLOG.md`, its epic, its acceptance criteria, and whether it is `critical-path`. If a GitHub issue exists for it, read the issue and treat the two as one record; the issue body wins on conflict.
2. Require its dependencies to be `done` (the epic order in `PLAN.md` is the dependency order unless the story says otherwise). Stop and report when a dependency is open; propose stacking on that story's branch if the owner wants to proceed anyway.
3. Fix scope in two or three sentences before delegating anything. State exclusions explicitly.
4. Mark the story `in-progress` in `BACKLOG.md` and create `cb-NN-<slug>` from `origin/main` (or from the dependency's branch when stacking).

## Deliver

1. When the path is not obvious, spawn `code-mapper` first.
2. Split the story into bounded write batches. Choose the model per the routing table in SKILL.md; anything touching money arithmetic, the ledger's append-only paths, `system_clock`, the recalculation engine, or EOD runs on `opus`.
3. Tests before or alongside implementation: pure domain specs need no database; integration specs run against the compose Postgres. Order-independence and invariant stories (CB-20, CB-22) use `Gen`-based property tests.
4. Every new tool: accepts `idempotency_key` and `dry_run` if it writes, includes `env` in its response, writes an `audit_log` row, reads time from `system_clock`.
5. Keep the diff within ~200 changed lines. When it will not fit, stack: finish the first increment, commit, branch the next from it, and say so in each PR body.
6. `risk-reviewer` on the full diff; disposition every finding; rerun after material corrections.

## Gates And Publish

```bash
sbt compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

Commit with a Conventional Commit referencing the story (`feat(tools): add get_loan_schedule (CB-05)`) and the issue number when one exists. Push and open the PR once the owner has authorized pushes this session; the PR body lists acceptance criteria with evidence, the model used per batch, and the stack position when stacked. Mark the story `in-review` in `BACKLOG.md` in the same commit series; the owner marks it `done` on merge, or asks you to.

Report per the Completion Report and name the next story on the critical path.
