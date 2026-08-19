---
name: kasa-bulb-support
description: Legacy Kasa (KL110 and similar) bulb support - measured write-protocol quirks (ignore_default, err_code semantics), plug/bulb/dimmer discriminator, and the LightState/applyCurrentLightState reuse pattern shared with Tapo.
metadata:
  type: project
---

Built 2026-08-19 on branch `feature/kasa-leuchtmittel`, merged into the existing `SmartDeviceService`
light-control path built for Tapo (see [[tplink-capability-mapper]], [[tplink-light-control]]).
First pass shipped with three documented-as-unverified protocol assumptions; a coordinator ran the
write path against the real device the same day and disproved all three (see below) — fixed in a
second pass, same day.

## Read-path ground truth (verified against a real KL110, 192.168.1.101, 2026-08-18)
- A bulb's `system.get_sysinfo` has **no `relay_state`** at all; on/off lives at
  `light_state.on_off` (0/1). A plug has `relay_state` and no `light_state`.
- When the bulb is OFF, current brightness/hue/saturation/color_temp are nested under
  `light_state.dft_on_state` (the values it will resume to). When ON, they sit directly in
  `light_state` alongside `on_off`.
- Capabilities are **explicit flags**: `is_dimmable`/`is_color`/`is_variable_color_temp` (1/0) at
  the sysinfo top level, sibling to `light_state` — no field-presence guessing needed here, unlike
  Tapo's `TapoCapabilityMapper`. **But `is_dimmable` alone is not sufficient**: a Kasa WALL DIMMER
  (HS220/KS220/KP405) reports `is_dimmable: 1` with `relay_state` and **no** `light_state` at all —
  it speaks a different, unimplemented dimming protocol, not
  `smartlife.iot.smartbulb.lightingservice`. `KasaCapabilityMapper.deriveCapabilities` therefore
  gates ALL light capabilities behind `light_state` object-presence first, THEN checks the `is_*`
  flags. Same fixed capability-string order as Tapo (`SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP`).
- `alias` frequently carries a trailing space (real KL110: `"Treppenhaus "`) — trimmed once in
  `KasaSysInfoMapper`.

## Write-path ground truth — MEASURED 2026-08-19 against the real KL110, three assumptions disproved
The first implementation guessed at the write protocol from `get_sysinfo`'s shape and documented
that guess as unverified. A coordinator then ran it for real (state restored after) and found:
1. **`{"brightness":40}` while OFF, no `on_off`** → device stayed OFF, brightness unchanged,
   **`err_code: 0` anyway**. A light-state change without `on_off` is a SILENT NO-OP.
2. **`{"on_off":1,"brightness":60}`, no `ignore_default`** → switched ON, but brightness landed on
   the device's stored default (100), not 60 — **`err_code: 0` anyway**.
3. **`{"on_off":1,"brightness":35,"ignore_default":1}`** → `brightness: 35` actually applied.
   `ignore_default: 1` is MANDATORY for any requested value to land, alongside `on_off: 1`.
4. **`{"on_off":1,"brightness":150,"ignore_default":1}`** → `{"err_code":-10000,"err_msg":"Invalid
   input argument"}`. The device DOES report real errors — must be checked and thrown.
5. **`{"hue":200,"saturation":80,"color_temp":0,"ignore_default":1}` on this non-colour bulb** →
   `err_code: 0`, `hue` unchanged. **`err_code: 0` never proves a value was applied** — capability
   gating in `SmartDeviceService.validateLightStateRequest` is the real protection; the code
   additionally never trusts the request, only what the response echoes back.
6. Response shape: `{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
   {<resulting state>,"err_code":N}}}` — same `light_state`/`dft_on_state` on/off-nesting split as
   `get_sysinfo` (fact reused via `KasaSysInfoMapper.currentValuesNode`/`readLightState`/`readOnOff`,
   shared between the read and write paths).

