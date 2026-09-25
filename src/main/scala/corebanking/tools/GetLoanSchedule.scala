package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given
import corebanking.domain.Schedule

/**
 * One scheduled installment on a loan: when it falls due, what is owed, and how much of that is
 * interest versus principal.
 */
final case class LoanScheduleData(
    loanId: String,
    seq: Int,
    dueDate: LocalDate,
    amountDue: BigDecimal,
    interest: BigDecimal,
    principal: BigDecimal
)

object LoanScheduleData:
  given JsonEncoder[LoanScheduleData] = DeriveJsonEncoder.gen[LoanScheduleData]

final private case class LoanRow(principal: BigDecimal, annualRate: BigDecimal) derives DbCodec
final private case class InstallmentRow(seq: Int, dueDate: LocalDate, amountDue: BigDecimal)
    derives DbCodec

object GetLoanSchedule:

  /**
   * The interest/principal split isn't stored — it's computed from the loan's own declining
   * balance. An account with no loan is "not found"; a loan with no installments yet returns an
   * empty schedule.
   */
  def find(loanId: UUID)(using DbCon): List[LoanScheduleData] =
    val loan = sql"SELECT principal, annual_rate FROM loans WHERE account_id = $loanId"
      .query[LoanRow]
      .run()
      .headOption
      .getOrElse(throw new NoSuchElementException(s"no loan with id $loanId"))
    val installments =
      sql"SELECT seq, due_date, amount_due FROM installments WHERE account_id = $loanId ORDER BY seq"
        .query[InstallmentRow]
        .run()
        .toList
        .map(r => (r.seq, r.dueDate, r.amountDue))
    Schedule
      .amortizationBreakdown(loan.principal, loan.annualRate, installments)
      .map(b =>
        LoanScheduleData(loanId.toString, b.seq, b.dueDate, b.amountDue, b.interest, b.principal)
      )

  def response(env: CoreEnv, loanId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(loanId))
