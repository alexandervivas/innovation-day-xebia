package corebanking.db

import java.time.LocalDate

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import com.augustnagro.magnum.{sql, transact}

import corebanking.config.DbConfig

/**
 * Proves the `dry_run` write-safety contract (CLAUDE.md rule 5) against the real docker-compose
 * Postgres. Uses the harmless, already-seeded `system_clock` singleton row rather than the
 * append-only `transactions` table, and restores the clock afterwards so later specs still see the
 * real date.
 */
object DryRunSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private def currentDate(): LocalDate =
    transact(xa):
      sql"SELECT current_date_value FROM system_clock WHERE id = true"
        .query[SystemClockRow]
        .run()
        .head
        .currentDateValue

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DryRun")(
      test("dryRun = false commits the write") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          result <- ZIO.attemptBlocking {
            DryRun(xa, dryRun = false):
              sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update.run()
              bumped
          }
          after <- ZIO.attemptBlocking(currentDate())
          // Restore the real clock so later specs (which assume "today") aren't left bumped.
          _ <- ZIO.attemptBlocking {
            transact(xa):
              sql"UPDATE system_clock SET current_date_value = $before WHERE id = true".update.run()
          }
        yield assertTrue(result == bumped, after == bumped)
      },
      test("dryRun = true returns the computed result but leaves the row unchanged") {
        for
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          result <- ZIO.attemptBlocking {
            DryRun(xa, dryRun = true):
              sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update.run()
              bumped
          }
          after <- ZIO.attemptBlocking(currentDate())
        yield assertTrue(result == bumped, after == before)
      }
    ) @@ sequential @@ timeout(1.minute)
