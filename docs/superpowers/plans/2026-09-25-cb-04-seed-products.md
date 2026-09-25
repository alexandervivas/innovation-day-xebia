# CB-04 Seed Products Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Seed the two demo banking products (Savings, 12-Month Consumer Loan) via a Flyway
migration, on a Postgres 18 stack so their ids are minted by native `uuidv7()`, and introduce the
opaque entity-id types CB-05+ will build on.

**Architecture:** Two-PR `gh stack`. PR 1 bumps the Docker Postgres image and `CLAUDE.md`'s stack
line from 16 to 18 (no Scala changes). PR 2 adds `domain/Ids.scala` (pure opaque types, no ZIO/DB
imports) and `V2__seed_products.sql`, verified end-to-end against the real Postgres 18 container
the same way `SchemaMigrationSpec` (CB-03) verified `V1__schema.sql`: raw JDBC, no magnum/ZIO
layer yet.

**Tech Stack:** Scala 3.9.0, ZIO 2.1.26, zio-test, Flyway 13.8.0, PostgreSQL 18 (Docker), magnum
(not used by this story — no repository layer exists yet).

**Spec:** `docs/superpowers/specs/2026-09-25-cb-04-seed-products.md`

## Global Constraints

- `CORE_ENV` must be `mock` or `sandbox` (unaffected by this story — no server startup changes).
- `transactions` append-only rules are unaffected — this story never inserts into `transactions`.
- Money fields are `NUMERIC`; `products.annual_rate` is `NUMERIC(6,4)` per `V1__schema.sql` — no
  schema change here, just literal values inserted into it.
- No PR exceeds 300 changed lines (additions + deletions; `docs/superpowers/**` excluded). Each
  task below is scoped to fit inside one of the two stack PRs on its own.
- `domain/` stays free of ZIO and DB imports (`CLAUDE.md` rule 9) — `Ids.scala` is plain Scala 3
  over `java.util.UUID`.
- Every commit passes `sbt -batch scalafmtCheckAll compile test` and the secret scan before it
  lands.

## Review Focus

- An existing `postgres_data` Docker volume was initialized by Postgres 16; Postgres 18 refuses to
  start against a data directory from a different major version. Task 1 recreates the volume.
- Flyway 13.8.0 / pgjdbc 42.7.13 compatibility with Postgres 18 is not assumed — Task 1's
  `docker compose up` health check and Task 3's full migration run against the real container are
  the empirical proof, not a version-support claim.
