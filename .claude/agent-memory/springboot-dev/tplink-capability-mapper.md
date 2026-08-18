---
name: tplink-capability-mapper
description: How Tapo device capabilities (SWITCH/BRIGHTNESS/COLOR/COLOR_TEMP) are derived from get_device_info, and how the raw JsonNode reaches the mapper without a new DTO
metadata:
  type: project
---

Task 2 of the "TP-Link Leuchtmittel" plan (branch `feature/tplink-leuchtmittel`) replaced the
hardcoded `device.setCapabilities("SWITCH")` in `SmartDeviceService.upsertTapoDevice` with a
real derivation. See [[tplink-modern-device-probe]] for the real L530 fixture this is tested
against.

**`TapoCapabilityMapper.deriveCapabilities(JsonNode)`**
(`backend/src/main/java/com/household/manager/tapo/TapoCapabilityMapper.java`) is a static,
side-effect-free mapper: always `SWITCH`; `brightness` field present ⇒ `+BRIGHTNESS`; `hue`
AND `saturation` present ⇒ `+COLOR`; `color_temp` OR `color_temp_range` present ⇒
`+COLOR_TEMP`. Order is fixed (`SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP`) so the comma-separated
DB column doesn't get rewritten (and doesn't fire a pointless entity-state change event) on
every scan just because `JsonNode` field iteration order isn't guaranteed to match insertion
order for object nodes built by different code paths.

**Field presence, not field value, is the signal.** `color_temp: 0` on the L530 means "bulb is
currently in pure colour mode", not "no colour-temperature capability" — `.has("color_temp")`
is correct, `.path("color_temp").asInt() > 0` would have silently broken the capability the
moment a user picked a colour.

**How the raw `JsonNode` reaches the mapper:** rather than inventing a new DTO/passthrough
parameter, `TapoDeviceState` (the existing record returned by `TapoDeviceService.getStatus`)
grew a fifth field, `capabilities`, computed inside its own factory methods (`fromLocal` and
`from`) where the raw `JsonNode deviceInfo` is already in scope. `SmartDeviceService` then
just reads `state.capabilities()` — it never touches the raw device-info JSON itself. This
kept the change to one new field on an existing record instead of threading a `JsonNode`
through another layer.

**The clobber-on-failure bug and its fix:** the pre-existing code called
`device.setCapabilities("SWITCH")` unconditionally, BEFORE the try/catch around the live
`getStatus()` probe — so an existing device with real capabilities already stored would lose
them to "SWITCH" the moment a single scan's live probe failed (e.g. a bulb briefly offline),
even though `poweredOn`/`online` were already correctly left untouched on failure via the
same catch block. Fix: the `"SWITCH"` default now only gets set at device-creation time
(inside the `else` branch when `existing.isEmpty()`), and the try block sets
`device.setCapabilities(state.capabilities())` only on a *successful* probe. A failed probe on
an existing device now leaves whatever capability string was already in the DB row untouched
— same pattern as the existing `poweredOn`/`online` handling right next to it. Covered by
`SmartDeviceServiceTest.scanTapoKeepsExistingCapabilitiesWhenStatusProbeFails`.

This same record-and-existing-catch-block pattern is worth reusing for whatever Task 4 (light
control via `set_device_info`) or later tasks in this plan touch next in
`SmartDeviceService.upsertTapoDevice`/`refreshTapoDeviceState` — don't reintroduce an
unconditional overwrite ahead of a try/catch that's supposed to preserve last-known-good state.
