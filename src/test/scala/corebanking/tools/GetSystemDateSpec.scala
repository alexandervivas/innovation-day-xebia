package corebanking.tools

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, SystemClockRow}
import corebanking.db.given

object GetSystemDateSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class DecodedData(currentDate: String)
  object DecodedData:
    given JsonCodec[DecodedData] = DeriveJsonCodec.gen[DecodedData]

  final case class DecodedEnvelope(env: String, data: DecodedData)
  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  private def rawCurrentDate(): java.time.LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  private def setCurrentDate(date: java.time.LocalDate): Unit =
    transact(xa):
      sql"UPDATE system_clock SET current_date_value = $date WHERE id = true".update.run()

  private def auditCount(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  def spec: Spec[TestEnvironment & Scope, Any] = suite("get_system_date (CB-12)")(
    test("returns the ledger's current_date_value, not the JVM clock") {
      val expected = rawCurrentDate()
      val json = GetSystemDate.run(xa, CoreEnv.Mock)
      assertTrue(
        json.fromJson[DecodedEnvelope] ==
          Right(DecodedEnvelope(env = "mock", data = DecodedData(currentDate = expected.toString)))
      )
    },
    test("reflects a clock value written after the seed") {
      val before = rawCurrentDate()
      val moved = before.plusDays(30)
      setCurrentDate(moved)
      val json = GetSystemDate.run(xa, CoreEnv.Mock)
      setCurrentDate(before)
      assertTrue(
        json.fromJson[DecodedEnvelope] ==
          Right(DecodedEnvelope(env = "mock", data = DecodedData(currentDate = moved.toString)))
      )
    },
    test("logs exactly one audit_log row per call") {
      val before = auditCount("get_system_date")
      GetSystemDate.run(xa, CoreEnv.Mock)
      val after = auditCount("get_system_date")
      assertTrue(after == before + 1)
    }
  ) @@ sequential @@ timeout(1.minute)