**Consequence for the design:** `KasaService.setLightState` always sends `on_off: 1` +
`ignore_default: 1` (documented with the verbatim measured evidence in its javadoc — this is
exactly the kind of fact nobody could re-derive from the API), checks `err_code` on EVERY
`smartlife.iot.smartbulb.lightingservice` response (bulb on/off toggles too, not just
`setLightState`), and returns a `KasaLightCommandResult` (poweredOn + `LightState`) parsed from the
device's own reported result — never an echo of the request. `SmartDeviceService.setLightState`'s
Kasa branch persists that returned result directly instead of issuing a second `getStatus()` round
trip the way the Tapo branch still does: one write response already carries everything needed, and
Kasa devices only accept one TCP connection at a time, so a redundant read is pure waste.

## Plug vs. bulb vs. dimmer discriminator: a stored flag, not a live re-probe
`isBulb` is derived structurally once per sysinfo read (`light_state.isObject()`,
`KasaSysInfoMapper`), persisted as `metadata["kasaBulb"]` on every scan/probe/refresh (mirrors
Tapo's `authProtocol` metadata pattern), and read back by `SmartDeviceService.isKasaBulb(device)`.
**Two independent gates use it**, not just one: `SmartDeviceService.setLightState` requires
`DeviceType.KASA && isKasaBulb(device)` (a wall dimmer is rejected with the same 400 a Meross
device gets — routing on `DeviceType.KASA` alone was a real bug caught in review, since a dimmer
reports `is_dimmable: 1` and would otherwise get a BRIGHTNESS slider that only ever 400s), and
`KasaCapabilityMapper` independently withholds BRIGHTNESS/COLOR/COLOR_TEMP unless `light_state` is
present. Belt-and-suspenders on purpose — the two checks come from the same underlying fact but are
computed via different paths (metadata vs. the `capabilities` column) and either one alone leaves a
gap if the other is ever refactored away.

`KasaService.turnOn/turnOff` kept their original 1-arg form (always plug payload) for the raw,
device-context-free `/kasa/{ip}/on` endpoint, and gained a 2-arg `(ip, boolean bulb)` overload for
`SmartDeviceService` — additive, no regression risk for the pre-existing endpoint.

**Rollout caveat:** a bulb already in the DB before this feature has no `kasaBulb` metadata key
(defaults `false`) and keeps getting the PLUG payload until the next scan or refresh writes the
flag — it will not actually switch in the meantime.

## `LightState` moved out of the `tapo` package
`com.household.manager.smartdevice.LightState` (moved from `tapo`, 2026-08-19 review fix) — once
Kasa needed the exact same brightness/hue/saturation/colorTemp shape for both a desired-state
request AND a device-reported result, keeping it under `tapo` was misleading. Reused directly by
both platforms; `SmartDeviceService.applyCurrentLightState` was split into a
`TapoDeviceState`-taking overload (unchanged call sites) plus a `LightState`-taking core both
platforms funnel through, so both write the identical `lightBrightness`/`lightHue`/
`lightSaturation`/`lightColorTemp` metadata keys and `toResponse()`/`SmartDeviceResponse` needed
zero changes.

## Documented-semantics, still NOT independently measured
- Colour/colour-temperature mutual exclusivity and the `color_temp:0`-forces-colour-mode trick are
  assumed identical to the verified Tapo behaviour (device is non-colour, so this couldn't be
  directly exercised) — the on_off/ignore_default requirement (measured) is orthogonal and applies
  regardless.
- No verified per-device colour-temperature range field for Kasa bulbs (unlike Tapo's
  `color_temp_range`) — Kasa devices always fall back to the shared 2500-6500K default in
  `SmartDeviceService.resolveColorTempRange` (now says so explicitly in its javadoc).

## Lesson for next time
Don't document a guessed write-protocol as "documented-semantics, not measured" and stop there when
a real device is reachable — a single real write call surfaced THREE silent-failure modes
(no-op, ignored value, wrong value trusted as applied) that no amount of re-reading the `get_sysinfo`
shape would have predicted. When a device is available, spend the one write call before shipping,
especially for anything that reports success (`err_code: 0`) while doing nothing.
