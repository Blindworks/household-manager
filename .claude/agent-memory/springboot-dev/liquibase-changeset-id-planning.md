---
name: liquibase-changeset-id-planning
description: implementation plans that hardcode a Liquibase changeset date-ID can go stale between planning and execution — always check the changelog directory for the actual next free ID before creating the file
metadata:
  type: feedback
---

When an implementation plan specifies an exact Liquibase changeset id/filename (e.g. `20260725-0041-create-user-management-tables.xml`), verify against the current `backend/src/main/resources/db/changelog/changes/` directory before creating the file. Plans are often written slightly before execution, and another feature branch/task can claim the same date-sequence number in the meantime.

**Why:** During the Usermanagement task (2026-07-26), the plan called for id `20260725-0041`, but that slot was already taken by `20260725-0041-create-tractive-auth-table.xml` ([[tractive-hundetracker]], merged earlier the same day). Using the plan's literal id would have collided.

**How to apply:** Before writing a new Liquibase changeset file, `ls`/`Glob` the `changes/` directory for the highest existing `YYYYMMDD-NNNN` sequence number that day and use the next free one, even if the plan text says otherwise. Note the deviation explicitly in the task report so the user can update the plan doc if needed. Don't silently overwrite or reuse a taken id.
