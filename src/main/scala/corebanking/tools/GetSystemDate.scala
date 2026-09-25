package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.SystemClockRow

/** The ledger's current date as `get_system_date` reports it back to the caller. */
final case class SystemDateData(currentDate: String)

object SystemDateData:
  given JsonEncoder[SystemDateData] = DeriveJsonEncoder.gen[SystemDateData]

/** Reads the ledger's own clock — never the JVM clock (CLAUDE.md rule 4). */
object GetSystemDate:

  def run(xa: Transactor, env: CoreEnv): String =
    val data = SystemDateData(readCurrentDate(xa).toString)
    val response = ToolResponse.respond(env, data)
    AuditLog.record(
      xa,
      toolName = "get_system_date",
      env = env,
      requestJson = "{}",
      responseJson = response
    )
    response

  private def readCurrentDate(xa: Transactor): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue
