package corebanking.tools

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

/**
 * Records one `audit_log` row per tool call — even a preview or a rejected call — so nothing goes
 * unaudited.
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
