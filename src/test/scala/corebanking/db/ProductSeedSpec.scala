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
 * `docker compose up -d`) and proves CB-04's acceptance criteria: exactly one savings and one loan
 * product, with the values pinned in the CB-04 design note, and ids minted by Postgres 18's native
 * uuidv7().
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
    val rs =
      conn.createStatement().executeQuery("SELECT kind, COUNT(*) AS n FROM products GROUP BY kind")
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
        }.map {
          case (savingsId, loanId) =>
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
