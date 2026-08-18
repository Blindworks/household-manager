---
name: flowengine-light-set-node
description: light-set flow action node (Task 6 of tplink-leuchtmittel plan) - error-swallowing decision, hue-without-saturation validation choice, NodeConfig idiom for optional numeric fields
metadata:
  type: project
---

Task 6 of the tplink-leuchtmittel plan (branch `feature/tplink-leuchtmittel`) added the flow-engine
action node `light-set` (`backend/src/main/java/com/household/manager/flowengine/nodes/LightSetNodeHandler.java`),
wrapping `SmartDeviceService.setLightState` (see [[tplink-light-control]]) as a flow action.

**Inconsistency found and deliberately kept, not "fixed":** `SwitchDeviceNodeHandler` (the other
device-addressing action node) does **not** catch exceptions from `SmartDeviceService.turnOn/Off`
— a failed switch call aborts the flow branch. The plan for `light-set` explicitly asked for the
opposite (catch-and-log, message still passes through), reasoning that an unreachable bulb must
not swallow a downstream Telegram/push notification in the same branch. This means the two
device-addressing nodes now behave differently on device failure — intentional per this task's
spec, not an oversight. If `switch-device` is ever revisited, check whether the same reasoning
should apply there too (e.g. a "device off, but still notify" flow would want it).

**hue-without-saturation: pass-through, not a validation reject.** `SmartDeviceService`'s own
`validateLightStateRequest` checks `hue` and `saturation` independently — both only require the
`COLOR` capability, and either can be set alone (the device keeps its current value for the
unset one). Rejecting a lone `hue` at the node's `validate()` would be **stricter than the actual
API boundary** and would need to be kept in sync if the backend rule ever changes. Documented
this reasoning as a comment in `LightSetNodeHandler.validate()` right where a future reader would
expect the opposite decision.

**NodeConfig idiom for "optional numeric field, but reject if present-and-garbage":**
`NodeConfig.integer(key)` returns `Optional.empty()` for both "key absent" and "key present but
not parseable" — it swallows `NumberFormatException` internally (see `SwitchDeviceNodeHandler`'s
`deviceId` check, which relies on exactly this and can't tell the two cases apart either). To
distinguish "field omitted" (fine, optional) from "field present but garbage" (validation error),
use `config.string(key)` + trim + filter-not-blank first, *then* `Integer.parseInt` in a
try/catch — this is the same pattern `PushSendNodeHandler` already uses for its optional `userId`
field. Applied it to all four optional light fields (`brightness`/`hue`/`saturation`/`colorTemp`)
plus the "at least one must be set" deploy-time check (a node with none of the four would be a
silent no-op at runtime otherwise).

Frontend catalog entry (`frontend/src/app/pages/flows/node-catalog.ts`): added to `ACTION_TYPES`
and `LABELS['light-set'] = 'Licht setzen'`, same pattern as the `push-send` entry.

Docs: added a `light-set` section to `docs/flows/flow-import-format.md` mirroring `push-send`'s
section, with an explicit note on the colour/colour-temp mutual-exclusivity being a *backend*
concern (see [[tplink-light-control]]), not something the node itself enforces.

See [[tplink-light-control]] for the underlying service/DTO this node wraps.
