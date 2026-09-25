package corebanking.tools

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

/**
 * Writes one `audit_log` row per tool call, in its own transaction, committed regardless of
 * `dry_run` or an idempotency hit -- CLAUDE.md rule 6 requires "every tool call logged", and a dry
 * run or a repeated key is still a call that happened. Kept separate from the caller's own
 * `DryRun`-wrapped transaction on purpose: a rolled-back dry run must not also roll back its own
 * audit trail entry.
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
