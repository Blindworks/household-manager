---
name: admin-tab-vendor-panel-removal
description: How the Admin "Smart Plugs" tab's per-vendor Kasa/Tapo/Meross panels were removed and replaced with SmartDeviceListComponent (Task 6 of smart-device-persistence plan)
metadata:
  type: project
---

`pages/admin/admin.component.ts` / `.html` used to have three vendor-specific diagnostic/direct-control UIs in the `activeTab === 'smart-plugs'` section: a Kasa board (IP-based control via `[(ngModel)]` on an IP input), a Tapo board (device-id based, with an `isTapoPowerControlSupported` guard for camera/hub/sensor devices), and a Meross board (cloud-based). All three were deleted and replaced with a single `<app-smart-device-list>` (see [[MEMORY]] SmartDeviceListComponent entry) inside one `admin__card`.

**Why:** User decision — the DB-backed unified list (used already on the Devices page) replaces the old live-discovery/direct-control panels; redundant UI for the same underlying devices.

**How to apply / verification approach used:**
- Before deleting anything, grepped the whole admin `.html` and `.ts` for every Kasa/Tapo/Meross-prefixed identifier to confirm each field/method was used *only* within the smart-plugs tab section (lines 125-368 in the old template) and not referenced from `ngOnInit`, `ngOnDestroy`, or any other tab's logic. All were self-contained — safe to remove in one pass.
- `FormsModule` was only imported for the Kasa IP `[(ngModel)]` input — grepped for `ngModel` across the whole template first (only one hit, inside the deleted section) before removing the `FormsModule` import too.
- `KasaService`/`TapoService`/`MerossService` and their model imports were removed from `admin.component.ts`, but the service **classes themselves** were left untouched in `services/` — only their usage in this one component was deleted.
- `ng build` (AOT) is the safety net for this kind of deletion: it errors on any leftover template reference to a removed method/field. Ran clean after the edit (only pre-existing unrelated `energy.component.scss` budget warning).
- There is no `admin.component.spec.ts` in this repo (confirmed via Glob) — nothing to update there.
- Net effect: `admin.component.ts` shrank from ~560 lines to ~190; `admin.component.html`'s smart-plugs section shrank from ~244 lines to 6.
