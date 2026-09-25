package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.{Spec as _, *}
import zio.*
import zio.json.*
import zio.test.*

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}
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

  final case class DecodedEnvelope(env: String, data: List[TransactionData])
  object DecodedEnvelope:
    given JsonDecoder[TransactionData] = DeriveJsonDecoder.gen[TransactionData]
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  private def seedFixture()(using DbCon): Unit =
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($ProductId, 'Test Savings', 'savings', 0.015)".update
      .run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Katherine Johnson', DATE '2026-01-01')".update
      .run()
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($AccountId, $ClientId, $ProductId, 'savings', DATE '2026-01-01')".update
      .run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxEarlyId, $AccountId, 'deposit', 100.00, DATE '2026-01-05', DATE '2026-01-05', 'get-tx-early')".update
      .run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxMidId, $AccountId, 'deposit', 200.00, DATE '2026-02-10', DATE '2026-02-10', 'get-tx-mid')".update
      .run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key) VALUES ($TxReversalId, $AccountId, 'reversal', 200.00, DATE '2026-02-11', DATE '2026-02-11', $TxMidId, 'get-tx-reversal')".update
      .run()
    sql"INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, idempotency_key) VALUES ($TxLateId, $AccountId, 'deposit', 300.00, DATE '2026-03-20', DATE '2026-03-20', 'get-tx-late')".update
      .run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetTransactions")(
    test(
      "find with no date filters returns every transaction ordered by value_date, with reversesId set"
    ) {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              GetTransactions.find(AccountId, None, None)
            }
          }
          .map { txs =>
            assertTrue(
              txs.map(_.id) == List(TxEarlyId, TxMidId, TxReversalId, TxLateId).map(_.toString),
              txs.find(_.id == TxReversalId.toString).flatMap(_.reversesId) == Some(
                TxMidId.toString
              )
            )
          }
    },
    test("find filters by value_date range, inclusive on both ends") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              GetTransactions.find(
                AccountId,
                Some(LocalDate.parse("2026-02-10")),
                Some(LocalDate.parse("2026-02-11"))
              )
            }
          }
          .map { txs =>
            assertTrue(txs.map(_.id) == List(TxMidId, TxReversalId).map(_.toString))
          }
    },
    test("find with start_date after end_date returns an empty list, not an error") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              GetTransactions.find(
                AccountId,
                Some(LocalDate.parse("2026-03-01")),
                Some(LocalDate.parse("2026-01-01"))
              )
            }
          }
          .map(txs => assertTrue(txs == Nil))
    },
    test("find raises NoSuchElementException for an unknown account id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa)(GetTransactions.find(MissingAccountId, None, None)))
          .exit
          .map(exit => assertTrue(exit.isFailure))
    },
    test("response envelopes every transaction, decoding dates and reversesId exactly") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedFixture()
              GetTransactions.response(CoreEnv.Mock, AccountId, None, None)
            }
          }
          .map { json =>
            assertTrue(
              json.fromJson[DecodedEnvelope] ==
                Right(
                  DecodedEnvelope(
                    env = "mock",
                    data = List(
                      TransactionData(
                        TxEarlyId.toString,
                        "deposit",
                        BigDecimal("100.00"),
                        LocalDate.parse("2026-01-05"),
                        LocalDate.parse("2026-01-05"),
                        None
                      ),
                      TransactionData(
                        TxMidId.toString,
                        "deposit",
                        BigDecimal("200.00"),
                        LocalDate.parse("2026-02-10"),
                        LocalDate.parse("2026-02-10"),
                        None
                      ),
                      TransactionData(
                        TxReversalId.toString,
                        "reversal",
                        BigDecimal("200.00"),
                        LocalDate.parse("2026-02-11"),
                        LocalDate.parse("2026-02-11"),
                        Some(TxMidId.toString)
                      ),
                      TransactionData(
                        TxLateId.toString,
                        "deposit",
                        BigDecimal("300.00"),
                        LocalDate.parse("2026-03-20"),
                        LocalDate.parse("2026-03-20"),
                        None
                      )
                    )
                  )
                )
            )
          }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
