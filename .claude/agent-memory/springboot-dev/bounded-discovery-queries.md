---
name: bounded-discovery-queries
description: Discovery queries over append-only history tables (find distinct devices/sensors) must be time-bounded, not full-table scans
metadata:
  type: feedback
---

When adding a "distinct devices/sensors that reported something" query against an append-only
history table (e.g. `ZigbeeMeasurement`, `AlexaAirQualityReading`), always bound it by a recent
time window (`WHERE readingTime >= :since` / `measuredAt between :from and :to`) rather than
scanning the whole table.

**Why:** In `TemperatureSeriesService.getCurrent()` (backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java)
I initially added unbounded discovery queries (`findDistinctDevicesByMeasurementType`,
`findDistinctApplianceIds`) for a "current readings" endpoint polled continuously by the tablet
dashboard. A code-quality review caught it: since the tables are append-only, the scan cost grows
unbounded over the app's lifetime, and a sensor silent for weeks isn't "current" anyway. Fixed by
introducing `CURRENT_LOOKBACK_DAYS = 7` and reusing/adding `*InRange`/`*Since` repository queries
(`findDistinctDevicesByMeasurementTypeInRange`, `findDistinctApplianceIdsSince`).

**How to apply:** Before writing any "find all X that have a Y in table Z" repository query for a
mirror/current-state or dashboard-polling use case, check whether the underlying table is
append-only history. If so, default to a bounded time window (pick something like 7 days unless a
narrower "staleness" concept already exists elsewhere, e.g. the 1h staleness threshold in the
frontend `temperature-comfort.util.ts`). Prefer reusing an existing `*InRange` query over adding a
new unbounded one from scratch.
