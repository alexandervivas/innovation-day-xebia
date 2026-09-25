package corebanking.db

import zio.*
import zio.test.*

import com.augustnagro.magnum.{Spec as _, *}

import corebanking.config.DbConfig

/**
 * Proves the shared `Transactor` and the `LocalDate` codec work end-to-end against the real
 * docker-compose Postgres (must already be up: `docker compose up -d`).
 */
object DbSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Db.transactor + localDateCodec")(
    test("connects and reads system_clock.current_date_value as a LocalDate") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            val xa = Db.transactor(config)
            connect(xa) {
              sql"SELECT current_date_value FROM system_clock".query[java.time.LocalDate].run()
            }
          }
          .map(rows => assertTrue(rows.size == 1))
    }
  ) @@ TestAspect.timeout(30.seconds)
