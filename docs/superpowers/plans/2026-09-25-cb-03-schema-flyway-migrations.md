# CB-03 Schema + Flyway Migrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `V1__schema.sql`, the Flyway migration that establishes every table the core-banking mock needs, with the `transactions` table append-only and every foreign key enforced, plus a `FlywayRunner`/`DbConfig` pair that can actually apply and prove it.

**Architecture:** One Flyway migration file under `src/main/resources/db/migration/`. A pure `DbConfig` case class reads connection settings from the environment with defaults matching `docker-compose.yml`. A thin `FlywayRunner` object wraps the Flyway Java API (plain blocking JDBC, no ZIO needed to run it). A `SchemaMigrationSpec` zio-test spec runs the real migration against the docker-compose Postgres and asserts the tables, the append-only trigger, and the foreign keys all behave as required.

**Tech Stack:** Scala 3, Flyway 13.8.0 (`org.flywaydb:flyway-core`, `flyway-database-postgresql`, already in `build.sbt`), PostgreSQL JDBC driver (`org.postgresql:postgresql`, already in `build.sbt`), zio-test.

**Spec:** Design agreed in chat (bounded path, brainstorming skill) on 2026-09-25; `CLAUDE.md` invariants 2–6 are the binding rules; `docs/handoff/V3__seed_demo_ln0042.sql` and `docs/handoff/RecalculationSpec.scala` are the binding column-naming references so later stories (CB-04, CB-15a) need no renames.

## Scope Note

The brainstormed design also wired `FlywayRunner` into `Server.scala` so migrations run automatically at server startup. Sizing this plan showed schema + runner + a real-DB migration test alone already approaches the ~200-line budget (`CLAUDE.md` rule 8: "one story ≈ one PR, max ~200 changed lines"; `references/story.md`: "when it will not fit, stack"). `Server.scala` is also CB-02's env-guard file, and touching it risks regressing `ServerProcessSpec`, whose `runHappyPath` spawns the process with a **cleared** environment (only `CORE_ENV` set). CB-03's literal acceptance criteria only asks for the schema and the migration file, not runtime wiring, so this plan builds `FlywayRunner`/`DbConfig` fully tested and ready to call, but does not call them from `Server.scala`. The natural place to add that call is whichever story first needs a live DB connection for a tool (most likely CB-05, read tools) — flag this to the user as a scope trim from the approved design.

## Global Constraints

- `transactions` has no UPDATE/DELETE path, enforced at the database layer (a trigger, not just application discipline) — CLAUDE.md rule 2.
- Every transaction row carries both `booking_date` and `value_date` — CLAUDE.md rule 3.
- `system_clock` is the only time source the application may read — CLAUDE.md rule 4 (this story only creates and seeds the table; nothing reads it yet).
- All money columns use `NUMERIC(18,2)`; all rate columns use `NUMERIC(6,4)` (matches `0.0800` in the seed reference file).
- Column names and table shapes must match `docs/handoff/V3__seed_demo_ln0042.sql` exactly (it is the binding reference, not the other way round) — e.g. `clients(id, display_name, opened_on)`, `accounts(id, client_id, product_id, kind, opened_on)`, `loans(account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee)`, `transactions(id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key)`, `accounting_periods(start_date, end_date, closed)`.
- **Flyway migration files are immutable once applied.** Write `V1__schema.sql` completely correct in one pass before the first `migrate()` call against the shared dev Postgres (the docker-compose container, already running on `localhost:5432`). If a mistake is discovered after it has been applied, do **not** edit `V1__schema.sql` — reset the dev database instead (`docker compose down -v && docker compose up -d`, then re-migrate); editing an applied file produces a Flyway checksum-mismatch error on every later run.
- No production/domain/engine code is touched by this story (`domain/` and `engine/` stay untouched, satisfying CLAUDE.md rule 9 by omission).
- `scalacOptions` include `-Wunused:all` and `-Werror`: every `val`/import must be referenced somewhere, or the build fails.

## Review Focus

