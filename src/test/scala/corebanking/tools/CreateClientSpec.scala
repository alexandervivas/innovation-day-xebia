package corebanking.tools

import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{DbCodec, sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}

object CreateClientSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  final private case class DecodedData(
      id: String,
      name: String,
      email: Option[String],
      openedOn: String,
      dryRun: Boolean
  )
  private object DecodedData:
    given JsonDecoder[DecodedData] = DeriveJsonDecoder.gen[DecodedData]

  final private case class DecodedEnvelope(env: String, data: DecodedData)
  private object DecodedEnvelope:
    given JsonDecoder[DecodedEnvelope] = DeriveJsonDecoder.gen[DecodedEnvelope]

  private def decode(json: String): DecodedEnvelope =
    json.fromJson[DecodedEnvelope].getOrElse(throw new RuntimeException(s"undecodable: $json"))

  final private case class CountRow(n: Long) derives DbCodec

  private def clientCount(id: String): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM clients WHERE id = ${UUID.fromString(id)}"
        .query[CountRow]
        .run()
        .head
        .n

  private def auditCount(marker: String): Long =
    transact(xa):
      sql"SELECT COUNT(*) AS n FROM audit_log WHERE request::text LIKE ${"%" + marker + "%"}"
        .query[CountRow]
        .run()
        .head
        .n

  private def freshKey(): String = s"cb06-create-client-${UUID.randomUUID()}"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CreateClient.run")(
      test("happy path: inserts a client and returns it in the envelope") {
        for _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
        yield
          val json = CreateClient.run(
            xa,
            CoreEnv.Mock,
            name = "Ada Lovelace",
            email = Some("ada@example.com"),
            idempotencyKey = None,
            dryRun = false
          )
          val decoded = decode(json)
          assertTrue(
            decoded.env == "mock",
            decoded.data.name == "Ada Lovelace",
            decoded.data.email == Some("ada@example.com"),
            decoded.data.dryRun == false,
            clientCount(decoded.data.id) == 1L
          )
      },
      test("email is optional") {
        val json = CreateClient.run(
          xa,
          CoreEnv.Mock,
          name = "No Email Client",
          email = None,
          idempotencyKey = None,
          dryRun = false
        )
        assertTrue(decode(json).data.email == None)
      },
      test("repeated idempotency_key returns the identical result and inserts only once") {
        val key = freshKey()
        val first = decode(
          CreateClient.run(xa, CoreEnv.Mock, "Repeat Client", None, Some(key), dryRun = false)
        )
        val second = decode(
          CreateClient.run(xa, CoreEnv.Mock, "Repeat Client", None, Some(key), dryRun = false)
        )
        assertTrue(
          first.data.id == second.data.id,
          clientCount(first.data.id) == 1L
        )
      },
      test("dry_run leaves the database unchanged") {
        val json = CreateClient.run(
          xa,
          CoreEnv.Mock,
          name = "Dry Run Client",
          email = None,
          idempotencyKey = None,
          dryRun = true
        )
        val decoded = decode(json)
        assertTrue(decoded.data.dryRun == true, clientCount(decoded.data.id) == 0L)
      },
      test("every call is audited exactly once, including idempotency hits and dry runs") {
        val fresh = s"audit-fresh-${UUID.randomUUID()}"
        val repeated = s"audit-repeat-${UUID.randomUUID()}"
        val dry = s"audit-dry-${UUID.randomUUID()}"
        val key = freshKey()

        val freshBefore = auditCount(fresh)
        CreateClient.run(xa, CoreEnv.Mock, fresh, None, None, dryRun = false)
        val freshAfter = auditCount(fresh)

        val repeatedBefore = auditCount(repeated)
        CreateClient.run(xa, CoreEnv.Mock, repeated, None, Some(key), dryRun = false)
        val afterFirstCall = auditCount(repeated)
        CreateClient.run(xa, CoreEnv.Mock, repeated, None, Some(key), dryRun = false)
        val afterRepeatedCall = auditCount(repeated)

        val dryBefore = auditCount(dry)
        CreateClient.run(xa, CoreEnv.Mock, dry, None, None, dryRun = true)
        val dryAfter = auditCount(dry)

        assertTrue(
          freshBefore == 0L,
          freshAfter == 1L,
          repeatedBefore == 0L,
          afterFirstCall == 1L,
          afterRepeatedCall == 2L,
          dryBefore == 0L,
          dryAfter == 1L
        )
      }
    ) @@ sequential @@ timeout(1.minute)
