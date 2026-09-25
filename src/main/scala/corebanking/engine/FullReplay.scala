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

  /**
   * Everything the day-by-day replay carries from one day to the next. Threaded through a fold, so
   * no step can mutate a balance another step still depends on.
   */
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

  /**
   * Splits one repayment in the fixed fees -> interest -> principal order and folds the split back
   * into the running position.
   */
  private def applyRepayment(state: RunningState, id: String, amount: BigDecimal): RunningState =
    val feePay = state.feesCharged.min(amount)
    val afterFees = amount - feePay

    // Zero out the UNROUNDED balance so no fractional cent survives to drift later accrual.
    val interestPayExact = state.interestUnpaid.min(afterFees)
    val interestPayRounded = interestPayExact.setScale(2, BigDecimal.RoundingMode.HALF_UP)

    // Rounding the interest HALF_UP can lift it above what the payment still had left, which would
    // make the principal allocation negative and grow the outstanding balance. Clamp at zero.
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

    // The oldest installment already due whose cumulative amount the repayments have not covered.
    // The cumulative total after the n-th installment is exactly n * installmentAmount.
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
          // The sort only keeps the list in value-date order for readability; it does not decide
          // the within-day order. `replay` regroups by value date with `groupBy`, which preserves
          // each group's original list order, so the `:+` above is what puts newTx last on its own
          // day -- and `sortBy` is stable, so it leaves that append order intact.
          val newEvents = (events :+ newTx).sortBy(_.valueDate.toEpochDay)
          val afterReplay = replay(terms, newEvents, systemDate)
          val after = afterReplay.state

          val affectedUserEvents = events.filter(!_.valueDate.isBefore(newTx.valueDate))

          // The correction window runs from the backdated value date to the newest transaction the
          // chain reposts. Only the synthetic late fees inside it are reversed explicitly: a fee
          // charged after the last repost is downstream of the chain, so the recomputation decides
          // on its own whether it still stands. With nothing to repost the window stays open to the
          // system date, so a fee the recomputation drops is still reported as reversed.
          val windowEnd = affectedUserEvents.map(_.valueDate).maxOption.getOrElse(systemDate)
          // Falling inside the window is not enough: with the window open to the system date it
          // also catches fees the recomputation charges all over again (the backdated payment was
          // too small to close the gap). Reverse only the fees the `after` replay no longer
          // charges, so the chain never claims a reversal the recomputation contradicts. The
          // converse isn't guaranteed: a fee outside the window can still drop from `after` with
          // no `Reverse` step (see the spec's open question for CB-13/16/17).
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