1. **Repeated `idempotency_key`.** A retried write with the same key must not be insertable as a second ledger row (CLAUDE.md rule 5). Covered by `transactions.idempotency_key UNIQUE`; test in Task 2.
2. **Dangling `reverses_id`.** A reversal transaction must not be insertable pointing at a transaction id that doesn't exist (CLAUDE.md rule 2 — corrections are reversal + repost, which only makes sense against a real prior transaction). Covered by the self-referencing foreign key; test in Task 2.
3. **`system_clock` with zero or more than one row.** Every date-sensitive tool silently breaks if "the" system date isn't exactly one row (CLAUDE.md rule 4). Covered by the singleton-table pattern (`BOOLEAN PRIMARY KEY DEFAULT TRUE` plus `CHECK(id)`) and a seeded row; test in Task 2.
4. **Orphan `accounts.client_id` / `accounts.product_id`.** An account must not be insertable against a client or product that doesn't exist — this is the literal "foreign key constraints enforce referential integrity" acceptance criterion. Covered by the two foreign keys; test in Task 2.
5. **Editing an already-applied migration.** Not a code defect to test, but the single most likely way this story goes sideways mid-implementation (an opaque Flyway checksum error easily misdiagnosed as a Postgres problem). Addressed procedurally in Global Constraints and restated in Task 2's steps.

---

## File Structure

- Create: `src/main/scala/corebanking/config/DbConfig.scala` — pure case class + env-reading companion.
- Create: `src/test/scala/corebanking/config/DbConfigSpec.scala` — pure unit test, no DB required.
- Create: `src/main/scala/corebanking/db/FlywayRunner.scala` — thin wrapper around the Flyway Java API.
- Create: `src/main/resources/db/migration/V1__schema.sql` — the schema migration itself.
- Create: `src/test/scala/corebanking/db/SchemaMigrationSpec.scala` — real-Postgres integration test.
- Modify: `BACKLOG.md` — CB-03 row to `in-review` (done in the Gates step, not a task).

---

### Task 1: DbConfig

**Files:**
- Create: `src/main/scala/corebanking/config/DbConfig.scala`
- Test: `src/test/scala/corebanking/config/DbConfigSpec.scala`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `final case class DbConfig(url: String, user: String, password: String)` and `DbConfig.fromEnv(env: Map[String, String] = sys.env): DbConfig`. Task 2's `FlywayRunner.migrate` takes a `DbConfig` as its only parameter.

**Model:** `sonnet` (routine bounded config plumbing — no money, ledger, clock, engine, or idempotency content).

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.config

import zio.test.*

object DbConfigSpec extends ZIOSpecDefault:
  def spec = suite("DbConfig.fromEnv")(
    test("defaults match docker-compose.yml when no env vars are set") {
      assertTrue(
        DbConfig.fromEnv(Map.empty) ==
          DbConfig(
            url = "jdbc:postgresql://localhost:5432/corebanking",
            user = "corebanking",
            password = "change-me"
          )
      )
    },
    test("DATABASE_URL, POSTGRES_USER and POSTGRES_PASSWORD override the defaults") {
      val env = Map(
        "DATABASE_URL" -> "jdbc:postgresql://db-host:5555/other",
        "POSTGRES_USER" -> "other-user",
        "POSTGRES_PASSWORD" -> "other-pass"
      )
      assertTrue(
        DbConfig.fromEnv(env) ==
          DbConfig(
            url = "jdbc:postgresql://db-host:5555/other",
            user = "other-user",
            password = "other-pass"
          )
      )
    },
    test("a partial env map falls back to defaults for the missing keys") {
      assertTrue(
        DbConfig.fromEnv(Map("POSTGRES_USER" -> "solo-user")) ==
          DbConfig(
            url = "jdbc:postgresql://localhost:5432/corebanking",
            user = "solo-user",
            password = "change-me"
          )
      )
    }
  )
```

Save this to `src/test/scala/corebanking/config/DbConfigSpec.scala`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt -batch "testOnly corebanking.config.DbConfigSpec"`
Expected: FAIL to compile — `object DbConfig is not a member of package corebanking.config`.

- [ ] **Step 3: Write the minimal implementation**

