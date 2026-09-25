# CB-15a — RecalculationStrategy trait + FullReplay

Issue: #16. Epic E5 (Backdating), critical path.

## Scope

In scope: pure domain types and the `FullReplay` recalculation engine in
`domain/` and `engine/`. No ZIO, no DB, no MCP tool wiring.

Out of scope (owned by later stories):

- Wiring the `strategy: "full" | "incremental"` tool parameter and its
  response field — that lives on the `preview_backdated_transaction` /
  `post_backdated_transaction` tools (CB-16, CB-17), which don't exist yet.
  CB-15a only has to make `FullReplay` correct and pure so those tools can
  call it.
- `SnapshotReplay` (CB-15c) and the `loan_snapshots` table (CB-15b).
- Property-based order-independence tests (CB-20) — this story's tests are
  the fixed fixture in `docs/handoff/RecalculationSpec.scala`.
- Broader input validation / additional `RecalcError` variants (CB-19).

The binding spec is `docs/handoff/RecalculationSpec.scala` (test file,
committed, not to be edited — copy it into `src/test/` verbatim as the
first task). `CLAUDE.md`'s domain simplifications apply: one savings
product, one 12-month annuity consumer loan, actual/365 daily accrual,
fees → interest → principal allocation, fixed late fee after
due-date + grace period.

## Module Layout

```
src/main/scala/corebanking/domain/
  LoanTerms.scala       // LoanTerms, UserEvent, Allocation, LoanStatus, LoanState
  Schedule.scala        // Schedule.installmentAmount, due-date generation
src/main/scala/corebanking/engine/
  RecalculationStrategy.scala   // trait + ChainStep, RecalcResult, RecalcError
  FullReplay.scala              // object FullReplay extends RecalculationStrategy
src/test/scala/corebanking/engine/
  RecalculationSpec.scala       // copied verbatim from docs/handoff/
```

Every file here is free of `import zio.*` and DB imports (`CLAUDE.md` rule
9). `build.sbt` already compiles with `-Wunused:all -Werror`; unused
imports/params in new files fail the build, so keep signatures exact.

## Types

Taken directly from the handoff spec's "Assumed domain API" docstring —
this story implements exactly that surface, no more:

```scala
final case class LoanTerms(
  principal: BigDecimal, annualRate: BigDecimal, termMonths: Int,
  disbursementDate: LocalDate, graceDays: Int, lateFee: BigDecimal)

enum UserEvent:
  case Disbursement(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)
  case Repayment(id: String, amount: BigDecimal, valueDate: LocalDate, bookingDate: LocalDate)

final case class Allocation(fees: BigDecimal, interest: BigDecimal, principal: BigDecimal)

enum LoanStatus:
  case Current, InArrears

final case class LoanState(
  principalOutstanding: BigDecimal, interestAccruedUnpaid: BigDecimal,
  lateFeesCharged: BigDecimal, daysPastDue: Int, status: LoanStatus,
  allocations: Map[String, Allocation])

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
  def backdate(terms: LoanTerms, events: List[UserEvent], newTx: UserEvent,
               systemDate: LocalDate, closedPeriods: List[LocalDate]): Either[RecalcError, RecalcResult]

object FullReplay extends RecalculationStrategy
```

`Schedule.installmentAmount(terms: LoanTerms): BigDecimal` is a separate
object per the spec's direct test of it (`Schedule.installmentAmount(terms) == eur("434.94")`).

## Algorithm

### `Schedule.installmentAmount`

Standard annuity payment on the monthly rate `annualRate / 12`:
`A = P * r / (1 - (1 + r)^-n)`, computed with enough precision (e.g. via
`Double` for the `(1+r)^-n` term, or an iterative/Newton refinement) and
rounded `HALF_UP` to cents. `termMonths` fixed at 12 per the domain
simplification; due dates are one calendar month apart starting one month
after `disbursementDate`.

### `FullReplay.stateAt`

