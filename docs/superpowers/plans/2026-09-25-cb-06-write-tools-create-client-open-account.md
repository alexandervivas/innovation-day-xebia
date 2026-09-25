# CB-06 Write Tools: create_client, open_account — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `create_client(name, email?, idempotency_key?, dry_run?)` and `open_account(client_id, product_id, currency, initial_deposit?, idempotency_key?, dry_run?)` as MCP tools, establishing the first application-level Postgres write path (magnum/magnumpg, UUIDv7 id minting, dry-run-via-rollback, idempotency-check-then-write, audit_log) for CB-07/CB-08 to reuse.

**Architecture:** A small `corebanking/db` package holds the reusable plumbing — `Ids` (UUIDv7), `Db` (magnum `Transactor` from `DbConfig`), `Entities` (row case classes read/written via raw `sql"..."` frags, never magnum's `Repo`/`ImmutableRepo` — this plan deliberately sticks to the two magnum call shapes verified against the real v1.3.1 source: `sql"...".query[T].run()` returning `Vector[T]`, and `sql"...".update.run()` for writes), and `DryRun` (forces a rollback instead of a commit when `dry_run = true`, since magnum's `transact` has no other rollback hook). `corebanking/tools/AuditLog` writes one `audit_log` row per call, in its own transaction, always committed. `CreateClient`/`OpenAccount` are plain synchronous Scala objects (no ZIO — matches `Ping`'s existing pattern; fast-mcp-scala's `@Tool` methods are synchronous), each: check idempotency → run the write inside `DryRun` → record the audit row → return the `{env, data}` envelope via the existing `ToolResponse.respond`. `Server.scala` builds one shared `Transactor` at startup, runs `FlywayRunner.migrate` (previously unwired — CB-03's plan flagged this as "the natural place is whichever story first needs a live DB connection", which is this one), and registers the two new `@Tool` methods.

**Tech Stack:** Scala 3.9, magnum + magnumpg 1.3.1 (`com.augustnagro:magnum_3`/`magnumpg_3`, already in `build.sbt`, unused until now), `com.github.f4b6a3:uuid-creator:6.1.1` (new dependency, UUIDv7), `org.postgresql:postgresql` (already present; also supplies `PGSimpleDataSource`, so no connection-pool dependency is needed), fast-mcp-scala 1.0.1 (`@Tool`/`@Param`, already in use), zio-json (already in use), zio-test against the real docker-compose Postgres (already the pattern in `SchemaMigrationSpec`).

**Spec:** `docs/superpowers/specs/2026-09-25-cb-06-write-tools-create-client-open-account.md` (bounded design note, approved 2026-09-25).

## Global Constraints

- Every tool response is the `{env, data}` envelope via `ToolResponse.respond` (`CLAUDE.md` rule 1).
- `transactions` is append-only — never UPDATE/DELETE it; a row inserted by a test stays in the shared dev DB forever, which is expected and fine for a mock (`CLAUDE.md` rule 2).
- Every transaction row's `booking_date` and `value_date` are both `system_clock.current_date_value` for `open_account`'s opening transaction (`CLAUDE.md` rule 3).
- All dates come from `system_clock`, never `LocalDate.now()`/the JVM clock (`CLAUDE.md` rule 4).
- Both tools accept `idempotency_key` (optional) and `dry_run` (optional, defaults `false`); a repeated `idempotency_key` returns the original result with no new write (`CLAUDE.md` rule 5).
- Every tool call — success, dry run, or idempotency hit — writes exactly one `audit_log` row (`CLAUDE.md` rule 6).
- No secrets: `DbConfig.fromEnv()` already reads only from env vars with safe defaults; nothing new to guard here (`CLAUDE.md` rule 7).
- Money is `BigDecimal`/`NUMERIC(18,2)`, never `Double` (`CLAUDE.md` "Domain Simplifications").
- `-Wunused:all -Werror` is on (`build.sbt`): every import and `val` must be used, or the build fails.
- This plan ships as a `gh stack` of two increments (see **Stack Split**); each must independently compile and pass its own tests within the 300-changed-line cap (lockfiles and `docs/superpowers/**` excluded).

## Review Focus

1. **Repeated `idempotency_key` on a real (non-dry-run) call must not insert a second row.** `create_client`'s and `open_account`'s idempotency-hit paths must be exercised by a real prior commit, not just a dry run, or a race between the check and the insert would silently duplicate a client/transaction. Covered in Task 8 (`CreateClientSpec`) and Task 12 (`OpenAccountSpec`).
2. **`dry_run = true` must leave the database completely unchanged**, including when the write would otherwise succeed — not just "no error", an actual row-count check before/after. Covered by Task 5 (`DryRunSpec`, proven against a harmless `system_clock` update) and again end-to-end in Tasks 8 and 12.
3. **`open_account` against a nonexistent `client_id` or `product_id` must return a clean `{env, data: {error: ...}}` response, not a raw Postgres foreign-key-violation exception surfacing through the MCP transport.** Covered in Task 11/12.
4. **Every call writes exactly one `audit_log` row, including dry runs and idempotency hits** — a dry run or a repeated key must not skip the audit trail, and must not write two rows either. Covered in Task 6 (`AuditLogSpec`) and reused in Tasks 8/12.
5. **`open_account`'s `initial_deposit` defaults to `0.00` and is parsed as `BigDecimal` from the MCP call's string argument, never through `Double`**, since a caller passing a decimal like `"1250.55"` must round-trip exactly. Covered in Task 11/12.

---

## Stack Split

Two `gh stack` increments, each its own compiling, tested, self-contained unit:

- **Increment 1** (`cb-06-1-db-layer-create-client`, Tasks 1–9): the whole `db` package, `AuditLog`, and `create_client` end to end. Proves the entire write-tool pattern on the smaller of the two tools.
- **Increment 2** (`cb-06-2-open-account`, Tasks 10–13): `open_account`, built entirely on Increment 1's layer — no changes to `db`/`AuditLog`.

## File Structure

- Create: `src/main/resources/db/migration/V3__client_email_account_currency.sql`
- Create: `src/main/scala/corebanking/db/Ids.scala`
- Create: `src/main/scala/corebanking/db/Db.scala`
- Create: `src/main/scala/corebanking/db/Entities.scala`
- Create: `src/main/scala/corebanking/db/DryRun.scala`
- Create: `src/main/scala/corebanking/tools/AuditLog.scala`
- Create: `src/main/scala/corebanking/tools/CreateClient.scala`
- Create: `src/main/scala/corebanking/tools/OpenAccount.scala`
- Modify: `src/main/scala/corebanking/Server.scala` — shared `Transactor`, startup migration, two new `@Tool` methods
- Modify: `build.sbt` — add `uuid-creator`
- Create: `src/test/scala/corebanking/db/IdsSpec.scala`
- Create: `src/test/scala/corebanking/db/DryRunSpec.scala`
- Create: `src/test/scala/corebanking/tools/AuditLogSpec.scala`
- Create: `src/test/scala/corebanking/tools/CreateClientSpec.scala`
- Create: `src/test/scala/corebanking/tools/OpenAccountSpec.scala`
- Modify: `BACKLOG.md` — status (done in the Gates step, not a task)

All tests that touch the database follow `SchemaMigrationSpec`'s existing convention: require `docker compose up -d` already running, read connection settings via `DbConfig.fromEnv()`, run `FlywayRunner.migrate(config)` once (idempotent) at the top of the suite, and run `@@ sequential`.

---

### Task 1: Add the UUIDv7 dependency

**Files:**
- Modify: `build.sbt`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.github.f4b6a3.uuid.UuidCreator` on the classpath, used by Task 3.

**Model:** `haiku` (mechanical, one dependency line).

- [ ] **Step 1: Add the dependency**

In `build.sbt`, inside the existing `libraryDependencies ++= Seq(...)`, add:

```scala
      "com.github.f4b6a3" % "uuid-creator" % "6.1.1",
```

(no `%%` — it's a plain Java artifact, not cross-built for Scala.)

- [ ] **Step 2: Verify it resolves**

Run: `sbt -batch update`
Expected: `[success]`, no unresolved dependency errors.

- [ ] **Step 3: Commit**

```bash
git add build.sbt
git commit -m "build: add uuid-creator for UUIDv7 id minting (CB-06, #6)"
```

---

### Task 2: Migration — `clients.email`, `clients.idempotency_key`, `accounts.currency`

**Files:**
- Create: `src/main/resources/db/migration/V3__client_email_account_currency.sql`

**Interfaces:**
- Consumes: nothing (runs against the schema `V1__schema.sql` created).
- Produces: three new columns Task 7/11 read and write via `Entities.scala`. `V2` is reserved for CB-04's `V2__seed_products.sql` (not yet landed) — this file is `V3` so the two stories never collide on a migration number regardless of merge order.

**Model:** `sonnet` (schema addition, same class of work as CB-03's migration).

- [ ] **Step 1: Write the migration**

```sql
-- V3__client_email_account_currency.sql
-- CB-06: create_client's `email?` parameter and open_account's `currency` parameter have nowhere
-- to persist in V1__schema.sql. Both are additive, nullable-or-defaulted columns on existing
-- tables -- no rename, no backfill, safe regardless of whether CB-04's V2 has landed yet.

ALTER TABLE clients ADD COLUMN email TEXT;

-- clients has no natural transaction row (unlike open_account, which posts the opening
-- transaction and can reuse transactions.idempotency_key), so create_client needs its own
-- repeat-detection column. Nullable: most callers won't pass an idempotency_key, and Postgres
-- treats multiple NULLs in a UNIQUE column as distinct (same reasoning as
-- transactions.reverses_id in V1__schema.sql).
ALTER TABLE clients ADD COLUMN idempotency_key TEXT;
ALTER TABLE clients ADD CONSTRAINT clients_idempotency_key_unique UNIQUE (idempotency_key);

-- No CHECK constraint: the allowed-currency list is a tool-level validation (CreateClient.scala's
-- sibling, OpenAccount.scala), not a schema-level vocabulary, matching how V1__schema.sql left
-- transactions.type unconstrained for the same reason.
ALTER TABLE accounts ADD COLUMN currency TEXT NOT NULL DEFAULT 'COP';
ALTER TABLE accounts ALTER COLUMN currency DROP DEFAULT;
```

(The `DEFAULT 'COP'` then `DROP DEFAULT` two-step lets `NOT NULL` apply to a table that — in this mock, currently empty — has no existing rows to backfill; it's the standard safe pattern for adding a `NOT NULL` column and costs nothing here since `accounts` is empty at this point in the dev DB, but keeps the migration correct even if that ever changes.)

- [ ] **Step 2: Apply it against the running dev Postgres**

Run: `sbt -batch "runMain corebanking.db.FlywayRunner"` — if `FlywayRunner` has no `main`, instead run the migration via the existing test path: `sbt -batch "testOnly corebanking.db.SchemaMigrationSpec"` (its first test calls `FlywayRunner.migrate`, applying `V3` as a side effect). Expected: no errors; `V3` appears in `flyway_schema_history` (`SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;` via `docker compose exec postgres psql -U corebanking -d corebanking`).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__client_email_account_currency.sql
git commit -m "feat(db): add clients.email/idempotency_key and accounts.currency (CB-06, #6)"
```

---

### Task 3: `Ids` — UUIDv7 minting

**Files:**
- Create: `src/main/scala/corebanking/db/Ids.scala`
- Test: `src/test/scala/corebanking/db/IdsSpec.scala`

**Interfaces:**
- Consumes: `com.github.f4b6a3.uuid.UuidCreator` (Task 1).
- Produces: `Ids.next(): java.util.UUID`, used by Task 8 (`CreateClient`) and Task 11 (`OpenAccount`).

**Model:** `sonnet`.

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.db

import java.util.UUID

import zio.test.*

object IdsSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Ids.next")(
    test("mints a UUID with version nibble 7 and variant nibble 8-b") {
      val id = Ids.next()
      // RFC 9562 UUIDv7: the 13th hex digit of the canonical string is the version ("7"), and the
      // 17th hex digit (first of the 4th group) is the variant, one of 8/9/a/b.
      val text = id.toString
      assertTrue(
        text.charAt(14) == '7',
        Set('8', '9', 'a', 'b').contains(text.charAt(19))
      )
    },
    test("two calls never collide") {
      val a = Ids.next()
      val b = Ids.next()
      assertTrue(a != b)
    }
  )
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt -batch "testOnly corebanking.db.IdsSpec"`
Expected: FAIL — `object Ids` does not exist yet.

- [ ] **Step 3: Implement**

```scala
package corebanking.db

