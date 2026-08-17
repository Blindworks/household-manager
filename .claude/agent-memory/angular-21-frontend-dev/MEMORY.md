# Angular 21 Frontend Development - Project Memory

## UI-Fallen
- [UI-Fallen](template-pitfalls.md) — `@for track` braucht eindeutige Keys (Laufzeitfehler!); `<label>` um mehrere Buttons leitet Klicks fehl; helle Seiten vertragen keine `rgba(255,255,255)`-Styles; **ein Fehlerfeld pro Ursache** — ein paralleler Abruf leert es sonst; `ngModel` in einem `<form>` ist im Test erst nach `await fixture.whenStable()` ans Modell gebunden.
- Dashboard-Fussleisten-Karte (`dashboard.component.html`, `<footer class="lumina__footer">`): neue Status-Hinweiskarten koennen die vorhandenen `.lumina__secured*`-Klassen der Nuki-Karte direkt wiederverwenden (nur Icon-Farbe per Modifier ueberschreiben) — `--error`/`--primary`/`--secondary`/`--tertiary` sind CSS-Vars auf `.lumina` (dunkles Glass-Theme, `rgba(255,255,255,…)` hier bewusst richtig, anders als bei hellen Seiten). `dashboard.component.scss` ueberschreitet das 16 kB-Budget bereits vorher — nicht actionable.

## Git Safety
- [Git concurrency hazard](git-concurrency.md) — repo/index shared across concurrent agent sessions; a commit can be silently clobbered by another session's amend. Always verify `git show --stat HEAD` right after committing.
- [Kasa per IP](kasa-manual-add.md) has the recipe for committing only your own files when a parallel agent stages files elsewhere in the same index: `git commit -m "<msg>" -- <your paths...>` (pathspec after `-m`, never before).

## Usermanagement Feature (WP7, Task 17+18)
- [Usermanagement Frontend](usermanagement-frontend.md) — Header role-filtered nav (visibleNavLinks), admin pages for users/tokens/audit-log; header.component.spec.ts fix (provideRouter([]) + HTTP providers) dropped baseline fails 4→3.

## Project Structure
- **Location**: `C:\Users\bened\IdeaProjects\Household-Manager\frontend\src\app`
- **Architecture**: Angular 21 standalone components (no NgModules)
- **Styling**: SCSS with global variables in `src/styles.scss`; NOT every page uses the light theme — some (e.g. devices/smart-device-list) are dark-glass gradient pages, see [kasa-manual-add.md](kasa-manual-add.md). Check the actual page SCSS before assuming.
- **Components Location**: `src/app/components/` for shared, `src/app/pages/` for pages

## Service Layer Patterns
- Use `inject()` function for DI
- Base URL: `http://localhost:8080/api/v1/{resource}` (smart-device endpoints use `/api/devices` — no `/v1`, legacy path)
- Observable-based with RxJS, date conversion (ISO → Date)
- Centralized error handling in each service's `handleError`: prefers `error.error?.message` (backend's message body) over generic per-status text — 404 as null not error in some services

## Form Component Patterns
- ReactiveFormsModule + FormBuilder for full forms, custom validators
- `isFieldInvalid()` helper, loading states, auto-hide messages (success ~3s, error ~5s via `setTimeout`) — see `meter-reading-form.component.ts` for the canonical pattern
- EventEmitter for parent notification
- Small inline add/confirm widgets (not a full form) can use plain `[(ngModel)]` + `FormsModule` instead of reactive forms — see [kasa-manual-add.md](kasa-manual-add.md)

## Shared Components
1. **HeaderComponent** - Navigation; nav links defined in `navLinks` array in `.ts` file, NOT HTML.
2. **MeterReadingFormComponent** - Meter reading entry form
3. **UtilityPriceFormComponent** - Price entry form with date range validation
4. **IconComponent** - Custom Lucide SVG icons
5. **StatementImportComponent** - CAMT file upload for finance module
6. **SwitchListComponent** (`components/switch-list/`) - Presentational-only switch row list. Inputs: `switches`, `pendingIds`, `variant: 'tile' | 'dialog'`. Output: `toggled`. No injected services. `SwitchEntity` model in `models/switch.model.ts`.
7. **SmartDeviceListComponent** (`components/smart-device-list/`) - Reused by `pages/devices` and Admin's "Smart Plugs" tab. Owns loading, grouping by `deviceType`, toggling, background+manual status refresh, per-type/scan-all rescan, and (since 2026-08-17) manual "Kasa per IP" add — see [kasa-manual-add.md](kasa-manual-add.md). Uses `SmartDeviceService`/`SmartDevice` model. `DevicesComponent`/Admin's smart-plugs tab are thin hosts, no business logic (see [[admin-tab-vendor-panel-removal]]).

