package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** One ledger entry: both dates are always present so a caller can see booking vs. value date. */
final case class TransactionData(
    id: String,
    `type`: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[String]
)

object TransactionData:
  given JsonEncoder[TransactionData] = DeriveJsonEncoder.gen[TransactionData]

final private case class TransactionRow(
    id: UUID,
    txType: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[UUID]
) derives DbCodec

object GetTransactions:

  /**
   * Lists an account's transactions, optionally filtered by an inclusive `value_date` range. Throws
   * `NoSuchElementException` when no account has `accountId`.
   */
  def find(
      accountId: UUID,
      startDate: Option[LocalDate],
      endDate: Option[LocalDate]
  )(using DbCon): List[TransactionData] =
    val accountExists = sql"SELECT 1 FROM accounts WHERE id = $accountId".query[Int].run()
    if accountExists.isEmpty then throw new NoSuchElementException(s"no account with id $accountId")
    sql"""
      SELECT id, type, amount, booking_date, value_date, reverses_id
      FROM transactions
      WHERE account_id = $accountId
        AND ($startDate IS NULL OR value_date >= $startDate)
        AND ($endDate IS NULL OR value_date <= $endDate)
      ORDER BY value_date, booking_date, id
    """.query[TransactionRow].run().toList.map { r =>
      TransactionData(
        r.id.toString,
        r.txType,
        r.amount,
        r.bookingDate,
        r.valueDate,
        r.reversesId.map(_.toString)
      )
    }

  def response(
      env: CoreEnv,
      accountId: UUID,
      startDate: Option[LocalDate],
      endDate: Option[LocalDate]
  )(using DbCon): String =
    ToolResponse.respond(env, find(accountId, startDate, endDate))
