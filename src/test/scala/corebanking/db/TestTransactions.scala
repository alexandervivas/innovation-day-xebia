package corebanking.db

import com.augustnagro.magnum.*

/**
 * Runs `f` inside a transaction that always rolls back, returning whatever `f` returned. Lets a
 * spec seed fixture rows and query them in the same transaction without leaving anything behind —
 * `SchemaMigrationSpec`'s savepoint-per-assertion pattern does the same thing with raw JDBC; this
 * is the equivalent for magnum-based reads.
 */
object TestTransactions:

  final private case class RollbackWithResult[A](value: A) extends RuntimeException

  def rollingBack[A](xa: Transactor)(f: DbTx ?=> A): A =
    try
      transact(xa) { (tx: DbTx) ?=>
        throw RollbackWithResult(f(using tx))
      }
      throw new IllegalStateException("unreachable: transact always rethrows after rollback")
    catch case RollbackWithResult(value) => value.asInstanceOf[A]