## Page Components
1. **DashboardComponent** (`/`) - Meter overview cards
2. **MarketingComponent** (`/marketing`) - Landing page
3. **MeterReadingsComponent** (`/meter-readings`) - Reading management
4. **UtilityPricesComponent** (`/utility-prices`) - Price management with grouped tables
4b. **CalendarComponent** (`/calendar`) - Monatsraster; Stammdaten (Kategorien+Nutzer) in `CalendarMasterDataService`; Aufbereitung pur in `shared/calendar-day-view.util.ts`. `update`/`updateOccurrence` sind PUT-Vollersetzung — `personUserIds` immer mitsenden (gleiche Falle wie `notes`).
5. **ZigbeeComponent** (`/zigbee`) - Live tiles + history chart + silent-outage banner (`ZigbeeService.getHealth()`, fire-and-forget-silent error handling).
6. **FinanceOverviewComponent** (`/finance`) - KPIs, ECharts donut + trend, A/B layout toggle
7. **FinanceTransactionsComponent** (`/finance/transactions`) - Transaction list, filters, inline categorization
8. **FinanceAccountsComponent** (`/finance/accounts`) - Bank account CRUD
9. **FinanceCategoriesComponent** (`/finance/categories`) - Category CRUD
10. **FinanceRulesComponent** (`/finance/rules`) - Categorization rule CRUD + apply-all
11. **FinanceBudgetsComponent** (`/finance/budgets`) - Budget CRUD + status bars
12. **FinanceRecurringComponent** (`/finance/recurring`) - Recurring payment detection + confirm
13. **AdminTractiveComponent** (`/admin/tractive`) - see [tractive-home-settings-frontend.md](tractive-home-settings-frontend.md)
14. **VisionComponent** (`/vision`) - see [vision-frontend.md](vision-frontend.md)

## Services
1. **MeterReadingService** - CRUD for meter readings
2. **UtilityPriceService** - CRUD for utility prices
3. **ZigbeeService** - REST: getDevices, getMeasurements, getHealth; baseUrl `/api/v1/zigbee`
4. **ZigbeeLiveService** - SSE EventSource on `/api/v1/zigbee/live`, named event `live`
5. **FinanceService** - Full finance API at `/api/v1/finance`
6. **SmartDeviceService** - `/api/devices` (Kasa/Tapo/Meross): CRUD, scan, toggle, refresh, `addKasaDeviceByIp` (2026-08-17)

## Models
- **MeterReading** + **UtilityPrice** interfaces; **MeterType** enum + **MeterTypeUtils** helpers
- **ZigbeeMeasurementType**, **ZigbeeDevice**, **ZigbeeMeasurement**, **ZigbeeLiveEvent**
- **finance.model.ts** - BankAccount, Category, TransactionDto, CategorizationRule, ImportSummary, OverviewResponse, TrendPoint, Budget, RecurringPayment + request/response types
- **smart-device.model.ts** - SmartDevice, SmartDeviceScanRequest, SmartDeviceUpdateRequest, KasaManualAddRequest

## Utility Price Feature
- Grouped tables by meter type, current/historical badges, 4 decimal prices, de-DE date format, delete with confirmation

## ECharts Pattern
- Import: `NgxEchartsDirective, provideEchartsCore` from `ngx-echarts`; `import * as echarts from 'echarts/core'` then `echarts.use([...])`
- Component decorator: `providers: [provideEchartsCore({ echarts })]`; template: `<div echarts [options]="chartOptions" class="chart"></div>`
- Reference: `airrohr-charts.component.ts`, `finance-overview.component.ts`; donut needs `PieChart` + `LegendComponent`

## Control Flow
- Use `@if`, `@for`, `@switch` — NOT `*ngIf`/`*ngFor` (deprecated). `@for` requires `track`. `@if (expr; as alias)` avoids NG8107 on non-nullable `?.`.

## SSE Live Service Pattern
- `EventSource` + named event listener, `BehaviorSubject` for connection status, Observable wraps connect/disconnect. Reference: `tasmota-live.service.ts`, `zigbee-live.service.ts`.

## Angular Budget
- `anyComponentStyle`: 16kB warning, 24kB error. Pre-existing warnings (not actionable): `energy.component.scss` (~16.16 kB), `dashboard.component.scss` (~21.5-25.5 kB, grows slowly with each dashboard change but stays a warning not an error).

## Third-Party Package Docs
- Check `node_modules/<pkg>/AI.md` before reading `index.d.ts` cold — some packages ship one, more version-accurate than training data. Still verify hard claims.
- [foblex-flow.md](foblex-flow.md) — Stufe-3b flow editor canvas library: Angular 19 compat, API surface, Chromium rendering gotcha (0×0 SVG + overflow:visible doesn't paint).

## Testing (Karma/Jasmine)
- [Detailed testing notes](testing-notes.md) — spy patterns, HttpTestingController gotchas, worktree npm-install requirement, auth-in-spec recipe, full-suite baseline (3 pre-existing fails, reconfirmed 2026-08-17).

## Flow Editor (Stufe 3b) — Frontend
- [flow-editor-frontend.md](flow-editor-frontend.md) — full build history: `flow.model.ts`/`flow.service.ts` contract, ED-B3–B11, import button, `@foblex/flow` link.
- Quick facts: routes `/flows` (list) and `/flows/:id` (editor, `unsavedChangesGuard`); error-signal convention `error.set(err.message)` + `*__error` banner div, used across all flow pages.
