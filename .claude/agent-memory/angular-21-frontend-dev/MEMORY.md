# Angular 21 Frontend Development - Project Memory

## UI-Fallen
- [UI-Fallen](template-pitfalls.md) — `@for track` braucht eindeutige Keys (Laufzeitfehler!); `<label>` um mehrere Buttons leitet Klicks fehl; helle Seiten vertragen keine `rgba(255,255,255)`-Styles; **ein Fehlerfeld pro Ursache** — ein paralleler Abruf leert es sonst.

## Git Safety
- [Git concurrency hazard](git-concurrency.md) — repo/index shared across concurrent agent sessions; a commit can be silently clobbered by another session's amend. Always verify `git show --stat HEAD` right after committing.

## Usermanagement Feature (WP7, Task 17+18)
- [Usermanagement Frontend](usermanagement-frontend.md) — Header role-filtered nav (visibleNavLinks), admin pages for users/tokens/audit-log; header.component.spec.ts fix (provideRouter([]) + HTTP providers) dropped baseline fails 4→3.

## Project Structure
- **Location**: `C:\Users\bened\IdeaProjects\Household-Manager\frontend\src\app`
- **Architecture**: Angular 21 standalone components (no NgModules)
- **Styling**: SCSS with global variables in `src/styles.scss`
- **Components Location**: `src/app/components/` for shared, `src/app/pages/` for pages

## Service Layer Patterns
- Use `inject()` function for DI
- Base URL: `http://localhost:8080/api/v1/{resource}`
- Observable-based with RxJS, date conversion (ISO → Date)
- Centralized error handling, 404 as null not error

## Form Component Patterns
- ReactiveFormsModule + FormBuilder, custom validators
- `isFieldInvalid()` helper, loading states, auto-hide messages
- EventEmitter for parent notification

## Shared Components
1. **HeaderComponent** - Navigation; nav links defined in `navLinks` array in `.ts` file, NOT HTML. Add entries to the TypeScript array; HTML iterates with `@for`.
2. **MeterReadingFormComponent** - Meter reading entry form
3. **UtilityPriceFormComponent** - Price entry form with date range validation
4. **IconComponent** - Custom Lucide SVG icons (euro, calendar-check, save, trash-2, etc.)
5. **StatementImportComponent** - CAMT file upload for finance module
6. **SwitchListComponent** (`components/switch-list/`) - Presentational-only switch row list (Task 9 of dashboard switch-tile plan). Inputs: `switches: SwitchEntity[]` (required), `pendingIds: ReadonlySet<string>` (disables rows mid-command), `variant: 'tile' | 'dialog'` (dark-glass tile vs light dialog tonality via `switch-list--{variant}` host class). Output: `toggled: EventEmitter<SwitchEntity>`. No injected services, no internal state — parent (e.g. `DashboardComponent`) owns loading/toggle/error state, same pattern as the existing energy-flow dialog. Whole row is a `<button>` (touch-friendly for wall dashboard); the visual toggle knob is `aria-hidden`, row carries `aria-pressed`. `SwitchEntity` model lives in `models/switch.model.ts` (`entityId`, `domain`, `source`, `displayName`, `state: string` ("on"/"off"/"unavailable"), `available`, `icon` (Material Symbols name), `toggleCount`, `lastToggledAt`).
7. **SmartDeviceListComponent** (`components/smart-device-list/`) - Extracted from `pages/devices` (Task 5 of smart-device-persistence plan) so both the user-facing Devices page and the Admin "Smart Plugs" tab reuse it (wired up in Task 6). Owns loading, grouping by `deviceType`, toggling, background+manual status refresh, and per-type rescan (`scanType(type)`) plus scan-all (`scanAllDeviceTypes()`). Uses `SmartDeviceService` (`services/smart-device.service.ts`) and `SmartDevice` model. `DevicesComponent` now only renders the page header (`dashboard`/`dashboard__header` SCSS) and hosts `<app-smart-device-list>` — no business logic left on the page. `AdminComponent`'s smart-plugs tab (`pages/admin/admin.component.html`) similarly now just hosts `<app-smart-device-list>` inside one `admin__card` — the old per-vendor Kasa/Tapo/Meross discovery-and-control panels were deleted (see [[admin-tab-vendor-panel-removal]]).

