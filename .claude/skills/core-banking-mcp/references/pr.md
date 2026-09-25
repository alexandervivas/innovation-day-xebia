# PR

Address the human review on one pull request and merge it once every comment is addressed. Runs in the story's session or a fresh one: `cd` into the repository and invoke `/core-banking-mcp pr <number>`.

## Collect

```bash
gh pr view <N> --json number,title,headRefName,baseRefName,state,isDraft,mergeStateStatus,reviewDecision,reviews,url,body
gh api repos/alexandervivas/innovation-day-xebia/pulls/<N>/comments --paginate     # inline review comments (id, path, line, body, in_reply_to_id, user)
gh api repos/alexandervivas/innovation-day-xebia/issues/<N>/comments --paginate    # conversation comments
gh api graphql -f query='{ repository(owner:"alexandervivas",name:"innovation-day-xebia"){ pullRequest(number:<N>){ reviewThreads(first:100){ nodes{ id isResolved isOutdated comments(first:20){ nodes{ author{login} body path line } } } } } } }'
```

Work in the story's worktree (`git worktree list`; create one with `git worktree add ../innovation-day-xebia-cbNN <headRefName>` if it is gone). Never check the branch out in the primary checkout. Run `gh stack sync` there first; a PR is always part of a stack, possibly of one.

A **human review** is a review or comment whose author is not a bot and not this agent. Requirement: at least one human review exists. If none does, report "awaiting human review" and stop; do not merge.

## Address Every Comment

Treat every unresolved review thread and every conversation comment that asks for something as an item. For each item, in order:

1. Classify: **change request** (do it), **question** (answer it), **suggestion** (accepted by default: apply it; push back only when it would break an invariant in `CLAUDE.md`, the acceptance criteria, or the 300-line PR limit, and say why).
2. Apply changes through `implementation-worker` or `test-worker` with an explicit model, one bounded batch per file group. The parent never edits code.
3. Run the gates (`sbt -batch scalafmtCheckAll compile test`, secret scan with self-test), commit with a Conventional Commit that names the review (`fix(db): address review on #12 — ...`) on the increment's branch, then `gh stack submit` to push the whole stack. Re-measure the increment (`git diff --shortstat <base>..HEAD -- . ':!docs/superpowers/**'`); if the fix pushes it over 300 changed lines, split it with `gh stack add` before publishing.
4. Reply on the thread: what changed and the commit SHA, or the answer, or the reasoned pushback. Then resolve the thread:

   ```bash
   gh api graphql -f query='mutation($id:ID!){ resolveReviewThread(input:{threadId:$id}){ thread{ isResolved } } }' -f id=<thread id>
   ```

   Resolve a pushback thread only after the reviewer replies accepting it; otherwise leave it open and report it as pending.

When material corrections change the risk surface, rerun `risk-reviewer` (explicit `model: opus`) on the new diff before pushing.

## Merge

Merge only when **all** of these hold:

- at least one human review exists;
- every review thread is resolved (`isResolved: true`) and no conversation comment asks for something still undone;
- `reviewDecision` is not `CHANGES_REQUESTED`; when it is `APPROVED` or `REVIEW_REQUIRED` with every thread resolved, proceed (an explicit re-approval is not required after addressing comments);
- `mergeStateStatus` is `CLEAN` (or `UNSTABLE` only when the failing check is unrelated and stated in the report);
- the PR body has `Closes #<issue>`.

Stacked PRs reject `gh pr merge` and the plain `pulls/<n>/merge` endpoint ("must be merged using the asynchronous merge REST API"). Merge bottom-up:

```bash
gh api -X PUT repos/alexandervivas/innovation-day-xebia/pulls/<N>/merge-async -f merge_method=squash   # returns status: pending
gh pr view <N> --json state --jq .state          # poll until MERGED
gh stack sync                                    # restack what remains
gh pr view <next> --json mergeStateStatus        # poll until CLEAN before the next merge-async
```

Each PR in the stack needs its own human review and its own resolved threads before its merge; a review on the top PR does not cover the ones below. If a merge is refused for another reason (protected branch), report the exact error and stop; do not force.

After the top of the stack merges: confirm the issue closed, set the `BACKLOG.md` row to `done` on `main` in a one-line docs commit and push, reclaim the worktree only when `git -C <worktree> status --short` and `git -C <worktree> diff origin/main HEAD` are both empty (`git worktree remove <path> && git worktree prune`; never `--force`, never `rm -rf`), delete the stack's local branches, and name the next unblocked story.

## Report

- PR, branch, review authors and decision
- Each comment: classification, what was done, commit, thread resolved or pending
- Gates run with results
- Merged (SHA) or not merged with the exact blocking condition
- Next story
