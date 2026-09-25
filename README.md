# Core Banking MCP – Backdated Transactions Test Engine

An MCP server that lets QA/integration engineers generate test data, time-travel, and safely test backdated transactions against a core-banking mock on Postgres. Built in one Innovation Day at Xebia.

**Why this matters**: Backdating is the hardest thing to test in core banking. When a customer repays a loan on the wrong date, or posts a transaction into the past, the entire history can shift — accrued interest, arrears status, late fees, and all downstream calculations must be recalculated in the correct order. This tool lets engineers preview changes before committing, explain why balances changed, and verify that recalculation is order-independent (a property test against chaos).

## Quick Start

**Prerequisites**: JDK 21, sbt, Docker (or Postgres 18 elsewhere).

```bash
# Start the Postgres database
docker compose up -d

# Verify database health
docker compose ps

# Copy .env.example to .env and adjust if needed
cp .env.example .env

# Compile and run tests
sbt compile test

# Run the server
scripts/run-server.sh
```

`scripts/run-server.sh` sources `.env`, stages the app once (`sbt -batch stage`, only if `target/universal/stage/bin/core-banking-mcp` is missing), and execs the staged binary. The server then listens on stdin/stdout for MCP protocol messages (used by Claude Code and other MCP clients).

**`sbt run` is not an MCP launch command**: sbt prints its own build output to stdout, which corrupts the MCP stdio channel. Only `scripts/run-server.sh` (or the staged binary directly, see below) may be wired up as an MCP server command.

For a manual run without the wrapper script, stage once and then run the binary directly with the environment variable set:

```bash
sbt stage
CORE_ENV=mock target/universal/stage/bin/core-banking-mcp
```

## Connect from Claude Code

This repo ships a project-scoped `.mcp.json` that Claude Code picks up automatically when started in this folder:

```json
{"mcpServers":{"core-banking":{"command":"target/universal/stage/bin/core-banking-mcp","args":[],"env":{"CORE_ENV":"mock"}}}}
```

Run `sbt stage` first so the binary at that path exists, then start Claude Code in this repo. Confirm the server is registered with:

```bash
claude mcp list
```

Smoke-test the connection by calling the `ping` tool; it should return the envelope:

```json
{"env":"mock","data":{"pong":true,"server":"core-banking-mcp","version":"0.1.0"}}
```

## Agent Sessions

When working on this project in Claude Code, enable VS Code's Metals MCP server (`metals.startMcpServer` in settings) so agents get real-time compile/test diagnostics and can validate changes before commit.

## Demo Prompts

See `docs/demo-script.md` for a guided set of prompts covering:
- Scenario setup (create loan portfolios, initialize test data)
- Time travel (advance system date, run end-of-day jobs)
- Backdating (preview, post, and explain recalculations)
- Edge cases (blocked operations, data invariants)
- QA assertions (loan balance verification, regression tests)
- Safety checks (environment guard, idempotency, audit log)

## Design Mockups

Mockups for the following are in progress (CB-24):
- **Timeline**: System clock + advancement UI
- **Recalculation Diff**: Before/after diff view for backdated transactions
- **Scenario Builder**: Declarative portfolio specification
- **Audit Log**: Tool call history + effects

## Safety Model

**Delivered**:
- **Environment guard**: `CORE_ENV` is checked once at startup and must be `mock` or `sandbox`; otherwise the process exits 1 with a `FATAL` line on stderr.
- **Response envelope**: every tool response is an `{env, data}` envelope carrying the current environment.

**Planned**:
- **Append-only ledger**: transactions never updated; corrections are reversal + repost.
- **Idempotency**: write tools accept `idempotency_key`; repeated keys return the original result.
- **Dry-run**: write tools accept `dry_run` to preview effects without committing.
- **Audit log**: every tool call logged with tool name, params, result, timestamp, and `dry_run` flag (no actor field).

### Response envelope

Every tool returns `{"env": "<mock|sandbox>", "data": {...}}`. JSON-RPC error frames currently carry no `env` field; this gap is tracked under CB-19.

## Backlog and Planning

- **`BACKLOG.md`**: This is the only backlog. Each story (CB-NN) has acceptance criteria and status.
- **`PLAN.md`**: Timeline and critical path. One Innovation Day, 09:00–17:00.

See `/core-banking-mcp` skill for story delivery, status tracking, and risk review.

---

**Status**: Scaffolding in progress. See `BACKLOG.md` for what's done and next.
