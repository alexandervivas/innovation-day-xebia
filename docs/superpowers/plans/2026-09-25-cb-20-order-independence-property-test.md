# CB-20 Order Independence Property Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove, with an exhaustive zio-test property, that `FullReplay`'s final `LoanState` after
posting three backdated repayments does not depend on the order they were posted in.

**Architecture:** `FullReplay.backdate` recomputes state each call from `replay(terms, events,
systemDate)`, and `replay` groups events by `valueDate` and folds day-by-day — the final state is a
pure function of the event *set*, not the order events were appended. One new test file exercises
this: post the same 3 fixed backdated repayments through `backdate`, one at a time, in every one of
the `3! = 6` permutations (exhaustive, via `Gen.fromIterable` + `checkAll`, not random sampling),
and assert every permutation's resulting `LoanState` equals a directly-computed reference. No
production code changes — this pins an already-satisfied invariant, so the test is expected to pass
on first run, not go red first (this is a characterization/property test of existing behavior, not
new-feature TDD).

**Tech Stack:** Scala 3, zio-test (`ZIOSpecDefault`, `Gen`, `checkAll`), existing `corebanking.domain`
/ `corebanking.engine` types (`LoanTerms`, `UserEvent`, `LoanState`, `FullReplay`).

**Spec:** `docs/superpowers/specs/2026-09-25-cb-20-order-independence-property-test.md`

## Global Constraints

- No PR over 300 changed lines (additions + deletions; `docs/superpowers/**` excluded) — this plan
  is one small test file, well under the limit.
- Comments explain business/domain meaning only, tersely, one line where possible (owner rule
  2026-09-25) — no implementation narration in the new file.
- Secret scan must pass before commit: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`.
- Pure domain/engine main sources stay free of ZIO/DB code (CLAUDE.md rule 9) — not touched by this
  plan; the new file is test-only and zio-test is already the project's test framework.
- Model routing: this is a test-only batch exercising the recalculation engine's core invariant →
  implementer `test-worker` on `sonnet`, reviewer `risk-reviewer` on `opus`.

## Review Focus

- An intermediate `backdate` call in some permutation returns `Left` (rejected), silently truncating
  that permutation's event list instead of failing the test loudly — pinned by asserting
  `result.isRight` inside the posting fold, with the rejection reason in the failure message.
- The fixture is accidentally vacuous (e.g. a copy-paste bug drops a repayment, or amounts never
  actually change principal), making all 6 permutations trivially "equal" without proving anything —
  pinned by a sanity test asserting the reference state's principal actually decreased and all three
  transaction ids appear in `allocations`.
- `Gen.fromIterable` + `check` samples permutations randomly (default 200 samples) instead of
  covering all 6 exhaustively — avoided by using `checkAll`, which runs the generator's stream to
  exhaustion for a finite `Gen.fromIterable`.
- Two of the three backdated value dates could tie, changing repost ordering inside `backdate`'s
  chain (unrelated to the final-state property being tested) — avoided by fixing three distinct
  value dates (day 3, day 5, day 7 after disbursement, matching the acceptance criteria's example).
- A backdated value date could fall before `disbursementDate`, tripping
  `RecalcError.ValueDateBeforeDisbursement` for some permutations — avoided by fixing all three
  dates strictly after `disbursementDate`; this can't vary by permutation since the *set* of dates is
  fixed, only their posting order changes.

---

## Task 1: Order-independence property spec

**Files:**
- Create: `src/test/scala/corebanking/engine/OrderIndependenceSpec.scala`

**Interfaces:**
- Consumes: `corebanking.domain.{LoanTerms, UserEvent, LoanState}`,
  `corebanking.engine.{FullReplay, RecalcResult, RecalcError}` — all existing, unchanged
  (`FullReplay.stateAt(terms: LoanTerms, events: List[UserEvent], asOf: LocalDate): LoanState`;
  `FullReplay.backdate(terms: LoanTerms, events: List[UserEvent], newTx: UserEvent, systemDate:
  LocalDate, closedPeriods: List[LocalDate]): Either[RecalcError, RecalcResult]`).
- Produces: nothing consumed by later tasks — this is the only task in the plan.

- [ ] **Step 1: Write the property test**

Create `src/test/scala/corebanking/engine/OrderIndependenceSpec.scala`:

```scala
package corebanking.engine

