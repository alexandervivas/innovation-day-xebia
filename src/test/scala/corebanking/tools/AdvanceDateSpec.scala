package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner, SystemClockRow}

object AdvanceDateSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  final case class DecodedData(
      previousDate: String,
      currentDate: String,
      daysAdvanced: Int,
      dryRun: Boolean
  )
  object DecodedData:
    given JsonCodec[DecodedData] = DeriveJsonCodec.gen[DecodedData]

  final case class DecodedEnvelope(env: String, data: DecodedData)
  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  final case class DecodedError(error: String)
  object DecodedError:
    given JsonCodec[DecodedError] = DeriveJsonCodec.gen[DecodedError]

  final case class DecodedErrorEnvelope(env: String, data: DecodedError)
  object DecodedErrorEnvelope:
    given JsonCodec[DecodedErrorEnvelope] = DeriveJsonCodec.gen[DecodedErrorEnvelope]

  private def rawCurrentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  private def auditCount(toolName: String): Int =
    transact(xa):
      sql"SELECT COUNT(*) FROM audit_log WHERE tool_name = $toolName".query[Int].run().head

  def spec: Spec[TestEnvironment & Scope, Any] = suite("advance_date (CB-12)")(
    test("advances the clock forward by the given number of days") {
      val before = rawCurrentDate()
      val json = AdvanceDate.run(xa, CoreEnv.Mock, days = 3, idempotencyKey = None, dryRun = false)
      val after = rawCurrentDate()
      assertTrue(
        after == before.plusDays(3),
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "mock",
              data = DecodedData(before.toString, after.toString, daysAdvanced = 3, dryRun = false)
            )
          )
      )
    },
    test("a non-positive days value returns an error envelope and still logs one audit_log row") {
      val before = rawCurrentDate()
      val beforeCount = auditCount("advance_date")
      val json = AdvanceDate.run(xa, CoreEnv.Mock, days = 0, idempotencyKey = None, dryRun = false)
      val after = rawCurrentDate()
      val afterCount = auditCount("advance_date")
      assertTrue(
        after == before,
        afterCount == beforeCount + 1,
        json.fromJson[DecodedErrorEnvelope] ==
          Right(
            DecodedErrorEnvelope(
              env = "mock",
              data = DecodedError(error = "days must be positive, got 0")
            )
          )
      )
    },
    test("dry_run leaves the clock unchanged but returns the would-be result") {
      val before = rawCurrentDate()
      val json = AdvanceDate.run(xa, CoreEnv.Mock, days = 4, idempotencyKey = None, dryRun = true)
      val after = rawCurrentDate()
      assertTrue(
        after == before,
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "mock",
              data = DecodedData(
                before.toString,
                before.plusDays(4).toString,
                daysAdvanced = 4,
                dryRun = true
              )
            )
          )
      )
    },
    test("a repeated idempotency_key replays the first result without advancing the clock again") {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val first =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 2, idempotencyKey = Some(key), dryRun = false)
      val afterFirst = rawCurrentDate()
      val second =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 9, idempotencyKey = Some(key), dryRun = false)
      val afterSecond = rawCurrentDate()
      assertTrue(
        afterFirst == before.plusDays(2),
        afterSecond == afterFirst,
        second.fromJson[DecodedEnvelope] == first.fromJson[DecodedEnvelope]
      )
    },
    test(
      "a repeated idempotency_key replays the original move even when the repeat's days is bad"
    ) {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val first =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 5, idempotencyKey = Some(key), dryRun = false)
      val afterFirst = rawCurrentDate()
      val second =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 0, idempotencyKey = Some(key), dryRun = false)
      val afterSecond = rawCurrentDate()
      assertTrue(
        afterFirst == before.plusDays(5),
        afterSecond == afterFirst,
        second.fromJson[DecodedEnvelope] == first.fromJson[DecodedEnvelope]
      )
    },
    test("a dry_run call does not poison a later real call with the same idempotency_key") {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val dryJson =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 7, idempotencyKey = Some(key), dryRun = true)
      val afterDry = rawCurrentDate()
      val realJson =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 7, idempotencyKey = Some(key), dryRun = false)
      val afterReal = rawCurrentDate()
      assertTrue(
        afterDry == before,
        afterReal == before.plusDays(7),
        dryJson.fromJson[DecodedEnvelope].map(_.data.dryRun) == Right(true),
        realJson.fromJson[DecodedEnvelope].map(_.data.dryRun) == Right(false)
      )
    },
    test("a repeated idempotency_key does not replay a response recorded under a different env") {
      val key = UUID.randomUUID().toString
      val before = rawCurrentDate()
      val mockJson =
        AdvanceDate.run(xa, CoreEnv.Mock, days = 3, idempotencyKey = Some(key), dryRun = false)
      val afterMock = rawCurrentDate()
      val sandboxJson =
        AdvanceDate.run(xa, CoreEnv.Sandbox, days = 3, idempotencyKey = Some(key), dryRun = false)
      val afterSandbox = rawCurrentDate()
      assertTrue(
        afterMock == before.plusDays(3),
        afterSandbox == afterMock.plusDays(3),
        mockJson.fromJson[DecodedEnvelope].map(_.env) == Right("mock"),
        sandboxJson.fromJson[DecodedEnvelope].map(_.env) == Right("sandbox")
      )
    },
    test("each call, including a replayed one, adds exactly one audit_log row") {
      val key = UUID.randomUUID().toString
      val beforeCount = auditCount("advance_date")
      AdvanceDate.run(xa, CoreEnv.Mock, days = 1, idempotencyKey = Some(key), dryRun = false)
      val afterFirstCall = auditCount("advance_date")
      AdvanceDate.run(xa, CoreEnv.Mock, days = 1, idempotencyKey = Some(key), dryRun = false)
      val afterSecondCall = auditCount("advance_date")
      assertTrue(afterFirstCall == beforeCount + 1, afterSecondCall == beforeCount + 2)
    }
  ) @@ sequential @@ timeout(1.minute)
