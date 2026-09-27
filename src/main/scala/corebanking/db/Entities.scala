package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*

/** Maps `current_date_value`'s SQL `DATE` column to `LocalDate` (magnum ships no such codec). */
given localDateCodec: DbCodec[LocalDate] with
  val cols: IArray[Int] = IArray(Types.DATE)
  def readSingle(rs: ResultSet, pos: Int): LocalDate =
    rs.getObject(pos, classOf[LocalDate])
  def writeSingle(date: LocalDate, ps: PreparedStatement, pos: Int): Unit =
    ps.setObject(pos, date)
  def queryRepr: String = "?"

/** The ledger's system date — a permanent singleton row, never inserted or looked up by id. */
@SqlName("system_clock")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec

/** A person the bank holds accounts for. */
@SqlName("clients")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Client(
    @Id id: UUID,
    displayName: String,
    openedOn: LocalDate,
    email: Option[String],
    idempotencyKey: Option[String]
) derives DbCodec

/** One client's holding of one product, in a single currency. */
@SqlName("accounts")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
    @Id id: UUID,
    clientId: UUID,
    productId: UUID,
    kind: String,
    openedOn: LocalDate,
    currency: String
) derives DbCodec

/** An append-only ledger entry, booked on one date and effective on another. */
@SqlName("transactions")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Transaction(
    @Id id: UUID,
    accountId: UUID,
    `type`: String,
    amount: BigDecimal,
    bookingDate: LocalDate,
    valueDate: LocalDate,
    reversesId: Option[UUID],
    idempotencyKey: String
) derives DbCodec

/** Product lookup for `open_account`: existence plus the `kind` the new account inherits. */
@SqlName("products")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ProductRef(@Id id: UUID, kind: String) derives DbCodec
