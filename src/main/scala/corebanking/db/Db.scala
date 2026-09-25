package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/** Builds the magnum `Transactor` every write tool shares. `PGSimpleDataSource` (shipped inside
  * the `org.postgresql:postgresql` dependency already on the classpath) opens one raw connection
  * per `getConnection()` call with no pooling -- correct and sufficient for this single-writer
  * mock server; a connection pool (e.g. HikariCP) is a separate dependency to add only if this
  * ever needs one.
  */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
