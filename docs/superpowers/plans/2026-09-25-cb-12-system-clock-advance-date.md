# CB-12: system_clock + advance_date Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose `get_system_date` (read) and `advance_date` (write) MCP tools backed by the `system_clock` table CB-03 already created, so every later story reads and moves the ledger's own clock instead of the JVM clock.

**Architecture:** A small magnum-based DB access layer (`Entities`, `Db`, `DryRun`) plus an `AuditLog` helper, then two tools built on top: `get_system_date` does a plain read; `advance_date` reads the current date, computes `current + days`, writes it back inside `DryRun`, and — because `system_clock` is a singleton row with no `idempotency_key` column of its own — recognizes a retried call by replaying the matching `audit_log` row instead of looking up a row on `system_clock` itself.

**Tech Stack:** Scala 3, ZIO 2, magnum/magnumpg 1.3.1, Postgres 18 (docker-compose), zio-test, zio-json.

**Spec:** `docs/superpowers/specs/2026-09-25-cb-12-system-clock-advance-date.md`

## Global Constraints

- `CORE_ENV` ∈ {`mock`, `sandbox`}; every tool response is the `{env, data}` envelope via `ToolResponse.respond` (CLAUDE.md rule 1).
- Time comes from the `system_clock` table, never the JVM clock (rule 4) — no task may call `LocalDate.now()`.
- `advance_date` is a write tool: it accepts `idempotency_key` and `dry_run` (rule 5).
- Every tool call — `get_system_date` and `advance_date` alike — writes one `audit_log` row (rule 6).
- No PR over 300 changed lines (additions + deletions; `docs/superpowers/**` and lockfiles excluded) (owner rule 2026-09-25). This plan is split into 3 tasks that map 1:1 to 3 stacked PR increments; the parent publishes the stack after all tasks land.
- Comments explain business domain only, tersely; no process narration in code or commit bodies (owner rule 2026-09-25).
- Every test in this plan is a real-Postgres integration test (docker-compose must be `up -d`), matching this repo's existing convention (`ProductSeedSpec`, `SchemaMigrationSpec`) — none of `Entities`/`Db`/`DryRun`/`AuditLog` has behavior worth asserting without a live connection.
- Every new file's package-level scaladoc is one sentence; nothing longer than the existing `Ping.scala`/`CreateClient.scala` style.

## Review Focus

- **`advance_date(days=0)` or a negative `days`:** a reasonable caller expects this to be rejected, not silently accepted as a no-op or a backdating move. Pinned in Task 3, "rejects a non-positive days value without touching the clock."
- **A retried `idempotency_key` with a *different* `days` value:** must return the original result unchanged, not re-advance by the new amount. Pinned in Task 3, "a repeated idempotency_key replays the first result without advancing the clock again."
- **A replayed idempotent call still needs its own audit trail entry** (rule 6 says every call is logged, not every state change) — a naive "skip everything on replay" implementation would silently drop this. Pinned in Task 3, "each call, including a replayed one, adds exactly one audit_log row."
- **`dry_run=true` must leave the row untouched but still return the correct would-be dates** — a naive implementation might compute the dates outside the rolled-back transaction and get them right by accident even if the rollback itself is broken. Pinned in Task 3, "dry_run leaves the clock unchanged but returns the would-be result," which asserts both the unchanged row and the returned dates.
- **`get_system_date` must reflect a clock that was moved by something other than itself** (e.g. a direct write, or — later — `advance_date`) rather than any cached or seeded value. Pinned in Task 2, "reflects a clock value written after the seed."

---

### Task 1: DB access layer (Entities, Db, DryRun) + AuditLog

**Files:**
- Create: `src/main/scala/corebanking/db/Entities.scala`
- Create: `src/main/scala/corebanking/db/Db.scala`
- Create: `src/main/scala/corebanking/db/DryRun.scala`
- Create: `src/main/scala/corebanking/tools/AuditLog.scala`
- Test: `src/test/scala/corebanking/db/SystemClockDbSpec.scala`
- Test: `src/test/scala/corebanking/tools/AuditLogSpec.scala`

