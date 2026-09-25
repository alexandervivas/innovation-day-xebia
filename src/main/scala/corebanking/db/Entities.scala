package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*

/** Maps SQL `DATE` columns (booking date, value date, opened-on, system clock) to `LocalDate`. */
given localDateCodec: DbCodec[LocalDate] with
  val cols: IArray[Int] = IArray(Types.DATE)
  def readSingle(rs: ResultSet, pos: Int): LocalDate =
    rs.getObject(pos, classOf[LocalDate])
  def writeSingle(date: LocalDate, ps: PreparedStatement, pos: Int): Unit =
    ps.setObject(pos, date)
  def queryRepr: String = "?"

@SqlName("clients")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Client(
    @Id id: UUID,
    displayName: String,
    openedOn: LocalDate,
    email: Option[String],
    idempotencyKey: Option[String]
) derives DbCodec

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

/** The bank's current system date; never inserted or looked up by id. */
@SqlName("system_clock")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec

/** Product lookup for `open_account`: existence plus the `kind` the new account inherits. */
@SqlName("products")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ProductRef(@Id id: UUID, kind: String) derives DbCodec

/** Read-only row shape for verifying an `audit_log` entry's columns in tests. */
@SqlName("audit_log")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class AuditLogRow(
    @Id id: Long,
    toolName: String,
    env: String,
    request: String,
    response: String
) derives DbCodec