## Page Components
1. **DashboardComponent** (`/`) - Meter overview cards
2. **MarketingComponent** (`/marketing`) - Landing page
3. **MeterReadingsComponent** (`/meter-readings`) - Reading management
4. **UtilityPricesComponent** (`/utility-prices`) - Price management with grouped tables
4b. **CalendarComponent** (`/calendar`) - Monatsraster. Kategorien sind **Stammdaten** (`CalendarCategoryService`, `GET /api/v1/calendar/categories`), kein Enum mehr — `CATEGORY_META` existiert nicht mehr. Farbe/Name kommen aus der am Termin **eingebetteten** `category`. Termine tragen `persons` (leer = ganzer Haushalt); `HouseholdUserService` (`GET /api/v1/users`) fuellt die Auswahl. `update`/`updateOccurrence` sind PUT-**Vollersetzung** — der Dialog muss `personUserIds` beim Speichern immer mitsenden, sonst verschwinden sie still (gleiche Falle wie bei `notes`).
5. **ZigbeeComponent** (`/zigbee`) - Zigbee sensor live tiles + history chart
6. **FinanceOverviewComponent** (`/finance`) - KPIs, ECharts donut + trend, A/B layout toggle (`localStorage` key `finance.overviewLayout`)
7. **FinanceTransactionsComponent** (`/finance/transactions`) - Transaction list, filters, inline categorization, rule-suggestion banner
8. **FinanceAccountsComponent** (`/finance/accounts`) - Bank account CRUD
9. **FinanceCategoriesComponent** (`/finance/categories`) - Category CRUD
10. **FinanceRulesComponent** (`/finance/rules`) - Categorization rule CRUD + apply-all
11. **FinanceBudgetsComponent** (`/finance/budgets`) - Budget CRUD + status bars
12. **FinanceRecurringComponent** (`/finance/recurring`) - Recurring payment detection + confirm

## Services
1. **MeterReadingService** - CRUD for meter readings
2. **UtilityPriceService** - CRUD for utility prices (getAllPrices, getPricesByMeterType, getCurrentPrice, createPrice, deletePrice)
3. **ZigbeeService** - REST: getDevices, getMeasurements; baseUrl `/api/v1/zigbee`
4. **ZigbeeLiveService** - SSE EventSource on `/api/v1/zigbee/live`, named event `live`
5. **FinanceService** - Full finance API at `/api/v1/finance`: accounts, import, categories, transactions (categorize with PATCH), rules (apply-all POST), analytics (overview/trend), budgets (status GET), recurring (detect POST, confirm POST)

## Models
- **MeterReading** + **UtilityPrice** interfaces
- **MeterType** enum: ELECTRICITY, GAS, WATER
- **MeterTypeUtils** - Static helpers (getLabel, getIcon, getUnit)
- **ZigbeeMeasurementType**, **ZigbeeDevice**, **ZigbeeMeasurement**, **ZigbeeLiveEvent**
- **finance.model.ts** - BankAccount, Category, TransactionDto, CategorizationRule, ImportSummary, OverviewResponse, TrendPoint, Budget, RecurringPayment + request/response types

## Utility Price Feature
- Grouped tables by meter type, current/historical badges
- 4 decimal prices, de-DE date format
- Delete with confirmation dialog
- Two-column layout: sticky form + scrolling history

## ECharts Pattern
- Import: `NgxEchartsDirective, provideEchartsCore` from `ngx-echarts`
- `import * as echarts from 'echarts/core'` then `echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])`
- In component decorator: `providers: [provideEchartsCore({ echarts })]`
- Template: `<div echarts [options]="chartOptions" class="chart"></div>`
- Reference: `airrohr-charts.component.ts`, `finance-overview.component.ts`
- For donut chart: use `PieChart` + `LegendComponent` in `echarts.use([])`

