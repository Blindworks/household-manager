---
name: presence-manual-refresh-frontend
description: Manual-refresh button on admin-presence page - own signals pattern, response reuse instead of reload
metadata:
  type: project
---

Built 2026-08-26 on `feature/presence-wifi` (backend already done in commit
e6b491f, see [[../springboot-dev/presence-manual-refresh]]).

`PresenceService.refresh()` is a thin `POST /api/v1/presence/refresh` with an
empty body, returning `PresenceStatusResponse` (same shape as `GET /status`).

`AdminPresenceComponent.refresh()`:
- Own `refreshing`/`refreshMessage` signals, separate from `errorMessage`
  (devices) and `usersMessage` (persons) — same reasoning as the existing
  split: a shared signal would let one action's message erase another's,
  see [[switch-confirmation-pattern]]-style guards used across this codebase.
- **No follow-up `load()`/`getStatus()` call** — the refresh response already
  carries the fresh status, so it's fed straight into `lastSeenByDeviceId`.
  Extracted the status→Map mapping (previously inlined in `loadLastSeen`)
  into a private static `lastSeenMapOf(status)` so both call sites share one
  definition — a second inlined copy would have been an easy mismatch to
  introduce later.
- Guard against double-click is a plain `if (this.refreshing()) return;` at
  the top of the method (button is also `[disabled]`) — same shape as
  `togglingDeviceId` but a boolean since there's only one such action on the
  page, not per-row.

Test gotcha confirmed again: DOM-level spec, `httpMock.expectNone(DEVICES_URL)`
after flushing the refresh response is what actually proves "no second
roundtrip happened" — asserting only the UI update would pass even if a
redundant `load()` were mistakenly added back in.

## Review round (same day, commit c08aed1 -> fix)

A real review caught four things worth remembering for future "manual action
+ hint text" buttons:

1. **Never restate a backend-configurable interval as a fixed number in a UI
   hint.** The original hint said "sofort... statt bis zu 30 Sekunden" — reads
   as "faster than 30s", but a sequential per-device probe (`active devices x
   3 ports x timeout`) can easily *exceed* 30s with several devices. Fixed by
   describing the value prop ("...statt auf den nächsten Durchlauf zu warten")
   without any duration claim, and adding "kann je nach Anzahl ein paar
   Sekunden dauern" so the wait itself isn't a surprise. `presence.poll-interval-ms`
   is the backend property this would have duplicated.
2. **A message signal needs a type/color signal, not just error styling.**
   `refreshMessage` originally always rendered with `--error`. Added
   `refreshMessageType: 'success' | 'error' | null` mirroring the existing
   `settingsMessageType` pattern exactly (same class bindings) instead of
   inventing new CSS. Success text: "Geprüft." — button un-disabling alone
   isn't enough feedback after a multi-second wait, especially when nothing
   in the table visibly changes (already-fresh or already-silent device).
3. **Accessibility on a long-running action button:** add
   `[attr.aria-busy]="refreshing()"` and `aria-describedby` pointing at the
   hint's `id`. Kept the hint as a `<span>` (it sits inline in a flex row with
   the button and message, not block content) but gave it an id for the link.
   Changed the message's `role` from `alert` to `status` since it's no longer
   error-only.
4. **A "message survives a sibling action" test must actually perform the
   sibling action.** The original 429 test only checked *where* the message
   rendered, not that a later device action (`setActive`, which calls
   `errorMessage.set(null)`) leaves it alone. A regression that merged
   `refreshMessage` back into `errorMessage` would NOT have failed that test.
   Extended it: after the 429, click "Deaktivieren", flush the PUT + reload,
   assert the refresh message is still there. This is the same class of gap
   `usersMessage`'s own test guards against — a *separate signal* claim needs
   a test that exercises the *other* signal's mutation, not just a render
   check.

General lesson: when a hint text or error message makes a claim ("this is
fast", "this is separate from X"), the test/description should encode the
actual mechanism the claim depends on, not just the visible symptom.
