# Status

Read-only. Answer: where does the day stand, what is next on the critical path, what needs the owner?

## Collect

```bash
git status --short --branch
git log --oneline -15
git branch -a
gh issue list --limit 50 --state all 2>/dev/null
gh pr list --state all --limit 30 2>/dev/null
docker compose ps 2>/dev/null
```

Read `BACKLOG.md` for story status and `PLAN.md` for the timeline. Treat `BACKLOG.md` as truth for status; report drift when a merged PR's story is not `done` or an open issue's story is `done`.

## Present

Report **by lane**: each lane is a separate workstream (its own sessions and worktrees). Lanes and their story order are defined in `PLAN.md` § Lanes; membership comes from that table, readiness from the native blocked-by edges (`gh api graphql` on `blockedBy` for every open issue). Classify each open story as `done`, `in-review` (PR open), `in-progress` (worktree or branch exists, or backlog says so), `ready` (open, all blockers closed, nobody on it), or `gated` (an open blocker remains, name it).

At most 45 lines:

```markdown
# core-banking-mcp — Status (<local time>)

Timeline slot now: <slot from PLAN.md> · critical path: <done>/<total> · sessions active: <n> (<worktrees>)

## Lanes

| Lane | Done | In review | In progress | Ready to start | Gated (on) |
|---|---|---|---|---|---|
| D — Pure engine | | | | | |
| A — Data | | | | | |
| B — Writes | | | | | |
| C — Platform | | | | | |
| Joins | | | | | |
| QA & demo | | | | | |

**Start now (one session each):** #N CB-xx, #N CB-yy   ← every `ready` story, so the owner can dispatch them
**In flight:** <branch / worktree / PR per story, one line each, with size and review state>
**Needs the owner:** <PR reviews pending, decisions>
**Drift:** <backlog row vs issue/PR state mismatches, or "none">
**Risks:** <schedule or technical, one line each; name the lane>
```

Order "Start now" by critical-path first (PLAN.md chain), then stretch.
