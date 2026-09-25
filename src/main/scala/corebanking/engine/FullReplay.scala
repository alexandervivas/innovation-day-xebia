package corebanking.engine

import corebanking.domain.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Recalculates a loan's position by replaying every event from disbursement, day by day. */
object FullReplay extends RecalculationStrategy:

  private case class Installment(index: Int, dueDate: LocalDate)

  private def feeId(index: Int): String = s"LATE-FEE:installment-$index"

  /** The replayed position plus the late fees the replay itself charged, with their value dates. */
  private case class ReplayResult(state: LoanState, lateFees: List[(String, LocalDate)])

  /** The loan's running position as the day-by-day replay proceeds. */
  private case class RunningState(
      principal: BigDecimal = BigDecimal(0),
      interestUnpaid: BigDecimal = BigDecimal(0),
      feesCharged: BigDecimal = BigDecimal(0),
      cumScheduled: BigDecimal = BigDecimal(0),
      cumPaidInterestPrincipal: BigDecimal = BigDecimal(0),
      allocations: Map[String, Allocation] = Map.empty,
      feeChargedFor: Set[Int] = Set.empty,
      lateFees: List[(String, LocalDate)] = Nil
  )

  /** Splits one repayment: fees, then interest, then principal. */
  private def applyRepayment(state: RunningState, id: String, amount: BigDecimal): RunningState =
    val feePay = state.feesCharged.min(amount)
    val afterFees = amount - feePay

    val interestPayExact = state.interestUnpaid.min(afterFees)
    val interestPayRounded = interestPayExact.setScale(2, BigDecimal.RoundingMode.HALF_UP)

    val afterInterest = (afterFees - interestPayRounded).max(BigDecimal(0))
    val principalPay = state.principal.min(afterInterest)

    state.copy(
      principal = state.principal - principalPay,
      interestUnpaid = state.interestUnpaid - interestPayExact,
      feesCharged = state.feesCharged - feePay,
      cumPaidInterestPrincipal = state.cumPaidInterestPrincipal + interestPayRounded + principalPay,
      allocations = state.allocations + (id -> Allocation(feePay, interestPayRounded, principalPay))
    )

  private def replay(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): ReplayResult =
    val installmentAmount = Schedule.installmentAmount(terms)
    val installments = Schedule.dueDates(terms).zipWithIndex.map((d, i) => Installment(i + 1, d))
    val eventsByDate = events.groupBy(_.valueDate)

    val days = Iterator
      .iterate(terms.disbursementDate)(_.plusDays(1))
      .takeWhile(!_.isAfter(asOf))

    val closing = days.foldLeft(RunningState()) { (dayOpen, day) =>
      val accrued =
        if dayOpen.principal > 0 then
          val interest = dayOpen.principal * terms.annualRate / BigDecimal(365)
          dayOpen.copy(interestUnpaid = dayOpen.interestUnpaid + interest)
        else dayOpen

      val dueToday = installmentAmount * BigDecimal(installments.count(_.dueDate == day))
      val scheduled = accrued.copy(cumScheduled = accrued.cumScheduled + dueToday)

      // Deliberate ordering: the fee is charged before the day's repayments are applied, so money
      // arriving exactly on dueDate + graceDays is too late to avoid it (it pays the fee instead).
      // `feeChargedFor` is a belt-and-braces guard, not a working de-duplicator: the day loop
      // visits each installment's fee day exactly once per replay, so it can never block a repeat.
      val charged = installments.foldLeft(scheduled) { (state, inst) =>
        val feeDay = inst.dueDate.plusDays(terms.graceDays.toLong)
        if feeDay == day && !state.feeChargedFor.contains(inst.index)
          && state.cumPaidInterestPrincipal < state.cumScheduled
        then
          state.copy(
            feesCharged = state.feesCharged + terms.lateFee,
            feeChargedFor = state.feeChargedFor + inst.index,
            lateFees = (feeId(inst.index), day) :: state.lateFees
          )
        else state
      }

      eventsByDate.getOrElse(day, Nil).foldLeft(charged) { (state, event) =>
        event match
          case UserEvent.Disbursement(_, amount, _, _) =>
            state.copy(principal = state.principal + amount)
          case UserEvent.Repayment(id, amount, _, _) =>
            applyRepayment(state, id, amount)
      }
    }

    // Oldest due installment not yet fully covered — drives days-past-due / arrears.
    val oldestUnpaidDue = installments
      .filterNot(_.dueDate.isAfter(asOf))
      .zipWithIndex
      .collectFirst {
        case (inst, i)
            if installmentAmount * BigDecimal(i + 1) > closing.cumPaidInterestPrincipal =>
          inst.dueDate
      }

    val dpd = oldestUnpaidDue.map(d => ChronoUnit.DAYS.between(d, asOf).toInt).getOrElse(0)
    val status = if dpd > 0 then LoanStatus.InArrears else LoanStatus.Current

    ReplayResult(
      LoanState(
        principalOutstanding = closing.principal.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        interestAccruedUnpaid = closing.interestUnpaid.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        lateFeesCharged = closing.feesCharged.setScale(2, BigDecimal.RoundingMode.HALF_UP),
        daysPastDue = dpd,
        status = status,
        allocations = closing.allocations
      ),
      closing.lateFees
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
          // On a value-date tie, the backdated transaction is applied after the existing ones.
          val newEvents = (events :+ newTx).sortBy(_.valueDate.toEpochDay)
          val afterReplay = replay(terms, newEvents, systemDate)
          val after = afterReplay.state

          val affectedUserEvents = events.filter(!_.valueDate.isBefore(newTx.valueDate))

          // Only reverses late fees charged between the backdated value date and the last reposted
          // transaction (or the system date, if nothing is reposted).
          val windowEnd = affectedUserEvents.map(_.valueDate).maxOption.getOrElse(systemDate)
          // Falling inside the window is not enough: with the window open to the system date it
          // also catches fees the recomputation charges all over again (the backdated payment was
          // too small to close the gap). Reverse only the fees the `after` replay no longer
          // charges, so `chain` and `after` can never contradict each other.
          val afterFeeIds = afterReplay.lateFees.map((id, _) => id).toSet
          val affectedFees = beforeReplay.lateFees.filter { (id, chargedOn) =>
            !afterFeeIds.contains(id) &&
            !chargedOn.isBefore(newTx.valueDate) && !chargedOn.isAfter(windowEnd)
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
