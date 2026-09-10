---
name: test-environment
description: How to run a single Angular spec file locally on this Windows dev machine and what to expect
metadata:
  type: project
---

Karma + a real (non-headless) Chrome launcher works fine locally on this Windows 11 machine — no
need to fall back to "tests can't run here" assumptions from other environments.

Command (from `frontend/`):
`npm test -- --watch=false --include='**/<name>.spec.ts'`

This runs `ng test` with a filtered include glob, launches Chrome, and exits after one run
(non-watch mode). A missing-module RED failure shows as a webpack "Module not found" + TS2307
compile error before Karma even starts the browser; a GREEN run shows
`Chrome ### (Windows ##): Executed N of N SUCCESS`.

**Why:** Confirmed working during the alexa.service TDD task (2026-07-08) — RED run correctly
failed to resolve `./alexa.service` before the file existed, GREEN run showed `3 SUCCESS` after
implementing it. Full run takes well under 2 minutes.

**How to apply:** Don't skip actually running the tests and defaulting to `DONE_WITH_CONCERNS` /
`tsc --noEmit`-only verification for this project — real Karma runs are cheap and available here.
