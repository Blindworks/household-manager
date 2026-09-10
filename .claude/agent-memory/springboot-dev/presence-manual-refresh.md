---
name: presence-manual-refresh
description: PresencePollingService.refreshNow() manual trigger for the presence module - shared runCycle(), AtomicBoolean guard, ADMIN-only endpoint
metadata:
  type: project
---

Built 2026-08-26 on `feature/presence-wifi` (commit e6b491f), backend only -
frontend button is a separate task.

**`PresencePollingService.refreshNow()`**: extracted the body of `poll()`
(everything after loading the device list) into a private `runCycle(List<PresenceDevice>)`,
called by both `poll()` and `refreshNow()` - guarantees a manual refresh can
never diverge from the scheduled one. Differs from `poll()` in two ways:
- DB load failure is NOT swallowed - wrapped as `IllegalStateException` (house
  style for "unavailable/misconfigured", matches `TractivePollingService.refreshNow`
  handling) and propagates (mapped to 400 by the existing `GlobalExceptionHandler`
  handler).
- Overlapping manual calls are rejected via an `AtomicBoolean` reserved with
  `compareAndSet` BEFORE the work starts and released in a `finally` (pattern:
  `NetworkSpeedtestService.reserveManualSlot`). Violation throws
  `TooManyRequestsException` (429) - reused as-is rather than duplicating a
  presence-local type. Overlap with the *scheduled* poll is bewusst
  ungeschuetzt on the `PresenceMonitor` side (atomic via `compute`), but the
  reporting half (`entityStateService.reportState`) is NOT ordered between
  two concurrent cycles - see [[presence-wifi-review-fixes]] for the
  follow-up review that named this cost precisely and moved the exception
  out of `network`.

**`POST /v1/presence/refresh`**: thin controller method, calls `refreshNow()`
then returns the FRESH `PresenceStatusService.getStatus()` in the same
round-trip. ADMIN-only, method-specific matcher next to the `/v1/presence/devices`
ones in `SecurityConfig` - deliberately NOT in the KIOSK-POST whitelist (unlike
the Tractive refresh / network speedtest) because it lives only on the admin
page and can occupy a request thread for several seconds (sequential probing:
active devices x 3 ports x timeout, default 2 s -> 6 s per silent device).

See [[mockito-restub-after-thenthrow]] for a Mockito gotcha hit while writing
the "flag released after a failed refresh" test.
