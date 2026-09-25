package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{DryRun, SystemClockRow}
import corebanking.db.given

/** The clock move as `advance_date` reports it back to the caller. */
final case class AdvanceDateData(
    previousDate: String,
    currentDate: String,
    daysAdvanced: Int,
    dryRun: Boolean
)

object AdvanceDateData:
  given JsonEncoder[AdvanceDateData] = DeriveJsonEncoder.gen[AdvanceDateData]

/** Reported instead of `AdvanceDateData` when `days` fails validation. */
final case class AdvanceDateError(error: String)

object AdvanceDateError:
  given JsonEncoder[AdvanceDateError] = DeriveJsonEncoder.gen[AdvanceDateError]

final private case class AdvanceDateRequest(
    days: Int,
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object AdvanceDateRequest:
  given JsonEncoder[AdvanceDateRequest] = DeriveJsonEncoder.gen[AdvanceDateRequest]

/**
 * Moves the ledger's system date forward; a repeated idempotency key returns the original move
 * unchanged.
 */
object AdvanceDate:

  def run(
      xa: Transactor,
      env: CoreEnv,
      days: Int,
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    val requestJson = AdvanceDateRequest(days, idempotencyKey, dryRun).toJson
    val response = idempotencyKey.flatMap(findReplay(xa, env, _)) match
      case Some(replayed) => replayed
      case None =>
        if days <= 0 then
          ToolResponse.respond(env, AdvanceDateError(s"days must be positive, got $days"))
        else ToolResponse.respond(env, execute(xa, days, dryRun))
    AuditLog.record(
      xa,
      toolName = "advance_date",
      env = env,
      requestJson = requestJson,
      responseJson = response
    )
    response

  /** Most recent response a real call stored under this key and env; a dry run caches nothing. */
  private def findReplay(xa: Transactor, env: CoreEnv, key: String): Option[String] =
    transact(xa):
      sql"""
        SELECT response::text
        FROM audit_log
        WHERE tool_name = 'advance_date'
          AND env = ${env.label}
          AND request ->> 'idempotencyKey' = $key
          AND request ->> 'dryRun' = 'false'
        ORDER BY id DESC
        LIMIT 1
      """.query[String].run().headOption

  private def execute(xa: Transactor, days: Int, dryRun: Boolean): AdvanceDateData =
    DryRun(xa, dryRun):
      val previous = readCurrentDate()
      val next = previous.plusDays(days.toLong)
      updateCurrentDate(next)
      AdvanceDateData(previous.toString, next.toString, days, dryRun)

  private def readCurrentDate()(using DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def updateCurrentDate(next: LocalDate)(using DbCon): Unit =
    sql"UPDATE system_clock SET current_date_value = $next WHERE id = true".update.run()
