package corebanking.tools

import java.sql.DriverManager

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}

/**
 * `GetAuditLog.run` is the query behind the `get_audit_log` tool. Each test tags its rows with a
 * unique `tool_name` prefix and filters on it, so this suite stays correct as the table accumulates
 * rows from every other spec that also exercises `audit_log`.
 */
object GetAuditLogSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  /**
   * Inserts one row with an explicit `called_at`, bypassing `AuditLog.record`'s `now()` default so
   * time-window assertions are deterministic.
   */
  private def seed(toolName: String, calledAt: String): Task[Unit] =
    ZIO.attemptBlocking {
      val conn = DriverManager.getConnection(config.url, config.user, config.password)
      try
        conn
          .createStatement()
          .execute(
            "INSERT INTO audit_log (tool_name, env, request, response, called_at) " +
              s"VALUES ('$toolName', 'mock', '{}'::jsonb, '{}'::jsonb, '$calledAt'::timestamptz)"
          )
      finally conn.close()
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GetAuditLog.run")(
      test("returns rows between start_time and end_time inclusive, ordered by called_at") {
        val suffix = java.util.UUID.randomUUID()
        val beforeName = s"get-audit-log-spec-before-$suffix"
        val inRangeName = s"get-audit-log-spec-in-range-$suffix"
        val afterName = s"get-audit-log-spec-after-$suffix"
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          _ <- seed(beforeName, "2020-01-01T00:00:00Z")
          _ <- seed(inRangeName, "2020-01-02T00:00:00Z")
          _ <- seed(afterName, "2020-01-03T00:00:00Z")
          entries <- ZIO.attempt(
            GetAuditLog.run(
              xa,
              startTime = Some("2020-01-02T00:00:00Z"),
              endTime = Some("2020-01-02T23:59:59Z")
            )
          )
          names = entries
            .map(_.toolName)
            .filter(name =>
              name.startsWith("get-audit-log-spec-") && name.endsWith(suffix.toString)
            )
        yield assertTrue(names == List(inRangeName))
      },
      test("returns the full history when both bounds are omitted") {
        val unboundedName = s"get-audit-log-spec-unbounded-${java.util.UUID.randomUUID()}"
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          _ <- seed(unboundedName, "2021-06-15T12:00:00Z")
          entries <- ZIO.attempt(GetAuditLog.run(xa, startTime = None, endTime = None))
        yield assertTrue(entries.exists(_.toolName == unboundedName))
      },
      test("returns no match when start_time is after end_time") {
        val invertedName = s"get-audit-log-spec-inverted-${java.util.UUID.randomUUID()}"
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          _ <- seed(invertedName, "2022-03-01T00:00:00Z")
          entries <- ZIO.attempt(
            GetAuditLog.run(
              xa,
              startTime = Some("2022-03-02T00:00:00Z"),
              endTime = Some("2022-03-01T00:00:00Z")
            )
          )
        yield assertTrue(!entries.exists(_.toolName == invertedName))
      },
      test("a malformed start_time fails the call instead of silently ignoring the filter") {
        val result = scala.util.Try(
          GetAuditLog.run(xa, startTime = Some("not-a-date"), endTime = None)
        )
        assertTrue(result.isFailure)
      }
    ) @@ sequential
