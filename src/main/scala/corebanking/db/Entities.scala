package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate

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
