---
name: foblex-flow
description: @foblex/flow canvas library integration notes for the Stufe-3b flow editor — API, gotchas, Chromium rendering fix
metadata:
  type: project
---

Task B1 (2026-07-09) spiked `@foblex/flow@19.0.0` for the visual flow editor
(Stufe 3b). Installed via plain `npm install @foblex/flow` (no `ng add` — kept
schematic changes out of scope for the spike). Peer deps are
`@angular/core`/`@angular/common >= 17.3.0`, so Angular 19 (this project's
version) has no conflicts. `npx ng build --configuration production` stays
clean with it installed.

**Why:** Task B1 was a feasibility + API-documentation spike ahead of the real
canvas component (Task B8). Full findings live in
`frontend/src/app/pages/flows/foblex-spike.notes.md` (committed, kept after the
spike component/route were removed again).

**How to apply:** When building `FlowCanvasComponent` (Task B8) or anything
else touching `@foblex/flow`, read `foblex-spike.notes.md` first, and also
check `node_modules/@foblex/flow/AI.md` + `STYLING.md` — the package ships its
own LLM-oriented API guide that is more version-accurate than training data.
Do not guess at `@foblex/flow` selectors/inputs/outputs; verify against
`node_modules/@foblex/flow/index.d.ts`.

Key gotchas discovered (verified live with Playwright against `ng serve`, not
just read from docs):

1. **`<f-minimap>` must be a direct child of `<f-flow>`, not `<f-canvas>`.**
   Placed inside `<f-canvas>` it silently doesn't render at all (0 DOM
   elements) — `FFlowComponent`'s content-projection metadata only accepts it
   in the generic `"*"` slot of `f-flow`.

2. **Connection lines are invisible in Chromium unless you add an explicit
   nonzero size to the connection's inner `<svg>`.** The shipped component CSS
   is `:host svg{overflow:visible!important;position:absolute}` with no
   width/height, which resolves to a genuine 0×0 box (CSS shrink-to-fit for an
   absolutely-positioned replaced element with no intrinsic size). Chromium
   148 (Playwright, both headless and headed) does not paint SVG content that
   overflows a truly 0×0-sized root `<svg>`, despite `overflow: visible
   !important` — confirmed independently with a minimal non-Angular HTML
   repro. Fix (verified working, no visual side effect since content
   overflows the box anyway):
   ```scss
   f-connection svg { width: 1px; height: 1px; }
   ```
   This must be added to global styles wherever `<f-connection>` is used
   (Task B8). Not verified in Firefox (no Firefox binary available in this
   environment) or in a real interactive Chrome session (claude-in-chrome MCP
   was unreachable during the spike) — worth a quick recheck once B8 ships,
   but low risk since it's a trivial, side-effect-free CSS addition.

3. **Theme wiring is manual unless you use `ng add`.** Plain `npm install`
   does not add `node_modules/@foblex/flow/styles/default.scss` to
   `angular.json`'s global `styles` array. Without it, connectors/connections
   render with no color/sizing at all. Add it before `src/styles.scss` in the
   `styles` array (or `@use`/`@forward` it from `src/styles.scss`).

4. Core event surface (all on `<f-flow fDraggable>`), verified live:
   `(fCreateConnection)` → `FCreateConnectionEvent{sourceId, targetId,
   dropPosition}`; `(fMoveNodes)` → `FMoveNodesEvent{nodes: {id,position}[]}`;
   `(fSelectionChange)` → `FSelectionChangeEvent{nodeIds, groupIds,
   connectionIds}`; `(fNodesRendered)`/`(fFullRendered)` → flow id string,
   fire after initial render (call `fitToScreen()`/`centerGroupOrNode()` etc.
   only after these, not eagerly — `FF1009`). The library never mutates your
   graph state — every event is read-only notification, app owns persistence.

See also [[angular-control-flow]] if it exists for unrelated `@if`/`@for`
conventions in this codebase — not related to this library.

## Task B8 (2026-07-09): `FlowCanvasComponent` shipped

Real component at `frontend/src/app/pages/flows/flow-canvas.component.ts/.html/.scss`
(commit `4c99bce`). Confirms/extends the spike notes:

