# CB-04 Seed products — design note

Bounded story. Issue: [#4](https://github.com/alexandervivas/innovation-day-xebia/issues/4). Blocked-by CB-03 (closed).

## Scope

`V2__seed_products.sql` inserts exactly two `products` rows. Plus, per owner direction during
brainstorming (2026-09-25):

1. Bump the stack's Postgres from 16 to 18, to use native `uuidv7()` instead of hand-minted
   literals.
2. Introduce opaque types for the entity ids already defined in the V1 schema (`clients`,
   `products`, `accounts`, `transactions`), as the domain id vocabulary CB-05+ will reuse.

Delivered as a two-PR `gh stack`, Postgres bump first (everything else needs it).

Out of scope: demo client/account/loan/transaction seed data
(`docs/handoff/V3__seed_demo_ln0042.sql`) — no backlog story currently covers that re-keying.

## PR 1/2 — Postgres 16 → 18

Verified via `version-scout`: PG18 is GA (current patch 18.6, per postgresql.org), ships
`uuidv7([shift interval]) → uuid` natively (RFC 9562-shaped, time-ordered, not guaranteed strictly
monotonic within the same millisecond). Docker Hub tag to pin: `postgres:18.6-alpine` (matches the
existing `-alpine` convention).

Files:
- `docker-compose.yml`: `image: postgres:16-alpine` → `postgres:18.6-alpine`.
- `CLAUDE.md`: "Postgres 16" → "Postgres 18" in the Stack line.

No Scala/migration changes in this PR. `pgjdbc` 42.7.13 is not version-pinned to PG16 and needs no
bump.

**Blast radius**: `docker-compose.yml` and `CLAUDE.md` are shared across the whole project. The
`cb-06` and `cb-15a` worktrees are already checked out against Postgres 16 — they pick up 18 only
when they rebase onto `main` after this merges (per story.md's mandatory pre-PR rebase). Flagging
this, not fixing it: those sessions handle their own rebase.

## PR 2/2 — Seed products + entity id opaque types

Depends on PR 1.

### `V2__seed_products.sql`

Two `INSERT INTO products (id, name, kind, annual_rate, term_months, accrual_basis)` rows, ids from
`uuidv7()`:

- Savings: `kind='savings'`, `annual_rate=0.0150` (1.50% p.a., per
  `docs/design/artboards/scenario-builder.dc.html`), `term_months=NULL`, default accrual basis.
- 12-Month Consumer Loan: `kind='loan'`, `annual_rate=0.0800` (8.00% p.a., per issue #4 /
  CLAUDE.md), `term_months=12`, `accrual_basis='actual/365'` explicit.

Ids are not hardcoded literals (PG18's `uuidv7()` mints them at insert time); the verification test
queries by `kind`, not by id.

### `domain/Ids.scala`

Scala 3 opaque types over `java.util.UUID`, one per entity table with a UUID primary key in V1:
`ClientId`, `ProductId`, `AccountId`, `TransactionId`. Each gets `apply(UUID): X` and an
`.value: UUID` extension. Pure — no ZIO or DB imports, per CLAUDE.md rule 9. Only `ProductId` is
exercised by this story's own test; `ClientId`/`AccountId`/`TransactionId` are defined now, per
owner direction, as the shared vocabulary CB-05 (read tools) will build on — not exercised until
then.

### Test

`src/test/scala/corebanking/db/ProductSeedSpec.scala`, same raw-JDBC pattern as
`SchemaMigrationSpec` (CB-03): runs `FlywayRunner.migrate`, queries `products` by `kind`, wraps the
returned id in `ProductId`, asserts name/rate/term/accrual_basis for both rows.

## Testing (both PRs)

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
docker compose up -d   # postgres:18.6-alpine health check green
```
