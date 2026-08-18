---
name: tplink-light-control
description: TapoDeviceService.setLightState / PUT /devices/{id}/light (Task 4 of the tplink-leuchtmittel plan) - colour vs colour-temp mutual exclusivity, capability/range validation, colorTemp range persistence
metadata:
  type: project
---

Task 4 of the tplink-leuchtmittel plan (branch `feature/tplink-leuchtmittel`) added light
control for Tapo bulbs (spec: `docs/superpowers/specs/2026-08-18-tplink-leuchtmittel-faehigkeiten-design.md`).

**Protocol finding (undocumented by TP-Link, inferred from field semantics, not yet verified
against the real L530 for `set_device_info` specifically — only `get_device_info` was probed in
Task 1, see [[tplink-modern-device-probe]]):** colour and colour-temperature are mutually
exclusive modes on these bulbs. Setting `hue`/`saturation` while a non-zero `color_temp` is still
active is documented (elsewhere, e.g. python-kasa/tapo community reverse-engineering) to leave the
bulb in white mode instead of switching to colour mode. So a colour request must send
`color_temp: 0` alongside `hue`/`saturation`; a colour-temp request sends only `color_temp` and
omits `hue`/`saturation` entirely. Encoded in exactly one place:
`TapoDeviceService.buildSetDeviceInfoParams(LightState)`. **If a real-device test of
`set_device_info` ever contradicts this, fix it there and only there.**

**Architecture:**
- `TapoLocalDeviceConnection.setDeviceInfo(ObjectNode params)` — new interface method alongside
  the existing `setDevicePowered(boolean)`, implemented in both `TapoKlapDeviceConnection` and
  `TapoAesDeviceConnection` by reusing their existing `set_device_info`/`executeRequest` plumbing.
- `LightState` record (`tapo` package): nullable `brightness`/`hue`/`saturation`/`colorTemp` —
  only non-null fields are sent.
- `SmartDeviceService.setLightState(Long id, LightStateRequest)`: not-Tapo check, capability
  check (device's stored `capabilities` string must contain `BRIGHTNESS`/`COLOR`/`COLOR_TEMP` for
  the fields being set — missing capability is a 400 with **nothing** sent to the device, not a
  silent ignore), range checks (brightness 1-100, hue 0-360, saturation 0-100, colorTemp against
  the device's own range), empty-request rejection, then calls `TapoDeviceService.setLightState`,
  refreshes+persists (reuses `refreshTapoDeviceState`), and records audit `device.light.set` —
  **all validation and the audit call live in the service, not the controller** (unlike
  on/off/kasa-add/tapo-address, which audit from `SmartDeviceController`; this task's own spec
  explicitly asked for the audit call inside the service, matching the `PetFoodService` pattern
  rather than the `SmartDeviceController` pattern used elsewhere in the same class).

**colorTemp range persistence (new):** the device's own `color_temp_range` (e.g. `[2500,6500]`
on the L530) wasn't stored anywhere before this task — only capabilities were derived from it.
Added `TapoDeviceState.colorTempMin`/`colorTempMax` (nullable Integer fields; kept the record's
old 5-arg constructor as a delegating overload so every existing caller/test with
`new TapoDeviceState(nickname, model, poweredOn, online, capabilities)` still compiles unchanged).
Persisted into device metadata as `colorTempRangeMin`/`colorTempRangeMax` (same map that already
carries `authProtocol`) by every Tapo upsert path (`upsertTapoDevice`, `upsertLocalOnlyTapoDevice`,
`setTapoDeviceAddress`) via a shared `applyColorTempRange(metadata, state)` helper — mirrors the
existing "preserve authProtocol if this round's probe didn't provide one" pattern exactly.
`SmartDeviceService.setLightState` reads it back via `resolveColorTempRange(device)`, falling back
to a hardcoded 2500-6500 default if the device was never probed since this field existed.

Endpoint: `PUT /devices/{id}/light` with `LightStateRequest` DTO (all fields optional Integer, no
bean-validation annotations — validation is business logic that needs the device's own stored
capabilities/range, so it lives in the service, not on the DTO). No new `SecurityConfig` matcher
needed (`/devices/**` writes already fall through to `anyRequest -> MEMBER`); pinned in
`SecurityRulesTest` mirroring the `/devices/kasa` and `/devices/{id}/address` cases.

See [[tplink-capability-mapper]] for how `capabilities` itself is derived, [[tplink-address-and-adoption]]
for the `authProtocol`-preservation pattern this task's `colorTempRange` persistence copies.

## Review-round fixes (same day, later — commit `ec4c10a`)

A senior review after Tasks 5-7 landed found one CRITICAL and several Important issues in the
above. Lessons worth keeping:

