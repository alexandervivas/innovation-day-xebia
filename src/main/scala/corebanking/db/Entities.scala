package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate

import com.augustnagro.magnum.*

/**
 * magnum/magnumpg 1.3.1 ship no `DbCodec[LocalDate]` (only `java.sql.Date`, `java.sql.Timestamp`,
 * and `OffsetDateTime` — see `DbCodec.scala`/`PgCodec.scala` in those artifacts), and
 * `current_date_value` is a SQL `DATE` column mapped to `LocalDate`. Modeled on magnum's own
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

/**
 * Read-only row shape for `SELECT current_date_value FROM system_clock WHERE id = true` — the table
 * is a permanent singleton row, never inserted or looked up by id, so no `@Id`.
 */
@SqlName("system_clock")
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SystemClockRow(currentDateValue: LocalDate) derives DbCodec