**Interfaces:**
- Produces: `given corebanking.db.localDateCodec: DbCodec[LocalDate]`
- Produces: `case class corebanking.db.SystemClockRow(currentDateValue: LocalDate) derives DbCodec`, mapped to table `system_clock`
- Produces: `object corebanking.db.Db { def transactor(config: DbConfig): Transactor }`
- Produces: `object corebanking.db.DryRun { def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T }`
- Produces: `object corebanking.tools.AuditLog { def record(xa: Transactor, toolName: String, env: CoreEnv, requestJson: String, responseJson: String): Unit }`
- Consumes: `corebanking.config.DbConfig` (existing), `corebanking.tools.ToolResponse`/`Envelope` (existing, unused by this task directly but the next tasks depend on it)

- [ ] **Step 1: Write the failing tests**

`src/main/scala/corebanking/db/Entities.scala`, `Db.scala`, `DryRun.scala` and `src/main/scala/corebanking/tools/AuditLog.scala` do not exist yet, so write both test files against the API shape above; they will fail to compile until Step 3.

`src/test/scala/corebanking/db/SystemClockDbSpec.scala`:

```scala
package corebanking.db

import java.time.LocalDate

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{DbCon, sql, transact}

import corebanking.config.DbConfig

/** Proves the LocalDate codec, transactor, and dry-run wrapper work together against the real `system_clock` row. */
object SystemClockDbSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  private def readCurrentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  /** Takes the ambient `DbCon` so it can run inside a `DryRun` block without opening a second connection. */
  private def setCurrentDate(date: LocalDate)(using DbCon): Unit =
    sql"UPDATE system_clock SET current_date_value = $date WHERE id = true".update.run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("system_clock DB access (CB-12)")(
    test("reads the current_date_value row through the LocalDate codec") {
      ZIO.attempt(readCurrentDate()).map(date => assertTrue(date.isInstanceOf[LocalDate]))
    },
    test("DryRun rolls back a write but still returns its computed result") {
      ZIO.attempt {
        val before = readCurrentDate()
        val target = before.plusDays(5)
        val result = DryRun(xa, dryRun = true):
          setCurrentDate(target)
          target
        val after = readCurrentDate()
        (result, target, before, after)
      }.map { case (result, target, before, after) =>
        assertTrue(result == target, after == before)
      }
    },
    test("DryRun commits a write when dryRun=false") {
      ZIO.attempt {
        val before = readCurrentDate()
        val target = before.plusDays(1)
        DryRun(xa, dryRun = false):
          setCurrentDate(target)
        val after = readCurrentDate()
        transact(xa):
          setCurrentDate(before)
        (target, after, before)
      }.map { case (target, after, before) =>
        assertTrue(after == target, after != before)
      }
    }
  ) @@ sequential @@ timeout(1.minute)
```

**Design note ruled on 2026-09-25 (controller ruling, recorded in the SDD ledger):** `setCurrentDate` above takes an ambient `(using DbCon)` — it is NOT wrapped in its own `transact(xa)` call. magnum 1.3.1's `transact` opens a brand-new physical JDBC connection per call with no ambient/thread-local transaction propagation, so a mutation helper that calls `transact` itself *inside* a `DryRun` block runs on a second, independent connection and commits for real regardless of the outer rollback. Every write helper in this plan (here and in Task 3's `AdvanceDate`) must take the ambient `DbCon`/`DbTx` and never open its own `transact` — the only two places a bare `transact(xa)` call is correct are (1) a plain standalone read/write with no surrounding `DryRun`, and (2) the outermost `DryRun.apply`/`AuditLog.record` calls themselves.

`src/test/scala/corebanking/tools/AuditLogSpec.scala`:

