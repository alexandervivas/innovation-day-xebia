package corebanking.tools

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

/**
 * Records one `audit_log` row per tool call, in its own transaction, so it survives a dry run's
 * rollback.
 */
object AuditLog:
  def record(
      xa: Transactor,
      toolName: String,
      env: String,
      requestJson: String,
      responseJson: String
  ): Unit =
    transact(xa):
      sql"""
        INSERT INTO audit_log (tool_name, env, request, response)
        VALUES ($toolName, $env, $requestJson::jsonb, $responseJson::jsonb)
      """.update.run()
      ()
