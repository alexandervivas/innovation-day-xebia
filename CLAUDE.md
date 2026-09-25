# CLAUDE.md – Core Banking MCP

This is an MCP server for testing backdated transactions in core banking. Built in one Innovation Day at Xebia. Stack: Scala 3, sbt, ZIO 2, fast-mcp-scala (annotation-driven tools), Postgres 18, Flyway migrations.

## What It Does

Lets QA/integration engineers safely test backdated transactions — the hardest thing in core banking — against a mock Postgres database. Time-travel, recalculate balances, validate invariants, generate test portfolios.

## Non-Negotiable Rules

1. **Environment guard**: `CORE_ENV` must be `mock` or `sandbox`. Server refuses to start otherwise. Every tool response is an `{env, data}` envelope carrying the current env.
2. **Append-only ledger**: `transactions` table has no UPDATE/DELETE. Corrections are reversal + repost via `reverses_id`.
3. **Two dates per transaction**: `booking_date` (when recorded) and `value_date` (when it applies). Backdating means `value_date` < system date.
4. **Time source**: System time comes from `system_clock` table, never the JVM clock.
5. **Write safety**: All write tools accept `idempotency_key` and `dry_run`. Repeated keys return the original result.
6. **Audit trail**: Every tool call logged to `audit_log`.
7. **No secrets**: Personal emails, tokens, credentials only in `.env` (gitignored). `.env.example` has placeholders only. Before each commit: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`.
8. **No PR over 300 changed lines** (additions + deletions; `docs/superpowers/**` excluded). Larger stories ship as a `gh stack`; every story runs in its own git worktree so stacks never share a folder.
9. **Pure domain logic**: `domain/` and `engine/` modules free of ZIO and DB code. Recalculation runs through a `RecalculationStrategy` (FullReplay default, SnapshotReplay opt-in); both must stay pure over domain events.

## Domain Simplifications

- One savings product, one 12-month consumer loan (annuity, declining balance).
- Interest accrued daily on actual/365 basis.
- Arrears when an installment unpaid after due date + grace period; fixed late fee.
- Repayment allocation order: fees → interest → principal.

## Project Structure

```
core-banking-mcp/
├── CLAUDE.md, README.md, PLAN.md, BACKLOG.md
├── build.sbt, project/ (build.properties, plugins.sbt)
├── docker-compose.yml, .env.example, .gitignore
├── .mcp.json                          # project-scoped MCP config Claude Code picks up automatically
├── scripts/secret-scan.sh, scripts/run-server.sh
├── src/main/resources/db/migration/   # Flyway: V1__schema.sql, V2__seed_products.sql, ...
├── src/main/scala/corebanking/
│   ├── Server.scala                   # McpServerApp entrypoint + env guard
│   ├── config/  db/  domain/  engine/  tools/
├── src/test/scala/corebanking/
└── docs/demo-script.md
```

## How to Run

```bash
# Start database
docker compose up -d

# Copy environment file
cp .env.example .env

# Compile and test
sbt compile
sbt test

# Run server (stages once, then execs the staged binary)
scripts/run-server.sh

# Verify env guard (must fail: exit 1, FATAL on stderr)
CORE_ENV=production scripts/run-server.sh
```

`sbt run` is not a valid MCP launch command: it interleaves sbt's own build output with the server's stdout, corrupting the MCP stdio channel. Always launch via `scripts/run-server.sh` or, after `sbt stage`, the staged binary directly (`CORE_ENV=mock target/universal/stage/bin/core-banking-mcp`). Every tool response follows the `{env, data}` envelope convention: `{"env": "<mock|sandbox>", "data": {...}}`.

## Key Files for Agents

**Every story session uses the superpowers SDD pipeline, no exceptions:** `/core-banking-mcp story <ref>` runs brainstorming → writing-plans (plan in `docs/superpowers/plans/`) → git worktree → subagent-driven-development with TDD per task → verification → a pull request. Specs and plans are opened for the owner in VS Code (`code -n <files>`) before asking for approval. Never merge from a story session. No PR exceeds 300 changed lines; larger stories ship as a `gh stack`, each story in its own git worktree.

- `BACKLOG.md` — only backlog; GitHub issues mirror it once created
- `PLAN.md` — timeline and critical path
- `.claude/agents/` — orchestrator, code-mapper, implementation-worker, test-worker, risk-reviewer
- `/core-banking-mcp` skill for story delivery, status, review

## Commitment Protocol

- Work on `main` only for scaffolding (`CB-01`, `CB-02`); every story on its own `cb-NN-*` branch
- Secret scan before every commit (pattern must both self-test and find no leaks)
- Conventional Commits in English; ≤ 300 changed lines per PR, always via `gh stack` from the story worktree
- **Comments and PR text (owner rule 2026-09-25):** Comments explain the business domain only, in product words (value date, booking date, arrears, grace period, allocation order, reversal, repost). One line where possible. No implementation narration, no history of why an alternative was rejected, no review or ruling text, no restating what the code visibly does. Scaladoc on public domain types: one sentence. PR bodies stay under ~15 lines: `Closes #N`, what changed (≤3 bullets), acceptance evidence (commands and result lines), stack position. Commit bodies ≤3 bullets.
- `git push` and `gh pr create` once per session; other GitHub writes need explicit approval
