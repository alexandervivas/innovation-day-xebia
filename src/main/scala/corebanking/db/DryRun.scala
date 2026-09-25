package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/**
 * Runs a write in one transaction, rolling it back when `dryRun` is true but still returning its
 * result.
 */
object DryRun:

  /**
   * `f` must write through its ambient `DbTx`, never open its own `transact` call, or a dry run
   * would commit on a second connection instead of rolling back.
   */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    transact(xa):
      val result = f
      if dryRun then summon[DbTx].connection.rollback()
      result
