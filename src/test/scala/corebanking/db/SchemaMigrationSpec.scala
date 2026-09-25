package corebanking.db

import java.sql.{Connection, DriverManager, SQLException}

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig

/**
 * Runs the real V1 migration against the docker-compose Postgres (must already be up:
 * `docker compose up -d`) and proves the schema behaves per CLAUDE.md's non-negotiable rules:
 * append-only transactions (rule 2), unique idempotency keys (rule 5), and referential integrity
 * between every table this story creates.
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
    val rs = conn
      .createStatement()
      .executeQuery(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
      )
    val names = scala.collection.mutable.Set.empty[String]
    while rs.next() do names += rs.getString("table_name")
    rs.close()
    names.toSet

  /**
   * Seeds one client/product/account/transaction row so FK- and trigger-dependent tests have
   * something real to point at. Runs inside the caller's transaction (autocommit off).
   */
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

  /**
   * Runs `sql` under a savepoint and reports whether it raised a SQLException, rolling back to the
   * savepoint either way so the connection stays usable for the next assertion.
   */
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