```scala
package corebanking.tools

import java.sql.DriverManager

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}

object AuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class SampleRequest(a: Int)
  object SampleRequest:
    given JsonDecoder[SampleRequest] = DeriveJsonDecoder.gen[SampleRequest]

  final case class SampleResponse(env: String)
  object SampleResponse:
    given JsonDecoder[SampleResponse] = DeriveJsonDecoder.gen[SampleResponse]

  private def countByToolName(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  /** Raw JDBC (not magnum) since this reads back JSONB as text for value assertions, matching `ProductSeedSpec`'s convention for column-value checks. */
  private def readRow(toolName: String): (String, String, String, String) =
    val conn = DriverManager.getConnection(config.url, config.user, config.password)
    try
      val stmt = conn.prepareStatement(
        "SELECT tool_name, env, request::text, response::text FROM audit_log WHERE tool_name = ?"
      )
      stmt.setString(1, toolName)
      val rs = stmt.executeQuery()
      if !rs.next() then throw new RuntimeException(s"no audit_log row for tool_name=$toolName")
      val row = (rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
      rs.close()
      row
    finally conn.close()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AuditLog (CB-12)")(
    test("records one row with the given tool name, env, request and response") {
      ZIO.attempt {
        val toolName = "cb12_audit_log_spec_" + java.util.UUID.randomUUID().toString
        val before = countByToolName(toolName)
        AuditLog.record(
          xa,
          toolName,
          env = CoreEnv.Mock,
          requestJson = """{"a":1}""",
          responseJson = """{"env":"mock","data":{}}"""
        )
        val after = countByToolName(toolName)
        val (dbToolName, dbEnv, dbRequest, dbResponse) = readRow(toolName)
        (before, after, dbToolName, dbEnv, dbRequest, dbResponse, toolName)
      }.map { case (before, after, dbToolName, dbEnv, dbRequest, dbResponse, toolName) =>
        assertTrue(
          before == 0,
          after == 1,
          dbToolName == toolName,
          dbEnv == "mock",
          dbRequest.fromJson[SampleRequest] == Right(SampleRequest(a = 1)),
          dbResponse.fromJson[SampleResponse] == Right(SampleResponse(env = "mock"))
        )
      }
    }
  ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `docker compose up -d && sbt "testOnly corebanking.db.SystemClockDbSpec corebanking.tools.AuditLogSpec"`
Expected: compile FAILURE — `Entities.scala`, `Db.scala`, `DryRun.scala`, `AuditLog.scala` don't exist.

- [ ] **Step 3: Write the minimal implementation**

`src/main/scala/corebanking/db/Entities.scala`:

```scala
package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate

import com.augustnagro.magnum.*

/** Maps `current_date_value`'s SQL `DATE` column to `LocalDate` (magnum ships no such codec). */
given localDateCodec: DbCodec[LocalDate] with
  val cols: IArray[Int] = IArray(Types.DATE)
  def readSingle(rs: ResultSet, pos: Int): LocalDate =
    rs.getObject(pos, classOf[LocalDate])
  def writeSingle(date: LocalDate, ps: PreparedStatement, pos: Int): Unit =
    ps.setObject(pos, date)
  def queryRepr: String = "?"

/** The ledger's system date — a permanent singleton row, never inserted or looked up by id. */
@SqlName("system_clock")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec
```

`src/main/scala/corebanking/db/Db.scala`:

```scala
package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/** Builds the magnum `Transactor` every tool shares. */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
```

`src/main/scala/corebanking/db/DryRun.scala`:

```scala
package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/** Runs a write in one transaction, rolling it back when `dryRun` is true but still returning its result. */
object DryRun:

  /** `f` must write through its ambient `DbTx`, never open its own `transact` call, or a dry run would commit on a second connection instead of rolling back. */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    transact(xa):
      val result = f
      if dryRun then summon[DbTx].connection.rollback()
      result
```

`src/main/scala/corebanking/tools/AuditLog.scala`:

```scala
package corebanking.tools

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

import corebanking.config.CoreEnv

