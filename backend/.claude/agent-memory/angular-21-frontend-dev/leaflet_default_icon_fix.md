---
name: leaflet_default_icon_fix
description: Leaflet's default marker icons silently fail to render under the Angular esbuild bundler; the project's implementation plan for the Tractive page did not actually contain the fix despite text claiming otherwise
metadata:
  type: project
---

Leaflet resolves its default marker icon images via a relative-URL heuristic against
the currently executing script. This does not survive the Angular CLI's esbuild
application builder, so `L.marker(...)` renders with a broken/missing icon and no
error — easy to miss in review.

Fix applied in `frontend/src/app/pages/pets/pets.component.ts` (Tractive dog-tracker
page, Task 13 of `docs/superpowers/plans/2026-07-24-tractive-hundetracker.md`):
delete `L.Icon.Default.prototype._getIconUrl` and call
`L.Icon.Default.mergeOptions({...})` with explicit `iconUrl`/`iconRetinaUrl`/
`shadowUrl`, run once at module load (top-level statement, before any
`L.map(...)` call).

**Corrected: serve the images locally, not from a CDN.** First pass pointed the
three URLs at `unpkg.com/leaflet@1.9.4/dist/images/...`. The coordinator caught
this: this app is a self-hosted household dashboard on a local network (Docker
Compose, local MariaDB, driven partly from a wall-mounted tablet) and must not
depend on an external CDN — no internet means the markers fail exactly the way
the fix was meant to prevent, plus it leaks a request to a third party on every
page view. Fixed by adding an assets entry to `frontend/angular.json`
(`build.options.assets`, NOT the `test` architect) copying
`node_modules/leaflet/dist/images` to `assets/leaflet` at build time, then
pointing the icon URLs at `assets/leaflet/marker-icon.png` etc. Verified the
files actually land at `dist/household-manager/browser/assets/leaflet/` after
`npm run build`.

**How to apply generally in this project:** this app is meant to run fully
offline/local-network — do not point any asset (marker icons, fonts, JS libs)
at a public CDN; vendor everything through `node_modules` + an `angular.json`
assets entry instead, the same way `leaflet.css` was wired into the `styles`
array in Task 12.

**Why noted here:** the orchestrating task prompt asserted "the plan's code handles
this — do not simplify it away," but the plan document's Task 13 code block (and
Task 12, which added the Leaflet dependency) contains no such fix anywhere. Verified
by grepping the plan for `iconUrl`/`_getIconUrl`/`mergeOptions` — no hits. Treated the
explicit behavioural requirement in the task prompt as authoritative over the literal
plan text and added the fix anyway, since it's a well-known, real bundler pitfall.

**How to apply:** when a task prompt describes a pitfall as "handled in the plan,"
verify that claim against the actual plan text (grep for the relevant API names)
before trusting it — plans can be wrong about their own content. If any Angular page
in this repo uses Leaflet again, apply the same fix or extract it into a shared
util if a second page needs it.
