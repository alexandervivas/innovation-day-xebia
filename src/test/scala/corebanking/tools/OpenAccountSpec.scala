package corebanking.tools

import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{DbCodec, sql, transact}

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

  final private case class DecodedEnvelope(env: String, data: DecodedData)
  private object DecodedEnvelope:
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  final private case class DecodedError(error: String, message: String)
  private object DecodedError:
    given JsonDecoder[DecodedError] = DeriveJsonDecoder.gen[DecodedError]

  final private case class DecodedErrorEnvelope(env: String, data: DecodedError)
  private object DecodedErrorEnvelope:
    given JsonDecoder[DecodedErrorEnvelope] = DeriveJsonDecoder.gen[DecodedErrorEnvelope]

  private def decode(json: String): DecodedEnvelope =
    json.fromJson[DecodedEnvelope].getOrElse(throw new RuntimeException(s"undecodable: $json"))

  private def decodeError(json: String): DecodedError =
    json
      .fromJson[DecodedErrorEnvelope]
      .getOrElse(throw new RuntimeException(s"undecodable: $json"))
      .data

  final private case class CountRow(n: Long) derives DbCodec

  private def txCount(idempotencyKey: String): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM transactions WHERE idempotency_key = $idempotencyKey"
        .query[CountRow]
        .run()
        .head
        .n

  private def accountCount(clientId: UUID): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM accounts WHERE client_id = $clientId"
        .query[CountRow]
        .run()
        .head
        .n

  private def txCountForClient(clientId: UUID): Long =
    transact(xa):
      sql"""
        SELECT COUNT(*) AS n FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        WHERE a.client_id = $clientId
      """.query[CountRow].run().head.n

  private def seedClientAndProduct(): (UUID, UUID) =
    transact(xa):
      val clientId = corebanking.db.Ids.next()
      val productId = corebanking.db.Ids.next()
      sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($clientId, 'OpenAccount Spec Client', CURRENT_DATE)".update
        .run()
      sql"INSERT INTO products (id, name, kind, annual_rate, term_months) VALUES ($productId, 'Spec Savings', 'savings', 0.02, NULL)".update
        .run()
      (clientId, productId)

  private def freshKey(): String = s"cb06-open-account-${UUID.randomUUID()}"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("OpenAccount.run")(
      test("happy path: opens an account and posts the opening transaction") {
        for _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
        yield
          val (clientId, productId) = seedClientAndProduct()
          val decoded = decode(
            OpenAccount.run(
              xa,
              CoreEnv.Mock,
              clientId.toString,
              productId.toString,
              currency = "COP",
              initialDeposit = Some("1250.55"),
              idempotencyKey = None,
              dryRun = false
            )
          )
          assertTrue(
            decoded.env == "mock",
            decoded.data.currency == "COP",
            decoded.data.initialDeposit == "1250.55",
            decoded.data.clientId == clientId.toString,
            decoded.data.productId == productId.toString,
            decoded.data.dryRun == false,
            accountCount(clientId) == 1L,
            txCount(decoded.data.openingTransactionId) == 1L
          )
      },
      test("initial_deposit defaults to 0.00 when omitted") {
        val (clientId, productId) = seedClientAndProduct()
        val decoded = decode(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            "USD",
            None,
            None,
            dryRun = false
          )
        )
        assertTrue(decoded.data.initialDeposit == "0.00", decoded.data.currency == "USD")
      },
      test("unknown client_id returns a clean CLIENT_NOT_FOUND error, no partial write") {
        val (_, productId) = seedClientAndProduct()
        val missingClient = UUID.randomUUID()
        val decoded = decodeError(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            missingClient.toString,
            productId.toString,
            "COP",
            None,
            None,
            dryRun = false
          )
        )
        assertTrue(decoded.error == "CLIENT_NOT_FOUND", accountCount(missingClient) == 0L)
      },
      test("unknown product_id returns a clean PRODUCT_NOT_FOUND error") {
        val (clientId, _) = seedClientAndProduct()
        val missingProduct = UUID.randomUUID()
        val decoded = decodeError(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            missingProduct.toString,
            "COP",
            None,
            None,
            dryRun = false
          )
        )
        assertTrue(decoded.error == "PRODUCT_NOT_FOUND", accountCount(clientId) == 0L)
      },
      test("an unsupported currency is rejected before any write") {
        val (clientId, productId) = seedClientAndProduct()
        val decoded = decodeError(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            "JPY",
            None,
            None,
            dryRun = false
          )
        )
        assertTrue(decoded.error == "INVALID_CURRENCY", accountCount(clientId) == 0L)
      },
      test("an initial_deposit finer than the cent is rejected before any write") {
        val (clientId, productId) = seedClientAndProduct()
        val decoded = decodeError(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            "COP",
            Some("1250.555"),
            None,
            dryRun = false
          )
        )
        assertTrue(
          decoded.error == "INVALID_AMOUNT",
          accountCount(clientId) == 0L,
          txCountForClient(clientId) == 0L
        )
      },
      test("a negative initial_deposit is rejected before any write") {
        val (clientId, productId) = seedClientAndProduct()
        val decoded = decodeError(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            "COP",
            Some("-100"),
            None,
            dryRun = false
          )
        )
        assertTrue(
          decoded.error == "INVALID_AMOUNT",
          accountCount(clientId) == 0L,
          txCountForClient(clientId) == 0L
        )
      },
      test("repeated idempotency_key returns the identical result and posts only one transaction") {
        val (clientId, productId) = seedClientAndProduct()
        val key = freshKey()
        val first = decode(
          OpenAccount
            .run(
              xa,
              CoreEnv.Mock,
              clientId.toString,
              productId.toString,
              "COP",
              Some("10.00"),
              Some(key),
              dryRun = false
            )
        ).data
        val second = decode(
          OpenAccount
            .run(
              xa,
              CoreEnv.Mock,
              clientId.toString,
              productId.toString,
              "COP",
              Some("10.00"),
              Some(key),
              dryRun = false
            )
        ).data
        assertTrue(
          first.openingTransactionId == second.openingTransactionId,
          first.id == second.id,
          first.initialDeposit == second.initialDeposit,
          txCount(key) == 1L,
          accountCount(clientId) == 1L
        )
      },
      test("dry_run leaves the database unchanged") {
        val (clientId, productId) = seedClientAndProduct()
        val key = freshKey()
        val decoded = decode(
          OpenAccount.run(
            xa,
            CoreEnv.Mock,
            clientId.toString,
            productId.toString,
            "COP",
            Some("5.00"),
            Some(key),
            dryRun = true
          )
        ).data
        assertTrue(
          decoded.dryRun == true,
          txCount(key) == 0L,
          accountCount(clientId) == 0L
        )
      }
    ) @@ sequential @@ timeout(1.minute)
