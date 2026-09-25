package corebanking.domain

import java.time.LocalDate

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