## Control Flow
- Use `@if`, `@for`, `@switch` — NOT `*ngIf`/`*ngFor` (deprecated structural directives)
- `@for` requires `track` expression: `@for (item of items; track item.id)`
- `@if (expr; as alias)` replaces `*ngIf="expr as alias"` — avoid `?.` on non-nullable types (generates NG8107 warning)

## SSE Live Service Pattern
- `EventSource` + named event listener: `eventSource.addEventListener('live', ...)`
- `BehaviorSubject` for connection status
- Return `Observable` wrapping connect/disconnect lifecycle
- Reference: `tasmota-live.service.ts`, `zigbee-live.service.ts`

## Angular Budget
- `anyComponentStyle`: 16kB warning, 24kB error
- Pre-existing warning on `energy.component.scss` (16.16 kB) — not actionable

## Third-Party Package Docs
- Check `node_modules/<pkg>/AI.md` or similar LLM-oriented guide files before reading `index.d.ts` cold — some modern packages (e.g. `@foblex/flow`) ship one; it's more version-accurate than training data. Still verify hard claims against `index.d.ts` / compiled bundle.
- See [foblex-flow.md](foblex-flow.md) for the Stufe-3b flow editor canvas library: Angular 19 compat, API surface, and a verified Chromium rendering gotcha (0×0 SVG + overflow:visible doesn't paint — needs explicit nonzero size).

## Vision / Gesichtserkennung Feature (Task 12+13, worktree feature/blink-gesichtserkennung)
- `frontend/src/app/models/vision.model.ts`, `services/vision.service.ts` (+spec), `pages/vision/vision.component.*` (+spec) — mirrors `pages/announcements` structure (plain component properties, not signals, since AnnouncementsComponent predates the signal convention used elsewhere).
- `VisionService.handleError` passes through `error.error?.message` (like `AlexaService`), not a generic string — backend's `VisionException` returns 502 with a `message` body field that's actually useful to the user (e.g. "kein Gesicht erkannt").
- HttpTestingController gotcha: when a URL is built via manual string concatenation (`` `${base}/x?limit=${n}` ``, no `HttpParams` object), `HttpRequest.url` includes the full query string — `expectOne(r => r.url === '/path')` (without query) will NOT match. Use `r.url.startsWith('/path')` or match the full string with query. Same applies to `AlexaService.getDevices` (`?rescan=`) if ever spec'd this way.
- Route added as `/vision`, nav entry added inside the `/admin` group's `children` array in `header.component.ts` (alongside `/flows`, `/announcements`).

## Testing (Karma/Jasmine)
- Test builder is `@angular-devkit/build-angular:karma` configured in `angular.json` — no standalone `karma.conf.js` file exists or is needed.
- ChromeHeadless launches and runs fine in this environment (verified Chrome 149 on Windows, no NEEDS_CONTEXT blockers encountered).
- Run a single spec from `frontend/`: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/component-name.spec.ts'`
- Spy pattern for services: `jasmine.createSpyObj('ServiceName', ['method1', 'method2'])`, then `serviceSpy.method.and.returnValue(of(...))`; provide via `{ provide: ServiceClass, useValue: serviceSpy }` in `TestBed.configureTestingModule.providers`.
- Standalone components go directly into `imports: [ComponentClass]` in the TestBed config (no module wrapper needed).
- Devices page: `pages/devices/devices.component.ts` — `ngOnInit` calls `loadDevices()` only (DB load), NOT `scanAllDeviceTypes()` (full network rescan). The scan method still exists for manual/explicit triggering elsewhere, just not on page load.
- Fresh git worktrees under `.claude/worktrees/<name>/frontend` do NOT have `node_modules` installed — `ng test` fails with "Could not find the '@angular-devkit/build-angular:karma' builder's node package." Run `npm install` once per worktree before the first test run (takes ~30-40s).
- `HttpTestingController.expectOne(url)` can be called twice for the same URL when only one matching request was fired and it hasn't been flushed yet — the request stays in the "open" list until `flush()`/`error()` resolves it, so a first `expectOne` to assert `.request.method` followed by a second `expectOne` + `.flush()` on the same URL both succeed (no need for the workaround of splitting into two separate calls). Confirmed with Angular's Karma/Jasmine harness in this repo (frontend, Chrome 149).
- **Full-suite baseline is 3 FAILED as of 2026-07-26**, not 4: `AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create` (all `NullInjectorError: No provider for ActivatedRoute!`/`HttpClient`). `HeaderComponent should create` used to be the 4th but is now green everywhere `header.component.spec.ts`'s usermanagement-era fix (`provideHttpClient()`, `provideHttpClientTesting()`, `provideRouter([])` in its TestBed providers) is present — i.e. on `main` and any branch cut after it. Re-verify with a quick run rather than trusting "4" if this memory looks old. Plus the usual occasional `SmartDeviceListComponent` afterAll flake (browser disconnects, re-run).
- A getter that returns a **freshly-constructed array on every call** (e.g. `.filter(...)`) is safe to bind directly in `*ngIf`/`*ngFor` in this codebase's components — confirmed by a throwaway spec that called `fixture.detectChanges()` twice in a row (mimicking Angular's dev-mode `checkNoChanges` re-check) plus 20 repeated calls, no `ExpressionChangedAfterItHasBeenCheckedError`, no hang. `DashboardComponent.energyGauges` already does exactly this (new array literal per call, bound to `*ngFor`) and has run trouble-free for a while, which was the precedent that made me suspect it'd be fine — verified rather than assumed for the pets-tile addition (Task 7 of the Tractive-Hund-zuhause plan).

