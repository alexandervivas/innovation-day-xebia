# Handoff Spec: QA Console (CB-26)

> For the agent working in this repo. This folder is a **visual spec**, not production code.
> Read this file first, then open the artboards for exact layout and copy.
> **Do not start building.** Propose a plan (see "Your first task") and wait for approval.

## Overview

A web console for QA/integration engineers using the Core Banking MCP. It shows what the
MCP tools do, especially **backdated transactions**, which are hard to reason about in text.
All UI copy is in English. The audience is Xebia colleagues at the Innovation Day demo.

Four screens, all telling one story: loan **LN-0042**, seeded by
`V3__seed_demo_ln0042.sql`. System date is 2026-11-14 and the October installment is unpaid. A backdated
repayment (TX-1010, value date 2026-09-30) is previewed, committed and audited.
The figures in the artboards match `RecalculationSpec.scala`. Treat that test as the source
of truth if a figure seems to differ.

## Files

| File | Screen | Interactive parts in the mock |
|------|--------|-------------------------------|
| `artboards/timeline.dc.html` | Timeline (hero screen) | Full replay / Incremental toggle |
| `artboards/recalculation-diff.dc.html` | Recalculation Diff | Static |
| `artboards/scenario-builder.dc.html` | Scenario Builder | Distribution inputs update bar + JSON |
| `artboards/audit-log.dc.html` | Audit Log | Row selection updates the detail panel |

**How to read the artboards.** They are HTML with inline styles, written in a design-tool format:
- `{{name}}` is a template hole filled from the `renderVals()` method in the `<script type="text/x-dc">` block at the bottom.
- `<sc-for>` is a loop and `<sc-if>` is a conditional.
- `class Component extends DCLogic` holds local state and handlers.

Take the layout, spacing, colors, copy and behavior from them. Re-implement them in the
chosen stack; do not ship the `.dc.html` files or their runtime.

## Recommended architecture (propose, do not assume)

- **Backend:** add JSON HTTP endpoints (zio-http) to the existing Scala app. Both the MCP tools
  and the endpoints must call the **same service layer**, so the console shows exactly what
  the MCP does. Never have the HTTP layer call MCP tools.
- **Frontend:** a small React + Vite + TypeScript app in `console/`. Plain CSS with the
  tokens below as CSS custom properties; no UI kit is needed.
- **Guard:** the environment guard applies to HTTP endpoints exactly as to MCP tools.
- **Audit:** every endpoint call is written to the audit log, with the channel recorded (`mcp` or `http`).

Suggested endpoints (map 1:1 to existing tools):

| Endpoint | Backing service / tool | Screen |
|----------|------------------------|--------|
| `GET /api/env` | env + system_clock | all (badge, system date) |
| `GET /api/loans/{id}` | get_client, get_loan_schedule | Timeline |
| `GET /api/loans/{id}/transactions` | get_transactions | Timeline |
| `POST /api/clock/advance` | advance_date | all (header button) |
| `POST /api/backdating/preview` | preview_backdated_transaction | Diff |
| `POST /api/backdating/commit` | post_backdated_transaction | Diff |
| `GET /api/backdating/{txId}/explanation` | explain_recalculation | Diff |
| `POST /api/scenarios` (`dry_run` flag) | generate_scenario | Scenario Builder |
| `GET /api/audit?tool=&result=&q=` | get_audit_log | Audit Log |

## Design tokens

| Token | Value | Usage |
|-------|-------|-------|
| `--color-bg` | #F3F1EC | Page background |
| `--color-surface` | #FFFFFF | Cards |
| `--color-surface-muted` | #F8F7F3 | State-change rows, read-only inputs |
| `--color-border` | #DDD8CE | Card borders |
| `--color-border-input` | #C9C4B8 | Inputs, secondary buttons |
| `--color-divider` | #ECE9E2 | Table row separators |
| `--color-ink` | #17191E | Text, primary buttons, sidebar |
| `--color-ink-2` | #3C3F46 | Table headers, secondary text |
| `--color-muted` | #5B5F68 | Captions, "before" values |
| `--color-sidebar-raised` | #2A2D35 | Active nav item |
| `--color-accent` | #1F4FB8 | Backdated, links, selection, focus |
| `--color-accent-text` | #1B4299 | Accent text on tinted backgrounds |
| `--color-accent-tint` | #DCE6F8 / #EEF2FB | Backdated chip / highlighted row |
| `--color-reversal` | #B4530A (text #8A3F06, tint #FBE6D4) | Reversals, arrears |
| `--color-success` | #1E6B45 (tint #DCEFE3) | Current, committed |
| `--color-danger` | #8E2019 (tint #F6D5CF) | Blocked, severely late |
| `--color-env` | text #7A4205 on #FDE9C4 | Environment badge |
| `--font-sans` | IBM Plex Sans 400/500/600 | All UI text |
| `--font-mono` | IBM Plex Mono 400/600 | Amounts, IDs, dates, JSON |
| `--radius-card` | 12px | Cards |
| `--radius-control` | 8px | Inputs, buttons |
| `--radius-chip` | 999px | Status chips |
| `--space-page` | 28px 32px | Main padding |
| `--space-section` | 20px | Gap between cards |
| `--control-height` | 44px | Buttons, inputs (touch target minimum) |

Type scale: page title 26/600, card title 16/600, body 13–14/400, captions 12/400.

## Shared layout

