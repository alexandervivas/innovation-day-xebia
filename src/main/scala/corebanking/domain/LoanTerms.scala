package corebanking.domain

import java.time.LocalDate

/** Contract of the single 12-month annuity consumer loan the mock supports. */
final case class LoanTerms(
    principal: BigDecimal,
    annualRate: BigDecimal,
    termMonths: Int,
    disbursementDate: LocalDate,
    graceDays: Int,
    lateFee: BigDecimal
)

/**
 * A ledger fact the recalculation engine replays. Every case carries both dates of invariant 3:
 * `bookingDate` (when it was recorded) and `valueDate` (when it applies to the loan).
 */
enum UserEvent:
  case Disbursement(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)
  case Repayment(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)

object UserEvent:
  /** Reads the fields every case shares, so replay can sort and apply events without matching. */
  extension (event: UserEvent)
    def id: String = event match
      case Disbursement(id, _, _, _) => id
      case Repayment(id, _, _, _) => id
    def amount: BigDecimal = event match
      case Disbursement(_, amount, _, _) => amount
      case Repayment(_, amount, _, _) => amount
    def valueDate: LocalDate = event match
      case Disbursement(_, _, valueDate, _) => valueDate
      case Repayment(_, _, valueDate, _) => valueDate
    def bookingDate: LocalDate = event match
      case Disbursement(_, _, _, bookingDate) => bookingDate
      case Repayment(_, _, _, bookingDate) => bookingDate

    /** Rebuilds the event with some fields replaced, keeping its case. */
    def copy(
        id: String = event.id,
        amount: BigDecimal = event.amount,
        valueDate: LocalDate = event.valueDate,
        bookingDate: LocalDate = event.bookingDate
    ): UserEvent = event match
      case _: Disbursement => Disbursement(id, amount, valueDate, bookingDate)
      case _: Repayment => Repayment(id, amount, valueDate, bookingDate)

/** How one repayment was split, in the fixed fees -> interest -> principal order. */
final case class Allocation(fees: BigDecimal, interest: BigDecimal, principal: BigDecimal)

enum LoanStatus:
  case Current, InArrears

/** The loan's position after replaying events up to a given date. */
final case class LoanState(
    principalOutstanding: BigDecimal,
    interestAccruedUnpaid: BigDecimal,
    /** Unpaid late-fee balance, not a running total: a fee a repayment settles drops back out. */
    lateFeesCharged: BigDecimal,
    daysPastDue: Int,
    status: LoanStatus,
    allocations: Map[String, Allocation]
)