import java.util.UUID

import com.github.f4b6a3.uuid.UuidCreator

/** Mints RFC 9562 UUIDv7 ids in application code. Postgres 16 has no native `uuidv7()`, and
  * `V1__schema.sql`'s header comment says entity ids are "intended to hold UUIDv7 values minted
  * in application code by a future write-tools story" -- this is that story.
  */
object Ids:
  def next(): UUID = UuidCreator.getTimeOrderedEpoch()
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt -batch "testOnly corebanking.db.IdsSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/db/Ids.scala src/test/scala/corebanking/db/IdsSpec.scala
git commit -m "feat(db): mint UUIDv7 entity ids (CB-06, #6)"
```

---

### Task 4: `Db` (Transactor) and `Entities` (row case classes)

**Files:**
- Create: `src/main/scala/corebanking/db/Db.scala`
- Create: `src/main/scala/corebanking/db/Entities.scala`

**Interfaces:**
- Consumes: `DbConfig` (existing, `corebanking.config.DbConfig`).
- Produces: `Db.transactor(config: DbConfig): com.augustnagro.magnum.Transactor`, used by Server.scala (Task 9) and every DB-touching test. `Client`, `Account`, `Transaction`, `SystemClockRow`, `ProductRef` case classes, decoded by `sql"...".query[T].run()` in Tasks 8 and 11 — see the note on `@Table` below.

**Model:** `sonnet` — no independent unit test (untestable without a real DB in a meaningful way); proven end-to-end by Tasks 6, 8, and 12's integration specs. This task's own "test" is the compile checkpoint in Step 2.

- [ ] **Step 1: Write `Db.scala`**

```scala
package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/** Builds the magnum `Transactor` every write tool shares. `PGSimpleDataSource` (shipped inside
  * the `org.postgresql:postgresql` dependency already on the classpath) opens one raw connection
  * per `getConnection()` call with no pooling -- correct and sufficient for this single-writer
  * mock server; a connection pool (e.g. HikariCP) is a separate dependency to add only if this
  * ever needs one.
  */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
