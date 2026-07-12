---
name: entitystate-facade
description: EntityState mirror-layer architecture (entitystate package) — facade/writer split, upsert semantics, event timing, used by a 15-task rollout
metadata:
  type: project
---

New generic "entity state" mirror layer (`com.household.manager.entitystate`) is being
built across a 15-task plan (Task 1 = enums/entity/repository/Liquibase, Task 2 =
`EntityIds` utility, Task 3 = facade/writer/event — done as of commit `b6d0c4b`).
Tasks 4-12 wire every existing integration (SmartDevice/Kasa/Tapo, Zigbee, Tasmota,
Shelly, Airrohr, DWD weather, AnkerSolix) to call `EntityStateService.reportState(...)`
on every state observation; Task 13-14 add a frontend page; Task 15 is final review.

## Why two classes (EntityStateService + EntityStateWriter)
- `EntityStateService.reportState(EntityStateUpdate)` is the **only** entry point
  integrations call. It is deliberately **not** `@Transactional` and **catches every
  exception** (both from the writer and from event publishing) — a mirror-write must
  never break the calling integration's real work (e.g. `SmartDeviceService.turnOn`
  actually switching a device).
- `EntityStateWriter.upsert(...)` does the actual JPA read-modify-write and is
  `@Transactional(propagation = REQUIRES_NEW)`. REQUIRES_NEW is required because Spring's
  proxy-based `@Transactional` doesn't apply on self-invocation (facade calling its own
  `@Transactional` method wouldn't open a new tx), and because a `REQUIRES_NEW` failure
  is isolated from whatever transaction the calling integration might itself be in.
- `EntityStateChangedEvent` is published by the **facade**, not the writer, and only
  *after* `writer.upsert(...)` returns (i.e. after commit) — so `@EventListener`
  consumers (planned rule engine) always see committed state.

## Upsert / change-detection semantics (in EntityStateWriter)
- Unknown `entityId` → auto-register (upsert, not insert-or-fail). New rows start
  `state = "unknown"`, `lastChanged = now`.
- `null` incoming state is normalized to the literal string `"unknown"` before compare.
- `friendlyName` and serialized `attributes` are refreshed on **every** call regardless
  of whether the state value changed.
- `lastUpdated` bumps on every call; `lastChanged` only bumps (and an
  `EntityStateChangedEvent` is returned) when the new state string differs from the old
  one — plain no-op refresh (e.g. periodic poll reporting the same value) returns
  `Optional.empty()` and does not fire an event.
- Attributes are stored as a JSON TEXT column (`ObjectMapper.writeValueAsString`); a
  serialization failure is logged at WARN and stored as `null`, never thrown.

## Repository (Task 1, already exists — don't recreate)
`EntityStateRepository` (`com.household.manager.repository`) has: `findByEntityId`,
`findAllByOrderByEntityIdAsc`, `findByDomainOrderByEntityIdAsc`,
`findBySourceOrderByEntityIdAsc`, `findByDomainAndSourceOrderByEntityIdAsc`,
`deleteByEntityId`. `EntityState` entity (`model/entity`) fields: id, entityId, domain
(enum `EntityDomain`), friendlyName, source (enum `EntitySource`), sourceRef, state,
attributes (String/JSON), lastChanged, lastUpdated, createdAt (`@PrePersist`).

## Test approach that worked
Pure Mockito unit tests (`@ExtendWith(MockitoExtension.class)`), no `@SpringBootTest` —
fast and sufficient since both classes are thin. Writer test constructs a real
`ObjectMapper()` (not mocked) to exercise actual JSON serialization. Facade test uses
`@InjectMocks` with three mocks (`EntityStateWriter`, `EntityStateRepository`,
`ApplicationEventPublisher`) — explicitly asserts exceptions from *both* the writer and
the event publisher are swallowed (`assertDoesNotThrow`), since that's the whole point
of the facade existing.

## Task 6 gotcha: adding entitystate hook deps breaks existing direct-construction tests
Every integration wired into `entitystate` (Task 6 = SmartDeviceService, Tasks 7-12 =
Zigbee/Tasmota/Shelly/Airrohr/DWD/AnkerSolix) is `@RequiredArgsConstructor` with new
final fields (`XyzEntityMapper`, `EntityStateService`) added for the hook. If that
service already has a unit test that does `new XyzService(mockA, mockB, ...)` directly
(see [[smart-device-persistence]] Task 3 note — this is the established pattern in this
codebase, not `@SpringBootTest`), the constructor arg list changes and the WHOLE test
module fails to compile — `mvn test -Dtest=OnlyMyNewTest` still runs `testCompile` on
every test source first, so a narrow `-Dtest` filter does NOT hide this. Task
instructions for Task 6 only listed 3 files to touch/commit and didn't anticipate this;
had to also patch `SmartDeviceServiceTest`'s `newService()` helper (add
`new SmartDeviceEntityMapper()` as a real instance — it's pure/stateless, no need to
mock — and `mock(EntityStateService.class)`) and include that 4th file in the commit,
since leaving the module non-compiling is worse than a one-line scope deviation. Check
for this proactively on every remaining hook task: grep the target service's test file
for direct `new XyzService(...)` construction before writing the hook, not after.

