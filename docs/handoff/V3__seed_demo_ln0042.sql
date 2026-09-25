-- V3__seed_demo_ln0042.sql
-- Demo case used across the QA Console designs and RecalculationSpec.
--
-- Story: €5,000 consumer loan, 12 months, 8.00% p.a., disbursed 2026-08-01.
-- September paid on time, October missed, late fee charged 2026-10-08,
-- November paid on 2026-11-01. System date is 2026-11-14.
-- The demo then posts a backdated repayment (TX-1010, value 2026-09-30)
-- through the MCP tools — it is deliberately NOT seeded here.
--
-- Only USER events are seeded. System events (daily accruals, the late fee,
-- arrears status) are derived by the EOD job / replay engine. TX-1003 (late fee)
-- is seeded as the charge the EOD job would have booked, so the "before" state
-- is inspectable without running EOD first; replay must reproduce it exactly.
--
-- Column names follow the digest schema (CB-03). If V1 uses different names,
-- adapt this file to V1, not the other way round.

INSERT INTO clients (id, display_name, opened_on)
VALUES ('C-0017', 'Demo Client 0017', '2026-07-15');

INSERT INTO accounts (id, client_id, product_id, kind, opened_on)
VALUES ('LN-0042', 'C-0017', 'consumer-loan-12m', 'loan', '2026-08-01');

INSERT INTO loans (account_id, principal, annual_rate, term_months,
                   disbursement_date, installment_amount, grace_days, late_fee)
VALUES ('LN-0042', 5000.00, 0.0800, 12,
        '2026-08-01', 434.94, 7, 15.00);

-- Annuity schedule: due on the 1st of each month, 434.94 each.
-- The last installment is settled at payoff (actual/365 accrual makes it drift).
INSERT INTO installments (account_id, seq, due_date, amount_due)
SELECT 'LN-0042', n, (DATE '2026-08-01' + make_interval(months => n))::date, 434.94
FROM generate_series(1, 12) AS n;

INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key)
VALUES
  ('TX-1001', 'LN-0042', 'disbursement', 5000.00, '2026-08-01', '2026-08-01', NULL, 'seed-tx-1001'),
  ('TX-1002', 'LN-0042', 'repayment',     434.94, '2026-09-01', '2026-09-01', NULL, 'seed-tx-1002'),
  ('TX-1003', 'LN-0042', 'late_fee',       15.00, '2026-10-08', '2026-10-08', NULL, 'seed-tx-1003'),
  ('TX-1004', 'LN-0042', 'repayment',     434.94, '2026-11-01', '2026-11-01', NULL, 'seed-tx-1004');

-- All periods open, so backdating to 2026-09-30 is allowed.
-- (RecalculationSpec covers the closed-period rejection separately.)
INSERT INTO accounting_periods (start_date, end_date, closed)
VALUES ('2026-08-01', '2026-08-31', false),
       ('2026-09-01', '2026-09-30', false),
       ('2026-10-01', '2026-10-31', false),
       ('2026-11-01', '2026-11-30', false);

-- Simulated clock (single-row table).
UPDATE system_clock SET current_date_value = '2026-11-14';
