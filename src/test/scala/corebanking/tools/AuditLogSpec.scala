package corebanking.tools

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.sql

import corebanking.config.DbConfig
import corebanking.db.{AuditLogRow, Db, FlywayRunner}

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

  private def rowByMarker(marker: String): AuditLogRow =
    com.augustnagro.magnum.transact(xa):
      sql"""
        SELECT id, tool_name, env, request::text AS request, response::text AS response
        FROM audit_log
        WHERE request::text LIKE ${"%" + marker + "%"}
      """
        .query[AuditLogRow]
        .run()
        .head

  /**
   * Compares stored `request`/`response` to what was passed in structurally (`jsonb =`), not as
   * text, since Postgres reformats `jsonb` on storage.
   */
  private def matchesStored(
      marker: String,
      requestJson: String,
      responseJson: String
  ): JsonMatch =
    com.augustnagro.magnum.transact(xa):
      sql"""
        SELECT
          (request = ${requestJson}::jsonb) AS request_matches,
          (response = ${responseJson}::jsonb) AS response_matches
        FROM audit_log
        WHERE request::text LIKE ${"%" + marker + "%"}
      """
        .query[JsonMatch]
        .run()
        .head

  final private case class Count(n: Long) derives com.augustnagro.magnum.DbCodec

  final private case class JsonMatch(requestMatches: Boolean, responseMatches: Boolean)
      derives com.augustnagro.magnum.DbCodec

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AuditLog.record")(
      test("writes exactly one row per call, request/response stored as the given JSON") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          marker = s"audit-log-spec-${java.util.UUID.randomUUID()}"
          before <- ZIO.attemptBlocking(countByMarker(marker))
          requestJson = s"""{"marker": "$marker", "name": "Ada"}"""
          responseJson = """{"env": "mock", "data": {"ok": true}}"""
          _ <- ZIO.attemptBlocking(
            AuditLog.record(
              xa,
              toolName = "create_client",
              env = "mock",
              requestJson = requestJson,
              responseJson = responseJson
            )
          )
          after <- ZIO.attemptBlocking(countByMarker(marker))
          row <- ZIO.attemptBlocking(rowByMarker(marker))
          jsonMatch <- ZIO.attemptBlocking(matchesStored(marker, requestJson, responseJson))
        yield assertTrue(
          before == 0L,
          after == 1L,
          row.toolName == "create_client",
          row.env == "mock",
          jsonMatch.requestMatches,
          jsonMatch.responseMatches
        )
      }
    ) @@ sequential @@ timeout(1.minute)