- `NUMERIC(6,4)` storage of `0.0150` and `0.0800` must round-trip exactly (not merely "close
  enough") — Task 3 asserts exact `BigDecimal` equality, not a tolerance comparison.
- `uuidv7()`'s output must actually be version-7/variant-2 shaped, not just any UUID — Task 3
  asserts on `UUID.version()`/`UUID.variant()` directly rather than only checking non-null.
- Re-running the migration (a second `sbt test`, a container restart) must not duplicate seed
  rows — Task 3 re-invokes `FlywayRunner.migrate` and checks row counts stay at one per kind.

---

## Task 1: Postgres 16 → 18 (docker-compose, CLAUDE.md)

**Files:**
- Modify: `docker-compose.yml` (image tag)
- Modify: `CLAUDE.md` (Stack line)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a running Postgres 18 container that Task 3's integration test connects to via
  `DbConfig.fromEnv()` (unchanged — same host/port/user/db env vars, only the image differs).

- [ ] **Step 1: Confirm the current container and volume state**

Run: `docker compose ps && docker volume ls | grep innovation-day-xebia`
Expected: shows the existing `postgres` service (if running) and its `..._postgres_data` volume,
so the next step's teardown is deliberate, not a surprise.

- [ ] **Step 2: Edit `docker-compose.yml`**

Change:
```yaml
    image: postgres:16-alpine
```
to:
```yaml
    image: postgres:18.6-alpine
```

- [ ] **Step 3: Edit `CLAUDE.md`**

In the `## What It Does` / stack line near the top:
```
Stack: Scala 3, sbt, ZIO 2, fast-mcp-scala (annotation-driven tools), Postgres 16, Flyway migrations.
```
becomes:
```
Stack: Scala 3, sbt, ZIO 2, fast-mcp-scala (annotation-driven tools), Postgres 18, Flyway migrations.
```

- [ ] **Step 4: Tear down the old container and volume, bring up Postgres 18**

Postgres refuses to start against a data directory from a different major version, so the old
volume must go — this is local mock/dev data only, not anything CB-04 needs to preserve.

Run: `docker compose down -v && docker compose up -d`
Expected: the `postgres` service starts and its health check goes healthy within ~10s
(`docker compose ps` shows `healthy`).

- [ ] **Step 5: Verify the running server is actually Postgres 18 and `uuidv7()` works**

Run:
```bash
docker compose exec postgres psql -U corebanking -d corebanking -c "SELECT version();"
docker compose exec postgres psql -U corebanking -d corebanking -c "SELECT uuidv7();"
```
Expected: `version()` reports `PostgreSQL 18.x`; `uuidv7()` returns a UUID literal (no error about
an unknown function).

- [ ] **Step 6: Run the existing gates to confirm nothing else broke**

Run: `sbt -batch scalafmtCheckAll compile test`
Expected: PASS — `SchemaMigrationSpec` (CB-03) runs its V1 assertions against the new Postgres 18
container and still passes unchanged.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml CLAUDE.md
git commit -m "chore(infra): bump Postgres 16 to 18 for native uuidv7() (CB-04, #4)"
```

---

## Task 2: `domain/Ids.scala` — opaque entity id types

**Files:**
- Create: `src/main/scala/corebanking/domain/Ids.scala`
- Test: `src/test/scala/corebanking/domain/IdsSpec.scala`
- Delete: `src/main/scala/corebanking/domain/.gitkeep` (first real file in the package)

**Interfaces:**
- Consumes: nothing (pure Scala 3, `java.util.UUID` only).
- Produces: `ClientId`, `ProductId`, `AccountId`, `TransactionId` opaque types, each with
  `apply(java.util.UUID): X` and an `.value: java.util.UUID` extension method. Task 3 consumes
  `ProductId`.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/corebanking/domain/IdsSpec.scala`:

```scala
package corebanking.domain

import java.util.UUID

import zio.test.*

object IdsSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] =
    suite("entity id opaque types")(
      test("ProductId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(ProductId(raw).value == raw)
      },
      test("ClientId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(ClientId(raw).value == raw)
      },
      test("AccountId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(AccountId(raw).value == raw)
      },
      test("TransactionId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(TransactionId(raw).value == raw)
      }
    )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly corebanking.domain.IdsSpec"`
Expected: FAIL to compile — `object ProductId is not a member of package corebanking.domain` (the
type doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `src/main/scala/corebanking/domain/Ids.scala`:

```scala
package corebanking.domain

import java.util.UUID

opaque type ClientId = UUID
object ClientId:
  def apply(value: UUID): ClientId = value
  extension (id: ClientId) def value: UUID = id

opaque type ProductId = UUID
object ProductId:
  def apply(value: UUID): ProductId = value
  extension (id: ProductId) def value: UUID = id

opaque type AccountId = UUID
object AccountId:
  def apply(value: UUID): AccountId = value
  extension (id: AccountId) def value: UUID = id

opaque type TransactionId = UUID
object TransactionId:
  def apply(value: UUID): TransactionId = value
  extension (id: TransactionId) def value: UUID = id
```

Delete the now-obsolete placeholder: `git rm src/main/scala/corebanking/domain/.gitkeep`

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly corebanking.domain.IdsSpec"`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/domain/Ids.scala src/test/scala/corebanking/domain/IdsSpec.scala
git rm src/main/scala/corebanking/domain/.gitkeep
git commit -m "feat(domain): add opaque entity id types (CB-04, #4)"
```

---

## Task 3: `V2__seed_products.sql` — seed the two products

**Files:**
- Create: `src/main/resources/db/migration/V2__seed_products.sql`
- Test: `src/test/scala/corebanking/db/ProductSeedSpec.scala`

**Interfaces:**
- Consumes: `corebanking.domain.ProductId` (Task 2), `corebanking.db.FlywayRunner.migrate`
  (existing, CB-03), `corebanking.config.DbConfig.fromEnv()` (existing, CB-02/03) — same pattern
  as `SchemaMigrationSpec`.
- Produces: two rows in `products`, queryable by `kind`. Nothing downstream in this story consumes
  the migration directly; CB-05's read tools will.

- [ ] **Step 1: Write the failing test**

Create `src/test/scala/corebanking/db/ProductSeedSpec.scala`:

```scala
package corebanking.db

import java.sql.{Connection, DriverManager}
import java.util.UUID

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig
import corebanking.domain.ProductId

/**
 * Runs the real V2 migration against the docker-compose Postgres (must already be up:
 * `docker compose up -d`) and proves CB-04's acceptance criteria: exactly one savings and one
 * loan product, with the values pinned in the CB-04 design note, and ids minted by Postgres 18's
 * native uuidv7().
 */
object ProductSeedSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()

  private def withConnection[A](f: Connection => A): Task[A] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(config.url, config.user, config.password)
      try f(conn)
      finally conn.close()
    }

  private case class ProductRow(
      id: ProductId,
      name: String,
      kind: String,
      annualRate: BigDecimal,
      termMonths: Option[Int],
      accrualBasis: String
  )

  private def productByKind(conn: Connection, kind: String): ProductRow =
    val stmt = conn.prepareStatement(
      "SELECT id, name, kind, annual_rate, term_months, accrual_basis FROM products WHERE kind = ?"
    )
    stmt.setString(1, kind)
    val rs = stmt.executeQuery()
    rs.next()
    val row = ProductRow(
      id = ProductId(UUID.fromString(rs.getString("id"))),
      name = rs.getString("name"),
      kind = rs.getString("kind"),
      annualRate = BigDecimal(rs.getBigDecimal("annual_rate")),
      termMonths = Option(rs.getObject("term_months")).map(_ => rs.getInt("term_months")),
      accrualBasis = rs.getString("accrual_basis")
    )
    rs.close()
    row

  private def countsByKind(conn: Connection): Map[String, Int] =
    val rs = conn.createStatement().executeQuery("SELECT kind, COUNT(*) AS n FROM products GROUP BY kind")
    val results = scala.collection.mutable.Map.empty[String, Int]
    while rs.next() do results += rs.getString("kind") -> rs.getInt("n")
    rs.close()
    results.toMap

  private def isUuidV7(id: UUID): Boolean =
    id.version() == 7 && id.variant() == 2

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("V2__seed_products.sql (CB-04)")(
      test("seeds exactly one savings product with the expected fields") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          savings <- withConnection(productByKind(_, "savings"))
        yield assertTrue(
          savings.name == "Savings",
          savings.annualRate == BigDecimal("0.0150"),
          savings.termMonths.isEmpty,
          savings.accrualBasis == "actual/365"
        )
      },
      test("seeds exactly one loan product with the expected fields") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          loan <- withConnection(productByKind(_, "loan"))
        yield assertTrue(
          loan.name == "12-Month Consumer Loan",
          loan.annualRate == BigDecimal("0.0800"),
          loan.termMonths.contains(12),
          loan.accrualBasis == "actual/365"
        )
      },
      test("seeded product ids are UUIDv7 (version 7, variant 2)") {
        withConnection { conn =>
          (productByKind(conn, "savings").id.value, productByKind(conn, "loan").id.value)
        }.map { case (savingsId, loanId) =>
          assertTrue(isUuidV7(savingsId), isUuidV7(loanId))
        }
      },
      test("re-running the migration does not duplicate seed rows") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          counts <- withConnection(countsByKind)
        yield assertTrue(counts.get("savings").contains(1), counts.get("loan").contains(1))
      }
    ) @@ sequential @@ timeout(1.minute)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly corebanking.db.ProductSeedSpec"`
