---
name: git-concurrency
description: The repo working directory/index is shared across concurrent agent sessions; a commit can be silently clobbered by another session's amend.
metadata:
  type: feedback
---

Observed once (2026-07-15, branch `feature/entity-kurzname`): after `git commit` for a
scoped 3-file change (entity-picker `displayName` task), `git show --stat HEAD`
showed 9 unrelated files (dashboard/energy-flow) instead of the 3 I staged. Root
cause via `git reflog`: a concurrent sibling/parent agent session was working on an
unrelated dashboard feature ("Energiefluss-Dialog") in the SAME working directory
and ran `git commit --amend`, which replaced my just-created commit wholesale with
its own tree (same parent, different content) — my commit's SHA and content were
gone from history even though my edited files were still correct on disk.

**Why:** Multiple agents can be dispatched against the same repo/worktree at the
same time (e.g. a top-level conversation doing dashboard work while a sub-agent
handles a separate task). Git's index and refs are global mutable state with no
isolation between them — there is no per-session sandbox unless a worktree was
explicitly created for the task.

**How to apply:**
- After every commit that matters, immediately verify with `git show --stat HEAD`
  (or `git log --oneline -3` + `git show HEAD:<file>` for a content spot-check)
  that HEAD actually contains what you intended — do not trust the `git commit`
  command's own stdout summary alone if there's any chance of concurrent activity.
- If HEAD looks wrong (wrong files, wrong file count, missing your expected diff),
  don't panic-revert: check `git reflog` first to understand what happened, confirm
  your source edits are still intact on disk (Read the files), then re-stage and
  create a fresh commit on top of the current HEAD. Never force-push or reset
  without first confirming what the other session's commit contains — it's likely
  legitimate work that must be preserved.
- Prefer creating a new commit over amending in shared repos generally (already
  house style), but here the risk is the *other* session amending, which you can't
  prevent — the mitigation is the post-commit verification step above, done every
  time, not just when something looks suspicious.
- If a task instructs "leave other files untouched/unstaged," a post-commit
  `git status --short` check confirming only your intended files are gone from
  the unstaged list (and nothing else changed) is a fast sanity check.

Second occurrence (2026-07-16, branch `feature/muellabfuhr-kalender`, worktree
`muellabfuhr-kalender`): mid-task, not just around commit. After editing an
import path in `node-config-panel.component.ts` and successfully running build +
tests, a system-reminder fired claiming the file had been "modified, either by
the user or by a linter," showing my edit reverted back to the old import. A
subsequent `Read` showed the file back with my correct edit in place — net
effect was a transient flicker, not a real revert, but it also made a follow-up
`Edit` call fail with "File has been modified since read" (harness's own
staleness guard tripping on the concurrent write). Also saw `git mv` immediately
followed by `Edit` on the moved file leave `git status` showing the old path as
an *unstaged* delete and the new path as a *staged* add (i.e. not shown as `R`)
even though both halves were genuinely staged — re-running `git add <old> <new>`
right before commit fixed the rename detection display. **How to apply:** if a
file you just edited appears reverted in a tool result or system-reminder,
don't assume your edit was lost — re-`Read` it before retrying `Edit`/`Write`;
it may just be a stale snapshot from a concurrent writer. Always re-`git add`
both the old and new paths of a `git mv` right before committing if any content
edits happened on the new path afterward, so `git show --stat HEAD` reports
clean renames instead of add+delete pairs.
