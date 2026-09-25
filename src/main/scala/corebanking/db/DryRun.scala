package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/**
 * Internal signal used to force `transact` to roll back a dry run while still returning its
 * computed result. magnum's `transact` (see its `util.scala`) always commits on success and rolls
 * back only when the block throws, with no other rollback hook -- so a dry run has to look like a
 * failure to the transaction manager. `transact` rethrows whatever the block threw unchanged (it
 * doesn't wrap it), so catching this exact type immediately outside `transact` recovers the value;
 * the `Any` payload plus a single confined cast avoids a generic-erasure pattern match on
 * `DryRunSignal[T]`, which this codebase's `-Werror` would likely flag. Package-private to
 * `corebanking.db`, so nothing outside this package can construct or match it.
 */
final private case class DryRunSignal(value: Any) extends RuntimeException

object DryRun:

  /**
   * Runs `f` inside one transaction. When `dryRun` is false, commits normally and returns `f`'s
   * result. When `dryRun` is true, everything `f` did is rolled back -- nothing persists -- but the
   * same result is still returned, so callers can build an identical response either way.
   *
   * A failure raised by `f` itself is never swallowed: only the internal rollback signal is caught,
   * so any other exception propagates unchanged and its transaction is rolled back.
   *
   * Ordering contract with the audit trail: a tool's `audit_log` insert must happen OUTSIDE the
   * block this wraps, in its own `transact` call (see `AuditLog.record`), so it is committed
   * regardless of `dryRun`. Putting it inside would make a dry run roll back its own audit trail
   * and violate CLAUDE.md rule 6, which requires every tool call to be logged.
   *
   * Calls must not be nested: `PGSimpleDataSource` does no pooling, so a nested call takes a second
   * physical connection and can deadlock against the row locks the outer transaction still holds.
   */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    try
      transact(xa):
        val result = f
        if dryRun then throw DryRunSignal(result)
        result
    catch case DryRunSignal(value) => value.asInstanceOf[T]
