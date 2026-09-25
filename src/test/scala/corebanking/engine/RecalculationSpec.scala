package corebanking.engine

import zio.test.*
import java.time.LocalDate
import corebanking.domain.*

/**
 * Spec-first test for CB-15a (RecalculationStrategy + FullReplay).
 *
 * Written BEFORE the engine exists: it defines the contract the implementation must satisfy. All
 * expected figures were verified independently with decimal arithmetic.
 *
 * Conventions the engine must follow:
 *   - Installment = annuity on monthly rate (annualRate / 12), rounded HALF_UP to cents.
 *   - Interest accrues daily, actual/365, on outstanding principal only, kept at full precision and
 *     rounded HALF_UP to cents when allocated or reported.
 *   - Repayment allocation: fees -> interest -> principal.
 *   - Late fee: charged once per installment, graceDays after its due date if still short.
 *   - Days past due: counted from the due date of the oldest installment not fully covered by
 *     repayment amounts allocated to interest + principal.
 *   - Only USER events go in; the engine derives accruals, late fees and status.
 *
 * Assumed domain API (to be created in corebanking.domain / corebanking.engine): final case class
 * LoanTerms(principal, annualRate, termMonths, disbursementDate, graceDays, lateFee) enum
 * UserEvent: Disbursement(id, amount, valueDate, bookingDate) Repayment(id, amount, valueDate,
 * bookingDate) final case class Allocation(fees, interest, principal) enum LoanStatus: Current,
 * InArrears final case class LoanState(principalOutstanding, interestAccruedUnpaid,
 * lateFeesCharged, daysPastDue, status, allocations: Map[String, Allocation]) enum ChainStep:
 * Reverse(targetId), Post(txId), Repost(originalId) final case class RecalcResult(before:
 * LoanState, after: LoanState, chain: List[ChainStep]) enum RecalcError:
 * ValueDateBeforeDisbursement, PeriodClosed(periodStart), ... trait RecalculationStrategy: def
 * stateAt(terms, events, asOf): LoanState def backdate(terms, events, newTx, systemDate,
 * closedPeriods): Either[RecalcError, RecalcResult] object FullReplay extends RecalculationStrategy
 */
object RecalculationSpec extends ZIOSpecDefault:

  private def d(s: String) = LocalDate.parse(s)
  private def eur(s: String) = BigDecimal(s)

  val terms = LoanTerms(
    principal = eur("5000.00"),
    annualRate = eur("0.08"),
    termMonths = 12,
    disbursementDate = d("2026-08-01"),
    graceDays = 7,
    lateFee = eur("15.00")
  )

  val systemDate = d("2026-11-14")

  val history = List(
    UserEvent.Disbursement("TX-1001", eur("5000.00"), d("2026-08-01"), d("2026-08-01")),
    UserEvent.Repayment("TX-1002", eur("434.94"), d("2026-09-01"), d("2026-09-01")),
    UserEvent.Repayment("TX-1004", eur("434.94"), d("2026-11-01"), d("2026-11-01"))
  )

  val backdated = UserEvent.Repayment("TX-1010", eur("434.94"), d("2026-09-30"), systemDate)

  def spec = suite("FullReplay — LN-0042 backdated repayment")(
    test("installment amount is 434.94") {
      assertTrue(Schedule.installmentAmount(terms) == eur("434.94"))
    },

    test("state before backdating: fee-first allocation leaves October short, 44 DPD") {
      val s = FullReplay.stateAt(terms, history, systemDate)
      assertTrue(
        s.principalOutstanding == eur("4240.58"),
        s.interestAccruedUnpaid == eur("12.08"),
        s.lateFeesCharged == eur("15.00"),
        s.daysPastDue == 44,
        s.status == LoanStatus.InArrears,
        s.allocations("TX-1004") == Allocation(eur("15.00"), eur("61.49"), eur("358.45"))
      )
    },

    test("state after backdating: loan is current, fee reversed, principal lower") {
      val result = FullReplay.backdate(terms, history, backdated, systemDate, closedPeriods = Nil)
      assertTrue(result.isRight) &&
      assertTrue {
        val a = result.toOption.get.after
        a.principalOutstanding == eur("3787.79") &&
        a.interestAccruedUnpaid == eur("10.79") &&
        a.lateFeesCharged == eur("0.00") &&
        a.daysPastDue == 0 &&
        a.status == LoanStatus.Current
      }
    },

    test("the 1 November repayment is reapplied with a new allocation") {
      val after = FullReplay.backdate(terms, history, backdated, systemDate, Nil).toOption.get.after
      assertTrue(
        after.allocations("TX-1010") == Allocation(eur("0.00"), eur("29.23"), eur("405.71")),
        after.allocations("TX-1004") == Allocation(eur("0.00"), eur("29.41"), eur("405.53"))
      )
    },

    test("principal delta = backdated payment + reversed fee + interest saved") {
      val r = FullReplay.backdate(terms, history, backdated, systemDate, Nil).toOption.get
      val delta = r.after.principalOutstanding - r.before.principalOutstanding
      assertTrue(delta == eur("-452.79"), delta == -(eur("434.94") + eur("15.00") + eur("2.85")))
    },

    test("reversal/repost chain: newest reversed first, then post, then repost") {
      val chain = FullReplay.backdate(terms, history, backdated, systemDate, Nil).toOption.get.chain
      assertTrue(
        chain == List(
          ChainStep.Reverse("TX-1004"),
          ChainStep.Reverse("LATE-FEE:installment-2"),
          ChainStep.Post("TX-1010"),
          ChainStep.Repost("TX-1004")
        )
      )
    },

    test("rejects backdating into a closed accounting period") {
      val result = FullReplay
        .backdate(terms, history, backdated, systemDate, closedPeriods = List(d("2026-09-01")))
      assertTrue(result == Left(RecalcError.PeriodClosed(d("2026-09-01"))))
    },

    test("rejects a value date before disbursement") {
      val tooEarly = backdated.copy(valueDate = d("2026-07-31"))
      val result = FullReplay.backdate(terms, history, tooEarly, systemDate, Nil)
      assertTrue(result == Left(RecalcError.ValueDateBeforeDisbursement))
    }
  )