Expected: FAIL — `rs.next()` returns `false` / `NoSuchElementException`-style failure, since
`products` has no rows yet (no `V2` migration exists).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V2__seed_products.sql`:

```sql
-- V2__seed_products.sql
-- CB-04: seed the two demo banking products. Ids are minted by Postgres 18's native uuidv7(),
-- not hardcoded literals — tests look these rows up by `kind`, not by id.

INSERT INTO products (id, name, kind, annual_rate, term_months, accrual_basis)
VALUES (uuidv7(), 'Savings', 'savings', 0.0150, NULL, 'actual/365');

INSERT INTO products (id, name, kind, annual_rate, term_months, accrual_basis)
VALUES (uuidv7(), '12-Month Consumer Loan', 'loan', 0.0800, 12, 'actual/365');
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly corebanking.db.ProductSeedSpec"`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 5: Run the full gate suite**

Run: `sbt -batch scalafmtCheckAll compile test`
Expected: PASS — all specs including `SchemaMigrationSpec` and `IdsSpec` still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V2__seed_products.sql src/test/scala/corebanking/db/ProductSeedSpec.scala
git commit -m "feat(db): seed savings and loan products (CB-04, #4)"
```

---

## Final Verification (whole story, before PR)

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
docker compose ps   # postgres service healthy on postgres:18.6-alpine
```

Then hand off to `superpowers:finishing-a-development-branch` for the `gh stack` publish
(Task 1 as stack position 1/2, Tasks 2-3 combined as stack position 2/2 — both fit well under the
300-line PR limit).
