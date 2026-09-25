package corebanking.engine

import corebanking.domain.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Recalculates a loan by replaying every user event from disbursement, day by day. Simple and
 * always exact: no snapshot can go stale, because nothing is cached between runs.
 */
object FullReplay extends RecalculationStrategy:

  private case class Installment(index: Int, dueDate: LocalDate)

  private def feeId(index: Int): String = s"LATE-FEE:installment-$index"

  /** The replayed position plus the late fees the replay itself charged, with their value dates. */
  private case class ReplayResult(state: LoanState, lateFees: List[(String, LocalDate)])

  private def replay(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): ReplayResult =
    val installmentAmount = Schedule.installmentAmount(terms)
    val installments = Schedule.dueDates(terms).zipWithIndex.map((d, i) => Installment(i + 1, d))
    val eventsByDate = events.groupBy(_.valueDate)

    var principal = BigDecimal(0)
    var interestUnpaid = BigDecimal(0)
    var feesCharged = BigDecimal(0)
    var cumScheduled = BigDecimal(0)
    var cumPaidInterestPrincipal = BigDecimal(0)
    var allocations = Map.empty[String, Allocation]
    var feeChargedFor = Set.empty[Int]
    var lateFees = List.empty[(String, LocalDate)]

    var day = terms.disbursementDate
    while !day.isAfter(asOf) do
      if principal > 0 then interestUnpaid += principal * terms.annualRate / BigDecimal(365)

      installments.foreach(inst => if inst.dueDate == day then cumScheduled += installmentAmount)

      installments.foreach { inst =>
        val feeDay = inst.dueDate.plusDays(terms.graceDays.toLong)
        if feeDay == day && !feeChargedFor.contains(inst.index)
          && cumPaidInterestPrincipal < cumScheduled
        then
          feesCharged += terms.lateFee
          feeChargedFor += inst.index
          lateFees = (feeId(inst.index), day) :: lateFees
      }

      eventsByDate.getOrElse(day, Nil).foreach {
        case UserEvent.Disbursement(_, amount, _, _) =>
          principal += amount
        case UserEvent.Repayment(id, amount, _, _) =>
          var remaining = amount

          val feePay = feesCharged.min(remaining)
          feesCharged -= feePay
          remaining -= feePay

          // Zero out the UNROUNDED balance so no fractional cent survives to drift later accrual.
          val interestPayExact = interestUnpaid.min(remaining)
          interestUnpaid -= interestPayExact
          val interestPayRounded = interestPayExact.setScale(2, BigDecimal.RoundingMode.HALF_UP)
          remaining -= interestPayRounded

          val principalPay = principal.min(remaining)
          principal -= principalPay

          allocations += id -> Allocation(feePay, interestPayRounded, principalPay)
          cumPaidInterestPrincipal += (interestPayRounded + principalPay)
      }

      day = day.plusDays(1)

    var cum = BigDecimal(0)
    val oldestUnpaidDue = installments
      .filterNot(_.dueDate.isAfter(asOf))
      .find { _ =>
        cum += installmentAmount
        cum > cumPaidInterestPrincipal
      }
      .map(_.dueDate)

    val dpd = oldestUnpaidDue.map(d => ChronoUnit.DAYS.between(d, asOf).toInt).getOrElse(0)
    val status = if dpd > 0 then LoanStatus.InArrears else LoanStatus.Current

    ReplayResult(
      LoanState(
        principalOutstanding = principal.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        interestAccruedUnpaid = interestUnpaid.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        lateFeesCharged = feesCharged.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        daysPastDue = dpd,
        status = status,
        allocations = allocations
      ),
      lateFees
    )

  def stateAt(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): LoanState =
    replay(terms, events, asOf).state

  def backdate(
      terms: LoanTerms,
      events: List[UserEvent],
      newTx: UserEvent,
      systemDate: LocalDate,
      closedPeriods: List[LocalDate]
  ): Either[RecalcError, RecalcResult] =
    if newTx.valueDate.isBefore(terms.disbursementDate) then
      Left(RecalcError.ValueDateBeforeDisbursement)
    else
      val closed = closedPeriods.find { p =>
        p.getYear == newTx.valueDate.getYear && p.getMonth == newTx.valueDate.getMonth
      }
      closed match
        case Some(period) => Left(RecalcError.PeriodClosed(period))
        case None =>
          val beforeReplay = replay(terms, events, systemDate)
          // Stable sort: newTx is appended last, so on a value-date tie it sorts after the
          // events already on that day.
          val newEvents = (events :+ newTx).sortBy(_.valueDate.toEpochDay)
          val after = replay(terms, newEvents, systemDate).state

          val affectedUserEvents = events.filter(!_.valueDate.isBefore(newTx.valueDate))

          // The correction window runs from the backdated value date to the newest transaction the
          // chain reposts. Only the synthetic late fees inside it are reversed explicitly: a fee
          // charged after the last repost is downstream of the chain, so the recomputation decides
          // on its own whether it still stands.
          val windowEnd = affectedUserEvents.map(_.valueDate).maxOption
          val affectedFees = beforeReplay.lateFees.filter { (_, chargedOn) =>
            !chargedOn.isBefore(newTx.valueDate) && windowEnd.exists(!chargedOn.isAfter(_))
          }

          val reverseSteps = (affectedUserEvents.map(e => (e.id, e.valueDate)) ++ affectedFees)
            .sortBy((_, d) => d.toEpochDay)
            .reverse
            .map((id, _) => ChainStep.Reverse(id))

          val repostSteps = affectedUserEvents
            .sortBy(_.valueDate.toEpochDay)
            .map(e => ChainStep.Repost(e.id))

          val chain = reverseSteps ++ List(ChainStep.Post(newTx.id)) ++ repostSteps

          Right(RecalcResult(beforeReplay.state, after, chain))
