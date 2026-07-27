---
name: optional-empty-two-reasons
description: Optional.empty() from a resolver can mean two unrelated things (no config vs. no data); collapsing both into "report nothing" silently freezes an entity when config disappears
metadata:
  type: project
---

## The bug (found in final review of feature/tractive-home-settings, fixed 2026-07-27)

`TractiveHomeResolver.resolve(snapshot, now)` returns `Optional<HomeVerdict>`, and
`Optional.empty()` was documented as "no statement possible". In practice it arose
from two very different situations:

1. **No home configured at all** — nobody set coordinates, or an admin just cleared
   them via the "Koordinaten entfernen" button.
2. **No usable position data** — the tracker reports nothing and isn't charging.

`TractiveEntityMapper.map()` treated both the same way: report no update, so the
entity state layer keeps the last value. For case 2 that is correct and deliberate
(silence at home is normal, freezing the last value is the wanted answer — see
[[tractive-hund-zuhause]]). For case 1 it is wrong: the admin deleted the *definition*
of "at home", `TractivePetService` maps the same empty Optional to `atHome = null`
which `@JsonInclude(NON_NULL)` drops from the JSON (badge/tile vanish immediately),
while the entity keeps asserting its last `on`/`off` forever. Entity and UI then
contradict each other permanently, and any flow built on the entity keeps acting on a
value nobody can see or correct.

## The general lesson

When a resolver's `Optional.empty()` (or `null`, or any single "no answer" signal)
can be produced by semantically distinct causes, and different callers already react
differently to it (mapper freezes, REST endpoint nulls-out), **check whether every
cause deserves the same reaction**. "No answer" is not automatically "do nothing" —
here, "no *definition* exists" needed an explicit, visible `unavailable` state instead
of freezing, precisely because freezing was the *right* behavior for the sibling cause
("no *data* yet"). Don't just add a boolean flag to the return type — a thin add-on
method (`isHomeConfigured()`) that answers only the one extra question the caller
needs kept the resolver's core contract (`resolve()`) and its existing tests
untouched.

## Mechanical fix applied

- Added `TractiveHomeResolver.isHomeConfigured()` — delegates to
  `settingsService.getSettings().hasHomeCoordinates()`, same check `resolve()` already
  does internally, exposed for callers that need to distinguish the empty cases.
- `TractiveEntityMapper.map()`: only call `isHomeConfigured()` when `resolve()` came
  back empty (cheap — costs at most one extra settings read per pet per cycle, only in
  the already-degenerate paths) and only then emit a synthetic `unavailable` update
  with `deviceClass: presence` (same attributes shape as the real entity, so frontend
  code doesn't need a special case).
- Left `TractivePollingService.markUnavailable()` (cloud-outage path) and
  `TractivePetService` (REST `atHome = null` on empty Optional) untouched — both are
  independently correct for their own failure mode; this fix only closes the gap in
  the entity-mapper's "freeze on empty" default.

## Regression test pattern

Build two entity-mapper instances from mocks: one with a settings service returning
coordinates (existing `setUp()`), one with `TractiveHomeSettings(null, null, ...)`.
Feed both the *same* otherwise-valid snapshot (fresh position, not charging) and
assert opposite outcomes — the unconfigured one must produce a `unavailable` update
for the home entity, the configured one must still freeze (no update) when data is
missing. Proved by verification, not just by claim: temporarily revert the mapper's
new branch back to plain `.ifPresent(updates::add)` and confirm the new
"unconfigured -> unavailable" test actually fails before restoring — this is the only
way to know the test isn't vacuously passing.

See also [[tractive-hund-zuhause]] (why silence-at-home freezes on purpose) and
[[tractive-home-resolver-fix]] (a different bug in the same resolver, about `Instant`
consistency across callers).
