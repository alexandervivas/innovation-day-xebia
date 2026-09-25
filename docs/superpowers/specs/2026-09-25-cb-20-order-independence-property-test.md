# CB-20 Property test: order independence — design note

Bounded story. Issue: [#23](https://github.com/alexandervivas/innovation-day-xebia/issues/23).
Blocked-by CB-15a (#16, closed).

## Scope

One new zio-test property spec, no production code changes.

`FullReplay.backdate` recomputes each call from `replay(terms, newEvents, systemDate)`, and
`replay` groups events by `valueDate` and folds day-by-day — so the state after replaying a fixed
event set never depends on the order those events were appended to the list, only on their value
dates. The property to verify: posting the same 3 backdated repayments through `backdate`, one at
a time, in different orders, converges to the same final `LoanState` regardless of posting order.

## Fixture

Reuse the `RecalculationSpec` fixture shape (LN-0042-style loan): `LoanTerms` with a disbursement,
one existing repayment, and system date after all three backdated value dates. Three fixed
backdated `Repayment` events at distinct value dates (e.g. day 5, day 3, day 7 after disbursement),
distinct ids, amounts small enough to stay within one installment so allocation stays simple.

## Property

```
Gen.shuffle(List(tx5, tx3, tx7))  // zio-test Gen, permutations of the 3 fixed events
```

For each permutation: fold `backdate` over the three transactions in that order, starting from the
base history, accumulating `events :+ newTx` after each successful step (mirroring how a caller
would post them one at a time), take `stateAt(terms, finalEvents, systemDate)` as the final state.
Assert every permutation's final `LoanState` equals a state computed once from the union of base
history + all three transactions via `stateAt` directly (the order-independent reference).

`checkN` (fixed sample count, no external randomness needed beyond `Gen.shuffle`'s own) or
`check` with a bounded `Gen` is fine — 3! = 6 permutations, so a `Gen.fromIterable` over
`List(tx5,tx3,tx7).permutations.toList` covers the full space deterministically instead of relying
on random sampling coverage.

## File

`src/test/scala/corebanking/engine/OrderIndependenceSpec.scala` — new file, `ZIOSpecDefault`,
`corebanking.engine` package, following `RecalculationSpec`'s fixture/import style.

## Testing

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

## Model routing

Single test-only batch → `test-worker` on `sonnet` (routine bounded feature; not money/ledger
production code, but exercises the recalculation engine's core invariant, so `risk-reviewer` runs
on `opus`).
