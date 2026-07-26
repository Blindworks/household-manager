---
name: tractive-home-resolver-fix
description: Bug pattern - a "single source of truth" resolver called by two paths at different Instant.now() moments silently diverges; fix by threading one Instant through both callers
metadata:
  type: project
---

## The bug (found in final review of feature/tractive-hund-zuhause, fixed 2026-07-26)

`TractiveHomeResolver.resolve(snapshot, Instant now)` was documented as the single
definition of "is the dog at home", called from both `TractiveEntityMapper.map()`
(entity/flow path) and `TractivePetService.listPets()` (REST/dashboard path). The
"single definition" claim was **false** because each caller passed its own
`Instant.now()`:

- The entity path only re-evaluates on a successful poll cycle.
- The REST path re-evaluates on every HTTP request — including while the poller is
  failing (e.g. expired cloud token) and the cached snapshot is frozen.

A snapshot that was "fresh" at poll time could cross a staleness threshold
(`powered-off-after-minutes`) purely because wall-clock time moved on while the REST
path kept re-resolving it with a new `now`. Result: entity said `off` forever (frozen
after the poll started failing), REST/dashboard said `on` (same frozen snapshot judged
stale-but-near-home instead). Divergence was silent and directional toward the
"reassuring" answer — the worst direction for a security-relevant presence signal.

## The general lesson

**A resolver taking `Instant now` as a parameter is not automatically consistent
across callers just because it's the same class/method.** If two callers can invoke
it at different wall-clock moments against the *same* cached/frozen input, the
"single source of truth" guarantee is fiction. The fix must make the two `now`
values identical **by construction**, not by coincidence:

- Compute exactly one `Instant` per poll/evaluation cycle.
- Thread it through every mapper/resolver call within that cycle.
- Store it (e.g. `volatile Instant lastPolledAt`) and have every other caller (REST
  endpoints, exports, etc.) read *that* stored instant instead of calling
  `Instant.now()` itself.
- If the stored instant is `null` (nothing succeeded yet), the caller must return an
  empty/absent result rather than defaulting to real time.

When reviewing any "single definition" / "one place decides X" claim in this codebase
(see also [[entitystate-facade]], [[flowengine-stage3a]]), check whether every caller
resolves against the *same* input+time pair, not just the same code.

## Mechanical fix applied

- `TractiveEntityMapper.map(snapshot)` → `map(snapshot, Instant now)`, caller-supplied.
- `TractivePollingService.poll()`: one `Instant now = Instant.now()` per successful
  cycle, passed to every `mapper.map(snapshot, now)`, stored as `lastPolledAt` in the
  same place `lastSnapshots` is assigned (only on success, never cleared on failure —
  that's the whole point).
- `TractivePetService.listPets()`: reads `pollingService.lastPolledAt()` instead of
  calling `Instant.now()`; returns `List.of()` if null (no poll ever succeeded).
- Bonus: a WARN log for clock-skew detection inside the resolver now fires on every
  REST call too (not just once per poll cycle) once the resolver is on the hot path of
  a per-request caller — guard such warnings with their own `AtomicBoolean` (don't
  share one flag between two unrelated warnings), same pattern as the existing
  missing-home-coordinates warning.

## Regression test pattern

To prove two time-dependent callers agree, build a snapshot whose report timestamp is
fresh relative to the *stored* poll instant but stale relative to real `Instant.now()`
(i.e. push the poll instant far enough into the past, e.g. `Instant.now().minus(65,
MINUTES)`, and put the report ~5 min before that). Assert the caller-under-test
returns the answer the "fresh" rule would give, not the "stale" one — that only holds
if it's actually using the stored instant.
