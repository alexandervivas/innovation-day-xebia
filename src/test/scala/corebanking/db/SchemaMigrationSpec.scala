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

  /** A plain `RAISE EXCEPTION` in plpgsql, i.e. one of this schema's own guard triggers. */
  private val RaiseException = "P0001"

  /** foreign_key_violation. */
  private val ForeignKeyViolation = "23503"

  /** unique_violation. */
  private val UniqueViolation = "23505"

  /** check_violation. */
  private val CheckViolation = "23514"

  /**
   * Runs `sql` under a savepoint and reports whether it was rejected for the *expected* reason,
   * rolling back to the savepoint either way so the connection stays usable for the next assertion.
   * Matching on SQLSTATE rather than on "any SQLException" is what stops this suite from staying
   * green if a constraint is ever lost and some unrelated error throws in its place.
   */
  private def rejects(
      conn: Connection,
      savepointName: String,
      sql: String,
      expectedSqlState: String
  ): Boolean =
    val savepoint = conn.setSavepoint(savepointName)
    val wasRejected =
      try
        conn.createStatement().execute(sql)
        false
      catch case e: SQLException => e.getSQLState == expectedSqlState
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
            "UPDATE transactions SET amount = amount WHERE id = 'schema-spec-tx'",
            RaiseException
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
            "DELETE FROM transactions WHERE id = 'schema-spec-tx'",
            RaiseException
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
              "VALUES ('schema-spec-orphan', 'does-not-exist', 'schema-spec-product', 'loan', CURRENT_DATE)",
            ForeignKeyViolation
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
              "VALUES ('schema-spec-tx-2', 'schema-spec-account', 'repayment', 10.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-idem')",
            UniqueViolation
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
              "VALUES ('schema-spec-tx-3', 'schema-spec-account', 'reversal', 100.00, CURRENT_DATE, CURRENT_DATE, 'does-not-exist', 'schema-spec-idem-2')",
            ForeignKeyViolation
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
            "INSERT INTO system_clock (current_date_value) VALUES ('2020-01-01')",
            UniqueViolation
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("transactions is append-only: TRUNCATE is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_truncate",
            "TRUNCATE transactions",
            RaiseException
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("a transaction can be reversed only once: a second reversal is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          conn
            .createStatement()
            .execute(
              "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) " +
                "VALUES ('schema-spec-rev-1', 'schema-spec-account', 'reversal', 100.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-tx', 'schema-spec-idem-rev-1')"
            )
          val wasRejected = rejects(
            conn,
            "sp_double_reversal",
            "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) " +
              "VALUES ('schema-spec-rev-2', 'schema-spec-account', 'reversal', 100.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-tx', 'schema-spec-idem-rev-2')",
            UniqueViolation
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("a transaction cannot reverse itself") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          val wasRejected = rejects(
            conn,
            "sp_self_reversal",
            "INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) " +
              "VALUES ('schema-spec-self', 'schema-spec-account', 'reversal', 100.00, CURRENT_DATE, CURRENT_DATE, 'schema-spec-self', 'schema-spec-idem-self')",
            CheckViolation
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("system_clock cannot be emptied: DELETE is rejected") {
        withConnection { conn =>
          conn.setAutoCommit(false)
          val wasRejected = rejects(
            conn,
            "sp_clock_delete",
            "DELETE FROM system_clock",
            RaiseException
          )
          conn.rollback()
          wasRejected
        }.map(wasRejected => assertTrue(wasRejected))
      },
      test("accruals.amount keeps full precision: an 8-decimal accrual round-trips exactly") {
        // 5000.00 * 0.08 / 365 = one day's actual/365 interest on the demo loan. At NUMERIC(18,2)
        // this would be stored as 1.10, drifting by real cents once summed over a month;
        // RecalculationSpec requires it unrounded until allocated or reported.
        val dailyAccrual = "1.09589041"
        withConnection { conn =>
          conn.setAutoCommit(false)
          seedLoanAccount(conn)
          conn
            .createStatement()
            .execute(
              "INSERT INTO accruals (account_id, accrual_date, amount) " +
                s"VALUES ('schema-spec-account', CURRENT_DATE, $dailyAccrual)"
            )
          val rs = conn
            .createStatement()
            .executeQuery(
              "SELECT amount FROM accruals WHERE account_id = 'schema-spec-account'"
            )
          rs.next()
          val stored = rs.getBigDecimal("amount").toPlainString
          rs.close()
          conn.rollback()
          stored
        }.map(stored => assertTrue(stored == dailyAccrual))
      }
    ) @@ sequential @@ timeout(1.minute)
