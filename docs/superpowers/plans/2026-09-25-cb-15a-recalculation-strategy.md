# CB-15a RecalculationStrategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `RecalculationStrategy` + `FullReplay` — pure domain types and a day-by-day loan simulation engine that reproduces the exact acceptance fixture in `docs/handoff/RecalculationSpec.scala`.

**Architecture:** Four files. `domain/LoanTerms.scala` holds the data types (`LoanTerms`, `UserEvent`, `Allocation`, `LoanStatus`, `LoanState`) plus extension accessors on `UserEvent`. `domain/Schedule.scala` computes the fixed annuity installment and due dates. `engine/RecalculationStrategy.scala` holds the trait and its supporting types (`ChainStep`, `RecalcResult`, `RecalcError`). `engine/FullReplay.scala` implements both trait methods via one shared day-by-day replay loop.

**Tech Stack:** Scala 3.9, zio-test (no ZIO runtime dependency in the code under test — `domain/` and `engine/` stay pure). `scalacOptions` include `-Wunused:all -Werror`; unused imports/params fail the build.

**Spec:** `docs/superpowers/specs/2026-09-25-cb-15a-recalculation-strategy.md`. Binding acceptance test: `docs/handoff/RecalculationSpec.scala` (copy verbatim in Task 4, do not edit its assertions).

## Global Constraints

- `domain/` and `engine/` have no `zio.*` or DB imports (`CLAUDE.md` rule 9).
- BigDecimal rounding: `HALF_UP` to 2 decimal places, applied only at allocation/reporting points — never round an intermediate running balance before subtracting it from itself (see Task 4 Step 3 for the exact place this matters).
- `sbt -batch scalafmtCheckAll compile test` must pass; `-Werror` turns any unused import or parameter into a build failure.
- Keep the branch diff at or under 300 changed lines, `docs/superpowers/**` excluded (`CLAUDE.md` rule 8). If the running total after Task 4 exceeds it, split into a `gh stack`: Task 1–2 as the bottom PR, Task 3–4 (plus the copied acceptance spec) as the next — decide at Gates-and-Publish time, not mid-task.

## Review Focus

- **Same-`valueDate` tie-breaking on insertion** — `backdate` must place `newTx` after existing events sharing its `valueDate`, not before; an implementation that sorts unstably would silently reorder allocations. Task 4 pins this with a stable sort and an explicit comment.
- **Interest running-balance zero-out** — capping a repayment's interest portion at the *rounded* cents figure (instead of the unrounded running balance) leaves a fractional residue that drifts every later accrual by a cent. Task 4 Step 3 documents the exact place this bites and the fixture pins it via the `TX-1004` allocation and the after-state totals.
- **Late fee charged only once per installment, and only if still short** — a naive implementation might re-charge the fee on every day at-or-after the grace date, or charge it even when the installment was actually paid on time. Task 4's fee-tracking `Set[Int]` plus the `cumPaidInterestPrincipal < cumScheduled` guard cover this; the fixture's `lateFeesCharged == 15.00` (exactly one fee) pins it.
- **`daysPastDue` after a payment that doesn't fully close the oldest obligation** — the fixture's "before" state (`daysPastDue == 44`) is exactly this case: a payment lands but doesn't cover the oldest unpaid installment, so DPD must still count from that installment's due date, not reset to 0 or count from the payment date.
- **Rejection precedence** — `newTx.valueDate` before disbursement AND inside a closed period is not a fixture case, but the plan's Task 4 order (disbursement check first) is a deliberate, documented choice so a future caller gets a deterministic single error.

---

### Task 1: Domain types

**Files:**
- Create: `src/main/scala/corebanking/domain/LoanTerms.scala`
- Test: `src/test/scala/corebanking/domain/LoanTermsSpec.scala`

**Interfaces:**
- Produces: `LoanTerms`, `UserEvent` (cases `Disbursement`, `Repayment`), extension methods `UserEvent#id: String`, `UserEvent#amount: BigDecimal`, `UserEvent#valueDate: LocalDate`; `Allocation`, `LoanStatus` (`Current`, `InArrears`), `LoanState`. All consumed by Tasks 2–4.

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.domain

import zio.test.*
import java.time.LocalDate

