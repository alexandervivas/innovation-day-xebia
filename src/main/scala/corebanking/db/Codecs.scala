package corebanking.db

import java.sql.{PreparedStatement, ResultSet, Types}
import java.time.LocalDate

import com.augustnagro.magnum.DbCodec

/**
 * Maps SQL `DATE` columns (booking date, value date, opened-on, due date, system clock) to
 * `LocalDate`.
 */
given localDateCodec: DbCodec[LocalDate] with
  val cols: IArray[Int] = IArray(Types.DATE)
  def readSingle(rs: ResultSet, pos: Int): LocalDate = rs.getObject(pos, classOf[LocalDate])
  def writeSingle(date: LocalDate, ps: PreparedStatement, pos: Int): Unit = ps.setObject(pos, date)
  def queryRepr: String = "?"