- **CSS workaround (gotcha #2) applied component-scoped, not global**: `:host
  ::ng-deep f-connection svg { width: 1px; height: 1px; }` inside the
  component's own `.scss`, not `src/styles.scss`. Works because the elements
  we write (`<f-connection>` etc.) are in our own template, but `f-connection`
  renders its `<svg>` in *its own* internal template — a view-encapsulation
  boundary a plain scoped selector can't cross, hence `::ng-deep` is needed
  (not just avoidable via normal scoping). Verified via full `ng build
  --configuration production` after wiring the component into a throwaway
  route (see gotcha below) — connection styling compiles and applies.
- **Theme wiring (gotcha #3) intentionally NOT done in B8.** Registering
  `node_modules/@foblex/flow/styles/default.scss` in `angular.json` would mean
  touching a global config file outside B8's file scope (task only allowed
  global-style edits for the connection-SVG workaround specifically). Without
  it, connectors/connections will render colorless until whoever wires
  `FlowCanvasComponent` into the real editor page (Task B9) adds the theme
  import too — **flag this explicitly in B9**, it's an easy thing to forget
  and the canvas will look broken (no connector color/size) without it.
- **Delete UX**: no lib-native delete API without `withA11y()` (a `provideFFlow`
  app-wide provider change, out of scope for a single component). Used two
  lib-documented affordances instead of guessing: a plain delete button per
  node (blocked from starting a node-drag via the `fDragBlocker` directive), and
  a delete button *inside* `<f-connection>` via the `[fConnectionContent]`
  content-projection slot (`position` input, 0..1 along the path, defaults to
  0.5 = midpoint) — both call straight back into the app's own state via
  `nodeDeleted`/`connectionDeleted` outputs, no lib mutation involved.
- **Connector ID scheme**: `` `${nodeId}::in` `` for the single target
  connector, `` `${nodeId}::out::${portIndex}` `` per source connector — chosen
  so `(fCreateConnection)`'s `sourceId`/`targetId` (plain connector-id strings)
  can be parsed back into `{fromNode, fromPort, toNode}` without a lookup
  table. Fallback port label when `portLabelsByType()[type]` is empty/missing
  is `'Ausgang'`, matching the backend default in
  `NodeHandler.portLabels()` (`backend/.../flowengine/NodeHandler.java`).

**Important verification gotcha (generalizes beyond this library):**
`ng build --configuration production` does **not** type-check a standalone
component that isn't imported/routed from anywhere — confirmed empirically by
adding a deliberate `TS2322` type error to `flow-canvas.component.ts` and
observing the build still succeed while the component had zero consumers. The
Angular AOT compiler apparently only processes the reachable component graph,
not every file matched by `tsconfig.json`. **To actually verify a new,
not-yet-wired-up component compiles, you must temporarily add it to a route
(`loadComponent` in `app.routes.ts`), build, then revert the route change**
(confirmed the revert leaves zero `git diff` on `app.routes.ts`) — a bare `ng
build` passing is not sufficient evidence on its own. This matches what the B1
spike did (build, verify, then remove the spike wiring) and should be the
default verification pattern for every future flow-editor task (B9, B10...)
until these components get a permanent consumer.

## Bug fix (2026-07-10): drag-to-connect moved the node instead of connecting

Root cause, found via Playwright against a throwaway harness (never guessed):
`FlowCanvasComponent`'s template never rendered `<f-connection-for-create>`. AI.md
calls it "optional preview connection used during drag-to-connect UX" — that
description is misleading. In the compiled lib
(`node_modules/@foblex/flow/fesm2022/foblex-flow.mjs`), `CreateConnectionPreparation
._isValidConditions()` requires `this._store.connections.getForCreate()` to be
truthy, and `getForCreate()` only returns something once an
`FConnectionForCreateComponent` has registered itself via
`AddConnectionForCreateToStoreRequest` in its `ngOnInit`. **Without the element in
the template, the whole drag-to-connect gesture is unconditionally inert** — verified
empirically: even a pixel-precise drag from a connector's exact center to another
connector's exact center still fired `fMoveNodes`, never `fCreateConnection`, no
matter how careful the pointer path. `[fNode][fDragHandle]` on the whole node was
never the problem; `DragNodePreparation._canStartDrag()` already correctly refuses to
start a node-move once `CreateConnectionPreparation` has claimed the drag session —
that arbitration works fine out of the box. **Fix: add
`<f-connection-for-create></f-connection-for-create>` as a child of `<f-canvas>`**
(sibling of the `<f-connection>` `@for` block). One line, no other template/TS
changes needed — confirmed via `onCreateConnection`'s payload
(`{fromNode,fromPort,toNode}`) being correct immediately after.

Two more things found and fixed in the same pass, both via Playwright, not guessing:

1. **The 10px connector is genuinely too small for reliable real-world clicking.**
   Even after the fix above, a drag starting a few px outside the connector's actual
   box still fell through to node-move. Enlarged to 18px. Gotcha: `@foblex/flow`'s
   own theme (`styles/domains/_connector.scss`) sets `width`/`height` on
   `.f-connector-source`/`.f-connector-target` etc. via a *scoped* selector
   (`f-flow .f-connector-source:not(.f-node)`, specificity 0,2,1 — the `f-flow`
   ancestor and the `:not()` argument both count). A naive component-scoped override
   (`.flow-canvas__connector[_ngcontent-hash]`, specificity 0,2,0) loses that fight
   silently (computed style stays 10px, no error). Fix without `!important`:
   compound the BEM base class with the modifier class that's always present
   alongside it (`&.flow-canvas__connector--in, &.flow-canvas__connector--out`),
   bumping to 0,3,0. **General lesson: when overriding a third-party library's own
   themed CSS, check the *actual* compiled selector's specificity
   (`node_modules/<pkg>/styles/**/*.scss`), don't assume a single BEM class beats
   it.**
2. **Border-radius circles clip hit-testing in Chromium — the effective click target
   of a `border-radius:50%` div is the visual circle, not its square bounding box.**
   Confirmed via `elementFromPoint`: a point diagonally ~10px from a connector's
   center (inside the 18px square's corner, outside the circle's 9px radius) missed
   the connector. Not chased further (would need a bigger box than looks reasonable);
   accepted as inherent to round hit-targets.
3. **A connector's own theme CSS is `position: absolute`, so it does NOT participate
   in the flex layout/gap of its container** (`.flow-canvas__port-out` in this
   component) — it visually overlaps whatever sits at its "static position" as it
   grows. The adjacent `<span class="flow-canvas__port-label">` was overlapping the
   enlarged connector and stealing pointer events meant for it (confirmed via
   `elementFromPoint` returning the label). Fixed with `pointer-events: none` on the
   label (purely decorative text, never meant to be interactive) — **general lesson:
   any non-interactive sibling placed next to an `@foblex/flow` connector should get
   `pointer-events: none`, since the connector's forced `position: absolute` makes
   layout-based spacing assumptions (flex gap, margins) unreliable for avoiding
   overlap.**

## Bug fix (2026-07-10): selected entity/device in a node "not saved" — actually a display bug

Reported as "die ausgewählte Entity im Node wird nicht gespeichert". Root cause was
**not** in the save/persistence chain — confirmed via two isolated TestBed reproductions
before touching any code (per this agent's own systematic-debugging discipline):
`NodeConfigPanelComponent.setField()`→`configChange`→`FlowEditorComponent.onConfigChange()`→
`canvasNodes.update()`→`save()`→`saveDraft()` round-trips the config correctly every time,
verified with a real DOM `change` event dispatch, not just calling `.select()` directly.

The actual bug: `entity-picker.component.html` / `device-picker.component.html` bound the
native `<select>` via a **plain property binding**: `[value]="value() ?? ''" (change)="..."`.
`EntityPickerComponent`/`DevicePickerComponent` load their `<option>` list asynchronously
(`ngOnInit` → real `HttpClient` call). Sequence that breaks it: (1) picker renders with an
*already-saved* value (e.g. reopening a configured node) but options are still `[]` (HTTP
pending) → only the disabled placeholder `<option>` exists, so the browser can't select
anything; (2) options arrive, `@for` re-renders adding real `<option>` elements; (3) Angular's
change-detection dirty-check (`ɵɵbindingUpdated`) **skips re-applying `[value]`** because the
bound primitive string (`value()`) itself didn't change between CD cycles — even though the
DOM structure did. Net effect: the browser's native `<select>` falls back to auto-selecting
the first non-disabled `<option>` (**not even blank — a plausible-looking WRONG entity**, no
error/fallback text shown since `displayLabel()` still correctly matches the true `value()`).
User sees an unrelated/wrong entity in the dropdown and concludes their selection "didn't
save" — it did; only the redisplay was broken. Reproduced with a `Subject`-based stub of
`getEntities()`/`getAllDevices()` to control async timing (an `of([...])` stub resolves
*synchronously* on subscribe and completely masks this race — a plain `of()`-based test gave
false confidence here, don't rely on it alone for anything touching async-loaded `<select>`
options).

**Fix**: switch both pickers' `<select>` to `[ngModel]="value() ?? ''" (ngModelChange)="select($event)"`
(needs `FormsModule` added to the standalone component's `imports`), matching the pattern
`node-config-panel.component.html` already used for its own ENUM/NUMBER/default fields.
Angular's `NgSelectOption` directive re-invokes the parent `SelectControlValueAccessor.writeValue()`
on every new `<option>`'s `ngOnInit` — this is what re-syncs the native selection once options
finally exist, which plain `[value]` property binding has no equivalent hook for. **Verifying
this in a Karma test needs `fakeAsync`+`tick()`, not just repeated `fixture.detectChanges()`**:
`NgModel`'s model→view sync (`_updateValue`) is deferred through a resolved microtask Promise,
so a plain non-fakeAsync test that only calls `detectChanges()` after the options arrive will
still show the stale/blank value and looks like the fix didn't work, even though it does in the
real app (Zone.js auto-flushes microtasks and re-runs CD outside of tests). Don't trust a
same-tick `detectChanges()`-only assertion for anything involving `ngModel`+async data — false
negative, not a real bug.

`alexa-device-picker` uses independent `[checked]` bindings per checkbox (no shared `<select>`
plus late-registering `<option>` siblings), so it does not have this race — verified by
inspection, not just assumed, before leaving it unchanged.

**Harness technique used for reproduction** (Playwright against `ng serve`, not
guessing): a throwaway standalone component importing `FlowCanvasComponent` directly
with 2 static `CanvasNode`s/empty connections, added to a temporary `flows-harness`
route in `app.routes.ts`, with `console.log` temporarily added inside
`onCreateConnection`/the harness's own output handlers to inspect the actual emitted
payload — **necessary** because this component's plain (non-signal, non-OnPush)
property update (`this.log = ...`) read back via the DOM
(`page.locator('#log').innerText()`) never reflected the change: `@foblex/flow` runs
its drag/pointer handling via `NgZone.runOutsideAngular()` (documented in AI.md's
"Additional Rules": "its events do not trigger change detection... call
markForCheck() where needed"), so Angular's zone-based automatic change detection
genuinely never re-ran after a drag-originated event, even though the JS state
(`this.log`) was updated correctly and synchronously. **Don't trust a harness's
rendered DOM output alone to verify an `@foblex/flow` event fired — log inside the
actual handler and read browser console output, or explicitly call
`ChangeDetectorRef.detectChanges()`/`markForCheck()` in the harness.** Checked
whether this is a real production risk: `FlowEditorComponent.onConnectionCreated`
(and all its other mutation handlers) write through `signal.update(...)`
(`canvasConnections`, `canvasNodes`, etc. — see `flow-editor.component.ts`), and
Angular's signal-based change-detection scheduler notifies independently of
`NgZone` since Angular 18ish even in zone-enabled apps — so the real editor likely
self-heals and does NOT need an explicit `markForCheck()`. The zone-escape only bit
my *harness*, which used a plain non-signal property. Not re-verified live in the
real editor (no backend running in this session to load a real flow) — worth a
quick manual click-through check next time the editor is touched, but not treated
as a bug here.
