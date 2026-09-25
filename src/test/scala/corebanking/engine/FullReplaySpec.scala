package corebanking.engine

import corebanking.domain.*
import zio.test.*

import java.time.LocalDate

/**
 * Scenarios the binding acceptance fixture (`RecalculationSpec`, copied verbatim from
 * `docs/handoff/`) does not cover. That file stays byte-equivalent to its oracle, so extra cases
 * live here.
 */
object FullReplaySpec extends ZIOSpecDefault:

  private def d(s: String) = LocalDate.parse(s)
  private def eur(s: String) = BigDecimal(s)

  private val terms = LoanTerms(
    principal = eur("5000.00"),
    annualRate = eur("0.08"),
    termMonths = 12,
    disbursementDate = d("2026-08-01"),
    graceDays = 7,
    lateFee = eur("15.00")
  )

  private val systemDate = d("2026-11-14")

  private val history = List(
    UserEvent.Disbursement("TX-1001", eur("5000.00"), d("2026-08-01"), d("2026-08-01")),
    UserEvent.Repayment("TX-1002", eur("434.94"), d("2026-09-01"), d("2026-09-01")),
    UserEvent.Repayment("TX-1004", eur("434.94"), d("2026-11-01"), d("2026-11-01"))
  )

  // Value date after every existing event, so nothing is reversed and reposted, but still before
  // the system date, so the recompute can still drop a late fee charged in between.
  private val afterAllHistory =
    UserEvent.Repayment("TX-1020", eur("500.00"), d("2026-11-05"), systemDate)

  /**
   * One day of interest on 1000.00 at 8% actual/365 is 0.2191780821..., which rounds HALF_UP to
   * 0.22. A repayment of 0.2195 therefore sits *above* the unrounded interest but *below* the
   * rounded interest: the allocation pays all the interest it can, and what is nominally left for
   * principal is 0.2195 - 0.22 = -0.0005. Without a clamp that negative amount is subtracted from
   * the principal, i.e. it *grows* the outstanding balance. Twelve such payments move it a full
   * 0.006, enough to survive the HALF_UP rounding of the reported figure.
   */
  private val boundaryTerms = LoanTerms(
    principal = BigDecimal("1000.00"),
    annualRate = BigDecimal("0.08"),
    termMonths = 12,
    disbursementDate = d("2026-01-01"),
    graceDays = 7,
    lateFee = BigDecimal("15.00")
  )

  private val boundaryPayment = BigDecimal("0.2195")

  private val boundaryEvents =
    UserEvent.Disbursement("TX-D", BigDecimal("1000.00"), d("2026-01-01"), d("2026-01-01")) ::
      (1 to 12).toList.map { n =>
        val day = d("2026-01-01").plusDays(n.toLong)
        UserEvent.Repayment(s"TX-R$n", boundaryPayment, day, day)
      }

  private val boundaryAsOf = d("2026-01-13")

  def spec = suite("FullReplay — chain beyond the acceptance fixture")(
    test("a late fee the recompute drops is reversed even when no user event is reposted") {
      val r = FullReplay.backdate(terms, history, afterAllHistory, systemDate, Nil).toOption.get
      assertTrue(
        r.before.lateFeesCharged == eur("15.00"),
        r.after.lateFeesCharged == eur("0.00"),
        r.chain == List(
          ChainStep.Reverse("LATE-FEE:installment-3"),
          ChainStep.Post("TX-1020")
        )
      )
    }
  ) + suite("FullReplay — rounding boundaries")(
    test("a repayment below the rounded interest never allocates negative principal") {
      val s = FullReplay.stateAt(boundaryTerms, boundaryEvents, boundaryAsOf)
      val principalParts = (1 to 12).map(n => s.allocations(s"TX-R$n").principal)
      assertTrue(principalParts.forall(_ >= BigDecimal(0)))
    },
    test("such repayments never grow the outstanding principal") {
      val s = FullReplay.stateAt(boundaryTerms, boundaryEvents, boundaryAsOf)
      assertTrue(s.principalOutstanding == BigDecimal("1000.00"))
    },
    test("the loan is still current and no installment is due yet") {
      val s = FullReplay.stateAt(boundaryTerms, boundaryEvents, boundaryAsOf)
      assertTrue(s.daysPastDue == 0, s.status == LoanStatus.Current)
    }
  )
