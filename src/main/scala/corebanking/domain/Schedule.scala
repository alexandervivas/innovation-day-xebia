package corebanking.domain

import java.time.LocalDate

/**
 * One installment's principal/interest split, replayed from the standard declining-balance formula
 * over whatever `amount_due` rows the DB actually holds — not a stored column.
 */
final case class InstallmentBreakdown(
    seq: Int,
    dueDate: LocalDate,
    amountDue: BigDecimal,
    interest: BigDecimal,
    principal: BigDecimal
)

/** The repayment schedule of the single annuity consumer loan the mock supports. */
object Schedule:

  /** Monthly due dates, the first one month after disbursement. */
  def dueDates(terms: LoanTerms): List[LocalDate] =
    (1 to terms.termMonths).map(terms.disbursementDate.plusMonths(_)).toList

  /** Annuity payment on the monthly rate (annualRate / 12), rounded HALF_UP to cents. */
  def installmentAmount(terms: LoanTerms): BigDecimal =
    val monthlyRate = terms.annualRate.toDouble / 12.0
    val onePlusR = 1.0 + monthlyRate
    val factor = monthlyRate / (1.0 - math.pow(onePlusR, -terms.termMonths.toDouble))
    (terms.principal * BigDecimal(factor)).setScale(2, BigDecimal.RoundingMode.HALF_UP)

  /**
   * Replays each installment's interest/principal split against a declining balance, starting from
   * `principal` and stepping at `annualRate / 12` each period — the scheduled split, not a real
   * repayment's fees-then-interest-then-principal allocation (that's CB-08's job).
   */
  def amortizationBreakdown(
      principal: BigDecimal,
      annualRate: BigDecimal,
      installments: List[(Int, LocalDate, BigDecimal)]
  ): List[InstallmentBreakdown] =
    val monthlyRate = annualRate.toDouble / 12.0
    val sorted = installments.sortBy(_._1)
    val (_, breakdownReversed) =
      sorted.foldLeft((principal, List.empty[InstallmentBreakdown])) {
        case ((balance, acc), (seq, dueDate, amountDue)) =>
          val interest =
            (balance * BigDecimal(monthlyRate)).setScale(2, BigDecimal.RoundingMode.HALF_UP)
          val principalPortion = amountDue - interest
          val nextBalance = balance - principalPortion
          val step = InstallmentBreakdown(seq, dueDate, amountDue, interest, principalPortion)
          (nextBalance, step :: acc)
      }
    breakdownReversed.reverse
