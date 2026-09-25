package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*

/**
 * Neither `magnum` nor `magnumpg` 1.3.1 ships a `DbCodec[LocalDate]` (only `java.sql.Date`,
 * `java.sql.Timestamp`, and `OffsetDateTime` are covered -- see `DbCodec.scala` and `PgCodec.scala`
 * in those artifacts). `booking_date`/`value_date`/`opened_on`/ `current_date_value` are all SQL
 * `DATE` columns mapped to `LocalDate` per this codebase's domain types, so this instance is
 * required for every entity below to derive `DbCodec`. Modeled on magnum's own
 * `OffsetDateTimeCodec`, using the JDBC 4.2 `getObject`/`setObject(LocalDate)` overloads the
 * PostgreSQL driver supports for `DATE`.
 */
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

/**
 * Read-only row shape for `SELECT current_date_value FROM system_clock WHERE id = true` -- never
 * inserted or looked up by id, so no `@Id`.
 */
@SqlName("system_clock")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec

/**
 * Read-only row shape for the `open_account` product lookup: existence plus `kind`, which the new
 * account row copies (`accounts.kind` mirrors its product's kind; there is no separate `kind`
 * parameter on `open_account`).
 */
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