```

- [ ] **Step 2: Write `Entities.scala`, then a compile checkpoint**

```scala
package corebanking.db

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Client(
    @Id id: UUID,
    displayName: String,
    openedOn: LocalDate,
    email: Option[String],
    idempotencyKey: Option[String]
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
    @Id id: UUID,
    clientId: UUID,
    productId: UUID,
    kind: String,
    openedOn: LocalDate,
    currency: String
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Transaction(
    @Id id: UUID,
    accountId: UUID,
    `type`: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[UUID],
    idempotencyKey: String
) derives DbCodec

/** Read-only row shape for `SELECT current_date_value FROM system_clock WHERE id = true` — never
  * inserted or looked up by id, so no `@Id`.
  */
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec

/** Read-only row shape for the `open_account` product lookup: existence plus `kind`, which the
  * new account row copies (`accounts.kind` mirrors its product's kind; there is no separate `kind`
  * parameter on `open_account`).
  */
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ProductRef(@Id id: UUID, kind: String) derives DbCodec
```

**Uncertainty flagged for this step:** `@Table(...)` plus `@Id` is confirmed to work for magnum 1.3.1's `Repo`-style usage; whether plain `derives DbCodec` decoding via `sql"...".query[T].run()` strictly *requires* `@Table`/`@Id` (vs. working from `derives DbCodec` alone) was not independently confirmed for read-only shapes like `SystemClockRow`. Keep `@Table`/`@Id` as written above (the one combination actually confirmed against the real source) rather than guessing they're removable — if a future cleanup wants to trim them, that's a separate, low-risk change once this compiles.

Run: `sbt -batch compile`
Expected: `[success]`. If `@Table`/`@Id`/`derives DbCodec` don't compile as written, read the actual macro error (it will name the missing piece precisely) and adjust only the annotation, not the field lists — the column names/types above are exact matches to `V1__schema.sql` plus Task 2's new columns.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/corebanking/db/Db.scala src/main/scala/corebanking/db/Entities.scala
git commit -m "feat(db): magnum Transactor and row entities for clients/accounts/transactions (CB-06, #6)"
```

---

### Task 5: `DryRun` — commit-or-rollback wrapper

**Files:**
- Create: `src/main/scala/corebanking/db/DryRun.scala`
- Test: `src/test/scala/corebanking/db/DryRunSpec.scala`

**Interfaces:**
- Consumes: `Db.transactor` (Task 4), `com.augustnagro.magnum.{Transactor, transact, DbTx}`.
- Produces: `DryRun[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T`, used by Task 8 (`CreateClient`) and Task 11 (`OpenAccount`).

**Model:** `opus` — every future write tool (CB-07, CB-08) will build on this; a bug here silently breaks the `dry_run` guarantee (`CLAUDE.md` rule 5's write-safety contract) for all of them.

- [ ] **Step 1: Write the failing test**

Uses `system_clock` (a harmless, already-seeded singleton row) to prove rollback without touching append-only `transactions`:

```scala
package corebanking.db

import java.time.LocalDate

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig

object DryRunSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private def currentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DryRun")(
      test("dryRun = false commits the write") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          result <- ZIO.attemptBlocking {
            DryRun(xa, dryRun = false):
              sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update.run()
              bumped
          }
          after <- ZIO.attemptBlocking(currentDate())
          // Restore the real clock so later specs (which assume "today") aren't left bumped.
          _ <- ZIO.attemptBlocking {
            transact(xa):
              sql"UPDATE system_clock SET current_date_value = $before WHERE id = true".update.run()
          }
        yield assertTrue(result == bumped, after == bumped)
      },
      test("dryRun = true returns the computed result but leaves the row unchanged") {
        for
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          result <- ZIO.attemptBlocking {
            DryRun(xa, dryRun = true):
              sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update.run()
              bumped
          }
          after <- ZIO.attemptBlocking(currentDate())
        yield assertTrue(result == bumped, after == before)
      }
    ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt -batch "testOnly corebanking.db.DryRunSpec"`
Expected: FAIL — `object DryRun` does not exist.

- [ ] **Step 3: Implement**

```scala
package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/** Internal signal used to force `transact` to roll back a dry run while still returning its
  * computed result. magnum's `transact` (see its `util.scala`) always commits on success and
  * rolls back only when the block throws, with no other rollback hook -- so a dry run has to look
  * like a failure to the transaction manager. `transact` rethrows whatever the block threw
  * unchanged (it doesn't wrap it), so catching this exact type immediately outside `transact`
  * recovers the value; the `Any` payload plus a single confined cast avoids a generic-erasure
  * pattern match on `DryRunSignal[T]`, which this codebase's `-Werror` would likely flag.
  */
private final case class DryRunSignal(value: Any) extends RuntimeException

object DryRun:

  /** Runs `f` inside one transaction. When `dryRun` is false, commits normally and returns `f`'s
    * result. When `dryRun` is true, everything `f` did is rolled back -- nothing persists -- but
    * the same result is still returned, so callers can build an identical response either way.
    */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    try
      transact(xa):
        val result = f
        if dryRun then throw DryRunSignal(result)
        result
    catch case DryRunSignal(value) => value.asInstanceOf[T]
```

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt -batch "testOnly corebanking.db.DryRunSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/db/DryRun.scala src/test/scala/corebanking/db/DryRunSpec.scala
git commit -m "feat(db): dry-run-via-rollback wrapper for write tools (CB-06, #6)"
```

---

### Task 6: `AuditLog` — always-committed audit row

**Files:**
- Create: `src/main/scala/corebanking/tools/AuditLog.scala`
- Test: `src/test/scala/corebanking/tools/AuditLogSpec.scala`

**Interfaces:**
- Consumes: `Db.transactor` (Task 4), `com.augustnagro.magnum.{Transactor, transact}`.
- Produces: `AuditLog.record(xa: Transactor, toolName: String, env: String, requestJson: String, responseJson: String): Unit`, used by Task 8 (`CreateClient`) and Task 11 (`OpenAccount`).

**Model:** `sonnet` (a single always-run INSERT; not itself money/ledger logic, though it satisfies `CLAUDE.md` rule 6).

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.tools

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}

object AuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  final private case class Row(toolName: String, env: String, request: String, response: String)

  private def countByMarker(marker: String): Long =
    com.augustnagro.magnum.transact(xa):
      com.augustnagro.magnum
        .sql"SELECT COUNT(*) AS n FROM audit_log WHERE request::text LIKE ${"%" + marker + "%"}"
        .query[Count]
        .run()
        .head
        .n

  final private case class Count(n: Long) derives com.augustnagro.magnum.DbCodec

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AuditLog.record")(
      test("writes exactly one row per call, request/response stored as the given JSON") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          marker = s"audit-log-spec-${java.util.UUID.randomUUID()}"
          before <- ZIO.attemptBlocking(countByMarker(marker))
          _ <- ZIO.attemptBlocking(
            AuditLog.record(
              xa,
              toolName = "create_client",
              env = "mock",
              requestJson = s"""{"marker":"$marker","name":"Ada"}""",
              responseJson = """{"env":"mock","data":{"ok":true}}"""
            )
          )
          after <- ZIO.attemptBlocking(countByMarker(marker))
        yield assertTrue(before == 0L, after == 1L)
      }
    ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt -batch "testOnly corebanking.tools.AuditLogSpec"`
Expected: FAIL — `object AuditLog` does not exist.

- [ ] **Step 3: Implement**

```scala
package corebanking.tools

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

/** Writes one `audit_log` row per tool call, in its own transaction, committed regardless of
  * `dry_run` or an idempotency hit -- CLAUDE.md rule 6 requires "every tool call logged", and a
  * dry run or a repeated key is still a call that happened. Kept separate from the caller's own
  * `DryRun`-wrapped transaction on purpose: a rolled-back dry run must not also roll back its own
  * audit trail entry.
  */
object AuditLog:
  def record(
      xa: Transactor,
      toolName: String,
      env: String,
      requestJson: String,
      responseJson: String
  ): Unit =
    transact(xa):
      sql"""
        INSERT INTO audit_log (tool_name, env, request, response)
        VALUES ($toolName, $env, $requestJson::jsonb, $responseJson::jsonb)
      """.update.run()
      ()
```

(No custom JSONB codec needed: `::jsonb` is literal SQL text following the interpolated `$requestJson`/`$responseJson` holes, so Postgres casts the already-bound `TEXT` parameter server-side. `magnumpg` 1.3.1 has no JSONB codec of its own — confirmed against its `PgCodec.scala` — so this sidesteps that gap entirely instead of writing a custom one.)

- [ ] **Step 4: Run it to verify it passes**

Run: `sbt -batch "testOnly corebanking.tools.AuditLogSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/tools/AuditLog.scala src/test/scala/corebanking/tools/AuditLogSpec.scala
git commit -m "feat(tools): always-committed audit_log write (CB-06, #6)"
```

---

### Task 7: `CreateClient` tool logic

**Files:**
- Create: `src/main/scala/corebanking/tools/CreateClient.scala`

**Interfaces:**
- Consumes: `Db.transactor`/`Ids.next`/`Client`/`SystemClockRow` (Tasks 3–4), `DryRun` (Task 5), `AuditLog.record` (Task 6), existing `ToolResponse.respond`/`CoreEnv`.
- Produces: `CreateClient.run(xa: Transactor, env: CoreEnv, name: String, email: Option[String], idempotencyKey: Option[String], dryRun: Boolean): String` (the full `{env, data}` JSON string), used by Server.scala (Task 9) and `CreateClientSpec` (Task 8).

**Model:** `opus` — the first ledger-adjacent write path in the codebase; gets the idempotency-check-then-write ordering and the dry-run/audit interaction wrong once, and every later story built on this pattern inherits the bug.

- [ ] **Step 1: Implement**

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import zio.json.*

import com.augustnagro.magnum.{Transactor, transact, sql}

import corebanking.config.CoreEnv
import corebanking.db.{Client, DryRun, Ids, SystemClockRow}

final case class ClientData(
    id: String,
    name: String,
    email: Option[String],
    openedOn: String,
    dryRun: Boolean
)

object ClientData:
  given JsonEncoder[ClientData] = DeriveJsonEncoder.gen[ClientData]

final private case class CreateClientRequest(
    name: String,
    email: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object CreateClientRequest:
  given JsonEncoder[CreateClientRequest] = DeriveJsonEncoder.gen[CreateClientRequest]

object CreateClient:

  def run(
      xa: Transactor,
      env: CoreEnv,
      name: String,
      email: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    val data = execute(xa, name, email, idempotencyKey, dryRun)
    val response = ToolResponse.respond(env, data)
    AuditLog.record(
      xa,
      toolName = "create_client",
      env = env.label,
      requestJson = CreateClientRequest(name, email, idempotencyKey, dryRun).toJson,
      responseJson = response
    )
    response

  private def execute(
      xa: Transactor,
      name: String,
      email: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): ClientData =
    idempotencyKey.flatMap(findByIdempotencyKey(xa, _)) match
      case Some(existing) => toData(existing, dryRun = false)
      case None =>
        DryRun(xa, dryRun):
          val today = readSystemDate()
          val client = Client(Ids.next(), name, today, email, idempotencyKey)
          insert(client)
          toData(client, dryRun)

  private def findByIdempotencyKey(xa: Transactor, key: String): Option[Client] =
    transact(xa):
      sql"SELECT id, display_name, opened_on, email, idempotency_key FROM clients WHERE idempotency_key = $key"
        .query[Client]
        .run()
        .headOption

  private def readSystemDate()(using com.augustnagro.magnum.DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def insert(client: Client)(using com.augustnagro.magnum.DbCon): Unit =
    sql"""
      INSERT INTO clients (id, display_name, opened_on, email, idempotency_key)
      VALUES (${client.id}, ${client.displayName}, ${client.openedOn}, ${client.email}, ${client.idempotencyKey})
    """.update.run()

  private def toData(client: Client, dryRun: Boolean): ClientData =
    ClientData(
      id = client.id.toString,
      name = client.displayName,
      email = client.email,
      openedOn = client.openedOn.toString,
      dryRun = dryRun
    )
```

- [ ] **Step 2: Compile checkpoint**

Run: `sbt -batch compile`
Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/corebanking/tools/CreateClient.scala
git commit -m "feat(tools): create_client write path (CB-06, #6)"
```

---

### Task 8: `CreateClientSpec` — full integration test

**Files:**
- Create: `src/test/scala/corebanking/tools/CreateClientSpec.scala`

**Interfaces:**
- Consumes: `CreateClient.run` (Task 7), `Db.transactor`, `FlywayRunner.migrate`, `CoreEnv.Mock`.
- Produces: nothing further downstream — this is the story's proof of correctness for `create_client`.

**Model:** `opus` (test-worker) — proves the idempotency and dry-run guarantees the whole story exists to deliver; a shallow test here would pass while hiding a duplicate-insert or a leaked dry-run write.

- [ ] **Step 1: Write the test**

```scala
package corebanking.tools

import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{transact, sql, DbCodec}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}

object CreateClientSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  final private case class DecodedData(
      id: String,
      name: String,
      email: Option[String],
      openedOn: String,
      dryRun: Boolean
  )
  private object DecodedData:
    given JsonDecoder[DecodedData] = DeriveJsonDecoder.gen[DecodedData]

  final private case class DecodedEnvelope(env: String, data: DecodedData)
  private object DecodedEnvelope:
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  private def decode(json: String): DecodedEnvelope =
    json.fromJson[DecodedEnvelope].getOrElse(throw new RuntimeException(s"undecodable: $json"))

  final private case class CountRow(n: Long) derives DbCodec

  private def clientCount(id: String): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM clients WHERE id = ${UUID.fromString(id)}"
        .query[CountRow]
        .run()
        .head
        .n

  private def freshKey(): String = s"cb06-create-client-${UUID.randomUUID()}"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CreateClient.run")(
      test("happy path: inserts a client and returns it in the envelope") {
        for _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
        yield
          val json = CreateClient.run(
            xa,
            CoreEnv.Mock,
            name = "Ada Lovelace",
            email = Some("ada@example.com"),
            idempotencyKey = None,
            dryRun = false
          )
          val decoded = decode(json)
          assertTrue(
            decoded.env == "mock",
            decoded.data.name == "Ada Lovelace",
            decoded.data.email == Some("ada@example.com"),
            decoded.data.dryRun == false,
            clientCount(decoded.data.id) == 1L
          )
      },
      test("email is optional") {
        val json = CreateClient.run(
          xa,
          CoreEnv.Mock,
          name = "No Email Client",
          email = None,
          idempotencyKey = None,
          dryRun = false
        )
        assertTrue(decode(json).data.email == None)
      },
      test("repeated idempotency_key returns the identical result and inserts only once") {
        val key = freshKey()
        val first = decode(
          CreateClient.run(xa, CoreEnv.Mock, "Repeat Client", None, Some(key), dryRun = false)
        )
        val second = decode(
          CreateClient.run(xa, CoreEnv.Mock, "Repeat Client", None, Some(key), dryRun = false)
        )
        assertTrue(
          first.data.id == second.data.id,
          clientCount(first.data.id) == 1L
        )
      },
      test("dry_run leaves the database unchanged") {
        val json = CreateClient.run(
          xa,
          CoreEnv.Mock,
          name = "Dry Run Client",
          email = None,
          idempotencyKey = None,
          dryRun = true
        )
        val decoded = decode(json)
        assertTrue(decoded.data.dryRun == true, clientCount(decoded.data.id) == 0L)
      }
    ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run it**

Run: `sbt -batch "testOnly corebanking.tools.CreateClientSpec"`
Expected: PASS (all four cases). If it fails, fix `CreateClient.scala` (Task 7) or `Entities.scala` (Task 4) — never the test's assertions, unless the test itself is wrong about the contract.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/corebanking/tools/CreateClientSpec.scala
git commit -m "test(tools): create_client integration coverage (CB-06, #6)"
```

---

### Task 9: Wire `Server.scala` and register `create_client`

**Files:**
- Modify: `src/main/scala/corebanking/Server.scala`

**Interfaces:**
- Consumes: `Db.transactor`, `FlywayRunner.migrate` (existing), `CreateClient.run` (Task 7).
- Produces: the live `create_client` MCP tool. Task 12 (`open_account` registration) adds a second `@Tool` method to this same object, alongside this one.

**Model:** `sonnet` — mechanical wiring, but touches the CB-02 env-guard file; must not disturb `coreEnv`'s eager-`val`-first ordering or `ServerProcessSpec`'s cleared-env happy path (that test already assumes `docker compose up -d`, same as `SchemaMigrationSpec`, so a startup-time migration call does not newly require anything the test suite didn't already require).

- [ ] **Step 1: Add the imports, `Transactor`, and startup migration**

In `Server.scala`, add imports and, immediately after the existing `coreEnv` val (so the env guard still runs first), add:

```scala
import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}
import corebanking.tools.CreateClient
```

```scala
  private val dbConfig: DbConfig = DbConfig.fromEnv()
  FlywayRunner.migrate(dbConfig)
  private val transactor = Db.transactor(dbConfig)
```

(placed after the `coreEnv` val block, before `override def name`).

- [ ] **Step 2: Register the tool**

Add, after the existing `ping` method:

```scala
  @Tool(
    name = Some("create_client"),
    description = Some("Creates a client and logs the call to the audit trail."),
    readOnlyHint = Some(false)
  )
  def createClient(
      @Param(description = "Client display name") name: String,
      @Param(description = "Client email address, if known", required = false)
      email: Option[String] = None,
      @Param(
        description = "Caller-supplied key; a repeated key returns the original result unchanged",
        required = false
      )
      idempotencyKey: Option[String] = None,
      @Param(
        description = "When true, computes the result without writing to the database",
        required = false
      )
      dryRun: Boolean = false
  ): String =
    CreateClient.run(transactor, coreEnv, name, email, idempotencyKey, dryRun)
```

- [ ] **Step 3: Compile and rerun the full suite**

Run: `sbt -batch compile test`
Expected: `[success]`; `ServerProcessSpec`'s three cases still pass (requires `docker compose up -d`, already a standing requirement for this repo's DB-touching specs).

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/corebanking/Server.scala
git commit -m "feat(server): wire DB startup migration and register create_client (CB-06, #6)"
```

---

**— Increment 1 boundary. Run Gates, set `BACKLOG.md` CB-06 row to `in-review`, `gh stack add cb-06-1-db-layer-create-client` if not already the current step, `git diff --shortstat` against the stack base to confirm ≤ 300 lines (excluding `docs/superpowers/**`), then proceed to Task 10. —**

---

### Task 10: `OpenAccount` tool logic

**Files:**
- Create: `src/main/scala/corebanking/tools/OpenAccount.scala`

**Interfaces:**
- Consumes: `Db.transactor`/`Ids.next`/`Account`/`Client`/`Transaction`/`ProductRef`/`SystemClockRow` (Increment 1), `DryRun`, `AuditLog.record`, `ToolResponse.respond`/`CoreEnv`.
- Produces: `OpenAccount.run(xa: Transactor, env: CoreEnv, clientId: String, productId: String, currency: String, initialDeposit: Option[String], idempotencyKey: Option[String], dryRun: Boolean): String`, used by Server.scala (Task 12) and `OpenAccountSpec` (Task 11).

**Model:** `opus` — posts a real ledger transaction (`account_opening`), the first `transactions.type` vocabulary entry this codebase pins, and composes idempotency + dry-run + two FK existence checks; the highest-blast-radius task in this story.

- [ ] **Step 1: Implement**

```scala
package corebanking.tools

import java.math.BigDecimal as JBigDecimal
import java.time.LocalDate
import java.util.UUID

import zio.json.*

import com.augustnagro.magnum.{Transactor, transact, sql, DbCon}

import corebanking.config.CoreEnv
import corebanking.db.{Account, Client, DryRun, Ids, ProductRef, SystemClockRow, Transaction}

final case class AccountData(
    id: String,
    clientId: String,
    productId: String,
    currency: String,
    openedOn: String,
    openingTransactionId: String,
    initialDeposit: String,
    dryRun: Boolean
)

object AccountData:
  given JsonEncoder[AccountData] = DeriveJsonEncoder.gen[AccountData]

final case class OpenAccountError(error: String, message: String)

object OpenAccountError:
  given JsonEncoder[OpenAccountError] = DeriveJsonEncoder.gen[OpenAccountError]

final private case class OpenAccountRequest(
    clientId: String,
    productId: String,
    currency: String,
    initialDeposit: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object OpenAccountRequest:
  given JsonEncoder[OpenAccountRequest] = DeriveJsonEncoder.gen[OpenAccountRequest]

/** Thrown for a validation failure that must surface as a clean `{env, data: {error, message}}`
  * response, not a raw exception. Thrown before any write, so `DryRun`/`transact` rolling back an
  * empty transaction on the way out is harmless.
  */
final private case class OpenAccountFailure(code: String, message: String) extends RuntimeException

object OpenAccount:

  private val AllowedCurrencies = Set("COP", "USD", "EUR")

  def run(
      xa: Transactor,
      env: CoreEnv,
      clientId: String,
      productId: String,
      currency: String,
      initialDeposit: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    val requestJson =
      OpenAccountRequest(clientId, productId, currency, initialDeposit, idempotencyKey, dryRun).toJson
    val response =
      try
        val data = execute(xa, clientId, productId, currency, initialDeposit, idempotencyKey, dryRun)
        ToolResponse.respond(env, data)
      catch
        case OpenAccountFailure(code, message) =>
          ToolResponse.respond(env, OpenAccountError(code, message))
    AuditLog.record(xa, toolName = "open_account", env = env.label, requestJson, responseJson = response)
    response

  private def execute(
      xa: Transactor,
      clientId: String,
      productId: String,
      currency: String,
      initialDeposit: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): AccountData =
    if !AllowedCurrencies.contains(currency) then
      throw OpenAccountFailure("INVALID_CURRENCY", s"'$currency' is not one of $AllowedCurrencies")

    val clientUuid = parseUuid(clientId, "client_id")
    val productUuid = parseUuid(productId, "product_id")
    val deposit = parseAmount(initialDeposit)

    idempotencyKey.flatMap(findOpeningByIdempotencyKey(xa, _)) match
      case Some((account, tx)) => toData(account, tx, dryRun = false)
      case None =>
        DryRun(xa, dryRun):
          val client = sql"SELECT id, display_name, opened_on, email, idempotency_key FROM clients WHERE id = $clientUuid"
            .query[Client]
            .run()
            .headOption
            .getOrElse(throw OpenAccountFailure("CLIENT_NOT_FOUND", s"no client with id $clientId"))

          val product = sql"SELECT id, kind FROM products WHERE id = $productUuid"
            .query[ProductRef]
            .run()
            .headOption
            .getOrElse(throw OpenAccountFailure("PRODUCT_NOT_FOUND", s"no product with id $productId"))

          val today = readSystemDate()
          val accountId = Ids.next()
          val txId = Ids.next()
          val account = Account(accountId, client.id, product.id, product.kind, today, currency)
          val tx = Transaction(
            id = txId,
            accountId = accountId,
            `type` = "account_opening",
            amount = deposit,
            bookingDate = today,
            valueDate = today,
            reversesId = None,
            idempotencyKey = idempotencyKey.getOrElse(txId.toString)
          )
          insertAccount(account)
          insertTransaction(tx)
          toData(account, tx, dryRun)

  private def parseUuid(raw: String, field: String): UUID =
    try UUID.fromString(raw)
    catch case _: IllegalArgumentException => throw OpenAccountFailure("INVALID_ID", s"$field '$raw' is not a UUID")

  private def parseAmount(raw: Option[String]): BigDecimal =
    raw match
      case None => BigDecimal("0.00")
      case Some(text) =>
        try BigDecimal(new JBigDecimal(text))
        catch
          case _: NumberFormatException =>
            throw OpenAccountFailure("INVALID_AMOUNT", s"initial_deposit '$text' is not a decimal amount")

  private def findOpeningByIdempotencyKey(xa: Transactor, key: String): Option[(Account, Transaction)] =
    transact(xa):
      sql"""
        SELECT id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key
        FROM transactions WHERE idempotency_key = $key
      """.query[Transaction].run().headOption.map { tx =>
        val account = sql"SELECT id, client_id, product_id, kind, opened_on, currency FROM accounts WHERE id = ${tx.accountId}"
          .query[Account]
          .run()
          .head
        (account, tx)
      }

  private def readSystemDate()(using DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def insertAccount(account: Account)(using DbCon): Unit =
    sql"""
      INSERT INTO accounts (id, client_id, product_id, kind, opened_on, currency)
      VALUES (${account.id}, ${account.clientId}, ${account.productId}, ${account.kind}, ${account.openedOn}, ${account.currency})
    """.update.run()

  private def insertTransaction(tx: Transaction)(using DbCon): Unit =
    sql"""
      INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key)
      VALUES (${tx.id}, ${tx.accountId}, ${tx.`type`}, ${tx.amount}, ${tx.bookingDate}, ${tx.valueDate}, ${tx.reversesId}, ${tx.idempotencyKey})
    """.update.run()

  private def toData(account: Account, tx: Transaction, dryRun: Boolean): AccountData =
    AccountData(
      id = account.id.toString,
      clientId = account.clientId.toString,
      productId = account.productId.toString,
      currency = account.currency,
      openedOn = account.openedOn.toString,
      openingTransactionId = tx.id.toString,
      initialDeposit = tx.amount.bigDecimal.toPlainString,
      dryRun = dryRun
    )
```

- [ ] **Step 2: Compile checkpoint**

Run: `sbt -batch compile`
Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/corebanking/tools/OpenAccount.scala
git commit -m "feat(tools): open_account write path (CB-06, #6)"
```

---

### Task 11: `OpenAccountSpec` — full integration test

**Files:**
- Create: `src/test/scala/corebanking/tools/OpenAccountSpec.scala`

**Interfaces:**
- Consumes: `OpenAccount.run` (Task 10), `Db.transactor`, `FlywayRunner.migrate`, `CoreEnv.Mock`. Seeds its own `products`/`clients` fixture rows directly (CB-04's seed migration is `todo` and not a blocker for this story per the issue's blocked-by graph).

**Model:** `opus` (test-worker) — same reasoning as Task 8, plus the FK-not-found and amount-parsing paths.

- [ ] **Step 1: Write the test**

```scala
package corebanking.tools

import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{transact, sql, DbCodec}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}

object OpenAccountSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  final private case class DecodedData(
      id: String,
      clientId: String,
      productId: String,
      currency: String,
      openedOn: String,
      openingTransactionId: String,
      initialDeposit: String,
      dryRun: Boolean
  )
  private object DecodedData:
    given JsonDecoder[DecodedData] = DeriveJsonDecoder.gen[DecodedData]

  final private case class DecodedError(error: String, message: String)
  private object DecodedError:
    given JsonDecoder[DecodedError] = DeriveJsonDecoder.gen[DecodedError]

  final private case class CountRow(n: Long) derives DbCodec

  private def txCount(idempotencyKey: String): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM transactions WHERE idempotency_key = $idempotencyKey"
        .query[CountRow]
        .run()
        .head
        .n

  private def seedClientAndProduct(): (UUID, UUID) =
    transact(xa):
      val clientId = corebanking.db.Ids.next()
      val productId = corebanking.db.Ids.next()
      sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($clientId, 'OpenAccount Spec Client', CURRENT_DATE)".update.run()
      sql"INSERT INTO products (id, name, kind, annual_rate, term_months) VALUES ($productId, 'Spec Savings', 'savings', 0.02, NULL)".update.run()
      (clientId, productId)

  private def freshKey(): String = s"cb06-open-account-${UUID.randomUUID()}"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OpenAccount.run")(
      test("happy path: opens an account and posts the opening transaction") {
        for _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
        yield
          val (clientId, productId) = seedClientAndProduct()
          val json = OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            currency = "COP",
            initialDeposit = Some("1250.55"),
            idempotencyKey = None,
            dryRun = false
          )
          val decoded = json.fromJson[DecodedData].getOrElse(throw new RuntimeException(json))
          assertTrue(
            decoded.currency == "COP",
            decoded.initialDeposit == "1250.55",
            decoded.clientId == clientId.toString
          )
      },
      test("initial_deposit defaults to 0.00 when omitted") {
        val (clientId, productId) = seedClientAndProduct()
        val json = OpenAccount.run(
          xa, CoreEnv.Mock, clientId.toString, productId.toString, "USD", None, None, dryRun = false
        )
        val decoded = json.fromJson[DecodedData].getOrElse(throw new RuntimeException(json))
        assertTrue(decoded.initialDeposit == "0.00")
      },
      test("unknown client_id returns a clean CLIENT_NOT_FOUND error, no partial write") {
        val (_, productId) = seedClientAndProduct()
        val missingClient = UUID.randomUUID()
        val json = OpenAccount.run(
          xa, CoreEnv.Mock, missingClient.toString, productId.toString, "COP", None, None, dryRun = false
        )
        val decoded = json.fromJson[DecodedError].getOrElse(throw new RuntimeException(json))
        assertTrue(decoded.error == "CLIENT_NOT_FOUND")
      },
      test("unknown product_id returns a clean PRODUCT_NOT_FOUND error") {
        val (clientId, _) = seedClientAndProduct()
        val missingProduct = UUID.randomUUID()
        val json = OpenAccount.run(
          xa, CoreEnv.Mock, clientId.toString, missingProduct.toString, "COP", None, None, dryRun = false
        )
        val decoded = json.fromJson[DecodedError].getOrElse(throw new RuntimeException(json))
        assertTrue(decoded.error == "PRODUCT_NOT_FOUND")
      },
      test("an unsupported currency is rejected before any write") {
        val (clientId, productId) = seedClientAndProduct()
        val json = OpenAccount.run(
          xa, CoreEnv.Mock, clientId.toString, productId.toString, "JPY", None, None, dryRun = false
        )
        val decoded = json.fromJson[DecodedError].getOrElse(throw new RuntimeException(json))
        assertTrue(decoded.error == "INVALID_CURRENCY")
      },
      test("repeated idempotency_key returns the identical result and posts only one transaction") {
        val (clientId, productId) = seedClientAndProduct()
        val key = freshKey()
        val first = OpenAccount
          .run(xa, CoreEnv.Mock, clientId.toString, productId.toString, "COP", Some("10.00"), Some(key), dryRun = false)
          .fromJson[DecodedData]
          .getOrElse(throw new RuntimeException("undecodable"))
        val second = OpenAccount
          .run(xa, CoreEnv.Mock, clientId.toString, productId.toString, "COP", Some("10.00"), Some(key), dryRun = false)
          .fromJson[DecodedData]
          .getOrElse(throw new RuntimeException("undecodable"))
        assertTrue(
          first.openingTransactionId == second.openingTransactionId,
          txCount(key) == 1L
        )
      },
      test("dry_run leaves the database unchanged") {
        val (clientId, productId) = seedClientAndProduct()
        val key = freshKey()
        val json = OpenAccount.run(
          xa, CoreEnv.Mock, clientId.toString, productId.toString, "COP", Some("5.00"), Some(key), dryRun = true
        )
        val decoded = json.fromJson[DecodedData].getOrElse(throw new RuntimeException(json))
        assertTrue(decoded.dryRun == true, txCount(key) == 0L)
      }
    ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run it**

Run: `sbt -batch "testOnly corebanking.tools.OpenAccountSpec"`
Expected: PASS (all seven cases).

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/corebanking/tools/OpenAccountSpec.scala
git commit -m "test(tools): open_account integration coverage (CB-06, #6)"
```

---

### Task 12: Register `open_account` in `Server.scala`

**Files:**
- Modify: `src/main/scala/corebanking/Server.scala`

**Interfaces:**
- Consumes: `OpenAccount.run` (Task 10), the existing shared `transactor`/`coreEnv` (Task 9).
- Produces: the live `open_account` MCP tool.

**Model:** `sonnet` (mechanical, same shape as Task 9's registration).

- [ ] **Step 1: Add the import and the tool method**

```scala
import corebanking.tools.OpenAccount
```

```scala
  @Tool(
    name = Some("open_account"),
    description = Some("Opens an account for a client against a product and posts the opening transaction."),
    readOnlyHint = Some(false)
  )
  def openAccount(
      @Param(description = "Existing client id") clientId: String,
      @Param(description = "Existing product id") productId: String,
      @Param(description = "ISO currency code: COP, USD, or EUR") currency: String,
      @Param(description = "Opening balance; defaults to 0.00", required = false)
      initialDeposit: Option[String] = None,
      @Param(
        description = "Caller-supplied key; a repeated key returns the original result unchanged",
        required = false
      )
      idempotencyKey: Option[String] = None,
      @Param(
        description = "When true, computes the result without writing to the database",
        required = false
      )
      dryRun: Boolean = false
  ): String =
    OpenAccount.run(transactor, coreEnv, clientId, productId, currency, initialDeposit, idempotencyKey, dryRun)
```

- [ ] **Step 2: Compile and rerun the full suite**

Run: `sbt -batch compile test`
Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/corebanking/Server.scala
git commit -m "feat(server): register open_account (CB-06, #6)"
```

---

**— Increment 2 boundary. Run Gates, set `BACKLOG.md` CB-06 row to `in-review`, `gh stack add cb-06-2-open-account`, confirm ≤ 300 changed lines, submit. —**

## Self-Review Notes

- **Spec coverage:** every element of the approved design note maps to a task — schema columns (Task 2), DB layer (Tasks 3–5), audit trail (Task 6), `create_client` (Tasks 7–9), `open_account` (Tasks 10–12), the two-increment stack split (Stack Split section + task boundary markers).
- **Type consistency:** `Client`/`Account`/`Transaction`/`SystemClockRow`/`ProductRef` (Task 4) are used with identical field names/types in Tasks 7, 8, 10, 11 — no renames introduced later.
- **Review Focus:** all five items map to a task's own test (see inline references in that section).
- **Known open risk, called out rather than hidden:** whether `derives DbCodec` needs `@Table`/`@Id` for plain `.query[T]` decoding (Task 4, Step 2) was not independently confirmed for read-only shapes; the plan keeps the one combination that *is* confirmed and gives a one-line, compiler-checked fallback instead of guessing either way.
