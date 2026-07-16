---
name: waste-collection-clock
description: WasteCollectionService's injected Clock bean must be pinned to Europe/Berlin, not systemDefaultZone() — the backend container has no TZ set
metadata:
  type: project
---

`ClockConfig` (backend/src/main/java/com/household/manager/config/ClockConfig.java) provides the
`Clock` bean used by `WasteCollectionService` (and, later, the Task 9 reminder scheduler) so that
"today"/"tomorrow" and the announcement-time comparison are testable and correct.

**The bean must use `Clock.system(ZoneId.of("Europe/Berlin"))`, never `Clock.systemDefaultZone()`.**

Why: `backend/Dockerfile` is `eclipse-temurin:21-jre` with no `TZ` env var and no
`-Duser.timezone`. `docker-compose.yml` sets `TZ: Europe/Berlin` only for the `zigbee2mqtt`
service — not for `backend`. So in production the JVM default zone is UTC, and
`systemDefaultZone()` would silently become a UTC clock. Consequences: `today()`/`tomorrow()`
flip 1-2 hours early every night, and — more seriously — Task 9's reminder scheduler compares
`LocalTime.now(clock)` against the configured announcement time (default 19:00 local); on a UTC
clock that comparison fires two hours late (21:00 Berlin instead of 19:00), drifting by an
additional hour between summer/winter time.

**Do not "fix" this by adding `TZ: Europe/Berlin` to the `backend` service in docker-compose.yml
instead.** That changes the JVM default zone for the whole app, and every other feature that
calls `LocalDateTime.now()` directly (meter readings, Tasmota/Shelly, air quality, all
`@PrePersist` timestamps) would shift by two hours relative to every already-stored row —
silently corrupting the time axis of existing charts. That's a deliberate migration decision for
the project owner, not a side effect of a Clock-bean fix. Pinning only the `Clock` bean has zero
blast radius beyond code that injects `Clock`.

**Do not make the zone configurable via a property either** — this is a German household app
(DWD weather, German waste calendar); a hardcoded zone with a comment explaining why is honest
and sufficient, a property would be speculative generality.

**How to apply:** Before trusting any `Clock`/`LocalDate.now()`/`LocalDateTime.now()` code path
in this project, check what zone the container will actually run in — don't assume
`systemDefaultZone()` matches the household's local time. Grep `backend/Dockerfile` and
`docker-compose.yml` for `TZ` rather than assuming.
