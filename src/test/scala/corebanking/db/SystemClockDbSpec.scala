package corebanking.db

import java.time.LocalDate

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{DbCon, sql, transact}

import corebanking.config.DbConfig

/**
 * `system_clock` and its no-delete trigger come from CB-03's `V1__schema.sql`; this spec proves the
 * `LocalDate` codec, the transactor, and the dry-run wrapper this story adds on top of it all work
 * together against the real row. `FlywayRunner.migrate` runs eagerly (mirrors `Server.scala`'s own
 * startup order) so this spec works against a freshly created docker-compose Postgres with no
 * schema yet, not only one another spec already migrated.
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
   * Takes the ambient `DbCon`/`DbTx` rather than opening its own `transact` call, so it can run
   * inside a `DryRun` block without escaping it onto a second, independent connection — see the
   * ruling below.
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
