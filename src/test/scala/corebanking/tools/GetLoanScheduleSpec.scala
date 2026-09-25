package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.{Spec as _, *}
import zio.*
import zio.test.*

import corebanking.config.DbConfig
import corebanking.db.{Db, FlywayRunner}
import corebanking.db.TestTransactions.rollingBack

object GetLoanScheduleSpec extends ZIOSpecDefault:

  private val config = DbConfig.fromEnv()
  private val xa = Db.transactor(config)

  private val LoanProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d1")
  private val SavingsProductId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d2")
  private val ClientId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d3")
  private val LoanAccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d4")
  private val SavingsAccountId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d5")
  private val LoanNoInstallmentsId = UUID.fromString("018f3f00-0000-7000-8000-0000000000d6")
  private val MissingLoanId = UUID.fromString("018f3f00-0000-7000-8000-0000000000ff")

  private def seedProducts()(using DbCon): Unit =
    sql"INSERT INTO products (id, name, kind, annual_rate, term_months) VALUES ($LoanProductId, 'Test Loan', 'loan', 0.08, 12)".update
      .run()
    sql"INSERT INTO products (id, name, kind, annual_rate) VALUES ($SavingsProductId, 'Test Savings', 'savings', 0.015)".update
      .run()
    sql"INSERT INTO clients (id, display_name, opened_on) VALUES ($ClientId, 'Margaret Hamilton', DATE '2026-01-01')".update
      .run()

  private def seedLoanWithInstallments()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($LoanAccountId, $ClientId, $LoanProductId, 'loan', DATE '2026-08-01')".update
      .run()
    sql"INSERT INTO loans (account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee) VALUES ($LoanAccountId, 5000.00, 0.08, 12, DATE '2026-08-01', 434.94, 7, 15.00)".update
      .run()
    sql"INSERT INTO installments (account_id, seq, due_date, amount_due) VALUES ($LoanAccountId, 1, DATE '2026-09-01', 434.94)".update
      .run()
    sql"INSERT INTO installments (account_id, seq, due_date, amount_due) VALUES ($LoanAccountId, 2, DATE '2026-10-01', 434.94)".update
      .run()

  private def seedSavingsAccount()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($SavingsAccountId, $ClientId, $SavingsProductId, 'savings', DATE '2026-08-01')".update
      .run()

  private def seedLoanWithNoInstallments()(using DbCon): Unit =
    sql"INSERT INTO accounts (id, client_id, product_id, kind, opened_on) VALUES ($LoanNoInstallmentsId, $ClientId, $LoanProductId, 'loan', DATE '2026-08-01')".update
      .run()
    sql"INSERT INTO loans (account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee) VALUES ($LoanNoInstallmentsId, 5000.00, 0.08, 12, DATE '2026-08-01', 434.94, 7, 15.00)".update
      .run()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GetLoanSchedule")(
    test("find returns each installment's dates, amount due, and interest/principal split") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedProducts()
              seedLoanWithInstallments()
              GetLoanSchedule.find(LoanAccountId)
            }
          }
          .map { schedule =>
            assertTrue(
              schedule == List(
                LoanScheduleData(
                  loanId = LoanAccountId.toString,
                  seq = 1,
                  dueDate = LocalDate.parse("2026-09-01"),
                  amountDue = BigDecimal("434.94"),
                  interest = BigDecimal("33.33"),
                  principal = BigDecimal("401.61")
                ),
                LoanScheduleData(
                  loanId = LoanAccountId.toString,
                  seq = 2,
                  dueDate = LocalDate.parse("2026-10-01"),
                  amountDue = BigDecimal("434.94"),
                  interest = BigDecimal("30.66"),
                  principal = BigDecimal("404.28")
                )
              )
            )
          }
    },
    test("find returns an empty schedule for a loan with no installments yet") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedProducts()
              seedLoanWithNoInstallments()
              GetLoanSchedule.find(LoanNoInstallmentsId)
            }
          }
          .map(schedule => assertTrue(schedule == Nil))
    },
    test(
      "find raises NoSuchElementException for an account with no loans row (e.g. a savings account)"
    ) {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking {
            rollingBack(xa) {
              seedProducts()
              seedSavingsAccount()
              scala.util.Try(GetLoanSchedule.find(SavingsAccountId)).isFailure
            }
          }
          .map(wasRejected => assertTrue(wasRejected))
    },
    test("find raises NoSuchElementException for an unknown loan id") {
      ZIO.attemptBlocking(FlywayRunner.migrate(config)) *>
        ZIO
          .attemptBlocking(rollingBack(xa)(GetLoanSchedule.find(MissingLoanId)))
          .exit
          .map(exit => assertTrue(exit.isFailure))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
