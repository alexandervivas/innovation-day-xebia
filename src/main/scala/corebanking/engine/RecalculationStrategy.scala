package corebanking.engine

import corebanking.domain.*

import java.time.LocalDate

/** One correction step of the reversal + repost chain that replaces a backdated transaction. */
enum ChainStep:
  case Reverse(targetId: String)
  case Post(txId: String)
  case Repost(originalId: String)

/** The loan position before and after a backdated event, with the chain that gets it there. */
final case class RecalcResult(before: LoanState, after: LoanState, chain: List[ChainStep])

/** Why a backdated event cannot be applied. */
enum RecalcError:
  case ValueDateBeforeDisbursement
  case PeriodClosed(periodStart: LocalDate)

/** Replays domain events to a loan position; stays pure so strategies stay swappable. */
trait RecalculationStrategy:

  /** The loan position after replaying `events` up to and including `asOf`. */
  def stateAt(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): LoanState

  /** Applies `newTx` to the existing `events`, or explains why its value date is not acceptable. */
  def backdate(
      terms: LoanTerms,
      events: List[UserEvent],
      newTx: UserEvent,
      systemDate: LocalDate,
      closedPeriods: List[LocalDate]
  ): Either[RecalcError, RecalcResult]
