package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/** Rollback signal that carries a dry run's result back out of its own transaction. */
final private case class DryRunSignal(value: Any) extends RuntimeException

object DryRun:

  /**
   * Runs `f` in one transaction: previews on `dryRun` (rolls everything back, still returns `f`'s
   * result) or commits normally otherwise, so callers build an identical response either way.
   *
   * A tool's `audit_log` insert must happen outside this block, in its own transaction, so a dry
   * run still gets logged. Do not nest calls to this method -- it can deadlock.
   */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    try
      transact(xa):
        val result = f
        if dryRun then throw DryRunSignal(result)
        result
    catch case DryRunSignal(value) => value.asInstanceOf[T]
