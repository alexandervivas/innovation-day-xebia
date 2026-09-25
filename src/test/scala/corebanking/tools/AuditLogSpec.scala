package corebanking.tools

import java.sql.DriverManager

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, DryRun, FlywayRunner}

object AuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class SampleRequest(a: Int)
  object SampleRequest:
    given JsonDecoder[SampleRequest] = DeriveJsonDecoder.gen[SampleRequest]

  final case class SampleResponse(env: String)
  object SampleResponse:
    given JsonDecoder[SampleResponse] = DeriveJsonDecoder.gen[SampleResponse]

  private def countByToolName(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  /**
   * Raw JDBC (not magnum) since this reads back JSONB as text for value assertions, matching
   * `ProductSeedSpec`'s convention for column-value checks.
   */
  private def readRow(toolName: String): (String, String, String, String) =
    val conn = DriverManager.getConnection(config.url, config.user, config.password)
    try
      val stmt = conn.prepareStatement(
        "SELECT tool_name, env, request::text, response::text FROM audit_log WHERE tool_name = ?"
      )
      stmt.setString(1, toolName)
      val rs = stmt.executeQuery()
      if !rs.next() then throw new RuntimeException(s"no audit_log row for tool_name=$toolName")
      val row = (rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
      rs.close()
      row
    finally conn.close()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AuditLog (CB-12)")(
    test("records one row with the given tool name, env, request and response") {
      ZIO
        .attempt {
          val toolName = "cb12_audit_log_spec_" + java.util.UUID.randomUUID().toString
          val before = countByToolName(toolName)
          AuditLog.record(
            xa,
            toolName,
            env = CoreEnv.Mock,
            requestJson = """{"a":1}""",
            responseJson = """{"env":"mock","data":{}}"""
          )
          val after = countByToolName(toolName)
          val (dbToolName, dbEnv, dbRequest, dbResponse) = readRow(toolName)
          (before, after, dbToolName, dbEnv, dbRequest, dbResponse, toolName)
        }
        .map {
          case (before, after, dbToolName, dbEnv, dbRequest, dbResponse, toolName) =>
            assertTrue(
              before == 0,
              after == 1,
              dbToolName == toolName,
              dbEnv == "mock",
              dbRequest.fromJson[SampleRequest] == Right(SampleRequest(a = 1)),
              dbResponse.fromJson[SampleResponse] == Right(SampleResponse(env = "mock"))
            )
        }
    },
    test("commits independently of a surrounding transaction that later rolls back") {
      ZIO
        .attempt {
          val toolName = "cb12_audit_log_rollback_spec_" + java.util.UUID.randomUUID().toString
          val before = countByToolName(toolName)
          DryRun(xa, dryRun = true):
            AuditLog.record(
              xa,
              toolName,
              env = CoreEnv.Mock,
              requestJson = """{"a":1}""",
              responseJson = """{"env":"mock","data":{}}"""
            )
          val after = countByToolName(toolName)
          (before, after)
        }
        .map { case (before, after) => assertTrue(before == 0, after == 1) }
    }
  ) @@ sequential @@ timeout(1.minute)
