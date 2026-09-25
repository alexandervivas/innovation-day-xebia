package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/**
 * Builds the magnum `Transactor` every tool shares. `PGSimpleDataSource` (already on the classpath
 * via `org.postgresql:postgresql`) opens one raw connection per `getConnection()` call with no
 * pooling — correct and sufficient for this single-writer mock server.
 */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
