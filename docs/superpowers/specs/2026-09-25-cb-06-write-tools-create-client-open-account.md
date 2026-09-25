# CB-06 — Write tools: create_client, open_account

Bounded design note (brainstorming skill, bounded path). Issue #6 / BACKLOG.md is the acceptance-criteria record; this note covers the parts the issue leaves open plus the concrete implementation shape.

## Scope

Two new MCP write tools:

- `create_client(name, email?, idempotency_key?, dry_run = false)`
- `open_account(client_id, product_id, currency, initial_deposit = 0, idempotency_key?, dry_run = false)`

`initial_deposit` is an addition beyond the issue's literal `open_account(client_id, product_id, currency)` signature — approved by the owner directly (2026-09-25) so the required "initial balance transaction" has a real amount instead of an invented always-zero row.

This is the **first story to write to Postgres from application code** (CB-03/FlywayRunner only ran migrations) and the first to mint entity ids, so it also establishes, for CB-07/CB-08 to reuse:

- the DB access layer (magnum/magnumpg, already pinned but unused),
- UUIDv7 id minting,
- the idempotency-check-then-write pattern,
- the dry-run-via-rollback pattern,
- the audit_log write pattern.

**Excluded:** CB-04's seed data (tests insert their own `products`/`clients` fixture rows directly — CB-04 is `todo` and not a blocker per the issue graph); CB-05 read tools; CB-07/CB-08's own transaction types; CB-09's generalized cross-tool idempotency framework (this story only makes idempotency correct for these two tools, using a pattern CB-09 can later consolidate); CB-10's `audit_log.dry_run_flag` column (doesn't exist in `V1__schema.sql`; `dry_run` is recorded inside the JSONB `response` instead).

## Schema changes — new migration

`V3__client_email_account_currency.sql` (V2 is CB-04's `V2__seed_products.sql`, already landed on `main` and owning that number):

- `clients.email TEXT NULL` — the issue's `email?` parameter has nowhere to persist today.
- `clients.idempotency_key TEXT NULL UNIQUE` — mirrors `transactions.idempotency_key`. `create_client` inserts no transaction row, so it needs its own repeat-detection column; a later story (CB-09) can consolidate this if it designs a shared mechanism.
- `accounts.currency TEXT NOT NULL` — the issue's `currency` parameter has nowhere to persist today. No CHECK constraint (an allowed-currency list isn't specified anywhere in `CLAUDE.md`'s Domain Simplifications); validated in the tool as a short allow-list (`COP`, `USD`, `EUR`) so a typo fails clearly instead of silently persisting.

Both are additive, nullable-or-defaulted columns on existing tables — no rename, no data migration, safe to land after CB-03.

## DB access layer (new)

- `corebanking/db/Db.scala` — a ZLayer around a magnum `Transactor` built from `DbConfig` via `org.postgresql.ds.PGSimpleDataSource` (ships inside the existing `org.postgresql:postgresql` dependency; no connection-pool dependency needed for a single-writer mock server).
- `corebanking/db/Ids.scala` — UUIDv7 minting via `com.github.f4b6a3:uuid-creator:6.1.1` (`UuidCreator.getTimeOrderedEpoch()`), a new `build.sbt` dependency.
- `corebanking/db/JsonbCodec.scala` — magnumpg 1.3.1 has no JSONB support (verified against its `PgCodec.scala`); a small custom `DbCodec[String]`-shaped codec writes JSON text into `audit_log.request`/`response` via `org.postgresql.util.PGobject` (`setType("jsonb")`), which also ships in the postgres driver already on the classpath.
- `corebanking/db/DryRun.scala` — magnum's `transact()` always commits on success / rolls back on exception, with no manual commit/rollback hook. `dry_run` is implemented as: run the real statements inside `transact`, and when `dry_run == true` throw a private sentinel exception carrying the computed result at the very end of the block (forcing a rollback), caught immediately outside `transact` to unwrap the result as a success. Documented inline since it isn't an idiomatic magnum pattern.
- `Server.scala` wires `Db`'s ZLayer and calls `FlywayRunner.migrate` at startup (the natural place per CB-03's plan, which deliberately left this unwired) — this story is the first one that needs a live DB connection for a tool.

## Tool pattern (reused by both tools)

1. If `idempotency_key` is supplied, look up a prior row by it (`clients.idempotency_key` / `transactions.idempotency_key`). If found, return the prior result unchanged — no writes, no new audit_log row beyond the one already written for the original call.
2. Otherwise, inside one `transact` block: read `system_clock.current_date_value`, mint UUIDv7 id(s), insert the row(s).
3. If `dry_run = true`, force a rollback per the pattern above; the response carries the same payload shape plus `dryRun: true`.
4. Always write one `audit_log` row (`tool_name`, `env`, `request` as JSON, `response` as JSON) in its own `transact` call, committed regardless of `dry_run` or idempotency-hit — an audit trail must record that the call happened even when nothing else did.
5. Wrap the payload in `{env, data}` via the existing `ToolResponse.respond`.

## Tools in detail

- **`create_client`** → `ClientData(id, name, email, openedOn)`. `openedOn` = `system_clock.current_date_value`.
- **`open_account`** → `AccountData(id, clientId, productId, currency, openedOn, openingTransactionId)`. Validates `client_id`/`product_id` exist before insert (clean `{env, data: {error: "CLIENT_NOT_FOUND"}}`-shaped response instead of a raw FK-violation exception surfacing to the MCP caller). Inserts one `transactions` row: `type = 'account_opening'`, `amount = initial_deposit` (default `0.00`), `booking_date = value_date = system_clock.current_date_value`. `account_opening` is the first entry in the `transactions.type` vocabulary the schema comment left for write-tool stories to pin — CB-07/CB-08 add their own values later, still no CHECK constraint.

## Testing

zio-test against the real dockerized Postgres (`docker compose up -d`), Flyway-migrated fresh, following `SchemaMigrationSpec`'s existing pattern. Cases per tool: happy path; repeated `idempotency_key` returns the identical result with no duplicate row; `dry_run` leaves row counts unchanged; `open_account` against a missing client/product returns a clean error, not a raw SQL exception; exactly one `audit_log` row per call, including dry runs and idempotency hits.

## Files touched

- `src/main/resources/db/migration/V3__client_email_account_currency.sql` (new)
- `src/main/scala/corebanking/db/Db.scala`, `Ids.scala`, `JsonbCodec.scala`, `DryRun.scala` (new)
- `src/main/scala/corebanking/tools/CreateClient.scala`, `OpenAccount.scala`, `AuditLog.scala` (new)
- `src/main/scala/corebanking/Server.scala` (wire `Db` layer + `FlywayRunner.migrate` at startup; register two `@Tool` methods)
- `build.sbt` (add `com.github.f4b6a3:uuid-creator:6.1.1`)
- `src/test/scala/corebanking/db/**`, `src/test/scala/corebanking/tools/CreateClientSpec.scala`, `OpenAccountSpec.scala` (new)
- `BACKLOG.md` (status, already `in-progress`)

## Sizing

DB layer + custom JSONB codec + dry-run plumbing + two tools + real-Postgres tests will not fit the 300-changed-line cap in one PR. The implementation plan splits this into a `gh stack`: increment 1 = migration + `Db`/`Ids`/`JsonbCodec`/`DryRun` + `create_client` (the smaller tool, proves the whole pattern); increment 2 = `open_account`, built on increment 1's layer. Each increment compiles and passes its own tests standalone.