/** Writes one `audit_log` row per tool call, in its own transaction, committed regardless of `dry_run`. */
object AuditLog:
  def record(
      xa: Transactor,
      toolName: String,
      env: CoreEnv,
      requestJson: String,
      responseJson: String
  ): Unit =
    transact(xa):
      sql"""
        INSERT INTO audit_log (tool_name, env, request, response)
        VALUES ($toolName, ${env.label}, $requestJson::jsonb, $responseJson::jsonb)
      """.update.run()
      ()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt "testOnly corebanking.db.SystemClockDbSpec corebanking.tools.AuditLogSpec"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full gate and commit**

Run: `sbt -batch scalafmtCheckAll compile test`
Run: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`

```bash
git add src/main/scala/corebanking/db/Entities.scala src/main/scala/corebanking/db/Db.scala \
        src/main/scala/corebanking/db/DryRun.scala src/main/scala/corebanking/tools/AuditLog.scala \
        src/test/scala/corebanking/db/SystemClockDbSpec.scala src/test/scala/corebanking/tools/AuditLogSpec.scala
git commit -m "feat(db): add system_clock access, dry-run wrapper, audit log (CB-12, #12)"
```

---

### Task 2: `get_system_date` read tool

**Files:**
- Create: `src/main/scala/corebanking/tools/GetSystemDate.scala`
- Modify: `src/main/scala/corebanking/Server.scala`
- Test: `src/test/scala/corebanking/tools/GetSystemDateSpec.scala`

**Interfaces:**
- Consumes: `corebanking.db.{Db, SystemClockRow}` and `corebanking.db.given` (Task 1), `corebanking.tools.{AuditLog, ToolResponse}` (Task 1 / existing), `corebanking.config.{CoreEnv, DbConfig}` (existing)
- Produces: `object corebanking.tools.GetSystemDate { def run(xa: Transactor, env: CoreEnv): String }`
- Produces (on `Server`): `private val dbConfig: DbConfig`, `private val transactor: Transactor` — Task 3 reuses both, does not redeclare them.

- [ ] **Step 1: Write the failing test**

`src/test/scala/corebanking/tools/GetSystemDateSpec.scala`:

```scala
package corebanking.tools

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, SystemClockRow}
import corebanking.db.given

object GetSystemDateSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class DecodedData(currentDate: String)
  object DecodedData:
    given JsonCodec[DecodedData] = DeriveJsonCodec.gen[DecodedData]

  final case class DecodedEnvelope(env: String, data: DecodedData)
  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  private def rawCurrentDate(): java.time.LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  private def setCurrentDate(date: java.time.LocalDate): Unit =
    transact(xa):
      sql"UPDATE system_clock SET current_date_value = $date WHERE id = true".update.run()

  private def auditCount(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  def spec: Spec[TestEnvironment & Scope, Any] = suite("get_system_date (CB-12)")(
    test("returns the ledger's current_date_value, not the JVM clock") {
      val expected = rawCurrentDate()
      val json = GetSystemDate.run(xa, CoreEnv.Mock)
      assertTrue(
        json.fromJson[DecodedEnvelope] ==
          Right(DecodedEnvelope(env = "mock", data = DecodedData(currentDate = expected.toString)))
      )
    },
    test("reflects a clock value written after the seed") {
      val before = rawCurrentDate()
      val moved = before.plusDays(30)
      setCurrentDate(moved)
      val json = GetSystemDate.run(xa, CoreEnv.Mock)
      setCurrentDate(before)
      assertTrue(
        json.fromJson[DecodedEnvelope] ==
          Right(DecodedEnvelope(env = "mock", data = DecodedData(currentDate = moved.toString)))
      )
    },
    test("logs exactly one audit_log row per call") {
      val before = auditCount("get_system_date")
      GetSystemDate.run(xa, CoreEnv.Mock)
      val after = auditCount("get_system_date")
      assertTrue(after == before + 1)
    }
  ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly corebanking.tools.GetSystemDateSpec"`
Expected: compile FAILURE — `GetSystemDate` doesn't exist.

- [ ] **Step 3: Write the minimal implementation**

`src/main/scala/corebanking/tools/GetSystemDate.scala`:

```scala
package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.SystemClockRow
import corebanking.db.given

/** The ledger's current date as `get_system_date` reports it back to the caller. */
final case class SystemDateData(currentDate: String)

object SystemDateData:
  given JsonEncoder[SystemDateData] = DeriveJsonEncoder.gen[SystemDateData]

/** Reads the ledger's own clock — never the JVM clock (CLAUDE.md rule 4). */
object GetSystemDate:

  def run(xa: Transactor, env: CoreEnv): String =
    val data = SystemDateData(readCurrentDate(xa).toString)
    val response = ToolResponse.respond(env, data)
    AuditLog.record(
      xa,
      toolName = "get_system_date",
      env = env,
      requestJson = "{}",
      responseJson = response
    )
    response

  private def readCurrentDate(xa: Transactor): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue
```

Modify `src/main/scala/corebanking/Server.scala`:

- Change the import block to:

```scala
import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}
import corebanking.tools.{GetSystemDate, Ping}
```

- Immediately after the `coreEnv` val, add:

```scala
  /** Mock Postgres connection settings, then every pending migration applied before serving tools. */
  private val dbConfig: DbConfig = DbConfig.fromEnv()
  FlywayRunner.migrate(dbConfig)
  private val transactor = Db.transactor(dbConfig)
```

- After the existing `ping` tool method, add:

```scala
  @Tool(
    name = Some("get_system_date"),
    description = Some("Returns the ledger's current date from system_clock, not the JVM clock"),
    readOnlyHint = Some(true)
  )
  def getSystemDate(): String =
    GetSystemDate.run(transactor, coreEnv)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly corebanking.tools.GetSystemDateSpec"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full gate and commit**

Run: `sbt -batch scalafmtCheckAll compile test`
Run: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`

```bash
git add src/main/scala/corebanking/tools/GetSystemDate.scala src/main/scala/corebanking/Server.scala \
        src/test/scala/corebanking/tools/GetSystemDateSpec.scala
git commit -m "feat(tools): add get_system_date read tool (CB-12, #12)"
```

---

### Task 3: `advance_date` write tool

**Files:**
- Create: `src/main/scala/corebanking/tools/AdvanceDate.scala`
- Modify: `src/main/scala/corebanking/Server.scala`
- Test: `src/test/scala/corebanking/tools/AdvanceDateSpec.scala`

**Interfaces:**
- Consumes: `corebanking.db.{DryRun, SystemClockRow}` and `corebanking.db.given` (Task 1), `corebanking.tools.{AuditLog, ToolResponse}` (Task 1 / existing), `Server`'s existing `dbConfig`/`transactor` vals (Task 2)
- Produces: `object corebanking.tools.AdvanceDate { def run(xa: Transactor, env: CoreEnv, days: Int, idempotencyKey: Option[String], dryRun: Boolean): String }`

- [ ] **Step 1: Write the failing test**

`src/test/scala/corebanking/tools/AdvanceDateSpec.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, SystemClockRow}
import corebanking.db.given

object AdvanceDateSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class DecodedData(
      previousDate: String,
      currentDate: String,
      daysAdvanced: Int,
      dryRun: Boolean
  )
  object DecodedData:
    given JsonCodec[DecodedData] = DeriveJsonCodec.gen[DecodedData]

  final case class DecodedEnvelope(env: String, data: DecodedData)
  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  private def rawCurrentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  private def auditCount(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  def spec: Spec[TestEnvironment & Scope, Any] = suite("advance_date (CB-12)")(
    test("advances the clock forward by the given number of days") {
      val before = rawCurrentDate()
      val json = AdvanceDate.run(xa, CoreEnv.Mock, days = 3, idempotencyKey = None, dryRun = false)
      val after = rawCurrentDate()
      assertTrue(
        after == before.plusDays(3),
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "mock",
              data = DecodedData(before.toString, after.toString, daysAdvanced = 3, dryRun = false)
            )
          )
      )
    },
    test("rejects a non-positive days value without touching the clock") {
      val before = rawCurrentDate()
      val result =
        scala.util.Try(AdvanceDate.run(xa, CoreEnv.Mock, days = 0, idempotencyKey = None, dryRun = false))
      val after = rawCurrentDate()
      assertTrue(result.isFailure, after == before)
    },
    test("dry_run leaves the clock unchanged but returns the would-be result") {
      val before = rawCurrentDate()
      val json = AdvanceDate.run(xa, CoreEnv.Mock, days = 4, idempotencyKey = None, dryRun = true)
      val after = rawCurrentDate()
      assertTrue(
        after == before,
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "mock",
              data = DecodedData(
                before.toString,
                before.plusDays(4).toString,
                daysAdvanced = 4,
                dryRun = true
              )
            )
          )
      )
    },
    test("a repeated idempotency_key replays the first result without advancing the clock again") {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val first = AdvanceDate.run(xa, CoreEnv.Mock, days = 2, idempotencyKey = Some(key), dryRun = false)
      val afterFirst = rawCurrentDate()
      val second = AdvanceDate.run(xa, CoreEnv.Mock, days = 9, idempotencyKey = Some(key), dryRun = false)
      val afterSecond = rawCurrentDate()
      assertTrue(
        afterFirst == before.plusDays(2),
        afterSecond == afterFirst,
        second.fromJson[DecodedEnvelope] == first.fromJson[DecodedEnvelope]
      )
    },
    test("each call, including a replayed one, adds exactly one audit_log row") {
      val key = UUID.randomUUID().toString
      val beforeCount = auditCount("advance_date")
      AdvanceDate.run(xa, CoreEnv.Mock, days = 1, idempotencyKey = Some(key), dryRun = false)
      val afterFirstCall = auditCount("advance_date")
      AdvanceDate.run(xa, CoreEnv.Mock, days = 1, idempotencyKey = Some(key), dryRun = false)
      val afterSecondCall = auditCount("advance_date")
      assertTrue(afterFirstCall == beforeCount + 1, afterSecondCall == beforeCount + 2)
    },
    test("a dry_run call does not poison a later real call with the same idempotency_key") {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val dryJson = AdvanceDate.run(xa, CoreEnv.Mock, days = 7, idempotencyKey = Some(key), dryRun = true)
      val afterDry = rawCurrentDate()
      val realJson = AdvanceDate.run(xa, CoreEnv.Mock, days = 7, idempotencyKey = Some(key), dryRun = false)
      val afterReal = rawCurrentDate()
      assertTrue(
        afterDry == before,
        afterReal == before.plusDays(7),
        dryJson.fromJson[DecodedEnvelope].map(_.data.dryRun) == Right(true),
        realJson.fromJson[DecodedEnvelope].map(_.data.dryRun) == Right(false)
      )
    }
  ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly corebanking.tools.AdvanceDateSpec"`
Expected: compile FAILURE — `AdvanceDate` doesn't exist.

- [ ] **Step 3: Write the minimal implementation**

`src/main/scala/corebanking/tools/AdvanceDate.scala`:

```scala
package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{DryRun, SystemClockRow}
import corebanking.db.given

/** The clock move as `advance_date` reports it back to the caller. */
final case class AdvanceDateData(
    previousDate: String,
    currentDate: String,
    daysAdvanced: Int,
    dryRun: Boolean
)

object AdvanceDateData:
  given JsonEncoder[AdvanceDateData] = DeriveJsonEncoder.gen[AdvanceDateData]

final private case class AdvanceDateRequest(days: Int, idempotencyKey: Option[String], dryRun: Boolean)

private object AdvanceDateRequest:
  given JsonEncoder[AdvanceDateRequest] = DeriveJsonEncoder.gen[AdvanceDateRequest]

/**
 * Advances the ledger's own clock (CLAUDE.md rule 4). `system_clock` is a singleton row with no
 * `idempotency_key` column of its own, so a retried call is recognized by replaying the matching
 * `audit_log` row from its first call, instead of looking up a row on `system_clock` itself.
 */
object AdvanceDate:

  def run(
      xa: Transactor,
      env: CoreEnv,
      days: Int,
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    require(days > 0, s"days must be positive, got $days")
    val requestJson = AdvanceDateRequest(days, idempotencyKey, dryRun).toJson
    val response = idempotencyKey.flatMap(findReplay(xa, _)) match
      case Some(replayed) => replayed
      case None => ToolResponse.respond(env, execute(xa, days, dryRun))
    AuditLog.record(
      xa,
      toolName = "advance_date",
      env = env,
      requestJson = requestJson,
      responseJson = response
    )
    response

  /** Most recent stored response from a prior real (non-dry-run) call for a matching key — a dry run never establishes a replay baseline. */
  private def findReplay(xa: Transactor, key: String): Option[String] =
    transact(xa):
      sql"""
        SELECT response::text
        FROM audit_log
        WHERE tool_name = 'advance_date'
          AND request ->> 'idempotencyKey' = $key
          AND request ->> 'dryRun' = 'false'
        ORDER BY id DESC
        LIMIT 1
      """.query[String].run().headOption

  private def execute(xa: Transactor, days: Int, dryRun: Boolean): AdvanceDateData =
    DryRun(xa, dryRun):
      val previous = readCurrentDate()
      val next = previous.plusDays(days.toLong)
      updateCurrentDate(next)
      AdvanceDateData(previous.toString, next.toString, days, dryRun)

  private def readCurrentDate()(using DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def updateCurrentDate(next: LocalDate)(using DbCon): Unit =
    sql"UPDATE system_clock SET current_date_value = $next WHERE id = true".update.run()
```

Modify `src/main/scala/corebanking/Server.scala`:

- Add `AdvanceDate` to the `corebanking.tools` import: `import corebanking.tools.{AdvanceDate, GetSystemDate, Ping}`
- After the `getSystemDate` tool method, add:

```scala
  @Tool(
    name = Some("advance_date"),
    description = Some(
      "Advances system_clock forward by the given number of days. A repeated idempotency_key " +
        "returns the original result unchanged, and dry_run has no effect on that path."
    ),
    readOnlyHint = Some(false)
  )
  def advanceDate(
      @Param(description = "Number of days to advance the clock forward; must be positive")
      days: Int,
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
    AdvanceDate.run(transactor, coreEnv, days, idempotencyKey, dryRun)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly corebanking.tools.AdvanceDateSpec"`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full gate and commit**

Run: `sbt -batch scalafmtCheckAll compile test`
Run: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`

```bash
git add src/main/scala/corebanking/tools/AdvanceDate.scala src/main/scala/corebanking/Server.scala \
        src/test/scala/corebanking/tools/AdvanceDateSpec.scala
git commit -m "feat(tools): add advance_date write tool (CB-12, #12)"
```

---

## After All Tasks: Publish As A Stack (parent, not a task)

Per `references/story.md`'s "Gates And Publish": from the worktree, `git fetch origin && git rebase origin/main`, rerun the full gate, then `gh stack init` on Task 1's commit, `gh stack add` at Task 2's commit and again at Task 3's commit, measuring each increment with `git diff --shortstat <base>..HEAD -- . ':!docs/superpowers/**' ':!*.lock'` (expected: Task 1 ≈150 lines, Task 2 ≈100 lines, Task 3 ≈230 lines — all under 300). Set the `BACKLOG.md` row to `in-review` in the bottom increment, run the final whole-branch `risk-reviewer` (opus), then `gh stack submit` and edit each PR body per the ≤15-line template.
