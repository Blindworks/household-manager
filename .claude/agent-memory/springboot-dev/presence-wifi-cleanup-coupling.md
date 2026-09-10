---
name: presence-wifi-cleanup-coupling
description: Anwesenheitserkennung (feature/presence-wifi) review-fix round 2026-08-26 — cleanup-coupling and aggregate-freeze patterns worth reusing elsewhere
metadata:
  type: project
---

Fixed in commit 3908160 on `feature/presence-wifi`, in response to two code
reviews. Two patterns here are general enough to reapply elsewhere in this
codebase, not just presence-specific:

**Pattern: a cleanup/compensation step must live OUTSIDE the try/catch of the
step it compensates for.** `PresencePollingService.cleanupOrphanedEntities`
(renamed from `cleanupOrphanedPersons`) used to be the last statement inside
`evaluateAndReport`, itself wrapped in one try/catch inside `poll()`. If
`evaluator.evaluate` threw for any person, the whole method aborted and the
cleanup never ran — so a frozen orphan entity stayed frozen for the entire
duration of an unrelated evaluation error. Fix: moved the cleanup call into
`poll()` as its own statement, after `evaluateAndReport`, with its own
try/catch, deriving input (userIds) from data already loaded earlier in
`poll()` rather than from inside the fallible method. **Why this matters
generally:** watch for this same coupling anywhere a "notice missing rows /
report unavailable" step sits at the tail of a method that can throw for
unrelated reasons (see also `TractiveHomeResolver`,
`ZigbeeAvailabilityWatchdog` — same "single source of truth" style, but check
whether their cleanup/fallback steps are similarly trapped).

**Pattern: when a person-level orphan-cleanup exists, check whether the
aggregate built FROM those persons has the identical freeze hole.**
`binary_sensor.presence_household` friezes forever on its last value if the
device table empties out completely, because `evaluateAndReport`'s `byUser`
map is then empty, `states` is empty, and
`PresenceEvaluator.aggregateState` returns `Optional.empty()` — nothing ever
calls `reportHouseholdState`. The fix folded aggregate-handling into the same
`cleanupOrphanedEntities` loop: the household entity (sourceRef `"household"`,
not parseable as a userId) is marked `unavailable` only when (a) it already
exists in the entity-state layer, and (b) `userIdsWithDeviceRows` is
completely empty (not just empty for one person). Mutation-blind test trap
here: a test asserting only `never()).reportState(any())` for the
"household untouched" case would stay green even if someone deletes the
`userId == null` guard and the household branch NPEs into the cleanup's own
catch block — fixed by always mixing in a real orphan person alongside the
household entity in that test so the orphan's expected report proves the
guard didn't silently eat the whole loop.

**Security matcher precedent, deviation is intentional and documented in the
comment:** `GET /v1/presence/devices` is ADMIN, unlike the `/v1/network/devices`
precedent where GET is KIOSK-readable (because the wall tablet's network view
needs the list). Presence device rows carry household members' phone IPs and
no tablet view reads them (`GET /status` is what the dashboard tile uses), so
the precedent doesn't transfer — don't copy the network-devices GET-is-open
pattern by default; check whether a tablet view actually needs read access
before deciding a device-list GET is KIOSK/MEMBER instead of ADMIN.

See also [[haushaltskalender]] and the SecurityConfig matcher-ordering notes
in CLAUDE.md for the broader "methodenspezifisch vs. methodenlos" convention
this branch follows.