## Task 7 review finding: binary_sensor state must follow HA "on = open/detected" convention, not the raw source truthy value
`ZigbeeEntityMapper` (commit `f7da986`) initially mapped BINARY_TYPES (CONTACT/OCCUPANCY/
WATER_LEAK) as `truthy value → "on"` uniformly — copying the raw source semantics
straight through. That's wrong for CONTACT: zigbee2mqtt's `contact=true` means the
magnet is present, i.e. the door/window is **closed**, but Home Assistant's
`binary_sensor` (device_class `door`/`window`/`opening`) convention is **on = open**.
Fixed in commit `5424751` — `toOnOff(type, value)` now inverts only for CONTACT
(`truthy → "off"`), while OCCUPANCY/WATER_LEAK stay `truthy → "on"` (HA-conformant
as-is, since "motion detected"/"leak detected" already means on=truthy). Also added an
`HA_DEVICE_CLASSES` map so `attributes.deviceClass` carries real HA device_class strings
(`door`, `motion`, `moisture`, ...) instead of the raw lowercased enum name — entity-ID
suffixes stay the raw lowercased enum name (`_contact`) since those must stay stable,
only the attribute value changed.
**Apply this check to every remaining binary/enum-ish mapper (Tasmota, Shelly, Airrohr,
AnkerSolix, Tasks 8-12):** for every boolean-like measurement, explicitly verify against
Home Assistant's device_class convention (https://www.home-assistant.io/integrations/binary_sensor/
lists on/off meaning per device_class) rather than assuming "source truthy = on".
Don't just extend `BINARY_TYPES`/`toOnOff` symmetrically — check each type's semantics
individually.

## Tasks 8-11 (Tasmota/Shelly/Airrohr/DWD weather) — done, commits `59741be`/`c373c02`/
`97a2906`/`d167e9d`
Unlike Tasks 6-7, these four are all plain numeric sensors (no mapper class needed) —
each polling service got a private `reportEntityStates(...)` + `reportSensor(...)` pair
inlined directly, both wrapped in try/catch per the canonical pattern (the outer
`reportEntityStates` catch covers `EntityIds.build` throwing; individual
`entityStateService.reportState` calls are already internally exception-safe per the
facade design above, so this is defense in depth, not redundant given `EntityIds.build`
runs *before* the facade call). No test-fixture fallout this round — grepped
`backend/src/test` for `new TasmotaElectricityPollingService(`/`new ShellyPollingService(`/
`new AirrohrPollingService(`/`new WeatherPollingService(` before touching each file and
found zero direct-construction tests, so (unlike Task 6, see gotcha above) each commit
stayed to exactly the one target file. Shelly's report call sits *before* the
`!status.reachable()` skip-and-continue, deliberately, so unreachable devices still get
mirrored as `"unavailable"` rather than going stale.

## Quality-review fixes after Tasks 8-11 (commits `06f7359`, `ad7c4ae`)
Two data-correctness issues found in review, both one-line-ish fixes:
- `AirrohrPollingService.reportSensor` originally hardcoded `deviceClass: "pm"`, not a
  valid HA device_class — split into `pm10`/`pm25` per sensor, added a `deviceClass`
  param to `reportSensor(...)` (mirrors the Weather hook's pattern).
- `WeatherConditions.windSpeed` had a stale `// wie geliefert (in Task 9 verifizieren)`
  comment; `DwdWeatherService.toCurrent` only does `/10` tenths-scaling with no unit
  conversion. Verified by reading `frontend/src/app/pages/weather/weather.component.html`
  line 41 (`{{ overview.current.windSpeed }} km/h`) — the frontend displays the raw
  backend value with a hardcoded `km/h` suffix and no JS-side conversion, so the DWD
  app API's tenths-scaled windSpeed field is tenths-of-km/h, not m/s. `pressure` (hPa,
  html line 47) and `temperature` (°C, html line 35) were cross-checked the same way and
  already matched — no discrepancy there. Since the entity attribute already said
  `"km/h"`, only the DTO comment needed updating (no attribute/behavior change).
**Takeaway for future unit-verification tasks in this project:** the weather/DWD
frontend template does zero client-side unit conversion — whatever unit string appears
next to a `{{ }}` binding in the `.html` is exactly what the raw backend/DTO value
represents. Check the template first before guessing from the external API's docs.
