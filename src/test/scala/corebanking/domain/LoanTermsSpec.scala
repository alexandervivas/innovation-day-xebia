package corebanking.domain

import zio.Scope
import zio.test.*

import java.time.LocalDate

object LoanTermsSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("domain types")(
    test("UserEvent extensions read id, amount, valueDate for both cases") {
      // Ascribed as UserEvent on purpose: on the concrete case types these would resolve to the
      // generated case fields, and the extension methods under test would never run.
      val disb: UserEvent =
        UserEvent.Disbursement(
          "TX-1001",
          BigDecimal("5000.00"),
          LocalDate.parse("2026-08-01"),
          LocalDate.parse("2026-08-01")
        )
      val repay: UserEvent =
        UserEvent.Repayment(
          "TX-1002",
          BigDecimal("434.94"),
          LocalDate.parse("2026-09-01"),
          LocalDate.parse("2026-09-01")
        )

      assertTrue(
        disb.id == "TX-1001",
        disb.amount == BigDecimal("5000.00"),
        disb.valueDate == LocalDate.parse("2026-08-01"),
        repay.id == "TX-1002",
        repay.amount == BigDecimal("434.94"),
        repay.valueDate == LocalDate.parse("2026-09-01")
      )
    },
    test("LoanState and Allocation hold the fields the engine needs") {
      val state = LoanState(
        principalOutstanding = BigDecimal("4240.58"),
        interestAccruedUnpaid = BigDecimal("12.08"),
        lateFeesCharged = BigDecimal("15.00"),
        daysPastDue = 44,
        status = LoanStatus.InArrears,
        allocations = Map(
          "TX-1004" -> Allocation(BigDecimal("15.00"), BigDecimal("61.49"), BigDecimal("358.45"))
        )
      )

      assertTrue(
        state.status == LoanStatus.InArrears,
        state.allocations("TX-1004").principal == BigDecimal("358.45")
      )
    },
    test("LoanTerms holds the schedule inputs the engine needs") {
      val terms = LoanTerms(
        principal = BigDecimal("5000.00"),
        annualRate = BigDecimal("0.18"),
        termMonths = 12,
        disbursementDate = LocalDate.parse("2026-08-01"),
        graceDays = 5,
        lateFee = BigDecimal("15.00")
      )

      assertTrue(
        terms.principal == BigDecimal("5000.00"),
        terms.annualRate == BigDecimal("0.18"),
        terms.termMonths == 12,
        terms.disbursementDate == LocalDate.parse("2026-08-01"),
        terms.graceDays == 5,
        terms.lateFee == BigDecimal("15.00")
      )
    }
  )
