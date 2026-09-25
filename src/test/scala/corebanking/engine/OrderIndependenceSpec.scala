package corebanking.engine

import zio.test.*
import java.time.LocalDate
import corebanking.domain.*

object OrderIndependenceSpec extends ZIOSpecDefault:

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

  private val baseHistory = List(
    UserEvent.Disbursement("TX-2001", eur("5000.00"), d("2026-08-01"), d("2026-08-01"))
  )

  // Day 3, Day 5, Day 7 after disbursement.
  private val txDay3 = UserEvent.Repayment("TX-2003", eur("100.00"), d("2026-08-04"), systemDate)
  private val txDay5 = UserEvent.Repayment("TX-2005", eur("100.00"), d("2026-08-06"), systemDate)
  private val txDay7 = UserEvent.Repayment("TX-2007", eur("100.00"), d("2026-08-08"), systemDate)

  private val threeBackdatedTxns = List(txDay5, txDay3, txDay7)

  /** The position the engine reports once all the repayments are posted in `order`. */
  private def postInOrder(order: List[UserEvent]): LoanState =
    val (_, finalState) = order.foldLeft((baseHistory, Option.empty[LoanState])) {
      case ((events, _), tx) =>
        val result = FullReplay.backdate(terms, events, tx, systemDate, closedPeriods = Nil)
        Predef.assert(result.isRight, s"backdate rejected $tx after $events: $result")
        (events :+ tx, result.toOption.map(_.after))
    }
    finalState.get

  private val referenceState =
    FullReplay.stateAt(terms, baseHistory ++ threeBackdatedTxns, systemDate)

  def spec = suite("FullReplay — order independence (CB-20)")(
    test("fixture sanity: the three repayments actually reduce principal") {
      assertTrue(
        referenceState.principalOutstanding < terms.principal,
        referenceState.allocations.keySet == Set("TX-2003", "TX-2005", "TX-2007")
      )
    },
    test("final loan state is identical for every posting order of the 3 backdated repayments") {
      checkAll(Gen.fromIterable(threeBackdatedTxns.permutations.toList)) { order =>
        assertTrue(postInOrder(order) == referenceState)
      }
    }
  )
