# Demo Script – Core Banking MCP

**How to run the demo**:
1. Start the database: `docker compose up -d`
2. Stage the server once: `sbt stage`
3. Start Claude Code in this repo; it picks up the project-scoped `.mcp.json` automatically and launches the server via `scripts/run-server.sh`
4. Confirm the connection with `claude mcp list`, then smoke-test with the `ping` tool
5. Paste each prompt below into Claude in order

---

## Scenario Setup

Create a diversified test portfolio of loans and savings accounts to work with.

**Prompt 1**: Create 10 retail clients, each with a savings account and a 12-month €5,000 loan at 8% annual interest.

**Prompt 2**: Build a loan portfolio where 30% are 1–30 days late, 10% are 60+ days late, and the rest are current.

**Prompt 3**: Create one client who repaid their loan early and another who made a partial prepayment in month 3.

---

## Time Travel

Advance the system clock and observe interest accrual, arrears calculations, and status transitions.

**Prompt 4**: Advance the system date by 45 days and list every loan that changed status.

**Prompt 5**: Run end-of-day for the next 3 months and show accrued interest per loan.

**Prompt 6**: Move the clock through February 2028 and check how daily accrual handles a leap year.

---

## Backdating

The core differentiator: preview, post, and explain recalculations on backdated transactions.

**Prompt 7**: Preview a €500 repayment on loan X with a value date 20 days ago. Show what would be recalculated before committing.

**Prompt 8**: Backdate a repayment to before the loan went into arrears. Does it return to current? Are the late fees reversed?

**Prompt 9**: Post three backdated repayments in random order and verify the final balance is identical regardless of order.

**Prompt 10**: Backdate a transaction to before an existing repayment and show the full reversal-and-repost chain.

**Prompt 11**: Explain in plain English why the interest on loan X changed after the last backdated transaction.

---

## Edge Cases That Must Fail

Verify the system correctly rejects invalid operations.

**Prompt 12**: Post a transaction with a value date before the account was opened. (Must fail.)

**Prompt 13**: Post a backdated transaction into a closed accounting period. (Must fail.)

**Prompt 14**: Repay more than the outstanding balance on loan X. (Must fail or return overpayment.)

---

## QA Assertions

Verify invariants and consistency across the loan portfolio.

**Prompt 15**: For every loan, assert that the sum of installments equals principal plus total interest.

**Prompt 16**: Compare loan X's current balance with what it would be if every repayment had been on time, and explain the difference.

**Prompt 17**: Turn the last scenario into a reusable regression test.

---

## Safety

Verify that the environment guard, idempotency, and audit trail work as intended.

**Prompt 18**: Restart the server with CORE_ENV=production and try to disburse a loan. (The server must refuse to start: exit 1 with a FATAL line on stderr.)

**Prompt 19**: Repeat the last repayment with the same idempotency key. (Must return the original result, no duplicate.)

**Prompt 20**: Show the audit log for everything you did in the last 10 minutes.

---

**Notes**:
- Prompts are grouped by feature area for clarity. Run them in order for dependency on prior state.
- Repayments and interest rates are illustrative; use actual values from generated scenarios.
- Audit log is your safety net: check it after each batch if something seems wrong.
- Backdating tests (Prompts 7–11) are the core value: show that recalculation is correct and order-independent.

**Expected total time**: ~30 minutes for full demo (depending on dialog depth).
