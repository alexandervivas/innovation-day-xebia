package corebanking.tools

import java.time.OffsetDateTime

import zio.json.*

import com.augustnagro.magnum.{DbCodec, Transactor, sql, transact}

/** One `audit_log` row as `get_audit_log` reports it back to the caller. */
final case class AuditLogEntry(
    id: Long,
    toolName: String,
    calledAt: String,
    env: String,
    request: Option[String],
    response: Option[String],
    dryRunFlag: Boolean
)

object AuditLogEntry:
  given JsonEncoder[AuditLogEntry] = DeriveJsonEncoder.gen[AuditLogEntry]

/** `get_audit_log`'s own arguments, logged as that call's own audit-log request. */
final case class GetAuditLogRequest(startTime: Option[String], endTime: Option[String])

object GetAuditLogRequest:
  given JsonEncoder[GetAuditLogRequest] = DeriveJsonEncoder.gen[GetAuditLogRequest]

/** `get_audit_log`'s own audited response: a count rather than the full row list. */
final case class AuditLogSummary(count: Int, startTime: Option[String], endTime: Option[String])

object AuditLogSummary:
  given JsonEncoder[AuditLogSummary] = DeriveJsonEncoder.gen[AuditLogSummary]

final private case class AuditLogRow(
    id: Long,
    toolName: String,
    calledAt: OffsetDateTime,
    env: String,
    request: Option[String],
    response: Option[String],
    dryRunFlag: Boolean
) derives DbCodec

/**
 * Reads `audit_log` history, optionally bounded by `called_at`. Both bounds are inclusive; an
 * omitted bound imposes no filter on that side.
 */
object GetAuditLog:

  def run(
      xa: Transactor,
      startTime: Option[String],
      endTime: Option[String]
  ): List[AuditLogEntry] =
    val start = startTime.map(OffsetDateTime.parse)
    val end = endTime.map(OffsetDateTime.parse)
    val rows = transact(xa):
      sql"""
        SELECT id, tool_name, called_at, env, request::text, response::text, dry_run_flag
        FROM audit_log
        WHERE ($start::timestamptz IS NULL OR called_at >= $start::timestamptz)
          AND ($end::timestamptz IS NULL OR called_at <= $end::timestamptz)
        ORDER BY called_at
      """.query[AuditLogRow].run()
    rows.map(toEntry).toList

  private def toEntry(row: AuditLogRow): AuditLogEntry =
    AuditLogEntry(
      id = row.id,
      toolName = row.toolName,
      calledAt = row.calledAt.toString,
      env = row.env,
      request = row.request,
      response = row.response,
      dryRunFlag = row.dryRunFlag
    )
