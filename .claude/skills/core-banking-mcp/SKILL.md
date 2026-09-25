---
name: core-banking-mcp
description: Use when working in the innovation-day-xebia repository on the core-banking-mcp project (Scala 3 / ZIO MCP server for backdated transactions on a Postgres core-banking mock) — scaffolding it, delivering a CB-NN backlog story, reporting status, or reviewing a diff before commit. Also use for `/core-banking-mcp help`.
---

# Core Banking MCP

Innovation Day project at Xebia: an MCP server that lets QA/integration engineers generate test data, time-travel, and safely test backdated transactions against a core-banking mock. One-day budget: optimise for a working demo. Everything in English.

`BACKLOG.md` is the only backlog and holds story status; GitHub issues mirror it once the owner approves their creation. `PLAN.md` holds the timeline and critical path. `CLAUDE.md` holds the condensed rules for every session.

## Route The Command

Interpret the first word of the skill arguments as the command:

- No command or `help`: print the help text below and stop.
- `setup`: read [references/setup.md](references/setup.md) and follow it.
- `story CB-NN`: read [references/story.md](references/story.md) and follow it.
- `status`: read [references/status.md](references/status.md) and follow it.
- `review`: run `risk-reviewer` on the current diff against `main`, disposition every finding, and report. Read-only.
- Unknown command: print the help text and name the unknown command. Do not guess.

## Help

```text
Core Banking MCP commands

/core-banking-mcp setup            Scaffold the repo, implement CB-01 and CB-02, propose GitHub issues
/core-banking-mcp story CB-07      Deliver one backlog story as one small PR
/core-banking-mcp status           Where the day stands: done, in flight, next on the critical path
/core-banking-mcp review           Risk-review the current diff before commit or PR
/core-banking-mcp help             Show this help
```

## Agent Orchestration

The parent is the delivery lead and an orchestrator, not an implementer. It owns story qualification, scope and architecture decisions, `BACKLOG.md` status, integration, final quality gates, git and GitHub writes, and the report. It edits no production code, migrations, or tests itself; every file-changing batch goes through a worker spawned with a deliberately chosen model.

Delegate to the project subagents under `.claude/agents/`, spawning them with the Agent tool by name:

- `version-scout` — pinned `sonnet`, read-only, web access. Use before pinning any library, plugin, image, or sbt version. Never guess versions.
- `code-mapper` — pinned `sonnet`, read-only. Use when the implementation path is not obvious.
- `implementation-worker` — defaults to `sonnet`, write access. Use for every production-code, migration, or docs batch.
- `test-worker` — defaults to `sonnet`, write access. Use for failure investigation, property tests, and test-only batches.
- `risk-reviewer` — pinned `opus`, high effort, read-only. Use after implementation and before commit, PR, or demo.

Pass the Agent tool's `model` override explicitly on **every** spawn, including the pinned profiles; never rely on inheritance from the session model, and never spawn a subagent on the session's own model (`fable`). Allowed values are `haiku`, `sonnet`, `opus`:

| Batch | Model |
|---|---|
| Mechanical: docs skeletons, `.gitignore`, `.env.example`, compose file, renames | `haiku` |
| Routine bounded feature: a read tool, a seed migration, a CRUD write tool | `sonnet` |
| Money semantics, ledger append-only paths, recalculation engine, EOD job, idempotency, property tests | `opus` |

If the runtime cannot spawn the named subagents, spawn a general-purpose agent with the profile's full instructions and an explicit `model`. If no model routing is possible at all, stop before implementation and report it.

Coordination rules:

1. Do not delegate story qualification, acceptance-criteria interpretation, scope, or architecture decisions.
2. Give every subagent the story text and acceptance criteria, the allowed files, the invariants from `CLAUDE.md`, the expected evidence, and the validation commands.
3. Delegate one bounded write batch at a time. Inspect the returned diff and evidence before delegating the next.
4. At most two concurrent read-only agents, which may run alongside the single workspace-writing agent. Never two writers at once.
5. Route corrections found by `test-worker` or `risk-reviewer` back through `implementation-worker`; the reviewer never edits.
6. Run `risk-reviewer` on the complete story diff before commit. Disposition every finding; rerun after material corrections.
7. Run the final gates in the parent thread even when a worker ran them: `sbt compile`, `sbt test`, `docker compose up -d` health, and the secret scan.

## Non-Negotiable Invariants

Every batch, review, and gate checks these. They are restated in `CLAUDE.md` for workers.

1. `CORE_ENV` ∈ {`mock`, `sandbox`}; the server refuses to start otherwise; every tool response carries the env.
2. `transactions` is append-only: no UPDATE/DELETE. Corrections = reversal (`reverses_id`) + repost.
3. Every transaction has `booking_date` and `value_date`; backdating = `value_date` < system date.
4. Time comes from the `system_clock` table, never the JVM clock.
5. All write tools accept `idempotency_key` and `dry_run`.
6. Every tool call is written to `audit_log`.
7. No personal emails, tokens, or credentials in committed files; `.env` gitignored, `.env.example` placeholders only.
8. One story ≈ one PR of at most ~200 changed lines, stacked where dependent.
9. Pure domain logic (`domain/`, `engine/`) is free of ZIO and DB code.

## Git And GitHub Write Boundaries

- Work on `main` only for `setup`'s initial commit. Every story lives on `cb-NN-<slug>` branched from `origin/main`; stack dependent stories with `gh stack` or plain `--base` chaining.
- Before every commit run the secret scan and prove it can fail, in one step:

  ```bash
  scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
  ```

  `--self-test` pipes a known fake token through the same pattern and must report a hit; the second call scans the staged tree and must report none. A scan whose self-test does not fire is not a scan. `setup` creates this script; until it exists, run the equivalent `git grep --cached -nIiE` inline.
- Local commits after green gates are pre-authorized. Conventional Commits, English, one story per commit or a small logical series.
- `git push` and `gh pr create` need the owner's word once per session; after that they are pre-authorized for the rest of the session.
- `gh issue create` (the backlog mirror), merging a PR, closing an issue, force-push, and any history rewrite always need the owner's explicit word for that specific action.

## Completion Report

Every command that changes files ends with:

- Story, branch, and `BACKLOG.md` status
- Delegated roles with the actual model used per batch
- Files changed, migrations added, tools exposed
- Acceptance-criteria evidence and validation commands with results
- Secret-scan output including the self-test hit
- Reviewer findings and their disposition
- What still needs the owner's word (push, PR, issues, merge) and the next story on the critical path
