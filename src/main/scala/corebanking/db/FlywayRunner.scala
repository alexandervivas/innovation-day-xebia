package corebanking.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

import corebanking.config.DbConfig

/**
 * Applies every pending migration under `db/migration` on the classpath. Synchronous and ZIO-free:
 * Flyway's own API is a single blocking JDBC call, so no runtime is needed to run it.
 */
object FlywayRunner:
  def migrate(config: DbConfig): MigrateResult =
    Flyway
      .configure()
      .dataSource(config.url, config.user, config.password)
      .load()
      .migrate()