object LoanTermsSpec extends ZIOSpecDefault:

  def spec = suite("domain types")(
    test("UserEvent extensions read id, amount, valueDate for both cases") {
      val disb = UserEvent.Disbursement("TX-1001", BigDecimal("5000.00"), LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-01"))
      val repay = UserEvent.Repayment("TX-1002", BigDecimal("434.94"), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-01"))
      assertTrue(
        disb.id == "TX-1001",
        disb.amount == BigDecimal("5000.00"),
        disb.valueDate == LocalDate.parse("2026-08-01"),
        repay.id == "TX-1002",
        repay.amount == BigDecimal("434.94")
      )
    },
    test("LoanState and Allocation hold the fields the engine needs") {
      val state = LoanState(
        principalOutstanding = BigDecimal("4240.58"),
        interestAccruedUnpaid = BigDecimal("12.08"),
        lateFeesCharged = BigDecimal("15.00"),
        daysPastDue = 44,
        status = LoanStatus.InArrears,
        allocations = Map("TX-1004" -> Allocation(BigDecimal("15.00"), BigDecimal("61.49"), BigDecimal("358.45")))
      )
      assertTrue(
        state.status == LoanStatus.InArrears,
        state.allocations("TX-1004").principal == BigDecimal("358.45")
      )
    }
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt -batch "testOnly corebanking.domain.LoanTermsSpec"`
Expected: FAIL to compile — `LoanTerms`, `UserEvent`, `Allocation`, `LoanStatus`, `LoanState` don't exist yet.

- [ ] **Step 3: Write the implementation**

```scala
package corebanking.domain

import java.time.LocalDate

final case class LoanTerms(
  principal: BigDecimal,
  annualRate: BigDecimal,
  termMonths: Int,
  disbursementDate: LocalDate,
  graceDays: Int,
  lateFee: BigDecimal
)

enum UserEvent:
  case Disbursement(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)
  case Repayment(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)

object UserEvent:
  extension (event: UserEvent)
    def id: String = event match
      case Disbursement(id, _, _, _) => id
      case Repayment(id, _, _, _)    => id
    def amount: BigDecimal = event match
      case Disbursement(_, amount, _, _) => amount
      case Repayment(_, amount, _, _)    => amount
    def valueDate: LocalDate = event match
      case Disbursement(_, _, valueDate, _) => valueDate
      case Repayment(_, _, valueDate, _)    => valueDate

final case class Allocation(fees: BigDecimal, interest: BigDecimal, principal: BigDecimal)

enum LoanStatus:
  case Current, InArrears

final case class LoanState(
  principalOutstanding: BigDecimal,
  interestAccruedUnpaid: BigDecimal,
  lateFeesCharged: BigDecimal,
  daysPastDue: Int,
  status: LoanStatus,
  allocations: Map[String, Allocation]
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt -batch "testOnly corebanking.domain.LoanTermsSpec"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/domain/LoanTerms.scala src/test/scala/corebanking/domain/LoanTermsSpec.scala
git commit -m "feat(domain): add LoanTerms, UserEvent, Allocation, LoanState (CB-15a, #16)"
```

---

### Task 2: Schedule

**Files:**
- Create: `src/main/scala/corebanking/domain/Schedule.scala`
- Test: `src/test/scala/corebanking/domain/ScheduleSpec.scala`

**Interfaces:**
- Consumes: `LoanTerms` (Task 1).
- Produces: `Schedule.installmentAmount(terms: LoanTerms): BigDecimal`, `Schedule.dueDates(terms: LoanTerms): List[LocalDate]` — both consumed by Task 4's `FullReplay`.

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.domain

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

  def spec = suite("Schedule")(
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt -batch "testOnly corebanking.domain.ScheduleSpec"`
Expected: FAIL to compile — `Schedule` doesn't exist.

- [ ] **Step 3: Write the implementation**

```scala
package corebanking.domain

import java.time.LocalDate

object Schedule:

  def dueDates(terms: LoanTerms): List[LocalDate] =
    (1 to terms.termMonths).map(terms.disbursementDate.plusMonths(_)).toList

  /** Annuity payment on the monthly rate (annualRate / 12), rounded HALF_UP to cents. */
  def installmentAmount(terms: LoanTerms): BigDecimal =
    val monthlyRate = terms.annualRate.toDouble / 12.0
    val onePlusR = 1.0 + monthlyRate
    val factor = monthlyRate / (1.0 - math.pow(onePlusR, -terms.termMonths.toDouble))
    (terms.principal * BigDecimal(factor)).setScale(2, BigDecimal.RoundingMode.HALF_UP)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt -batch "testOnly corebanking.domain.ScheduleSpec"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/domain/Schedule.scala src/test/scala/corebanking/domain/ScheduleSpec.scala
git commit -m "feat(domain): add Schedule.installmentAmount and dueDates (CB-15a, #16)"
```

---

### Task 3: Engine types

**Files:**
- Create: `src/main/scala/corebanking/engine/RecalculationStrategy.scala`
- Test: `src/test/scala/corebanking/engine/RecalculationStrategySpec.scala`

**Interfaces:**
- Consumes: `LoanTerms`, `UserEvent`, `LoanState` (Task 1).
- Produces: `ChainStep` (`Reverse`, `Post`, `Repost`), `RecalcResult`, `RecalcError` (`ValueDateBeforeDisbursement`, `PeriodClosed`), `RecalculationStrategy` trait — all consumed by Task 4's `FullReplay`.

- [ ] **Step 1: Write the failing test**

```scala
package corebanking.engine

import zio.test.*
import java.time.LocalDate

object RecalculationStrategySpec extends ZIOSpecDefault:

  def spec = suite("engine types")(
    test("ChainStep cases carry their id") {
      assertTrue(
        ChainStep.Reverse("TX-1") == ChainStep.Reverse("TX-1"),
        ChainStep.Post("TX-2") != ChainStep.Repost("TX-2")
      )
    },
    test("RecalcError.PeriodClosed carries the period start date") {
      val err = RecalcError.PeriodClosed(LocalDate.parse("2026-09-01"))
      assertTrue(err == RecalcError.PeriodClosed(LocalDate.parse("2026-09-01")))
    }
  )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt -batch "testOnly corebanking.engine.RecalculationStrategySpec"`
Expected: FAIL to compile — `ChainStep`, `RecalcError` don't exist.

- [ ] **Step 3: Write the implementation**

```scala
package corebanking.engine

import java.time.LocalDate
import corebanking.domain.*

enum ChainStep:
  case Reverse(targetId: String)
  case Post(txId: String)
  case Repost(originalId: String)

final case class RecalcResult(before: LoanState, after: LoanState, chain: List[ChainStep])

enum RecalcError:
  case ValueDateBeforeDisbursement
  case PeriodClosed(periodStart: LocalDate)

trait RecalculationStrategy:
  def stateAt(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): LoanState
  def backdate(
    terms: LoanTerms,
    events: List[UserEvent],
    newTx: UserEvent,
    systemDate: LocalDate,
    closedPeriods: List[LocalDate]
  ): Either[RecalcError, RecalcResult]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt -batch "testOnly corebanking.engine.RecalculationStrategySpec"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/corebanking/engine/RecalculationStrategy.scala src/test/scala/corebanking/engine/RecalculationStrategySpec.scala
git commit -m "feat(engine): add RecalculationStrategy trait, ChainStep, RecalcResult, RecalcError (CB-15a, #16)"
```

---

### Task 4: FullReplay — stateAt and backdate

**Files:**
- Create: `src/main/scala/corebanking/engine/FullReplay.scala`
- Test: `src/test/scala/corebanking/engine/RecalculationSpec.scala` (copy of `docs/handoff/RecalculationSpec.scala`, verbatim — this is the binding acceptance test; do not alter its assertions)

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces: `object FullReplay extends RecalculationStrategy` — the story's deliverable; CB-07 and CB-13 will consume `FullReplay.stateAt`/`backdate` directly in later stories.

- [ ] **Step 1: Copy the binding acceptance test verbatim**

```bash
cp docs/handoff/RecalculationSpec.scala src/test/scala/corebanking/engine/RecalculationSpec.scala
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run: `sbt -batch "testOnly corebanking.engine.RecalculationSpec"`
Expected: FAIL to compile — `FullReplay` doesn't exist.

- [ ] **Step 3: Write the implementation**

The core is one `replay` helper shared by `stateAt` and `backdate`: a day-by-day loop from `disbursementDate` to `asOf` that accrues interest daily, applies due-date and late-fee timing, and allocates each `Repayment` fees → interest → principal.

**The one rounding rule that matters:** when a repayment pays down accrued interest, subtract the *unrounded* interest amount from the running `interestUnpaid` balance (so it zeroes out exactly when fully paid), and only round for the reported `Allocation` figure and for deriving how much of the repayment is left for principal. Rounding the running balance itself before subtracting leaves a fractional cent behind that silently drifts every later day's accrual.

```scala
package corebanking.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import corebanking.domain.*

object FullReplay extends RecalculationStrategy:

  private case class Installment(index: Int, dueDate: LocalDate)
  private def feeId(index: Int): String = s"LATE-FEE:installment-$index"
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
      if principal > 0 then
        interestUnpaid += principal * terms.annualRate / BigDecimal(365)

      installments.foreach(inst => if inst.dueDate == day then cumScheduled += installmentAmount)

      installments.foreach { inst =>
        val feeDay = inst.dueDate.plusDays(terms.graceDays.toLong)
        if feeDay == day && !feeChargedFor.contains(inst.index) && cumPaidInterestPrincipal < cumScheduled then
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
      .find { inst =>
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
      closedPeriods.find(p => p.getYear == newTx.valueDate.getYear && p.getMonth == newTx.valueDate.getMonth) match
        case Some(period) =>
          Left(RecalcError.PeriodClosed(period))
        case None =>
          val beforeReplay = replay(terms, events, systemDate)
          // Stable sort: newTx is appended last, so on a valueDate tie it sorts after existing same-day events.
          val newEvents = (events :+ newTx).sortBy(_.valueDate.toEpochDay)
          val after = replay(terms, newEvents, systemDate).state

          val affectedUserEvents = events.filter(!_.valueDate.isBefore(newTx.valueDate))
          val affectedFees = beforeReplay.lateFees.filter((_, d) => !d.isBefore(newTx.valueDate))

          val reverseTargets = (affectedUserEvents.map(e => (e.id, e.valueDate)) ++ affectedFees)
            .sortBy((_, d) => d.toEpochDay)
            .reverse
            .map((id, _) => ChainStep.Reverse(id))

          val repostSteps = affectedUserEvents
            .sortBy(_.valueDate.toEpochDay)
            .map(e => ChainStep.Repost(e.id))

          val chain = reverseTargets ++ List(ChainStep.Post(newTx.id)) ++ repostSteps

          Right(RecalcResult(beforeReplay.state, after, chain))
```

- [ ] **Step 4: Run the full acceptance suite**

Run: `sbt -batch "testOnly corebanking.engine.RecalculationSpec"`
Expected: PASS (7 tests). If any monetary assertion is off by a cent, use `superpowers:systematic-debugging`: print `interestUnpaid`/`principal` at each `Repayment` event (temporarily) and compare against the plan's rounding rule in Step 3 above before changing the algorithm's shape — the fixture numbers were hand-verified against this exact algorithm before this plan was written.

- [ ] **Step 5: Run the whole module's tests**

Run: `sbt -batch "testOnly corebanking.domain.* corebanking.engine.*"`
Expected: PASS (all domain + engine specs from Tasks 1–4)

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/corebanking/engine/FullReplay.scala src/test/scala/corebanking/engine/RecalculationSpec.scala
git commit -m "feat(engine): add FullReplay strategy, pass CB-15a acceptance fixture (CB-15a, #16)"
```

---

## Final Verification (whole branch)

- [ ] `sbt -batch scalafmtCheckAll compile test` — full build, all tests green.
- [ ] `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh` — self-test fires every pattern class, then a clean scan of a non-zero file count.
- [ ] `git diff --shortstat origin/main..HEAD -- . ':!docs/superpowers/**'` — confirm the changed-line count; split into a `gh stack` per the Global Constraints note if it exceeds 300.
- [ ] `risk-reviewer` on `opus` reviews the whole branch diff before commit is finalized into a PR.
