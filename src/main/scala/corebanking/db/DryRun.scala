package corebanking.db

import com.augustnagro.magnum.{DbTx, Transactor, transact}

/**
 * Internal signal used to force `transact` to roll back a dry run while still returning its
 * computed result. magnum's `transact` always commits on success and rolls back only when the block
 * throws, so a dry run has to look like a failure to the transaction manager; catching this exact
 * type immediately outside `transact` recovers the value. Package-private to `corebanking.db`, so
 * nothing outside this package can construct or match it.
 */
final private case class DryRunSignal(value: Any) extends RuntimeException

object DryRun:

  /**
   * Runs `f` inside one transaction. When `dryRun` is false, commits normally and returns `f`'s
   * result. When `dryRun` is true, everything `f` did is rolled back — nothing persists — but the
   * same result is still returned.
   *
   * A failure raised by `f` itself is never swallowed: only the internal rollback signal is caught,
   * so any other exception propagates unchanged and its transaction is rolled back.
   *
   * The audit trail is written OUTSIDE the block this wraps, in its own `transact` call (see
   * `AuditLog.record`), so it is committed regardless of `dryRun` — CLAUDE.md rule 6 requires every
   * tool call logged, including a dry run.
   *
   * `f` must read and write through the ambient `DbTx`/`DbCon` it is given, never by opening its
   * own `transact(xa)` call: magnum opens a brand-new physical connection per `transact`, so a
   * nested call inside `f` would commit on its own connection immediately, escaping this rollback
   * entirely.
   */
  def apply[T](xa: Transactor, dryRun: Boolean)(f: DbTx ?=> T): T =
    try
      transact(xa):
        val result = f
        if dryRun then throw DryRunSignal(result)
        result
    catch case DryRunSignal(value) => value.asInstanceOf[T]
