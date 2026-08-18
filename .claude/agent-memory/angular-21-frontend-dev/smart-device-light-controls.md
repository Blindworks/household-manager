---
name: smart-device-light-controls
description: Task 5 (2026-08-18) - brightness/color/color-temp sliders + Tapo address form in smart-device-list; no-current-value-from-backend pattern, send-on-release pattern
metadata:
  type: project
---

Built on `feature/tplink-leuchtmittel`. Adds per-device light controls and a Tapo "IP setzen"
form to [[SmartDeviceListComponent]] (`frontend/src/app/components/smart-device-list/`).

**Key constraint that shapes the whole design:** `SmartDeviceResponse` (backend) never returns the
device's *current* brightness/hue/saturation/colorTemp — only `capabilities` (string array) and,
in `metadata.colorTempRangeMin`/`colorTempRangeMax`, the device's own colour-temp range. So sliders
cannot be initialized from server truth. Solution: a `Map<number, LightControlState>` keyed by
device id, lazily created with sane defaults (brightness 100, colorTemp = range midpoint, colour
`#ffffff`) the first time `getLightState(device)` is called from the template. State survives
`updateDeviceInList()` swapping the `SmartDevice` object reference because it's keyed by id, not
object identity. Same lazy-Map pattern for the per-Tapo-device address form
(`Map<number, TapoAddressFormState>`).

**Send-on-release without ngModel:** range/color inputs use `[value]` (one-way) +
`(input)="onXInput(...)"` (updates local state only, no HTTP) + `(change)="onXCommit(...)"` (fires
the PUT). This is deliberately NOT `[(ngModel)]`, because ngModel's default event is `input`, which
would flood these single-connection devices. `(change)` fires on release/blur, exactly the network
device semantics wanted. This is the reusable pattern for any future "send on release" slider.

**Colour conversion is a pure util, not inline:** `frontend/src/app/shared/color-conversion.util.ts`
- `hexToHueSaturation(hex): {hue, saturation}` does standard RGB→HSV, discarding V deliberately
(bulb's own brightness slider stays authoritative, so a colour pick must never silently touch it).
13 unit tests in the co-located `.spec.ts` covering primaries, grayscale, a wraparound-negative-hue
case, and hex without `#`/3-digit shorthand. Matches the `shared/*.util.ts` + `*.util.spec.ts`
convention used throughout this codebase (see [[temperature-comfort-util]] as reference style).

**Error handling:** each per-device state object owns its own `error: string | null` — since the
backend's 400 messages are already device-specific ("Geraet X meldet die Faehigkeit Y nicht."), no
extra device-name prefixing needed on the frontend side. On error, only `state.pending` and
`state.error` are touched — the slider/color value itself is never reset, satisfying "keep last
known value visible" from the spec by simply never touching it on the error path.

**Capability check is a plain array `.includes()`,** not a typed union, deliberately: backend's
`TapoCapabilityMapper` javadoc explicitly says new capability strings can appear over time. A
`SmartDeviceCapability` union type exists in the model file for documentation but is NOT used to
type `SmartDevice.capabilities` itself (stays `string[]`) — same reasoning.

Controls (brightness/colorTemp/color sliders, Tapo address form) are gated to `viewMode ===
'normal'` only — the compact view is a touch-toggle tile and has no room for this; not explicitly
required by the spec but a reasonable scope call, worth flagging if a future task wants compact-view
parity.

See also [[dashboard-style-encapsulation]] for the unrelated-but-similar "capsule your own styles"
lesson — not needed here because `.device-card` is always opaque white regardless of host page
(purple dashboard vs. white admin card), unlike `.kasa-ip-form` which needed a dark self-contained
chip. New light-control/address-form CSS lives directly in `smart-device-list.component.scss`
using the card's existing light-on-white palette (#2d3748/#4a5568/#4299e1).

## Follow-up (commit 50c01d3, same day): seed from real device state

A backend follow-up (`ec4c10a`) removed the "no current value" limitation above:
`SmartDeviceResponse` now carries `brightness`/`hue`/`saturation`/`colorTemp` as top-level
**optional** (not nullable — the DTO has class-level `@JsonInclude(NON_NULL)`, so an absent value
means the JSON key is missing entirely, never `null`) Integer fields, populated by
`refreshTapoDeviceState` on every scan/refresh/light-set. Frontend model field type is therefore
`brightness?: number`, checked with a plain `!== undefined && !== null` guard
(`isKnownNumber` in the component) — do NOT type these as `number | null`, the field is simply
absent when the device never reported it.

**Re-seeding architecture:** `seedLightState(device)` is the single place that (re)builds a
device's `LightControlState` from `SmartDevice.brightness/hue/saturation/colorTemp`, called from
two places: `loadDevices()` (for every device on a fresh load) and `updateDeviceInList()` (on every
refresh, which is also what a successful `setLightState`/`refreshDeviceState` response flows
through) — so a successful light-set's own refreshed-device response is what makes the slider
reflect the *device-confirmed* value, not the optimistically-sent one. `seedLightState` explicitly
preserves the *existing* `pending`/`error` fields across a reseed (only the numeric/hex values and
`*Known` flags are replaced) so an in-flight request or a still-visible error banner isn't
clobbered by an unrelated background refresh.

**Unknown-value UX:** each control has a `brightnessKnown`/`colorTempKnown`/`colorKnown` boolean.
False means "this is a component-invented default (100 / range-midpoint / #ffffff), not something
the device reported" — surfaced as an italic grey "(unbekannt)" suffix next to the value/label
(`.light-control__unknown` in the scss). The flag flips to `true` the instant the user actively
moves that control (`onXInput` sets it), since at that point it's the user's deliberate choice, not
a guess.

**`hueSaturationToHex(hue, saturation)`** added to `color-conversion.util.ts` as the inverse of
`hexToHueSaturation` — standard HSV→RGB with V pinned to 1 (brightness is deliberately not part of
this pair, same reasoning as the forward function). The hue/sat→hex→hue/sat round trip is lossless
for the tested primaries and mixed colours specifically *because* V=1 on both sides; a round trip
starting from an arbitrary user-picked hex (with its own V) would not preserve V, which is
correct/expected since V is never sent to or read from the device.

**Backend contract added the same day:** `PUT /devices/{id}/light` now 400s if a request contains
`hue`/`saturation` together with `colorTemp` (both are mutually-exclusive bulb modes). The frontend
was already structurally incapable of bundling them — three separate `commitLightState` call sites
each send exactly one of `{brightness}` / `{colorTemp}` / `{hue, saturation}` — but a component spec
test now pins this explicitly by inspecting every `setLightState.calls.allArgs()` for the XOR
property, so a future refactor that merges these paths would fail loudly.