Day-by-day simulation from `disbursementDate` through `asOf` (inclusive).
Chosen over closed-form interval math because it directly encodes "interest
accrues daily on outstanding principal" and "late fee grace days after due
date" without deriving separate formulas per case — simpler to get right
and to verify against the fixture. Per day:

1. Accrue interest: `principalOutstanding * annualRate / 365`, kept
   unrounded, added to a running `interestAccruedUnpaid` (rounded only when
   allocated or reported).
2. If the day is an installment due date, that installment becomes due
   (tracked as an ordered queue of unpaid installments).
3. If the day is `graceDays` after a due date whose installment is still
   short (its allocation doesn't cover principal+interest as scheduled),
   charge the fixed late fee once and record it as a synthetic event
   `LATE-FEE:installment-<n>` with that day as its effective/value date —
   these synthetic events feed the chain-diff in `backdate` the same way
   user events do.
4. If the day matches a `UserEvent`'s `valueDate`, apply it:
   - `Disbursement`: add to `principalOutstanding` (only the initial one
     in this domain).
   - `Repayment`: allocate the amount fees → interest → principal against
     the oldest unpaid obligations first; record the resulting
     `Allocation` under the event's id in `allocations`.
5. After the loop, `daysPastDue` = days from the due date of the oldest
   installment not fully covered (fees+interest+principal all paid) to
   `asOf`, or 0 if none. `status` = `InArrears` if `daysPastDue > 0` else
   `Current`.

Multiple events on the same `valueDate` apply in the order they appear in
the input `events` list (stable order — callers are responsible for
passing them in a sensible order; `backdate` handles insertion order for
its one inserted event explicitly, see below).

### `FullReplay.backdate`

1. Validate first: `newTx.valueDate < terms.disbursementDate` →
   `Left(ValueDateBeforeDisbursement)`. Else if `newTx.valueDate` falls
   within a closed period — a `closedPeriods` entry is a period-start date;
   the period runs to the next entry or to month-end — return
   `Left(PeriodClosed(thatPeriodStart))`. For this story's fixture, one
   closed period entry `2026-09-01` covers the whole of September 2026.
2. `before = stateAt(terms, events, systemDate)`.
3. `newEvents` = `events` with `newTx` inserted in `valueDate` order
   (ties: after existing same-day events).
4. `after = stateAt(terms, newEvents, systemDate)` — this is the entire
   "full replay" — no incremental patching.
5. Synthesize `chain` for reporting (it does not drive the recompute,
   which already happened in step 4): collect every original user event
   *and* every late-fee synthetic event from the `before` replay whose
   effective `valueDate >= newTx.valueDate`, sort newest-first, emit
   `Reverse(id)` for each; emit `Post(newTx.id)`; then emit `Repost(id)`
   for each collected *user* event (not synthetic fees) oldest-first.

Verified this against the fixture by hand: for the backdated `TX-1010`
(valueDate 2026-09-30), the collected set is `TX-1004` (valueDate
2026-11-01) and the October late fee (charged ~2026-10-08), both
`>= 2026-09-30`; newest-first reversal gives `Reverse(TX-1004)`,
`Reverse(LATE-FEE:installment-2)`; then `Post(TX-1010)`; then
`Repost(TX-1004)` (the only user event in the set) — matches the fixture's
expected chain exactly.

## Testing

`docs/handoff/RecalculationSpec.scala` copied verbatim to
`src/test/scala/corebanking/engine/RecalculationSpec.scala` is the
acceptance test — it must pass unmodified. TDD proceeds bottom-up: types
compile → `Schedule.installmentAmount` → `stateAt` (before-state test) →
`backdate` happy path → chain ordering → the two rejection cases.

## Risks / things to watch in review

- Rounding: BigDecimal `HALF_UP` must be applied only at allocation/report
  points, never mid-accrual, or the fixture's cent-level assertions won't
  match.
- `-Wunused:all -Werror` will fail the build on any unused enum case
  parameter or import; keep the pure files minimal.
- The closed-period semantics (a start date covering its whole month) are
  inferred from the single fixture case — if the task uncovers a
  contradiction, stop and flag it rather than guessing further.