```scala
package corebanking.config

/**
 * Where to find the mock Postgres core. Defaults match `docker-compose.yml`'s own defaults, so a
 * fresh `docker compose up -d` with no `.env` file works out of the box.
 */
final case class DbConfig(url: String, user: String, password: String)

object DbConfig:

  private val defaultUrl = "jdbc:postgresql://localhost:5432/corebanking"
  private val defaultUser = "corebanking"
  private val defaultPassword = "change-me"

  def fromEnv(env: Map[String, String] = sys.env): DbConfig =
    DbConfig(
      url = env.getOrElse("DATABASE_URL", defaultUrl),
      user = env.getOrElse("POSTGRES_USER", defaultUser),
      password = env.getOrElse("POSTGRES_PASSWORD", defaultPassword)
    )
```

Save this to `src/main/scala/corebanking/config/DbConfig.scala`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt -batch "testOnly corebanking.config.DbConfigSpec"`
Expected: PASS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/config/DbConfig.scala src/test/scala/corebanking/config/DbConfigSpec.scala
git commit -m "feat(db): add DbConfig for reading Postgres connection settings (CB-03, #3)"
```

---

### Task 2: V1 schema migration + FlywayRunner + real-DB verification

**Files:**
- Create: `src/main/scala/corebanking/db/FlywayRunner.scala`
- Create: `src/main/resources/db/migration/V1__schema.sql`
- Test: `src/test/scala/corebanking/db/SchemaMigrationSpec.scala`

**Interfaces:**
- Consumes: `corebanking.config.DbConfig` from Task 1 (`DbConfig.fromEnv()`, fields `url`/`user`/`password`).
- Produces: `object FlywayRunner { def migrate(config: DbConfig): org.flywaydb.core.api.output.MigrateResult }`. Nothing downstream in this story depends on it further; it is the story's deliverable, ready for a future story to call from `Server.scala`.

**Model:** `opus` — this task implements the append-only ledger trigger and the idempotency-key/reversal-chain constraints directly (CLAUDE.md rules 2 and 5; SKILL.md routing table: "ledger append-only paths ... idempotency" → opus).

**Important — read before starting:** Flyway migration files are checksummed once applied. Write the *entire* `V1__schema.sql` (Step 4 below) in one pass with every table, the trigger, and every foreign key, then run the migration exactly once against the shared dev Postgres. Do not edit `V1__schema.sql` after `migrate()` has succeeded against it. If you find a mistake post-apply, run `docker compose down -v && docker compose up -d` to reset the dev database (mock data only, safe to discard) before trying again.

- [ ] **Step 1: Write FlywayRunner (needed for the test to compile)**

```scala
package corebanking.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

import corebanking.config.DbConfig

/**
 * Applies every pending migration under `db/migration` on the classpath. Synchronous and
 * ZIO-free: Flyway's own API is a single blocking JDBC call, so no runtime is needed to run it.
 */
object FlywayRunner:
  def migrate(config: DbConfig): MigrateResult =
    Flyway
      .configure()
      .dataSource(config.url, config.user, config.password)
      .load()
      .migrate()
```

Save this to `src/main/scala/corebanking/db/FlywayRunner.scala`.

- [ ] **Step 2: Write the failing test**

