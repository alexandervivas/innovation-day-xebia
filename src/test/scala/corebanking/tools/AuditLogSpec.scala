package corebanking.tools

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}

object AuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  private def countByToolName(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AuditLog (CB-12)")(
    test("records one row with the given tool name, env, request and response") {
      ZIO
        .attempt {
          val toolName = "cb12_audit_log_spec_" + java.util.UUID.randomUUID().toString
          val before = countByToolName(toolName)
          AuditLog.record(
            xa,
            toolName,
            env = "mock",
            requestJson = """{"a":1}""",
            responseJson = """{"env":"mock","data":{}}"""
          )
          (before, countByToolName(toolName))
        }
        .map { case (before, after) => assertTrue(before == 0, after == 1) }
    }
  ) @@ sequential @@ timeout(1.minute)
