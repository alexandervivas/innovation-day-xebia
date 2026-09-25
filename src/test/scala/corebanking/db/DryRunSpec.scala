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
 * append-only `transactions` table.
 *
 * Every test that mutates the clock restores it through `.ensuring`, so the mandated system time
 * source (CLAUDE.md rule 4) is put back even when the assertions or the `DryRun` call itself fail.
 * A plain step in the `for`-comprehension would be skipped on the first failure and would leave
 * `system_clock` permanently bumped, which survives `make down` because `postgres_data` does.
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

  /** Restores the real clock unconditionally; a failure here is a defect, not a test failure. */
  private def restoreClock(date: LocalDate): UIO[Unit] =
    ZIO
      .attemptBlocking {
        transact(xa):
          sql"UPDATE system_clock SET current_date_value = $date WHERE id = true".update.run()
      }
      .unit
      .orDie

  /**
   * A real failure inside the block must reach the caller unchanged: `DryRun` catches only its own
   * rollback signal. If a refactor ever widened that `catch`, a genuine error would be silently
   * reported as a successful write, so this is asserted for both `dryRun` values.
   */
  private def errorPathTest(dryRun: Boolean) =
    test(
      s"a failure inside the block propagates unchanged and persists nothing (dryRun = $dryRun)"
    ) {
      val boom = new RuntimeException(s"boom inside the DryRun block (dryRun = $dryRun)")
      for
        before <- ZIO.attemptBlocking(currentDate())
        exit <- ZIO
          .attemptBlocking {
            DryRun(xa, dryRun = dryRun):
              sql"UPDATE system_clock SET current_date_value = ${before.plusDays(1)} WHERE id = true".update
                .run()
              throw boom
          }
          .exit
          .ensuring(restoreClock(before))
        after <- ZIO.attemptBlocking(currentDate())
        thrown = exit match
          case Exit.Failure(cause) => cause.failureOption
          case Exit.Success(_) => None
      yield assertTrue(thrown.contains(boom), after == before)
    }

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DryRun")(
      test("dryRun = false commits the write") {
        for
          _ <- ZIO.attemptBlocking(FlywayRunner.migrate(config))
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          assertion <- (
            for
              result <- ZIO.attemptBlocking {
                DryRun(xa, dryRun = false):
                  sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update
                    .run()
                  bumped
              }
              after <- ZIO.attemptBlocking(currentDate())
            yield assertTrue(result == bumped, after == bumped)
          ).ensuring(restoreClock(before))
        yield assertion
      },
      test("dryRun = true returns the computed result but leaves the row unchanged") {
        for
          before <- ZIO.attemptBlocking(currentDate())
          bumped = before.plusDays(1)
          assertion <- (
            for
              result <- ZIO.attemptBlocking {
                DryRun(xa, dryRun = true):
                  sql"UPDATE system_clock SET current_date_value = $bumped WHERE id = true".update
                    .run()
                  bumped
              }
              after <- ZIO.attemptBlocking(currentDate())
            yield assertTrue(result == bumped, after == before)
          ).ensuring(restoreClock(before))
        yield assertion
      },
      errorPathTest(dryRun = false),
      errorPathTest(dryRun = true)
    ) @@ sequential @@ timeout(1.minute)
