-- V2__seed_products.sql
-- CB-04: seed the two demo banking products. Ids are minted by Postgres 18's native uuidv7(),
-- not hardcoded literals — tests look these rows up by `kind`, not by id.

INSERT INTO products (id, name, kind, annual_rate, term_months, accrual_basis)
VALUES (uuidv7(), 'Savings', 'savings', 0.0150, NULL, 'actual/365');

INSERT INTO products (id, name, kind, annual_rate, term_months, accrual_basis)
VALUES (uuidv7(), '12-Month Consumer Loan', 'loan', 0.0800, 12, 'actual/365');
