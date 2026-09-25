package corebanking.db

import org.postgresql.ds.PGSimpleDataSource

import com.augustnagro.magnum.Transactor

import corebanking.config.DbConfig

/** Builds the database connection every write tool uses. */
object Db:
  def transactor(config: DbConfig): Transactor =
    val dataSource = new PGSimpleDataSource()
    dataSource.setURL(config.url)
    dataSource.setUser(config.user)
    dataSource.setPassword(config.password)
    Transactor(dataSource)