```scala
package corebanking.db

import java.sql.{Connection, DriverManager, SQLException}

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig

/**
 * Runs the real V1 migration against the docker-compose Postgres (must already be up:
 * `docker compose up -d`) and proves the schema behaves per CLAUDE.md's non-negotiable rules:
 * append-only transactions (rule 2), unique idempotency keys (rule 5), and referential
 * integrity between every table this story creates.
 */
object SchemaMigrationSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()

  private val expectedTables = Set(
    "clients",
    "products",
    "accounts",
    "loans",
    "installments",
    "transactions",
    "accruals",
    "system_clock",
    "accounting_periods",
    "audit_log"
  )

  private def withConnection[A](f: Connection => A): Task[A] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(config.url, config.user, config.password)
      try f(conn)
      finally conn.close()
    }

  private def tableNames(conn: Connection): Set[String] =
    val rs = conn.createStatement().executeQuery(
      "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
    )
    val names = scala.collection.mutable.Set.empty[String]
    while rs.next() do names += rs.getString("table_name")
    rs.close()
    names.toSet

  /** Seeds one client/product/account/transaction row so FK- and trigger-dependent tests have
    * something real to point at. Runs inside the caller's transaction (autocommit off). */
  private def seedLoanAccount(conn: Connection): Unit =
    val stmt = conn.createStatement()
    stmt.execute(
      "INSERT INTO products (id, name, kind, annual_rate, term_months) " +
        "VALUES ('schema-spec-product', 'Test loan', 'loan', 0.08, 12)"
    )
    stmt.execute(
      "INSERT INTO clients (id, display_name, opened_on) " +
        "VALUES ('schema-spec-client', 'Schema Spec Client', CURRENT_DATE)"
    )
    stmt.execute(
      "INSERT INTO accounts (id, client_id, product_id, kind, opened_on) " +
        "VALUES ('schema-spec-account', 'schema-spec-client', 'schema-spec-product', 'loan', CURRENT_DATE)"
    )
    stmt.execute(
      "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) " +
        "VALUES ('schema-spec-tx', 'schema-spec-account', 'disbursement', 100.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-idem')"
    )

  /** Runs `sql` under a savepoint and reports whether it raised a SQLException, rolling back to
    * the savepoint either way so the connection stays usable for the next assertion. */
  private def rejects(conn: Connection, savepointName: String, sql: String): Boolean =
    val savepoint = conn.setSavepoint(savepointName)
    val wasRejected =
      try
        conn.createStatement().execute(sql)
        false
      catch case _: SQLException => true
    conn.rollback(savepoint)
    wasRejected

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("V1__schema.sql (CB-03)")(
      test("Flyway migrate applies V1 and creates every required table") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          names <- withConnection(tableNames)
        yield assertTrue(expectedTables.subsetOf(names))
      },
      test("transactions is append-only: UPDATE is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_update",
            "UPDATE transactions SET amount = amount WHERE id = 'schema-spec-tx'"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("transactions is append-only: DELETE is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_delete",
            "DELETE FROM transactions WHERE id = 'schema-spec-tx'"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("accounts.client_id foreign key rejects an unknown client") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_fk_client",
            "INSERT INTO accounts (id, client_id, product_id, kind, opened_on) " +
              "VALUES ('schema-spec-orphan', 'does-not-exist', 'schema-spec-product', 'loan', CURRENT_DATE)"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("transactions.idempotency_key is unique: a repeated key is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_idem",
            "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) " +
              "VALUES ('schema-spec-tx-2', 'schema-spec-account', 'repayment', 10.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-idem')"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("transactions.reverses_id foreign key rejects a nonexistent target") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_reverses",
            "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) " +
              "VALUES ('schema-spec-tx-3', 'schema-spec-account', 'reversal', 100.00, CURRENT_DATE, CURRENT_DATE, 'does-not-exist', 'schema-spec-idem-2')"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("system_clock accepts only one row") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          val wasRejected = rejects(
            conn,
            "sp_clock",
            "INSERT INTO system_clock (current_date_value) VALUES ('2020-01-01')"
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      }
    ) @@ sequential @@ timeout(1.minute)
```

