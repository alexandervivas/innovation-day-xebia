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

   Any blocker whose `state` is `OPEN` means the story is **gated**: do not start it. Report the blockers and stop. The dependency graph is the lane table in `PLAN.md` § Lanes; GitHub's edges are the executable truth.
3. Check `git status --short` is clean and `git branch --show-current` is `main`; run `git pull --ff-only origin main`. If a branch `cb-NN-*` already exists for this story, resume it instead of creating a new one.
4. Fix scope in two or three sentences before delegating anything. State exclusions explicitly.

## Start

1. Isolation is mandatory: invoke `superpowers:using-git-worktrees` to create the story workspace as a sibling worktree on branch `cb-NN-<slug>` from `origin/main` (`git worktree add ../innovation-day-xebia-cbNN -b cb-NN-<slug> origin/main`). Never implement in the primary checkout; it stays on `main`. Lanes run in parallel, so other stories will land on `main` while this one is open: before opening the PR, `git fetch origin && git rebase origin/main`, resolve any conflict in the branch (shared files such as `Server.scala` and `build.sbt` are expected to conflict), rerun the gates, and push. Conflicts are normal SDLC work for this session, never a reason to stop.
2. Set the row's status to `in-progress` in `BACKLOG.md` (parent edit, committed with the story).
3. Post one concise issue comment naming the branch and worktree. Do not repeat it when resuming.

## Deliver — Superpowers SDD, No Exceptions

Every story session runs the superpowers pipeline in this order. Skipping or reordering a step is a process violation; announce each skill as you invoke it.

1. **`superpowers:brainstorming`** — classify the story (most CB stories are *bounded*; CB-13, CB-15a, CB-16/17 are *full*), ask only the questions that matter, and write the outcome to a file: a full spec at `docs/superpowers/specs/YYYY-MM-DD-cb-NN-<slug>.md`, or for a bounded story a short design note at the same path. The issue body and `CLAUDE.md` invariants are the requirements; `docs/handoff/RecalculationSpec.scala` is the binding spec for CB-15a. Then open the **Review Surface** (below) and wait for the owner's approval.
2. **`superpowers:writing-plans`** — write the plan to `docs/superpowers/plans/YYYY-MM-DD-cb-NN-<slug>.md`: bite-sized TDD tasks, exact files, test commands. Each task names its model per the SKILL.md routing table. Then open the **Review Surface** again (plan plus spec) and wait for the owner's approval before any implementation subagent is dispatched. Specs and plans are committed with the story and do not count toward the ~200-line budget.
3. **`superpowers:subagent-driven-development`** — execute the plan in this session: a fresh implementer subagent per task, a task review after each, a whole-branch review at the end. Map the roles to this repository's profiles, always with an explicit `model`:
   - implementer → `implementation-worker` (or `test-worker` for test-only tasks); each task follows `superpowers:test-driven-development` (failing test first).
   - task reviewer → `risk-reviewer` on `sonnet` for small mechanical diffs, `opus` for money, ledger, clock, engine, or idempotency diffs.
   - final whole-branch reviewer → `risk-reviewer` on `opus`.
   Keep the SDD ledger under `.superpowers/sdd/<plan>/` (git-ignored). Rulings go in the ledger; the four stop conditions (destructive op, security-sensitive action, push/merge/publish, unrecoverable plan) still apply and the push is covered by the standing authorization below.
4. **`superpowers:verification-before-completion`** — run the gates below yourself and read the output before claiming anything is done.
5. **`superpowers:finishing-a-development-branch`** — the only allowed outcome is *open a pull request*; never merge from the story session and never discard the branch.

### Review Surface — mandatory before every approval request

The owner reviews specs and plans in VS Code, not in chat. Whenever a spec, design note, or plan is ready for review before coding, and again whenever it is revised after the owner asks for changes:

1. Open a fresh VS Code window containing exactly the files to review, with real absolute paths (non-interactive, returns immediately):

   ```bash
   code -n <abs worktree>/docs/superpowers/specs/<date>-cb-NN-<slug>.md \
        <abs worktree>/docs/superpowers/plans/<date>-cb-NN-<slug>.md   # only files that exist at this gate
   ```

   Include every artifact of the gate (spec or design note at the brainstorming gate; plan and spec at the planning gate; any handoff file the spec depends on, such as `docs/handoff/RecalculationSpec.scala` for CB-15a). Nothing else goes in that window.
2. State in the approval request that the window is open, list the same absolute paths, and give the fallback for when `code` is unavailable or the owner is remote: `cat` of each path.
3. Ask for one of two answers: **approve** or **changes**. On changes, revise the files, reopen the window with `code -n` on the revised files, and ask again. Do not proceed on silence, on a partial answer, or on a description of the files instead of the files.

An approval request without an open window listing the paths is invalid; treat it as not having been made.

Story-specific rules that hold inside every task:

- Every new tool returns the `{env, data}` envelope through `ToolResponse.respond`, accepts `idempotency_key` and `dry_run` if it writes, writes an `audit_log` row, reads time from `system_clock`.
- Pure domain and engine code has no ZIO or DB imports; order-independence and invariant stories (CB-20, CB-20b, CB-22) use `Gen`-based property tests.
- Keep the diff within ~200 changed lines (plans and ledgers excluded). When it will not fit, stack: finish the first increment, commit, branch the next from it, and say so in each PR body.

## Gates And Publish

```bash
sbt -batch scalafmtCheckAll compile test
scripts/secret-scan.sh --self-test && scripts/secret-scan.sh
```

Commit with a Conventional Commit referencing the story and issue (`feat(db): add schema and Flyway migrations (CB-03, #3)`). Set the row to `in-review` in `BACKLOG.md` in the same commit series. Push and open the PR without asking (standing authorization):

```bash
git push -u origin cb-NN-<slug>
gh pr create --title "CB-NN <story> (#N)" --body-file <body>   # body: "Closes #N", acceptance criteria with evidence, model per batch, stack position if stacked
```

Remove nothing: the worktree stays until the merge session reclaims it. Then stop: the PR waits for a human review. Never merge an unreviewed PR. When the review arrives, the owner (or the next session) runs `/core-banking-mcp pr <number>`, which addresses every comment and merges once all are addressed (see [pr.md](pr.md)). After the merge: `BACKLOG.md` row to `done` (in the merge session, committed to `main` as a one-line docs commit), delete the branch, remove the worktree if one was used.

Report per the Completion Report and name the next story: the lowest open issue whose blockers are all closed.
