# CB-05 Read Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four read-only MCP tools — `get_client`, `list_accounts`, `get_transactions`,
`get_loan_schedule` — each returning the `{env, data}` envelope and reading time from
`system_clock`, plus the first DB read layer (`db/Db.scala`, `db/Codecs.scala`) and one pure
domain function (`Schedule.amortizationBreakdown`) that this story needs and none before it built.

**Architecture:** A magnum `Transactor` (unpooled `PGSimpleDataSource`, mirroring `FlywayRunner`'s
own `DbConfig`-driven wiring) is constructed once in `Server.scala`. Each tool is a small object
pairing a pure, JSON-encodable `*Data` case class with a `find(...)(using DbCon): ...` query method
and a `response(env, ...)(using DbCon): String` wrapper over `ToolResponse.respond`. `Server.scala`
registers each as a synchronous `@Tool` method (matching `ping`'s existing style) that opens one
`connect(xa) { ... }` block per call. Query result rows are read with per-query, positionally-typed
row case classes (`derives DbCodec`) — magnum's derived codecs read `ResultSet` columns by
declaration order, not by name, so no table-wide `@Table` entity types are needed for read-only
projections.

**Tech Stack:** Scala 3.9, ZIO 2.1.26, fast-mcp-scala 1.0.1, magnum 1.3.1 + magnumpg, PostgreSQL
JDBC driver, zio-json 0.10.0 (all already declared in `build.sbt`).

**Spec:** `docs/superpowers/specs/2026-09-25-cb-05-read-tools.md`

## Global Constraints

- Every tool response carries `{env, data}` via `ToolResponse.respond` (CLAUDE.md rule 1).
- This story is read-only: no `idempotency_key`/`dry_run`/`audit_log` plumbing (CB-06/09/10's job).
- `domain/` stays free of ZIO and DB imports (CLAUDE.md rule 9) — `Schedule.amortizationBreakdown`
  takes plain `BigDecimal`/`LocalDate`/`List` arguments only.
- Time comes from `system_clock`, never the JVM clock (CLAUDE.md rule 4) — `list_accounts`'
  balance filters on `system_clock.current_date_value`, never `LocalDate.now()`.
- No PR exceeds 300 changed lines, additions + deletions, excluding `docs/superpowers/**` and
  lockfiles (CLAUDE.md rule 8). Each task below is its own stack increment; measure with
  `git diff --shortstat <base>..HEAD -- . ':!docs/superpowers/**' ':!*.lock'` before moving to the
  next task, per `references/story.md`.
- Comments explain business-domain meaning only, tersely (owner rule 2026-09-25).
- Every DB-touching test needs the story's own docker-compose Postgres up: from this worktree,
  `set -a && source .env && set +a && docker compose up -d` (already running on port 5434 as of
  this plan's approval — re-run only if it was stopped).

## Review Focus

- An unknown `client_id`/`account_id`/`loan_id` raises a clear "not found" tool error, not a
  silent empty/null payload or an unhandled crash (Tasks 2, 3, 4, 5).
- A malformed UUID string in any id parameter fails as a framework-level decode error (zio-json's
  built-in `JsonDecoder[UUID]`), not an application NPE (Task 2).
- `get_transactions` with `start_date` after `end_date` (an inverted range) returns an empty list,
  not an error and not the unfiltered set (Task 4).
- `list_accounts` for a client with zero accounts returns an empty list — the client itself still
  resolves, so this is distinct from "client not found" (Task 3).
- `get_loan_schedule` for an account that exists but has no `loans` row (e.g. a savings account)
  raises "not found", distinct from a loan that exists but has no `installments` rows yet (returns
  an empty schedule, since no write tool populates `installments` until CB-07) (Task 5).

---

## Task 1: DB access layer + amortization math

**Files:**
- Create: `src/main/scala/corebanking/domain/Schedule.scala` (modify — add to existing file)
- Modify: `src/test/scala/corebanking/domain/ScheduleSpec.scala`
- Create: `src/main/scala/corebanking/db/Db.scala`
- Create: `src/main/scala/corebanking/db/Codecs.scala`
- Create: `src/test/scala/corebanking/db/DbSpec.scala`

**Interfaces:**
- Produces: `corebanking.domain.InstallmentBreakdown(seq: Int, dueDate: LocalDate, amountDue:
  BigDecimal, interest: BigDecimal, principal: BigDecimal)`; `corebanking.domain.Schedule
  .amortizationBreakdown(principal: BigDecimal, annualRate: BigDecimal, installments:
  List[(Int, LocalDate, BigDecimal)]): List[InstallmentBreakdown]`.
- Produces: `corebanking.db.Db.transactor(config: DbConfig): Transactor`.
- Produces: `corebanking.db.localDateCodec: DbCodec[LocalDate]` (a package-level `given`, imported
  elsewhere as `import corebanking.db.given`).
- Consumes: `corebanking.config.DbConfig` (existing), `corebanking.domain.LoanTerms` fields
  (existing, for reference only — this task does not touch `LoanTerms`).

- [ ] **Step 1: Write the failing test for `amortizationBreakdown`**

Append to `src/test/scala/corebanking/domain/ScheduleSpec.scala`, inside the existing `suite(...)`
argument list (add a comma after the last existing test):

```scala
    test("amortization breakdown replays declining balance across two installments") {
      val installments = List(
        (1, LocalDate.parse("2026-09-01"), BigDecimal("434.94")),
        (2, LocalDate.parse("2026-10-01"), BigDecimal("434.94"))
      )
      val breakdown = Schedule.amortizationBreakdown(terms.principal, terms.annualRate, installments)
      assertTrue(
        breakdown == List(
          InstallmentBreakdown(
            seq = 1,
            dueDate = LocalDate.parse("2026-09-01"),
            amountDue = BigDecimal("434.94"),
            interest = BigDecimal("33.33"),
            principal = BigDecimal("401.61")
          ),
          InstallmentBreakdown(
            seq = 2,
            dueDate = LocalDate.parse("2026-10-01"),
            amountDue = BigDecimal("434.94"),
            interest = BigDecimal("30.66"),
            principal = BigDecimal("404.28")
          )
        )
      )
    },
    test("amortization breakdown sorts installments by seq regardless of input order") {
      val inOrder = List(
        (1, LocalDate.parse("2026-09-01"), BigDecimal("434.94")),
        (2, LocalDate.parse("2026-10-01"), BigDecimal("434.94"))
      )
      val reversed = inOrder.reverse
      assertTrue(
        Schedule.amortizationBreakdown(terms.principal, terms.annualRate, reversed) ==
          Schedule.amortizationBreakdown(terms.principal, terms.annualRate, inOrder)
      )
    },
    test("amortization breakdown of an empty installment list is empty") {
      assertTrue(Schedule.amortizationBreakdown(terms.principal, terms.annualRate, Nil) == Nil)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly corebanking.domain.ScheduleSpec"`
Expected: FAIL to compile — `InstallmentBreakdown` and `Schedule.amortizationBreakdown` do not
exist yet.

- [ ] **Step 3: Implement `InstallmentBreakdown` and `amortizationBreakdown`**

In `src/main/scala/corebanking/domain/Schedule.scala`, add above `object Schedule`:

```scala
/**
 * One installment's principal/interest split, replayed from the standard declining-balance
 * formula over whatever `amount_due` rows the DB actually holds — not a stored column.
 */
final case class InstallmentBreakdown(
    seq: Int,
    dueDate: LocalDate,
    amountDue: BigDecimal,
    interest: BigDecimal,
    principal: BigDecimal
)
```

Inside `object Schedule`, add after `installmentAmount`:

```scala
  /**
   * Replays each installment's interest/principal split against a declining balance, starting
   * from `principal` and stepping at `annualRate / 12` each period — the scheduled split, not a
   * real repayment's fees-then-interest-then-principal allocation (that's CB-08's job).
   */
  def amortizationBreakdown(
      principal: BigDecimal,
      annualRate: BigDecimal,
      installments: List[(Int, LocalDate, BigDecimal)]
  ): List[InstallmentBreakdown] =
    val monthlyRate = annualRate.toDouble / 12.0
    val sorted = installments.sortBy(_._1)
    val (_, breakdownReversed) =
      sorted.foldLeft((principal, List.empty[InstallmentBreakdown])) {
        case ((balance, acc), (seq, dueDate, amountDue)) =>
          val interest = (balance * BigDecimal(monthlyRate)).setScale(2, BigDecimal.RoundingMode.HALF_UP)
          val principalPortion = amountDue - interest
          val nextBalance = balance - principalPortion
          val step = InstallmentBreakdown(seq, dueDate, amountDue, interest, principalPortion)
          (nextBalance, step :: acc)
      }
    breakdownReversed.reverse
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly corebanking.domain.ScheduleSpec"`
Expected: PASS, all tests in the suite green.

- [ ] **Step 5: Write the failing test for `Db` + `Codecs`**

Create `src/test/scala/corebanking/db/DbSpec.scala`:

```scala
package corebanking.db

import zio.Scope
import zio.test.*

import corebanking.config.DbConfig

/**
 * Proves the shared `Transactor` and the `LocalDate` codec work end-to-end against the real
 * docker-compose Postgres (must already be up: `docker compose up -d`), the same convention
 * `SchemaMigrationSpec` uses.
 */
object DbSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Db.transactor + localDateCodec")(
    test("connects and reads system_clock.current_date_value as a LocalDate") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          val xa = Db.transactor(config)
          connect(xa) {
            sql"SELECT current_date_value FROM system_clock".query[java.time.LocalDate].run()
          }
        }.map(rows => assertTrue(rows.size == 1))
    }
  ) @@ TestAspect.timeout(30.seconds)
```

- [ ] **Step 6: Run test to verify it fails**

Run: `sbt "testOnly corebanking.db.DbSpec"`
Expected: FAIL to compile — `Db.transactor` does not exist yet, and `sql"..."`/`connect` are
unresolved without `com.augustnagro.magnum.*` in scope and no `DbCodec[LocalDate]` given.

- [ ] **Step 7: Implement `Db.scala` and `Codecs.scala`**

Create `src/main/scala/corebanking/db/Db.scala`:

```scala
package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/** Builds the shared, unpooled `Transactor` every read tool uses against the mock database. */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
```

Create `src/main/scala/corebanking/db/Codecs.scala`:

```scala
package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate

import com.augustnagro.magnum.DbCodec

/**
 * Maps SQL `DATE` columns (booking date, value date, opened-on, due date, system clock) to
 * `LocalDate`; magnum has no built-in codec for it.
 */
given localDateCodec: DbCodec[LocalDate] with
  val cols: IArray[Int] = IArray(Types.DATE)
  def readSingle(rs: ResultSet, pos: Int): LocalDate = rs.getObject(pos, classOf[LocalDate])
  def writeSingle(date: LocalDate, ps: PreparedStatement, pos: Int): Unit = ps.setObject(pos, date)
  def queryRepr: String = "?"
```

Add the `com.augustnagro.magnum.*` import to the top of `DbSpec.scala` written in Step 5 (it was
left out deliberately so Step 6 fails on both the missing type and the missing import):

```scala
import com.augustnagro.magnum.*
```

- [ ] **Step 8: Run test to verify it passes**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.db.DbSpec"`
Expected: PASS.

- [ ] **Step 9: Run the full test suite and formatter**

Run: `sbt -batch scalafmtCheckAll compile test`
Expected: PASS, no formatting diffs, no failures.

- [ ] **Step 10: Measure the increment**

Run: `git diff --shortstat origin/main..HEAD -- . ':!docs/superpowers/**' ':!*.lock'`
Expected: additions + deletions ≤ 300.

- [ ] **Step 11: Commit**

```bash
git add src/main/scala/corebanking/domain/Schedule.scala \
        src/test/scala/corebanking/domain/ScheduleSpec.scala \
        src/main/scala/corebanking/db/Db.scala \
        src/main/scala/corebanking/db/Codecs.scala \
        src/test/scala/corebanking/db/DbSpec.scala
git commit -m "feat(db): magnum transactor, LocalDate codec, loan amortization breakdown (CB-05, #5)"
```

---

## Task 2: `get_client` tool

**Files:**
- Create: `src/test/scala/corebanking/db/TestTransactions.scala`
- Create: `src/main/scala/corebanking/tools/GetClient.scala`
- Create: `src/test/scala/corebanking/tools/GetClientSpec.scala`
- Modify: `src/main/scala/corebanking/Server.scala`
- Modify: `src/test/scala/corebanking/ServerProcessSpec.scala`

**Interfaces:**
- Consumes: `corebanking.db.Db.transactor`, `corebanking.db.localDateCodec` (Task 1);
  `corebanking.config.DbConfig.fromEnv()`, `corebanking.config.CoreEnv` (existing);
  `corebanking.tools.ToolResponse.respond` (existing).
- Produces: `corebanking.db.TestTransactions.rollingBack[A](xa: Transactor)(f: DbTx ?=> A): A` —
  test-only helper every later tool spec in this plan reuses to seed fixture rows without leaving
  them behind.
- Produces: `corebanking.tools.ClientData(id: String, displayName: String, openedOn: LocalDate)`;
  `corebanking.tools.GetClient.find(clientId: UUID)(using DbCon): ClientData`;
  `corebanking.tools.GetClient.response(env: CoreEnv, clientId: UUID)(using DbCon): String`.
- Produces (`Server.scala`): `private val xa: Transactor` field every later task's `@Tool` method
  reuses; the `get_client` tool itself.

- [ ] **Step 1: Write the rollback-transaction test helper**

Magnum's `DbCon`/`DbTx` cannot be constructed directly outside the library (their constructors are
`private[magnum]`), so seeding fixture rows for a read-only query test must go through `transact`,
which commits on success and rolls back on any exception. Create
`src/test/scala/corebanking/db/TestTransactions.scala`:

```scala
package corebanking.db

import com.augustnagro.magnum.*

/**
 * Runs `f` inside a transaction that always rolls back, returning whatever `f` returned. Lets a
 * spec seed fixture rows and query them in the same transaction without leaving anything behind —
 * `SchemaMigrationSpec`'s savepoint-per-assertion pattern does the same thing with raw JDBC; this
 * is the equivalent for magnum-based reads.
 */
object TestTransactions:

  private final case class RollbackWithResult[A](value: A) extends RuntimeException

  def rollingBack[A](xa: Transactor)(f: DbTx ?=> A): A =
    try
      transact(xa) { (tx: DbTx) ?=>
        throw RollbackWithResult(f(using tx))
      }
      throw new IllegalStateException("unreachable: transact always rethrows after rollback")
    catch case RollbackWithResult(value) => value.asInstanceOf[A]
```

This file has no test of its own — it is exercised by every spec that calls it starting with Step 2
below, so there is nothing to run standalone yet.

- [ ] **Step 2: Write the failing test for `GetClient`**

Create `src/test/scala/corebanking/tools/GetClientSpec.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.Scope
import zio.json.*
import zio.test.*

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, given}
import corebanking.db.TestTransactions.rollingBack

object GetClientSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000a1")
  private val MissingClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedClient(id: UUID)(using DbCon): Unit =
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($id, 'Ada Lovelace', DATE '2026-01-15')".update.run()

  final case class DecodedEnvelope(env: String, data: ClientData)
  object DecodedEnvelope:
    given JsonDecoder[ClientData] = DeriveJsonDecoder.gen[ClientData]
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetClient")(
    test("find returns the client's display name and opened-on date") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedClient(ClientId)
            GetClient.find(ClientId)
          }
        }.map { data =>
          assertTrue(
            data == ClientData(
              id = ClientId.toString,
              displayName = "Ada Lovelace",
              openedOn = LocalDate.parse("2026-01-15")
            )
          )
        }
    },
    test("find raises NoSuchElementException for an unknown client id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa) { GetClient.find(MissingClientId) })
          .exit
          .map(exit => assertTrue(exit.isFailure))
    },
    test("response envelopes the client under the given env label") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedClient(ClientId)
            GetClient.response(CoreEnv.Mock, ClientId)
          }
        }.map { json =>
          assertTrue(
            json.fromJson[DecodedEnvelope] ==
              Right(
                DecodedEnvelope(
                  env = "mock",
                  data = ClientData(ClientId.toString, "Ada Lovelace", LocalDate.parse("2026-01-15"))
                )
              )
          )
        }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
```

- [ ] **Step 3: Run test to verify it fails**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetClientSpec"`
Expected: FAIL to compile — `ClientData` and `GetClient` do not exist yet.

- [ ] **Step 4: Implement `GetClient.scala`**

Create `src/main/scala/corebanking/tools/GetClient.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** A client's identity and the date they were onboarded. */
final case class ClientData(id: String, displayName: String, openedOn: LocalDate)

object ClientData:
  given JsonEncoder[ClientData] = DeriveJsonEncoder.gen[ClientData]

/** Query row shape for `get_client`, one-to-one with its `SELECT`'s column order. */
private final case class ClientRow(displayName: String, openedOn: LocalDate) derives DbCodec

object GetClient:

  /** Looks up one client by id. Throws `NoSuchElementException` when no client has that id,
   * which fast-mcp-scala surfaces as a tool-call error. */
  def find(clientId: UUID)(using DbCon): ClientData =
    val rows = sql"SELECT display_name, opened_on FROM clients WHERE id = $clientId"
      .query[ClientRow]
      .run()
    rows.headOption match
      case Some(row) => ClientData(clientId.toString, row.displayName, row.openedOn)
      case None => throw new NoSuchElementException(s"no client with id $clientId")

  def response(env: CoreEnv, clientId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(clientId))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetClientSpec"`
Expected: PASS, all three tests green.

- [ ] **Step 6: Wire `get_client` into `Server.scala`**

Read the current `src/main/scala/corebanking/Server.scala` first — it imports `corebanking.tools.Ping`
and declares `coreEnv`, `name`, `version`, and the `ping` tool. Modify it to:

```scala
package corebanking

import java.util.UUID

import com.augustnagro.magnum.{Transactor, connect}
import com.tjclp.fastmcp.{*, given}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.Db
import corebanking.tools.{GetClient, Ping}
```

(keep the existing doc comment on `object Server` unchanged), then add after the `coreEnv` val and
before `override def name`:

```scala
  /** Unpooled — this is a single-writer mock server, not a production connection pool. */
  private val xa: Transactor = Db.transactor(DbConfig.fromEnv())
```

and add after the `ping` tool method:

```scala
  @Tool(
    name = Some("get_client"),
    description = Some("Returns a client's display name and opened-on date"),
    readOnlyHint = Some(true)
  )
  def getClient(clientId: UUID): String =
    connect(xa) { GetClient.response(coreEnv, clientId) }
```

- [ ] **Step 7: Extend `ServerProcessSpec` with a real end-to-end `get_client` call**

`spawn` in `ServerProcessSpec.scala` clears the child process's environment entirely and sets only
what its `env: Map[String, String]` argument gives it, so `get_client` — the first tool that
touches the database — needs `DATABASE_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD` forwarded
explicitly from the test JVM's own `sys.env` (which already has them, sourced from `.env` before
`sbt test` runs). Add this near the top of `ServerProcessSpec.scala`, after the existing
`private val processTimeout = 30.seconds`:

```scala
  private val dbEnv: Map[String, String] =
    Seq("DATABASE_URL", "POSTGRES_USER", "POSTGRES_PASSWORD")
      .flatMap(key => java.lang.System.getenv(key) match
        case null => None
        case value => Some(key -> value)
      )
      .toMap
```

Change `runHappyPath`'s `spawn` call from:

```scala
      proc <- spawn(Map("CORE_ENV" -> "sandbox"))
```

to:

```scala
      proc <- spawn(Map("CORE_ENV" -> "sandbox") ++ dbEnv)
```

Add a new frame constant near `pingCallFrame`:

```scala
  private val getClientMissingCallFrame =
    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_client","arguments":{"clientId":"018f3f00-0000-7000-8000-0000000000ff"}}}"""
```

and add it to the `frames` vector:

```scala
  private val frames = Vector(initializeFrame, initializedNotification, pingCallFrame, getClientMissingCallFrame)
```

Add a new test to the `suite(...)` in `spec`, after the existing sandbox happy-path test:

```scala
      test("CORE_ENV=sandbox: get_client on an unknown id comes back as a tool-call error") {
        for
          outcome <- runHappyPath()
          toolCallLine = outcome.stdoutLines.find(_.contains("\"id\":3"))
        yield assertTrue(
          toolCallLine.isDefined,
          toolCallLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true)
        )
      }
```

- [ ] **Step 8: Run the full test suite and formatter**

Run: `set -a && source .env && set +a && sbt -batch scalafmtCheckAll compile test`
Expected: PASS. If `isError` does not decode as `true` for the unknown-client case, read the raw
`toolCallLine` value to see how fast-mcp-scala actually reports the thrown `NoSuchElementException`
and adjust the assertion (and, if needed, `GetClient.find`'s error type) to match — this step is
where that framework behavior gets pinned down for every later task in this plan to reuse.

- [ ] **Step 9: Measure the increment**

Run: `git diff --shortstat origin/main..HEAD -- . ':!docs/superpowers/**' ':!*.lock'`
Expected: additions + deletions ≤ 300 (this increment only — i.e. the diff since Task 1's commit,
not since `origin/main`; use `git diff --shortstat <task-1-commit>..HEAD -- ...` once `gh stack` is
initialized, per `references/story.md` Step 2 of "Gates And Publish").

- [ ] **Step 10: Commit**

```bash
git add src/test/scala/corebanking/db/TestTransactions.scala \
        src/main/scala/corebanking/tools/GetClient.scala \
        src/test/scala/corebanking/tools/GetClientSpec.scala \
        src/main/scala/corebanking/Server.scala \
        src/test/scala/corebanking/ServerProcessSpec.scala
git commit -m "feat(tools): get_client read tool (CB-05, #5)"
```

---

## Task 3: `list_accounts` tool

**Files:**
- Create: `src/main/scala/corebanking/tools/ListAccounts.scala`
- Create: `src/test/scala/corebanking/tools/ListAccountsSpec.scala`
- Modify: `src/main/scala/corebanking/Server.scala`

**Interfaces:**
- Consumes: `corebanking.db.TestTransactions.rollingBack` (Task 2); `corebanking.db.Db`,
  `corebanking.db.localDateCodec` (Task 1); `corebanking.tools.ToolResponse.respond` (existing).
- Produces: `corebanking.tools.AccountData(id: String, productId: String, kind: String, openedOn:
  LocalDate, balance: BigDecimal)`; `corebanking.tools.ListAccounts.find(clientId: UUID)(using
  DbCon): List[AccountData]`; `corebanking.tools.ListAccounts.response(env: CoreEnv, clientId:
  UUID)(using DbCon): String`.

- [ ] **Step 1: Write the failing tests for `ListAccounts`**

Create `src/test/scala/corebanking/tools/ListAccountsSpec.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.Scope
import zio.json.*
import zio.test.*

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, given}
import corebanking.db.TestTransactions.rollingBack

object ListAccountsSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val ProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b1")
  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b2")
  private val EmptyClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b3")
  private val AccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b4")
  private val TxPastId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b5")
  private val TxFutureId = UUID.fromString("018f3f00-0000-7000-8000-0000000000b6")
  private val MissingClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedFixture()(using DbCon): Unit =
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($ProductId, 'Test Savings', 'savings', 0.015)".update.run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Grace Hopper', DATE '2026-01-01')".update.run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($EmptyClientId, 'No Accounts', DATE '2026-01-01')".update.run()
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($AccountId, $ClientId, $ProductId, 'savings', DATE '2026-01-05')".update.run()
    // Effective today: counted in the balance.
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxPastId, $AccountId, 'deposit', 100.00, DATE '2026-01-06', DATE '2026-01-06', 'list-accounts-past')".update.run()
    // Value-dated after the system clock: not yet effective, must not be counted.
    sql"UPDATE system_clock SET current_date_value = DATE '2026-01-10'".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxFutureId, $AccountId, 'deposit', 50.00, DATE '2026-01-06', DATE '2026-06-01', 'list-accounts-future')".update.run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ListAccounts")(
    test("find returns one account per client with balance limited to value_date <= system_clock") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedFixture()
            ListAccounts.find(ClientId)
          }
        }.map { accounts =>
          assertTrue(
            accounts == List(
              AccountData(
                id = AccountId.toString,
                productId = ProductId.toString,
                kind = "savings",
                openedOn = LocalDate.parse("2026-01-05"),
                balance = BigDecimal("100.00")
              )
            )
          )
        }
    },
    test("find returns an empty list for a client that exists but has no accounts") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedFixture()
            ListAccounts.find(EmptyClientId)
          }
        }.map(accounts => assertTrue(accounts == Nil))
    },
    test("find raises NoSuchElementException for an unknown client id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa) { ListAccounts.find(MissingClientId) })
          .exit
          .map(exit => assertTrue(exit.isFailure))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.ListAccountsSpec"`
Expected: FAIL to compile — `AccountData` and `ListAccounts` do not exist yet.

- [ ] **Step 3: Implement `ListAccounts.scala`**

Create `src/main/scala/corebanking/tools/ListAccounts.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** One of a client's accounts, with its balance as of the mock system date. */
final case class AccountData(
    id: String,
    productId: String,
    kind: String,
    openedOn: LocalDate,
    balance: BigDecimal
)

object AccountData:
  given JsonEncoder[AccountData] = DeriveJsonEncoder.gen[AccountData]

/** Query row shape for `list_accounts`, one-to-one with its `SELECT`'s column order. */
private final case class AccountRow(
    id: UUID,
    productId: UUID,
    kind: String,
    openedOn: LocalDate,
    balance: BigDecimal
) derives DbCodec

object ListAccounts:

  /** Lists a client's accounts. Throws `NoSuchElementException` when no client has `clientId`. */
  def find(clientId: UUID)(using DbCon): List[AccountData] =
    val clientExists = sql"SELECT 1 FROM clients WHERE id = $clientId".query[Int].run()
    if clientExists.isEmpty then throw new NoSuchElementException(s"no client with id $clientId")
    sql"""
      SELECT a.id, a.product_id, a.kind, a.opened_on,
             COALESCE(SUM(t.amount) FILTER (WHERE t.value_date <= sc.current_date_value), 0.00) AS balance
      FROM accounts a
      CROSS JOIN system_clock sc
      LEFT JOIN transactions t ON t.account_id = a.id
      WHERE a.client_id = $clientId
      GROUP BY a.id, a.product_id, a.kind, a.opened_on
      ORDER BY a.opened_on
    """.query[AccountRow].run().toList.map { r =>
      AccountData(r.id.toString, r.productId.toString, r.kind, r.openedOn, r.balance)
    }

  def response(env: CoreEnv, clientId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(clientId))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.ListAccountsSpec"`
Expected: PASS, all three tests green.

- [ ] **Step 5: Wire `list_accounts` into `Server.scala`**

Add `ListAccounts` to the existing `import corebanking.tools.{GetClient, Ping}` line (making it
`import corebanking.tools.{GetClient, ListAccounts, Ping}`), then add after the `get_client` tool:

```scala
  @Tool(
    name = Some("list_accounts"),
    description = Some("Lists a client's accounts with balances as of the mock system date"),
    readOnlyHint = Some(true)
  )
  def listAccounts(clientId: UUID): String =
    connect(xa) { ListAccounts.response(coreEnv, clientId) }
```

- [ ] **Step 6: Run the full test suite and formatter**

Run: `set -a && source .env && set +a && sbt -batch scalafmtCheckAll compile test`
Expected: PASS.

- [ ] **Step 7: Measure the increment**

Run: `git diff --shortstat <task-2-commit>..HEAD -- . ':!docs/superpowers/**' ':!*.lock'`
Expected: additions + deletions ≤ 300.

- [ ] **Step 8: Commit**

```bash
git add src/main/scala/corebanking/tools/ListAccounts.scala \
        src/test/scala/corebanking/tools/ListAccountsSpec.scala \
        src/main/scala/corebanking/Server.scala
git commit -m "feat(tools): list_accounts read tool (CB-05, #5)"
```

---

## Task 4: `get_transactions` tool

**Files:**
- Create: `src/main/scala/corebanking/tools/GetTransactions.scala`
- Create: `src/test/scala/corebanking/tools/GetTransactionsSpec.scala`
- Modify: `src/main/scala/corebanking/Server.scala`

**Interfaces:**
- Consumes: `corebanking.db.TestTransactions.rollingBack` (Task 2); `corebanking.db.Db`,
  `corebanking.db.localDateCodec` (Task 1).
- Produces: `corebanking.tools.TransactionData(id: String, `type`: String, amount: BigDecimal,
  bookingDate: LocalDate, valueDate: LocalDate, reversesId: Option[String])`;
  `corebanking.tools.GetTransactions.find(accountId: UUID, startDate: Option[LocalDate], endDate:
  Option[LocalDate])(using DbCon): List[TransactionData]`; `corebanking.tools.GetTransactions
  .response(env: CoreEnv, accountId: UUID, startDate: Option[LocalDate], endDate:
  Option[LocalDate])(using DbCon): String`.

- [ ] **Step 1: Write the failing tests for `GetTransactions`**

Create `src/test/scala/corebanking/tools/GetTransactionsSpec.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.Scope
import zio.test.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner, given}
import corebanking.db.TestTransactions.rollingBack

object GetTransactionsSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val ProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c1")
  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c2")
  private val AccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c3")
  private val TxEarlyId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c4")
  private val TxMidId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c5")
  private val TxLateId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c6")
  private val TxReversalId = UUID.fromString("018f3f00-0000-7000-8000-0000000000c7")
  private val MissingAccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedFixture()(using DbCon): Unit =
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($ProductId, 'Test Savings', 'savings', 0.015)".update.run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Katherine Johnson', DATE '2026-01-01')".update.run()
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($AccountId, $ClientId, $ProductId, 'savings', DATE '2026-01-01')".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxEarlyId, $AccountId, 'deposit', 100.00, DATE '2026-01-05', DATE '2026-01-05', 'get-tx-early')".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxMidId, $AccountId, 'deposit', 200.00, DATE '2026-02-10', DATE '2026-02-10', 'get-tx-mid')".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) VALUES ($TxReversalId, $AccountId, 'reversal', 200.00, DATE '2026-02-11', DATE '2026-02-11', $TxMidId, 'get-tx-reversal')".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxLateId, $AccountId, 'deposit', 300.00, DATE '2026-03-20', DATE '2026-03-20', 'get-tx-late')".update.run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetTransactions")(
    test("find with no date filters returns every transaction ordered by value_date, with reversesId set") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedFixture()
            GetTransactions.find(AccountId, None, None)
          }
        }.map { txs =>
          assertTrue(
            txs.map(_.id) == List(TxEarlyId, TxMidId, TxReversalId, TxLateId).map(_.toString),
            txs.find(_.id == TxReversalId.toString).flatMap(_.reversesId) == Some(TxMidId.toString)
          )
        }
    },
    test("find filters by value_date range, inclusive on both ends") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedFixture()
            GetTransactions.find(
              AccountId,
              Some(LocalDate.parse("2026-02-10")),
              Some(LocalDate.parse("2026-02-11"))
            )
          }
        }.map { txs =>
          assertTrue(txs.map(_.id) == List(TxMidId, TxReversalId).map(_.toString))
        }
    },
    test("find with start_date after end_date returns an empty list, not an error") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedFixture()
            GetTransactions.find(
              AccountId,
              Some(LocalDate.parse("2026-03-01")),
              Some(LocalDate.parse("2026-01-01"))
            )
          }
        }.map(txs => assertTrue(txs == Nil))
    },
    test("find raises NoSuchElementException for an unknown account id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa) { GetTransactions.find(MissingAccountId, None, None) })
          .exit
          .map(exit => assertTrue(exit.isFailure))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetTransactionsSpec"`
Expected: FAIL to compile — `GetTransactions` does not exist yet.

- [ ] **Step 3: Implement `GetTransactions.scala`**

Create `src/main/scala/corebanking/tools/GetTransactions.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** One ledger entry: both dates are always present so a caller can see booking vs. value date. */
final case class TransactionData(
    id: String,
    `type`: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[String]
)

object TransactionData:
  given JsonEncoder[TransactionData] = DeriveJsonEncoder.gen[TransactionData]

/** Query row shape for `get_transactions`, one-to-one with its `SELECT`'s column order. */
private final case class TransactionRow(
    id: UUID,
    txType: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[UUID]
) derives DbCodec

object GetTransactions:

  /** Lists an account's transactions, optionally filtered by an inclusive `value_date` range.
   * Throws `NoSuchElementException` when no account has `accountId`. */
  def find(
      accountId: UUID,
      startDate: Option[LocalDate],
      endDate: Option[LocalDate]
  )(using DbCon): List[TransactionData] =
    val accountExists = sql"SELECT 1 FROM accounts WHERE id = $accountId".query[Int].run()
    if accountExists.isEmpty then throw new NoSuchElementException(s"no account with id $accountId")
    sql"""
      SELECT id, type, amount, booking_date, value_date, reverses_id
      FROM transactions
      WHERE account_id = $accountId
        AND ($startDate IS NULL OR value_date >= $startDate)
        AND ($endDate IS NULL OR value_date <= $endDate)
      ORDER BY value_date, booking_date
    """.query[TransactionRow].run().toList.map { r =>
      TransactionData(r.id.toString, r.txType, r.amount, r.bookingDate, r.valueDate, r.reversesId.map(_.toString))
    }

  def response(
      env: CoreEnv,
      accountId: UUID,
      startDate: Option[LocalDate],
      endDate: Option[LocalDate]
  )(using DbCon): String =
    ToolResponse.respond(env, find(accountId, startDate, endDate))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetTransactionsSpec"`
Expected: PASS, all four tests green.

- [ ] **Step 5: Wire `get_transactions` into `Server.scala`**

Add `java.time.LocalDate` to the imports (a new `import java.time.LocalDate` line alongside the
existing `import java.util.UUID`), add `GetTransactions` to the tools import (now
`import corebanking.tools.{GetClient, GetTransactions, ListAccounts, Ping}`), then add after
`list_accounts`:

```scala
  @Tool(
    name = Some("get_transactions"),
    description = Some("Lists an account's transactions, optionally filtered by an inclusive value_date range"),
    readOnlyHint = Some(true)
  )
  def getTransactions(
      accountId: UUID,
      startDate: Option[LocalDate] = None,
      endDate: Option[LocalDate] = None
  ): String =
    connect(xa) { GetTransactions.response(coreEnv, accountId, startDate, endDate) }
```

- [ ] **Step 6: Prove the optional date parameters actually decode over the wire**

`Option[LocalDate]` tool parameters with a default are new to this codebase (`ping` and
`get_client` take no or required args only) — confirm fast-mcp-scala's arg decoding actually
accepts both the omitted and the provided cases before trusting it in later tasks. Add to
`ServerProcessSpec.scala`: a new frame constant after `getClientMissingCallFrame`:

```scala
  private val getTransactionsNoDatesCallFrame =
    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_transactions","arguments":{"accountId":"018f3f00-0000-7000-8000-0000000000ff"}}}"""
  private val getTransactionsWithDatesCallFrame =
    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_transactions","arguments":{"accountId":"018f3f00-0000-7000-8000-0000000000ff","startDate":"2026-01-01","endDate":"2026-12-31"}}}"""
```

add both to `frames`:

```scala
  private val frames = Vector(
    initializeFrame,
    initializedNotification,
    pingCallFrame,
    getClientMissingCallFrame,
    getTransactionsNoDatesCallFrame,
    getTransactionsWithDatesCallFrame
  )
```

and a new test after the `get_client` one:

```scala
      test("CORE_ENV=sandbox: get_transactions decodes both with and without optional date args") {
        for
          outcome <- runHappyPath()
          noDatesLine = outcome.stdoutLines.find(_.contains("\"id\":4"))
          withDatesLine = outcome.stdoutLines.find(_.contains("\"id\":5"))
        yield assertTrue(
          noDatesLine.isDefined,
          withDatesLine.isDefined,
          // Both calls reach GetTransactions.find and fail there with "no account", not with a
          // framework-level argument-decoding error — proving Option[LocalDate] round-trips.
          noDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true),
          withDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true)
        )
      }
```

If either assertion fails, read the raw response line: a schema/decoding error looks different
from a `NoSuchElementException`-driven tool error (check the `content` text), and tells you whether
`Option[LocalDate] = None` needs a different encoding (e.g. splitting into two overloads, or an
explicit `@Param(required = false)`) — adjust `Server.scala`'s `getTransactions` signature
accordingly and re-run.

- [ ] **Step 7: Run the full test suite and formatter**

Run: `set -a && source .env && set +a && sbt -batch scalafmtCheckAll compile test`
Expected: PASS.

- [ ] **Step 8: Measure the increment**

Run: `git diff --shortstat <task-3-commit>..HEAD -- . ':!docs/superpowers/**' ':!*.lock'`
Expected: additions + deletions ≤ 300.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/corebanking/tools/GetTransactions.scala \
        src/test/scala/corebanking/tools/GetTransactionsSpec.scala \
        src/main/scala/corebanking/Server.scala \
        src/test/scala/corebanking/ServerProcessSpec.scala
git commit -m "feat(tools): get_transactions read tool (CB-05, #5)"
```

---

## Task 5: `get_loan_schedule` tool + BACKLOG status

**Files:**
- Create: `src/main/scala/corebanking/tools/GetLoanSchedule.scala`
- Create: `src/test/scala/corebanking/tools/GetLoanScheduleSpec.scala`
- Modify: `src/main/scala/corebanking/Server.scala`
- Modify: `BACKLOG.md`

**Interfaces:**
- Consumes: `corebanking.domain.Schedule.amortizationBreakdown`, `corebanking.domain
  .InstallmentBreakdown` (Task 1); `corebanking.db.TestTransactions.rollingBack` (Task 2).
- Produces: `corebanking.tools.LoanScheduleData(loanId: String, seq: Int, dueDate: LocalDate,
  amountDue: BigDecimal, interest: BigDecimal, principal: BigDecimal)`;
  `corebanking.tools.GetLoanSchedule.find(loanId: UUID)(using DbCon): List[LoanScheduleData]`;
  `corebanking.tools.GetLoanSchedule.response(env: CoreEnv, loanId: UUID)(using DbCon): String`.

- [ ] **Step 1: Write the failing tests for `GetLoanSchedule`**

Create `src/test/scala/corebanking/tools/GetLoanScheduleSpec.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.Scope
import zio.test.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner, given}
import corebanking.db.TestTransactions.rollingBack

object GetLoanScheduleSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val LoanProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d1")
  private val SavingsProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d2")
  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d3")
  private val LoanAccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d4")
  private val SavingsAccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d5")
  private val LoanNoInstallmentsId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d6")
  private val MissingLoanId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedProducts()(using DbCon): Unit =
    sql"INSERT INTO products (id, name, kind, annual_rate, term_months) VALUES ($LoanProductId, 'Test Loan', 'loan', 0.08, 12)".update.run()
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($SavingsProductId, 'Test Savings', 'savings', 0.015)".update.run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Margaret Hamilton', DATE '2026-01-01')".update.run()

  private def seedLoanWithInstallments()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($LoanAccountId, $ClientId, $LoanProductId, 'loan', DATE '2026-08-01')".update.run()
    sql"INSERT INTO loans (account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee) VALUES ($LoanAccountId, 5000.00, 0.08, 12, DATE '2026-08-01', 434.94, 7, 15.00)".update.run()
    sql"INSERT INTO installments (account_id, seq, due_date, amount_due) VALUES ($LoanAccountId, 1, DATE '2026-09-01', 434.94)".update.run()
    sql"INSERT INTO installments (account_id, seq, due_date, amount_due) VALUES ($LoanAccountId, 2, DATE '2026-10-01', 434.94)".update.run()

  private def seedSavingsAccount()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($SavingsAccountId, $ClientId, $SavingsProductId, 'savings', DATE '2026-08-01')".update.run()

  private def seedLoanWithNoInstallments()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($LoanNoInstallmentsId, $ClientId, $LoanProductId, 'loan', DATE '2026-08-01')".update.run()
    sql"INSERT INTO loans (account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee) VALUES ($LoanNoInstallmentsId, 5000.00, 0.08, 12, DATE '2026-08-01', 434.94, 7, 15.00)".update.run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetLoanSchedule")(
    test("find returns each installment's dates, amount due, and interest/principal split") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedProducts()
            seedLoanWithInstallments()
            GetLoanSchedule.find(LoanAccountId)
          }
        }.map { schedule =>
          assertTrue(
            schedule == List(
              LoanScheduleData(
                loanId = LoanAccountId.toString,
                seq = 1,
                dueDate = LocalDate.parse("2026-09-01"),
                amountDue = BigDecimal("434.94"),
                interest = BigDecimal("33.33"),
                principal = BigDecimal("401.61")
              ),
              LoanScheduleData(
                loanId = LoanAccountId.toString,
                seq = 2,
                dueDate = LocalDate.parse("2026-10-01"),
                amountDue = BigDecimal("434.94"),
                interest = BigDecimal("30.66"),
                principal = BigDecimal("404.28")
              )
            )
          )
        }
    },
    test("find returns an empty schedule for a loan with no installments yet") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedProducts()
            seedLoanWithNoInstallments()
            GetLoanSchedule.find(LoanNoInstallmentsId)
          }
        }.map(schedule => assertTrue(schedule == Nil))
    },
    test("find raises NoSuchElementException for an account with no loans row (e.g. a savings account)") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO.attemptBlocking {
          rollingBack(xa) {
            seedProducts()
            seedSavingsAccount()
            scala.util.Try(GetLoanSchedule.find(SavingsAccountId)).isFailure
          }
        }.map(wasRejected => assertTrue(wasRejected))
    },
    test("find raises NoSuchElementException for an unknown loan id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa) { GetLoanSchedule.find(MissingLoanId) })
          .exit
          .map(exit => assertTrue(exit.isFailure))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetLoanScheduleSpec"`
Expected: FAIL to compile — `LoanScheduleData` and `GetLoanSchedule` do not exist yet.

- [ ] **Step 3: Implement `GetLoanSchedule.scala`**

Create `src/main/scala/corebanking/tools/GetLoanSchedule.scala`:

```scala
package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given
import corebanking.domain.Schedule

/** One scheduled installment on a loan: `loanId` is the loan's `accounts.id` — there is no
 * separate human-facing loan label in this schema. */
final case class LoanScheduleData(
    loanId: String,
    seq: Int,
    dueDate: LocalDate,
    amountDue: BigDecimal,
    interest: BigDecimal,
    principal: BigDecimal
)

object LoanScheduleData:
  given JsonEncoder[LoanScheduleData] = DeriveJsonEncoder.gen[LoanScheduleData]

private final case class LoanRow(principal: BigDecimal, annualRate: BigDecimal) derives DbCodec
private final case class InstallmentRow(seq: Int, dueDate: LocalDate, amountDue: BigDecimal) derives DbCodec

object GetLoanSchedule:

  /** Loan terms come from `loans`; the interest/principal split is not stored anywhere and is
   * recomputed here by replaying `Schedule.amortizationBreakdown` over whatever `installments`
   * rows exist. Throws `NoSuchElementException` when `loanId` has no `loans` row at all — that is
   * distinct from a loan with zero installments, which returns an empty schedule. */
  def find(loanId: UUID)(using DbCon): List[LoanScheduleData] =
    val loan = sql"SELECT principal, annual_rate FROM loans WHERE account_id = $loanId"
      .query[LoanRow]
      .run()
      .headOption
      .getOrElse(throw new NoSuchElementException(s"no loan with id $loanId"))
    val installments = sql"SELECT seq, due_date, amount_due FROM installments WHERE account_id = $loanId ORDER BY seq"
      .query[InstallmentRow]
      .run()
      .toList
      .map(r => (r.seq, r.dueDate, r.amountDue))
    Schedule
      .amortizationBreakdown(loan.principal, loan.annualRate, installments)
      .map(b => LoanScheduleData(loanId.toString, b.seq, b.dueDate, b.amountDue, b.interest, b.principal))

  def response(env: CoreEnv, loanId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(loanId))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `set -a && source .env && set +a && sbt "testOnly corebanking.tools.GetLoanScheduleSpec"`
Expected: PASS, all four tests green.

- [ ] **Step 5: Wire `get_loan_schedule` into `Server.scala`**

Add `GetLoanSchedule` to the tools import (now
`import corebanking.tools.{GetClient, GetLoanSchedule, GetTransactions, ListAccounts, Ping}`),
then add after `get_transactions`:

```scala
  @Tool(
    name = Some("get_loan_schedule"),
    description = Some("Shows a loan's installment schedule with interest/principal breakdown"),
    readOnlyHint = Some(true)
  )
  def getLoanSchedule(loanId: UUID): String =
    connect(xa) { GetLoanSchedule.response(coreEnv, loanId) }
```

- [ ] **Step 6: Set `BACKLOG.md`'s CB-05 row to `in-review`**

Change the CB-05 row's status column from `in-progress` to `in-review`.

- [ ] **Step 7: Run the full test suite, formatter, and secret scan**

Run: `set -a && source .env && set +a && sbt -batch scalafmtCheckAll compile test`
Run: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`
Expected: both PASS.

- [ ] **Step 8: Measure the increment**

Run: `git diff --shortstat <task-4-commit>..HEAD -- . ':!docs/superpowers/**' ':!*.lock'`
Expected: additions + deletions ≤ 300.

- [ ] **Step 9: Commit**

```bash
git add src/main/scala/corebanking/tools/GetLoanSchedule.scala \
        src/test/scala/corebanking/tools/GetLoanScheduleSpec.scala \
        src/main/scala/corebanking/Server.scala \
        BACKLOG.md
git commit -m "feat(tools): get_loan_schedule read tool; CB-05 in-review (CB-05, #5)"
```

---

## After Task 5

Follow `references/story.md`'s "Gates And Publish" section: `git fetch origin && git rebase
origin/main` (resolve any conflict — `Server.scala`/`build.sbt` collisions with CB-06/CB-10's
unmerged worktrees are expected and normal), `gh stack init`, `gh stack add` for each task boundary
above, `gh stack submit`, then edit each PR body to the required ≤15-line shape. A whole-branch
`risk-reviewer` pass (opus) runs before the stack is submitted, per `SKILL.md`'s coordination rule
6.