- **Sidebar:** fixed at 240px, background `--color-ink`, with the product name, 4 nav links and an environment card at the bottom (env, system date, Postgres status).
- **Page header:** title and subtitle on the left. On the right, the env badge (`ENV: MOCK · Production writes blocked`, always visible), the system date and the Advance date button (Timeline only).
- **Desktop-first:** designed at 1440×960. Below 1100px the sidebar collapses to icons. Mobile is out of scope for the demo.

## Screens

### Timeline
- **Loan context bar:** loan ID, client, product, principal and rate, and a status chip. When status changed after a backdating, also show "was In arrears".
- **Strategy toggle:** Full replay (default) / Incremental, as buttons with `aria-pressed`. When Incremental is selected and there is no snapshot before the earliest value date, show the fallback note in reversal color.
- **Chart:** two horizontal lanes, booking date on top and value date below. Each transaction is a line between its two dates. Styles:
  - Original: ink, solid.
  - Backdated: accent, thick, with arrow.
  - Reversal: reversal color, dashed.
  - Reposted: accent, thin, hollow dot.
  - Voided original: grey, dotted.

  The recalculated window (earliest affected value date → system date) is shaded with a 8% accent tint, and the system date is a dashed vertical line. Build it as SVG; no chart library is needed.
- **Transactions table:** ID, type, amount, booking date, value date, lag (value − booking, in days), state chip. The backdated row is highlighted. Reversed amounts are struck through.

### Recalculation Diff
- **Summary card** (accent border): "PREVIEW · NOT COMMITTED", transaction, dates, strategy. Buttons: Discard (secondary) and Commit backdated transaction (primary).
- **Totals as of system date:** principal, unpaid accrued interest, late fees, days past due and status, each with before, after and change.
- **State changes:** old value struck through, then → new value.
- **Reversal & repost chain:** a numbered list in execution order, plus the reallocation table for reposted repayments.
- **Explanation:** text from `explain_recalculation`, plus the strategy fallback note.

### Scenario Builder
- **Form:** name, product, clients, principal, start date, and status distribution (3 number inputs + stacked bar). Warn inline when the sum ≠ 100. It also has edge-case checkboxes, a dry run checkbox (default on) and a read-only idempotency key.
- **Preview table:** the first rows of the generated portfolio.
- **"Equivalent MCP call":** live JSON of the `generate_scenario` call, with a Copy button. This panel teaches colleagues that the console and the MCP are the same thing, so keep it.

### Audit Log
- **Filters:** search, tool group, result, and Export JSON.
- **Table:** rows are buttons, and selecting one fills the detail panel with the pretty-printed request and response JSON.
- **Result chips:** Committed, Previewed, Blocked, Replayed and Read.

## States and interactions

| Element | State | Behavior |
|---------|-------|----------|
| Primary / secondary button | Hover | Primary: lift to #2A2D35; secondary: bg `--color-surface-muted` |
| Any button | Focus | 2px `--color-accent` outline, 2px offset |
| Any mutating button | Loading | Disabled + inline spinner, label kept |
| Commit backdated transaction | Error from guard/validation | Keep the preview and show the error inline above the buttons (e.g. "Period 2026-09 is closed") |
| Commit backdated transaction | Success | Navigate to Timeline, highlight the new rows |
| Advance date | Click | Small dialog asking for the number of days (1–365); refresh everything afterwards |
| Scenario Generate | Dry run on | Label reads "Preview"; nothing is persisted |
| Audit row | Selected | Accent border + `--color-accent-tint` background |

## Edge cases

- **Empty:** no transactions → "No transactions yet. Disburse a loan or generate a scenario." No audit rows → "No tool calls yet."
- **Loading:** skeleton rows in tables and a grey placeholder in the chart area.
- **Long text:** summaries truncate with an ellipsis and show the full text in a `title` attribute. Never truncate IDs or amounts.
- **Many transactions:** the chart clusters same-day bookings, and the table paginates at 50.
- **Money:** always a decimal string from the API; never parse amounts to floats in the frontend.

## Accessibility

- Real `<button>`, `<a>`, `<input>` + `<label>` everywhere; no clickable divs.
- The chart has an `aria-label` summarizing the story, and the table carries the same data for screen readers.
- Colors differ in lightness as well as hue, and chips always carry a text label.
- Focus order: sidebar → header actions → main content top to bottom.

## How this fits the existing backlog

- **CB-24** (Claude Design mockups) is delivered by this folder. Commit `` and close CB-24.
- **CB-26** (web console, stretch) is implemented from this spec. It stays an umbrella story.
- The critical path in BACKLOG.md (CB-01 → CB-13, CB-15 → CB-17, CB-19, CB-25) always comes first.
  Do not start console work until CB-17 is done, unless I say otherwise.

## Your first task

1. Read this spec and open the four artboards. Do not write console code yet.
2. When CB-17 is done (or when I ask), propose in chat:
   - the stack (confirm or challenge the recommendation above);
   - sub-stories under CB-26, e.g. CB-26a API endpoints, CB-26b app shell + tokens, CB-26c Timeline,
     CB-26d Diff, CB-26e Audit Log, CB-26f Scenario Builder, each a PR of max ~200 lines;
   - which backend stories each sub-story depends on.
3. Wait for approval. Then create the approved sub-stories as GitHub issues linked to CB-26,
   add them to BACKLOG.md and start with the first one only.
4. The Timeline + Diff pair is what the demo needs. The other two screens are stretch.
