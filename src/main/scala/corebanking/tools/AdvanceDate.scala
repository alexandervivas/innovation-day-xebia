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

final private case class AdvanceDateRequest(
    days: Int,
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object AdvanceDateRequest:
  given JsonEncoder[AdvanceDateRequest] = DeriveJsonEncoder.gen[AdvanceDateRequest]

/**
 * Advances the ledger's own clock (CLAUDE.md rule 4). `system_clock` is a singleton row with no
 * `idempotency_key` column of its own, so a retried call is recognized by replaying the matching
 * `audit_log` row from its first call, instead of looking up a row on `system_clock` itself.
 */
object AdvanceDate:

  def run(
      xa: Transactor,
      env: CoreEnv,
      days: Int,
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    require(days > 0, s"days must be positive, got $days")
    val requestJson = AdvanceDateRequest(days, idempotencyKey, dryRun).toJson
    val response = idempotencyKey.flatMap(findReplay(xa, _)) match
      case Some(replayed) => replayed
      case None => ToolResponse.respond(env, execute(xa, days, dryRun))
    AuditLog.record(
      xa,
      toolName = "advance_date",
      env = env,
      requestJson = requestJson,
      responseJson = response
    )
    response

  /** Most recent stored response for a matching key, or `None` on a first-time key. */
  private def findReplay(xa: Transactor, key: String): Option[String] =
    transact(xa):
      sql"""
        SELECT response::text
        FROM audit_log
        WHERE tool_name = 'advance_date' AND request ->> 'idempotencyKey' = $key
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
