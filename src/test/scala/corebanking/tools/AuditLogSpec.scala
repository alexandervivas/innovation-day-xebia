package corebanking.tools

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.sql

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}

object AuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private def countByMarker(marker: String): Long =
    com.augustnagro.magnum.transact(xa):
      sql"SELECT COUNT(*) AS n FROM audit_log WHERE request::text LIKE ${"%" + marker + "%"}"
        .query[Count]
        .run()
        .head
        .n

  final private case class Count(n: Long) derives com.augustnagro.magnum.DbCodec

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AuditLog.record")(
      test("writes exactly one row per call, request/response stored as the given JSON") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          marker = s"audit-log-spec-${java.util.UUID.randomUUID()}"
          before <- ZIO.attemptBlocking(countByMarker(marker))
          _ <- ZIO.attemptBlocking(
            AuditLog.record(
              xa,
              toolName = "create_client",
              env = "mock",
              requestJson = s"""{"marker":"$marker","name":"Ada"}""",
              responseJson = """{"env":"mock","data":{"ok":true}}"""
            )
          )
          after <- ZIO.attemptBlocking(countByMarker(marker))
        yield assertTrue(before == 0L, after == 1L)
      }
    ) @@ sequential @@ timeout(1.minute)
