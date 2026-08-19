---
name: kasa-bulb-support
description: Legacy Kasa (KL110 and similar) bulb support added alongside Tapo bulbs - protocol differences, plug/bulb discriminator, and why the design mirrors TapoCapabilityMapper/LightState instead of duplicating them.
metadata:
  type: project
---

Built 2026-08-19 on branch `feature/kasa-leuchtmittel`, merged into the existing `SmartDeviceService`
light-control path built for Tapo (see [[tplink-capability-mapper]], [[tplink-light-control]]).

## Ground truth (verified against a real KL110, 192.168.1.101, 2026-08-18)
- A bulb's `system.get_sysinfo` has **no `relay_state`** at all; on/off lives at
  `light_state.on_off` (0/1). A plug has `relay_state` and no `light_state`.
- When the bulb is OFF, current brightness/hue/saturation/color_temp are nested under
  `light_state.dft_on_state` (the values it will resume to). When ON, they sit directly in
  `light_state` alongside `on_off`.
- Capabilities are **explicit flags**: `is_dimmable`/`is_color`/`is_variable_color_temp` (1/0) at
  the sysinfo top level, sibling to `light_state` — no field-presence guessing needed here, unlike
  Tapo's `TapoCapabilityMapper`. `KasaCapabilityMapper.deriveCapabilities(JsonNode sysInfo)` uses the
  SAME fixed order (`SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP`) so identical capability sets produce an
  identical string across platforms.
- `alias` frequently carries a trailing space (real KL110: `"Treppenhaus "`) — trimmed once in
  `KasaSysInfoMapper`, same lesson as Blink camera names (see CLAUDE.md).

## Documented-semantics, NOT measured (flagged in code/tests as such)
- The switch/dim protocol: `{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
  {...}}}` instead of `{"system":{"set_relay_state":{...}}}`. `set_device_info` was never executed
  against the physical KL110, only `get_sysinfo` reads were.
- Colour/colour-temperature mutual exclusivity and the `color_temp:0`-forces-colour-mode trick are
  assumed identical to the verified Tapo behaviour ("exactly as on the Tapo side" per the task spec),
  not independently confirmed for Kasa.
- No verified per-device colour-temperature range field for Kasa bulbs (unlike Tapo's
  `color_temp_range`) — Kasa devices always fall back to the shared 2500-6500K default in
  `SmartDeviceService.resolveColorTempRange`.

## Plug vs. bulb discriminator: a stored flag, not a live re-probe
Every toggle command needs to know which payload shape to send, but a fresh sysinfo read before
every single turnOn/turnOff was rejected as design: Kasa devices accept only one TCP connection at
a time, so a needless extra round trip before every toggle doubles latency and risks contention
with a concurrent status poll. Instead: `isBulb` is derived structurally once (per sysinfo read,
via `light_state.isObject()`) in `KasaSysInfoMapper`, persisted as `metadata["kasaBulb"]` on every
scan/probe/refresh (mirrors Tapo's `authProtocol` metadata pattern), and read back by
`SmartDeviceService.isKasaBulb(device)` for turnOn/turnOff. `KasaService.turnOn/turnOff` kept their
original 1-arg form (always plug payload) for the raw, device-context-free `/kasa/{ip}/on` endpoint,
and gained a 2-arg `(ip, boolean bulb)` overload for `SmartDeviceService` — additive, no regression
risk for the pre-existing endpoint.

## Reuse over duplication
- `com.household.manager.tapo.LightState` (brightness/hue/saturation/colorTemp) is reused as-is for
  Kasa rather than creating a Kasa-specific twin — `SmartDeviceService.applyCurrentLightState` was
  split into a `TapoDeviceState`-taking overload (unchanged call sites) plus a new
  `LightState`-taking core that both platforms funnel through, so both write the identical
  `lightBrightness`/`lightHue`/`lightSaturation`/`lightColorTemp` metadata keys and
  `SmartDeviceService.toResponse()`/`SmartDeviceResponse` needed zero changes.
- `SmartDeviceService.validateLightStateRequest` (capability/range checks) was ALREADY device-type
  agnostic before this task — it reads only `device.getCapabilities()`/metadata, no Tapo-specific
  branching — confirmed no changes were needed there when wiring in `DeviceType.KASA`.
- `KasaService.setLightState` mirrors `TapoDeviceService.buildSetDeviceInfoParams` structurally
  (same exclusive-colour-vs-colour-temp branching, same `deviceSupportsColorTemp` gate).

## Regression note
`SmartDeviceServiceTest.setLightStateRejectsNonTapoDevice` used to assert KASA was rejected by
`setLightState` (pre-dates Kasa bulb support). Renamed to
`setLightStateRejectsUnsupportedDeviceType` and switched to `DeviceType.MEROSS`, the now-genuinely-
unsupported type. Any future review diff showing this rename is expected, not a silent scope change.
