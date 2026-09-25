package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/**
 * Internal signal used to force `transact` to roll back a dry run while still returning its
 * computed result. magnum's `transact` (see its `util.scala`) always commits on success and rolls
 * back only when the block throws, with no other rollback hook -- so a dry run has to look like a
 * failure to the transaction manager. `transact` rethrows whatever the block threw unchanged (it
 * doesn't wrap it), so catching this exact type immediately outside `transact` recovers the value;
 * the `Any` payload plus a single confined cast avoids a generic-erasure pattern match on
 * `DryRunSignal[T]`, which this codebase's `-Werror` would likely flag.
 */
final private case class DryRunSignal(value: Any) extends RuntimeException

object DryRun:

  /**
   * Runs `f` inside one transaction. When `dryRun` is false, commits normally and returns `f`'s
   * result. When `dryRun` is true, everything `f` did is rolled back -- nothing persists -- but the
   * same result is still returned, so callers can build an identical response either way.
   */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    try
      transact(xa):
        val result = f
        if dryRun then throw DryRunSignal(result)
        result
    catch case DryRunSignal(value) => value.asInstanceOf[T]
