package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.{Spec as _, *}
import zio.*
import zio.test.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}
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
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($ProductId, 'Test Savings', 'savings', 0.015)".update
      .run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Grace Hopper', DATE '2026-01-01')".update
      .run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($EmptyClientId, 'No Accounts', DATE '2026-01-01')".update
      .run()
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($AccountId, $ClientId, $ProductId, 'savings', DATE '2026-01-05')".update
      .run()
    // Effective today: counted in the balance.
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxPastId, $AccountId, 'deposit', 100.00, DATE '2026-01-06', DATE '2026-01-06', 'list-accounts-past')".update
      .run()
    // Value-dated after the system clock: not yet effective, must not be counted.
    sql"UPDATE system_clock SET current_date_value = DATE '2026-01-10'".update.run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxFutureId, $AccountId, 'deposit', 50.00, DATE '2026-01-06', DATE '2026-06-01', 'list-accounts-future')".update
      .run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ListAccounts")(
    test("find returns one account per client with balance limited to value_date <= system_clock") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              ListAccounts.find(ClientId)
            }
          }
          .map { accounts =>
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
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              ListAccounts.find(EmptyClientId)
            }
          }
          .map(accounts => assertTrue(accounts == Nil))
    },
    test("find raises NoSuchElementException for an unknown client id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa)(ListAccounts.find(MissingClientId)))
          .exit
          .map(exit => assertTrue(exit.isFailure))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