Save this to `src/test/scala/corebanking/db/SchemaMigrationSpec.scala`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `docker compose up -d && sbt -batch "testOnly corebanking.db.SchemaMigrationSpec"`
Expected: FAIL — the first test fails because `expectedTables.subsetOf(names)` is false (no migration has created them yet; only Flyway's own bookkeeping table, if any, exists). The remaining tests fail with `SQLException: relation "..." does not exist` surfacing as a `Task` failure, not as `wasRejected == true`, because there is no schema to insert against yet.

- [ ] **Step 4: Write the complete V1__schema.sql**

```sql
-- V1__schema.sql
-- CB-03: core schema for the mock banking ledger.
--
-- Column names and shapes follow docs/handoff/V3__seed_demo_ln0042.sql and
-- docs/handoff/RecalculationSpec.scala exactly, so CB-04's seed migration and CB-15a's engine
-- need no renames.
--
-- transactions is append-only (CLAUDE.md rule 2): corrections are reversal (reverses_id) plus
-- repost, never UPDATE/DELETE. That is enforced below with a trigger, not just app discipline.

CREATE TABLE clients (
  id           TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  opened_on    DATE NOT NULL
);

CREATE TABLE products (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL CHECK (kind IN ('savings', 'loan')),
  annual_rate   NUMERIC(6,4) NOT NULL,
  term_months   INT,
  accrual_basis TEXT NOT NULL DEFAULT 'actual/365'
);

CREATE TABLE accounts (
  id         TEXT PRIMARY KEY,
  client_id  TEXT NOT NULL REFERENCES clients(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  kind       TEXT NOT NULL CHECK (kind IN ('savings', 'loan')),
  opened_on  DATE NOT NULL
);

CREATE TABLE loans (
  account_id         TEXT PRIMARY KEY REFERENCES accounts(id),
  principal          NUMERIC(18,2) NOT NULL,
  annual_rate        NUMERIC(6,4) NOT NULL,
  term_months        INT NOT NULL,
  disbursement_date  DATE NOT NULL,
  installment_amount NUMERIC(18,2) NOT NULL,
  grace_days         INT NOT NULL,
  late_fee           NUMERIC(18,2) NOT NULL
);

CREATE TABLE installments (
  account_id TEXT NOT NULL REFERENCES accounts(id),
  seq        INT NOT NULL,
  due_date   DATE NOT NULL,
  amount_due NUMERIC(18,2) NOT NULL,
  PRIMARY KEY (account_id, seq)
);

CREATE TABLE transactions (
  id              TEXT PRIMARY KEY,
  account_id      TEXT NOT NULL REFERENCES accounts(id),
  type            TEXT NOT NULL,
  amount          NUMERIC(18,2) NOT NULL,
  booking_date    DATE NOT NULL,
  value_date      DATE NOT NULL,
  reverses_id     TEXT REFERENCES transactions(id),
  idempotency_key TEXT NOT NULL UNIQUE
);

CREATE FUNCTION forbid_transactions_mutation() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'transactions is append-only: % not allowed on id=%', TG_OP, OLD.id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactions_append_only
  BEFORE UPDATE OR DELETE ON transactions
  FOR EACH ROW EXECUTE FUNCTION forbid_transactions_mutation();

CREATE TABLE accruals (
  id           BIGSERIAL PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id),
  accrual_date DATE NOT NULL,
  amount       NUMERIC(18,2) NOT NULL,
  UNIQUE (account_id, accrual_date)
);

-- Single-row table: id is BOOLEAN and can only ever be TRUE, so the primary key alone forbids a
-- second row. Seeded with one row below so callers can UPDATE it without an INSERT ever being
-- needed later.
CREATE TABLE system_clock (
  id                 BOOLEAN PRIMARY KEY DEFAULT TRUE,
  current_date_value DATE NOT NULL,
  CONSTRAINT system_clock_is_singleton CHECK (id)
);

INSERT INTO system_clock (current_date_value) VALUES (CURRENT_DATE);

CREATE TABLE accounting_periods (
  start_date DATE PRIMARY KEY,
  end_date   DATE NOT NULL,
  closed     BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK (end_date > start_date)
);

CREATE TABLE audit_log (
  id        BIGSERIAL PRIMARY KEY,
  tool_name TEXT NOT NULL,
  called_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  env       TEXT NOT NULL,
  request   JSONB,
  response  JSONB
);
```

Save this to `src/main/resources/db/migration/V1__schema.sql`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `sbt -batch "testOnly corebanking.db.SchemaMigrationSpec"`
Expected: PASS, 7 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/corebanking/db/FlywayRunner.scala src/main/resources/db/migration/V1__schema.sql src/test/scala/corebanking/db/SchemaMigrationSpec.scala
git commit -m "feat(db): add V1 schema migration and Flyway runner (CB-03, #3)"
```

---

## Verification

After both tasks:

```bash
docker compose up -d
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

Expected: all existing suites (`CoreEnvSpec`, `PingSpec`, `ToolResponseSpec`, `ServerProcessSpec`) still pass unchanged (this story touches none of their files), plus the 3 new `DbConfigSpec` tests and 7 new `SchemaMigrationSpec` tests, all green. `ServerProcessSpec` in particular must show no behavior change, since `Server.scala` is untouched by this plan.
