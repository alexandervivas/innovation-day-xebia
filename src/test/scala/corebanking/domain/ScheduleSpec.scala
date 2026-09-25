package corebanking.domain

import zio.Scope
import zio.test.*

import java.time.LocalDate

object ScheduleSpec extends ZIOSpecDefault:

  val terms = LoanTerms(
    principal = BigDecimal("5000.00"),
    annualRate = BigDecimal("0.08"),
    termMonths = 12,
    disbursementDate = LocalDate.parse("2026-08-01"),
    graceDays = 7,
    lateFee = BigDecimal("15.00")
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Schedule")(
    test("installment amount is the annuity payment rounded to cents") {
      assertTrue(Schedule.installmentAmount(terms) == BigDecimal("434.94"))
    },
    test("due dates are 12 monthly dates starting one month after disbursement") {
      val dates = Schedule.dueDates(terms)
      assertTrue(
        dates.size == 12,
        dates.head == LocalDate.parse("2026-09-01"),
        dates(1) == LocalDate.parse("2026-10-01"),
        dates(2) == LocalDate.parse("2026-11-01")
      )
    }
  )