## Tractive Home-Settings Admin Page (Task 6 of tractive-home-settings plan, 2026-07-27)
- `pages/admin-tractive/admin-tractive.component.*`, route `/admin/tractive` (adminGuard), nav entry in `/admin` group of `header.component.ts`. Second Leaflet page besides `pages/pets/`; same `fixLeafletDefaultIcon()` snippet duplicated (no shared util yet — worth extracting to a shared helper if a third Leaflet page appears).
- **Verified pattern: async-callback-then-`setTimeout`-then-`getElementById` on an `*ngIf`-gated container never races.** Zone.js flushes change detection (and thus renders the `*ngIf`-revealed DOM) synchronously when the current zone task (e.g. the XHR/fetch `next` callback) completes — this happens before a `setTimeout(0)` scheduled inside that same callback ever fires, since the timeout is a new macrotask queued after the current one drains. `pets.component.ts` relies on the same ordering (nested subscribe instead of setTimeout) and has run fine, which is corroborating evidence, not just theory.
- Angular's built-in `NumberValueAccessor` (used by `[(ngModel)]` on `<input type="number">`) maps an emptied field to `null`, never `NaN` or `''` — confirmed from source (`value == '' ? null : parseFloat(value)`), relevant whenever a `number | null` model field (like `homeLatitude`/`homeLongitude` here) must PUT a clean `null` rather than a bad value on clear.
- `angular.json`'s `test` architect target does NOT copy the `node_modules/leaflet/dist/images → assets/leaflet` glob (only the `build` target does) — fine as long as no spec exercises actual marker rendering; would need adding if a Leaflet page ever gets a component spec that checks icons.
- Chrome Headless observed at v150 on this machine as of 2026-07-27 (previously noted as 149) — version drifts, not itself meaningful.

## Flow Editor (Stufe 3b) — Frontend
- See [flow-editor-frontend.md](flow-editor-frontend.md) for full build history: `flow.model.ts`/`flow.service.ts` contract, ED-B3 through ED-B11 (list page, pickers, config panel, canvas, debug panel, editor orchestration, final review fixes), the import button, and the `@foblex/flow` link.
- Quick facts: routes are `/flows` (list) and `/flows/:id` (editor, `unsavedChangesGuard`); error-signal convention is `error.set(err.message)` (or `err.error?.message` for structured 400 bodies) + a `*__error` banner div, used across all flow pages.
- Known pre-existing (unrelated) full-suite failure: see the Testing section above for the current, correct count (3, not 4) — `HeaderComponent` is no longer part of it.
