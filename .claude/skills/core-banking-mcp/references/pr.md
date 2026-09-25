# PR

Address the human review on one pull request and merge it once every comment is addressed. Runs in the story's session or a fresh one: `cd` into the repository and invoke `/core-banking-mcp pr <number>`.

## Collect

```bash
gh pr view <N> --json number,title,headRefName,baseRefName,state,isDraft,mergeStateStatus,reviewDecision,reviews,url,body
gh api repos/alexandervivas/innovation-day-xebia/pulls/<N>/comments --paginate     # inline review comments (id, path, line, body, in_reply_to_id, user)
gh api repos/alexandervivas/innovation-day-xebia/issues/<N>/comments --paginate    # conversation comments
gh api graphql -f query='{ repository(owner:"alexandervivas",name:"innovation-day-xebia"){ pullRequest(number:<N>){ reviewThreads(first:100){ nodes{ id isResolved isOutdated comments(first:20){ nodes{ author{login} body path line } } } } } } }'
```

Check out the PR branch (`gh pr checkout <N>`, or the story worktree) and `git pull --ff-only`.

A **human review** is a review or comment whose author is not a bot and not this agent. Requirement: at least one human review exists. If none does, report "awaiting human review" and stop; do not merge.

## Address Every Comment

Treat every unresolved review thread and every conversation comment that asks for something as an item. For each item, in order:

1. Classify: **change request** (do it), **question** (answer it), **suggestion** (accepted by default: apply it; push back only when it would break an invariant in `CLAUDE.md`, the acceptance criteria, or the ~200-line budget, and say why).
2. Apply changes through `implementation-worker` or `test-worker` with an explicit model, one bounded batch per file group. The parent never edits code.
3. Run the gates (`sbt -batch scalafmtCheckAll compile test`, secret scan with self-test), commit with a Conventional Commit that names the review (`fix(db): address review on #12 — ...`), push.
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

```bash
gh pr merge <N> --squash --delete-branch
```

If merging is refused (protected branch, stacked PR), report the exact error and stop; do not force. For stacked PRs merge bottom-up and restack (`gh stack sync`) between merges.

After the merge: confirm the issue closed, set the `BACKLOG.md` row to `done` on `main` in a one-line docs commit and push, remove the worktree if one was used, and name the next unblocked story.

## Report

- PR, branch, review authors and decision
- Each comment: classification, what was done, commit, thread resolved or pending
- Gates run with results
- Merged (SHA) or not merged with the exact blocking condition
- Next story
