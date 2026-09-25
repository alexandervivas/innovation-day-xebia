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

object GetClientSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000a1")
  private val MissingClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedClient(id: UUID)(using DbCon): Unit =
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($id, 'Ada Lovelace', DATE '2026-01-15')".update
      .run()

  final case class DecodedEnvelope(env: String, data: ClientData)
  object DecodedEnvelope:
    given JsonDecoder[ClientData] = DeriveJsonDecoder.gen[ClientData]
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetClient")(
    test("find returns the client's display name and opened-on date") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedClient(ClientId)
              GetClient.find(ClientId)
            }
          }
          .map { data =>
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
          .attemptBlocking(rollingBack(xa)(GetClient.find(MissingClientId)))
          .exit
          .map(exit => assertTrue(exit.isFailure))
    },
    test("response envelopes the client under the given env label") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedClient(ClientId)
              GetClient.response(CoreEnv.Mock, ClientId)
            }
          }
          .map { json =>
            assertTrue(
              json.fromJson[DecodedEnvelope] ==
                Right(
                  DecodedEnvelope(
                    env = "mock",
                    data =
                      ClientData(ClientId.toString, "Ada Lovelace", LocalDate.parse("2026-01-15"))
                  )
                )
            )
          }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
