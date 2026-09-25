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
    },
    test("amortization breakdown replays declining balance across two installments") {
      val installments = List(
        (1, LocalDate.parse("2026-09-01"), BigDecimal("434.94")),
        (2, LocalDate.parse("2026-10-01"), BigDecimal("434.94"))
      )
      val breakdown =
        Schedule.amortizationBreakdown(terms.principal, terms.annualRate, installments)
      assertTrue(
        breakdown == List(
          InstallmentBreakdown(
            seq = 1,
            dueDate = LocalDate.parse("2026-09-01"),
            amountDue = BigDecimal("434.94"),
            interest = BigDecimal("33.33"),
            principal = BigDecimal("401.61")
          ),
          InstallmentBreakdown(
            seq = 2,
            dueDate = LocalDate.parse("2026-10-01"),
            amountDue = BigDecimal("434.94"),
            interest = BigDecimal("30.66"),
            principal = BigDecimal("404.28")
          )
        )
      )
    },
    test("amortization breakdown sorts installments by seq regardless of input order") {
      val inOrder = List(
        (1, LocalDate.parse("2026-09-01"), BigDecimal("434.94")),
        (2, LocalDate.parse("2026-10-01"), BigDecimal("434.94"))
      )
      val reversed = inOrder.reverse
      assertTrue(
        Schedule.amortizationBreakdown(terms.principal, terms.annualRate, reversed) ==
          Schedule.amortizationBreakdown(terms.principal, terms.annualRate, inOrder)
      )
    },
    test("amortization breakdown of an empty installment list is empty") {
      assertTrue(Schedule.amortizationBreakdown(terms.principal, terms.annualRate, Nil) == Nil)
    },
    test("amortization breakdown of the full 12-installment schedule leaves a small residual") {
      val installments = Schedule
        .dueDates(terms)
        .zipWithIndex
        .map((dueDate, i) => (i + 1, dueDate, BigDecimal("434.94")))
      val breakdown =
        Schedule.amortizationBreakdown(terms.principal, terms.annualRate, installments)
      assertTrue(breakdown.map(_.principal).sum == BigDecimal("4999.97"))
    }
  )