- **CRITICAL — never let a validation gap turn into a wrong audit claim.** A mixed
  `{hue, saturation, colorTemp}` request passed validation, then `buildSetDeviceInfoParams` took
  the colour branch (`color_temp: 0`), silently discarding the requested `colorTemp` — but the
  audit entry `device.light.set` was built from the *requested* `LightState`, so it claimed
  `colorTemp` was set even though it never reached the device. **The bug was invisible from
  reading `buildSetDeviceInfoParams` alone** — it only shows up by tracing what the audit-record
  call and the HTTP response actually assert versus what the device received. Fix:
  `validateLightStateRequest` now rejects `(hue != null || saturation != null) && colorTemp !=
  null` outright, structurally, before any capability/range check runs — so `LightState` and
  `buildSetDeviceInfoParams` never even see a mixed instance.
- **A capability gate must cover the field it's actually about, not just the field the user
  asked for.** `color_temp: 0` was appended to every colour request regardless of whether the
  device reports `COLOR_TEMP` at all. A device with `COLOR` but not `COLOR_TEMP` would reject the
  unexpected field (non-zero `error_code` → `TapoException`), and because that's caught generically
  the caller sees "beide Protokolle fehlgeschlagen" — a wrong-parameter bug disguised as a network
  outage. Fixed by threading a `deviceSupportsColorTemp` boolean into
  `TapoDeviceService.setLightState`/`buildSetDeviceInfoParams`, computed in
  `SmartDeviceService.setLightState` from the device's own stored `capabilities`.
- **A "captures on scan/refresh/address-set" javadoc claim needs all three call sites checked,
  not assumed.** `applyColorTempRange` was wired into `upsertTapoDevice`/`upsertLocalOnlyTapoDevice`/
  `setTapoDeviceAddress` but never into `refreshTapoDeviceState` — a device that's only ever
  refreshed (not rescanned) kept the 2500-6500 fallback forever, silently contradicting its own
  javadoc. Fixed by giving `refreshTapoDeviceState` the same metadata read/merge/write shape as
  `setTapoDeviceAddress`.
- **Reused the same fix to close a real gap the frontend needed too:** `TapoDeviceState` gained
  `currentLightState` (a nullable `LightState` reusing the existing record — cheaper than 4 more
  positional Integer fields), parsed via `readCurrentLightState` (same "field presence, not value"
  rule as capability derivation — `color_temp: 0` in pure colour mode is a real value). Persisted
  to metadata (`lightBrightness`/`lightHue`/`lightSaturation`/`lightColorTemp`) by the same
  `applyCurrentLightState` helper everywhere `applyColorTempRange` is called, and surfaced as four
  new top-level nullable Integer fields on `SmartDeviceResponse` (`brightness`/`hue`/`saturation`/
  `colorTemp`, same names as `LightStateRequest`) so the frontend can seed its sliders with the
  bulb's actual state instead of an invented default.
- **A cache keyed on `deviceId:protocol` alone (no IP) silently survives a DHCP reshuffle.**
  `setTapoDeviceAddress` already called `tapoDeviceService.clearLocalConnection` after a manual IP
  correction; `upsertTapoDevice` (the *scan* path) never did, so a stale cached connection to the
  OLD ip could keep controlling whatever device now happens to sit there (it would authenticate
  fine if it's another bulb on the same account — no error anywhere). Fixed by capturing
  `previousIp` before overwriting `device.setIpAddress` and calling `clearLocalConnection` when it
  changed. **Decision explicitly logged (coordinator asked for it):** did NOT fold the IP into the
  connection cache key — that would still leave `deviceIpCache`/other lookups needing the same
  fix and grows the cache unboundedly across every IP a device has ever had; explicit invalidation
  on detected change mirrors the pattern already proven correct in `setTapoDeviceAddress`.
- **`mvn compile` succeeding tells you nothing about the test tree.** After widening
  `TapoDeviceService.setLightState` with the `deviceSupportsColorTemp` boolean, `mvn compile -q`
  produced no output (looked clean) purely because it doesn't touch `src/test` at all — the actual
  arity mismatches (every mocked `verify(...).setLightState(any(), any(), any(), any())` across two
  test files, now needing a 5th `anyBoolean()` matcher) only showed up once a *clean*
  `test-compile` was run (an incremental one had stale cached output and briefly looked green too).
  Always run `test-compile` — clean if in doubt — after any production method-signature change,
  never trust `compile` alone as a signal that call sites are fine.
- Kept (didn't delete) `ipsClaimedByMatchedDevices` in `scanTapoDevices` after review flagged it
  as looking unreachable — decided it's cheap defensive logging for a real, if rare, misconfigured
  static `tapo.devices` entry (stale/wrong `deviceId`, IP now belongs to a different, correctly
  cloud-matched device), and documented the concrete trigger in a comment rather than removing it
  silently.
