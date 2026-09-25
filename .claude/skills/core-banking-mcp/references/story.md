# Story

Deliver one backlog story as one small PR. Meant to run in its own session: `cd` into the repository and invoke `/core-banking-mcp story <ref>`.

## Resolve

`<ref>` is a backlog ID (`CB-03`, `CB-15a`), an issue number (`3`, `#3`), or an issue URL. Resolve the other half:

```bash
gh issue list --state all --limit 100 --search "CB-03 in:title" --json number,title,state,url   # from a backlog ID
gh issue view 3 --json number,title,state,body,url                                                 # from a number
```

The title starts with the backlog ID. The issue body is the acceptance-criteria record; `BACKLOG.md` holds the same row and is the status record. On conflict the issue body wins and the row is corrected in the same PR.

## Qualify

1. Require the issue to be open. A closed issue is finished; report and stop.
2. Fetch native blockers:

   ```bash
   gh api graphql -f query='{ repository(owner:"alexandervivas",name:"innovation-day-xebia"){ issue(number:<N>){ blockedBy(first:20){ nodes{ number title state } } } } }'
   ```

   Any blocker whose `state` is `OPEN` means the story is **gated**: do not start it. Report the blockers and stop. The dependency graph follows `PLAN.md` (E0→E1→E2→E3→E4 chain, CB-13 gates CB-15a/15b/21/22, CB-15a gates CB-16/17/15c, CB-17 gates CB-18/19/20, CB-14 gates CB-19, CB-15c gates CB-20b, CB-19 and CB-24 gate CB-25).
3. Check `git status --short` is clean and `git branch --show-current` is `main`; run `git pull --ff-only origin main`. If a branch `cb-NN-*` already exists for this story, resume it instead of creating a new one.
4. Fix scope in two or three sentences before delegating anything. State exclusions explicitly.

## Start

1. Branch `cb-NN-<slug>` from `origin/main` (`git switch -c cb-NN-<slug> origin/main`). When another story session is active in this checkout, use a sibling worktree instead: `git worktree add ../innovation-day-xebia-cbNN -b cb-NN-<slug> origin/main`, work there, and remove it after merge.
2. Set the row's status to `in-progress` in `BACKLOG.md` (parent edit, committed with the story).
3. Post one concise issue comment naming the branch. Do not repeat it when resuming.

## Deliver

1. When the path is not obvious, spawn `code-mapper` (explicit `model: sonnet`).
2. Split the story into bounded write batches. Choose the model per the routing table in SKILL.md and pass it explicitly on every spawn; anything touching money arithmetic, the ledger's append-only paths, `system_clock`, the recalculation engine, or EOD runs on `opus`.
3. Tests before or alongside implementation: pure domain specs need no database; integration specs run against the compose Postgres (`docker compose up -d`). Order-independence and invariant stories (CB-20, CB-20b, CB-22) use `Gen`-based property tests.
4. Every new tool: returns the `{env, data}` envelope through `ToolResponse.respond`, accepts `idempotency_key` and `dry_run` if it writes, writes an `audit_log` row, reads time from `system_clock`.
5. Keep the diff within ~200 changed lines. When it will not fit, stack: finish the first increment, commit, branch the next from it, and say so in each PR body.
6. `risk-reviewer` (explicit `model: opus`) on the full diff; disposition every finding; rerun after material corrections.

## Gates And Publish

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

Commit with a Conventional Commit referencing the story and issue (`feat(db): add schema and Flyway migrations (CB-03, #3)`). Set the row to `in-review` in `BACKLOG.md` in the same commit series. Then, with the owner's push authorization for the session:

```bash
git push -u origin cb-NN-<slug>
gh pr create --title "CB-NN <story> (#N)" --body-file <body>   # body: "Closes #N", acceptance criteria with evidence, model per batch, stack position if stacked
```

Merging the PR and closing the issue stay the owner's call. After the merge: `BACKLOG.md` row to `done` (in the next story's PR or a tiny follow-up commit on main), delete the branch, remove the worktree if one was used.

Report per the Completion Report and name the next story: the lowest open issue whose blockers are all closed.
