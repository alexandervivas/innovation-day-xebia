---
name: core-banking-mcp
description: Use when working in the innovation-day-xebia repository on the core-banking-mcp project (Scala 3 / ZIO MCP server for backdated transactions on a Postgres core-banking mock) — scaffolding it, delivering a CB-NN backlog story, reporting status, or reviewing a diff before commit. Also use for `/core-banking-mcp help`.
---

# Core Banking MCP

Innovation Day project at Xebia: an MCP server that lets QA/integration engineers generate test data, time-travel, and safely test backdated transactions against a core-banking mock. One-day budget: optimise for a working demo. Everything in English.

`BACKLOG.md` is the only backlog and holds story status; GitHub issues #1–#31 mirror it one-to-one (title starts with the backlog ID) and carry the native blocked-by dependencies. Issue bodies are the acceptance-criteria record; the backlog row is the status record. `PLAN.md` holds the timeline and critical path. `CLAUDE.md` holds the condensed rules for every session.

## Route The Command

Interpret the first word of the skill arguments as the command:

- No command or `help`: print the help text below and stop.
- `setup`: read [references/setup.md](references/setup.md) and follow it.
- `story <ref>` (`CB-NN`, an issue number, or an issue URL): read [references/story.md](references/story.md) and follow it.
- `status`: read [references/status.md](references/status.md) and follow it.
- `review`: run `risk-reviewer` on the current diff against `main`, disposition every finding, and report. Read-only.
- `pr <number>`: read [references/pr.md](references/pr.md) and follow it: address the human review on that pull request and merge once every comment is addressed.
- Unknown command: print the help text and name the unknown command. Do not guess.

## Help

```text
Core Banking MCP commands

/core-banking-mcp setup            Scaffold the repo, implement CB-01 and CB-02, propose GitHub issues
/core-banking-mcp story CB-07      Deliver one backlog story as one small PR (also: story 7, story <issue url>)
/core-banking-mcp pr 12            Address the human review on PR #12; merge when every comment is addressed
/core-banking-mcp status           Where the day stands: done, in flight, next on the critical path
/core-banking-mcp review           Risk-review the current diff before commit or PR
/core-banking-mcp help             Show this help
```

## Agent Orchestration

Story delivery runs the superpowers SDD pipeline without exception (brainstorming → writing-plans → using-git-worktrees → subagent-driven-development with test-driven-development per task → verification-before-completion → finishing-a-development-branch into a PR); [references/story.md](references/story.md) maps its roles onto the profiles below. Specs and plans are put in front of the owner in a fresh VS Code window (`code -n <abs paths>`) at every approval gate; an approval request without that window is invalid.

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
8. **No pull request exceeds 300 changed lines** (additions + deletions, excluding lockfiles, generated artifacts, and `docs/superpowers/**`). Larger stories are delivered as a `gh stack` of dependent PRs, each within the limit, and every story runs in its own git worktree so stacks never share a folder (owner rule 2026-09-25).
9. Pure domain logic (`domain/`, `engine/`) is free of ZIO and DB code.
10. Comments explain the business domain only, in product words, tersely; PR bodies stay under ~15 lines (owner rule 2026-09-25). Process narration, rejected alternatives, review history, and rulings live in the SDD ledger, never in code or PR text.

## Git And GitHub Write Boundaries

- Work on `main` only for `setup`'s initial commit. Every story lives on `cb-NN-<slug>` branched from `origin/main` **in its own sibling worktree** (`../innovation-day-xebia-cbNN`), and is published with `gh stack` (the `github/gh-stack` extension) even when it is a stack of one. Never run two stacks in the same folder.
- Before every commit run the secret scan and prove it can fail, in one step:

  ```bash
  scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
  ```

  `--self-test` plants one sample per pattern class and must report every class firing; the second call scans the staged tree and must report `clean` with a non-zero file count. A scan whose self-test does not fire, or that examined zero files, is not a scan.
- Local commits after green gates, `git push` of the story branch, and `gh pr create` are **standing-authorized** (owner decision 2026-09-25): every story ends with a pull request, never with a merge to `main` from the session. Conventional Commits, English, one story per commit or a small logical series.
- **Merging is gated on human review** (owner decision 2026-09-25). The agent never merges its own unreviewed PR. The merge is allowed only when the PR has at least one human review, every review comment is addressed per [references/pr.md](references/pr.md), and no review is in `CHANGES_REQUESTED` state. Then the agent merges with squash and the issue closes through `Closes #N`.
- `gh issue create`, force-push, history rewrites, branch deletion on `main`, and closing an issue by hand always need the owner's explicit word for that specific action.

## Completion Report

Every command that changes files ends with:

- Story, branch, and `BACKLOG.md` status
- Delegated roles with the actual model used per batch
- Files changed, migrations added, tools exposed
- Acceptance-criteria evidence and validation commands with results
- Secret-scan output including the self-test hit
- Reviewer findings and their disposition
- PR URL and diff size; review state (awaiting review / comments open / merged) and the next story on the critical path