import zio.test.*
import java.time.LocalDate
import corebanking.domain.*

object OrderIndependenceSpec extends ZIOSpecDefault:

  private def d(s: String) = LocalDate.parse(s)
  private def eur(s: String) = BigDecimal(s)

  private val terms = LoanTerms(
    principal = eur("5000.00"),
    annualRate = eur("0.08"),
    termMonths = 12,
    disbursementDate = d("2026-08-01"),
    graceDays = 7,
    lateFee = eur("15.00")
  )

  private val systemDate = d("2026-11-14")

  private val baseHistory = List(
    UserEvent.Disbursement("TX-2001", eur("5000.00"), d("2026-08-01"), d("2026-08-01"))
  )

  // Day 3, Day 5, Day 7 after disbursement, per the acceptance criteria's example.
  private val txDay3 = UserEvent.Repayment("TX-2003", eur("100.00"), d("2026-08-04"), systemDate)
  private val txDay5 = UserEvent.Repayment("TX-2005", eur("100.00"), d("2026-08-06"), systemDate)
  private val txDay7 = UserEvent.Repayment("TX-2007", eur("100.00"), d("2026-08-08"), systemDate)

  private val threeBackdatedTxns = List(txDay5, txDay3, txDay7)

  /** Posts each transaction through `backdate`, one at a time in `order`, then reads the final state. */
  private def postInOrder(order: List[UserEvent]): LoanState =
    val finalEvents = order.foldLeft(baseHistory) { (events, tx) =>
      val result = FullReplay.backdate(terms, events, tx, systemDate, closedPeriods = Nil)
      assert(result.isRight, s"backdate rejected $tx after $events: $result")
      events :+ tx
    }
    FullReplay.stateAt(terms, finalEvents, systemDate)

  private val referenceState =
    FullReplay.stateAt(terms, baseHistory ++ threeBackdatedTxns, systemDate)

  def spec = suite("FullReplay — order independence (CB-20)")(
    test("fixture sanity: the three repayments actually reduce principal") {
      assertTrue(
        referenceState.principalOutstanding < terms.principal,
        referenceState.allocations.keySet == Set("TX-2003", "TX-2005", "TX-2007")
      )
    },
    test("final loan state is identical for every posting order of the 3 backdated repayments") {
      checkAll(Gen.fromIterable(threeBackdatedTxns.permutations.toList)) { order =>
        assertTrue(postInOrder(order) == referenceState)
      }
    }
  )
```

- [ ] **Step 2: Run the spec and confirm it passes**

Run: `sbt -batch "testOnly corebanking.engine.OrderIndependenceSpec"`
Expected: both tests PASS (2 succeeded). This is expected to go green immediately — the property
already holds by construction in `FullReplay`; the test's job is to pin and prove it, not drive new
production code.

If either test fails, do not weaken the assertions — investigate `FullReplay.replay`/`backdate`
(`src/main/scala/corebanking/engine/FullReplay.scala`) for the actual defect, since a failure here
means the order-independence invariant CB-20 is meant to verify does not hold.

- [ ] **Step 3: Run the full gate**

Run: `sbt -batch scalafmtCheckAll compile test`
Expected: all pass, 0 failures.

- [ ] **Step 4: Secret scan**

Run: `scripts/secret-scan.sh --self-test && scripts/secret-scan.sh`
Expected: self-test reports every pattern class firing; the scan reports `clean` with a non-zero
file count.

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/corebanking/engine/OrderIndependenceSpec.scala
git commit -m "test(engine): prove order independence for backdated repayments (CB-20, #23)"
```
