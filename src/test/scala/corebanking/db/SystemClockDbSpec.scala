package corebanking.db

import java.time.LocalDate

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{DbCon, sql, transact}

import corebanking.config.DbConfig

/**
 * Proves the LocalDate codec, transactor, and dry-run wrapper work together against the real
 * `system_clock` row.
 */
object SystemClockDbSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  FlywayRunner.migrate(config)
  private val xa = Db.transactor(config)

  private def readCurrentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  /**
   * Takes the ambient `DbCon` so it can run inside a `DryRun` block without opening a second
   * connection.
   */
  private def setCurrentDate(date: LocalDate)(using DbCon): Unit =
    sql"UPDATE system_clock SET current_date_value = $date WHERE id = true".update.run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("system_clock DB access (CB-12)")(
    test("reads the current_date_value row through the LocalDate codec") {
      ZIO.attempt(readCurrentDate()).map(date => assertTrue(date.isInstanceOf[LocalDate]))
    },
    test("DryRun rolls back a write but still returns its computed result") {
      ZIO
        .attempt {
          val before = readCurrentDate()
          val target = before.plusDays(5)
          val result = DryRun(xa, dryRun = true):
            setCurrentDate(target)
            target
          val after = readCurrentDate()
          (result, target, before, after)
        }
        .map {
          case (result, target, before, after) =>
            assertTrue(result == target, after == before)
        }
    },
    test("DryRun commits a write when dryRun=false") {
      ZIO
        .attempt {
          val before = readCurrentDate()
          val target = before.plusDays(1)
          DryRun(xa, dryRun = false):
            setCurrentDate(target)
          val after = readCurrentDate()
          transact(xa):
            setCurrentDate(before)
          (target, after, before)
        }
        .map {
          case (target, after, before) =>
            assertTrue(after == target, after != before)
        }
    }
  ) @@ sequential @@ timeout(1.minute)
