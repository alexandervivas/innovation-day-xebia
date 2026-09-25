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

At most 30 lines:

```markdown
# core-banking-mcp — Status (<local time>)

Timeline slot now: <slot from PLAN.md> · critical path: <done>/<total>

| Epic | Done | In flight | Next |
|---|---|---|---|

**Open PRs:** <links or none> · **Stack:** <docker compose state>
**Needs the owner:** <push/PR/issue/merge decisions pending>
**Next story:** CB-NN — <title> (<why it is next>)
**Risks:** <schedule or technical, one line each>
```
