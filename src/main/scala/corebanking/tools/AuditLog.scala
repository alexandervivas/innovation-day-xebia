package corebanking.tools

import zio.json.*

import com.augustnagro.magnum.{Transactor, transact}
import com.augustnagro.magnum.sql

import corebanking.config.CoreEnv

/** Failure payload recorded to the audit log when a tool call raises an error. */
final case class AuditedFailure(error: String)

object AuditedFailure:
  given JsonEncoder[AuditedFailure] = DeriveJsonEncoder.gen[AuditedFailure]

/**
 * Writes one `audit_log` row per tool call, in its own transaction, committed regardless of
 * `dry_run` -- a dry run is still a call that happened.
 */
object AuditLog:
  def record(
      xa: Transactor,
      toolName: String,
      env: CoreEnv,
      requestJson: String,
      responseJson: String,
      dryRun: Boolean = false
  ): Unit =
    transact(xa):
      sql"""
        INSERT INTO audit_log (tool_name, env, request, response, dry_run_flag)
        VALUES ($toolName, ${env.label}, $requestJson::jsonb, $responseJson::jsonb, $dryRun)
      """.update.run()
      ()
