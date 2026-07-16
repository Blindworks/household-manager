# Angular 21 Frontend Development - Project Memory

## Git Safety
- [Git concurrency hazard](git-concurrency.md) — repo/index shared across concurrent agent sessions; a commit can be silently clobbered by another session's amend. Always verify `git show --stat HEAD` right after committing.

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
6. **SmartDeviceListComponent** (`components/smart-device-list/`) - Extracted from `pages/devices` (Task 5 of smart-device-persistence plan) so both the user-facing Devices page and the Admin "Smart Plugs" tab reuse it (wired up in Task 6). Owns loading, grouping by `deviceType`, toggling, background+manual status refresh, and per-type rescan (`scanType(type)`) plus scan-all (`scanAllDeviceTypes()`). Uses `SmartDeviceService` (`services/smart-device.service.ts`) and `SmartDevice` model. `DevicesComponent` now only renders the page header (`dashboard`/`dashboard__header` SCSS) and hosts `<app-smart-device-list>` — no business logic left on the page. `AdminComponent`'s smart-plugs tab (`pages/admin/admin.component.html`) similarly now just hosts `<app-smart-device-list>` inside one `admin__card` — the old per-vendor Kasa/Tapo/Meross discovery-and-control panels were deleted (see [[admin-tab-vendor-panel-removal]]).

## Page Components
1. **DashboardComponent** (`/`) - Meter overview cards
2. **MarketingComponent** (`/marketing`) - Landing page
3. **MeterReadingsComponent** (`/meter-readings`) - Reading management
4. **UtilityPricesComponent** (`/utility-prices`) - Price management with grouped tables
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

## Testing (Karma/Jasmine)
- Test builder is `@angular-devkit/build-angular:karma` configured in `angular.json` — no standalone `karma.conf.js` file exists or is needed.
- ChromeHeadless launches and runs fine in this environment (verified Chrome 149 on Windows, no NEEDS_CONTEXT blockers encountered).
- Run a single spec from `frontend/`: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/component-name.spec.ts'`
- Spy pattern for services: `jasmine.createSpyObj('ServiceName', ['method1', 'method2'])`, then `serviceSpy.method.and.returnValue(of(...))`; provide via `{ provide: ServiceClass, useValue: serviceSpy }` in `TestBed.configureTestingModule.providers`.
- Standalone components go directly into `imports: [ComponentClass]` in the TestBed config (no module wrapper needed).
- Devices page: `pages/devices/devices.component.ts` — `ngOnInit` calls `loadDevices()` only (DB load), NOT `scanAllDeviceTypes()` (full network rescan). The scan method still exists for manual/explicit triggering elsewhere, just not on page load.
- Fresh git worktrees under `.claude/worktrees/<name>/frontend` do NOT have `node_modules` installed — `ng test` fails with "Could not find the '@angular-devkit/build-angular:karma' builder's node package." Run `npm install` once per worktree before the first test run (takes ~30-40s).
- `HttpTestingController.expectOne(url)` can be called twice for the same URL when only one matching request was fired and it hasn't been flushed yet — the request stays in the "open" list until `flush()`/`error()` resolves it, so a first `expectOne` to assert `.request.method` followed by a second `expectOne` + `.flush()` on the same URL both succeed (no need for the workaround of splitting into two separate calls). Confirmed with Angular's Karma/Jasmine harness in this repo (frontend, Chrome 149).

## Flow Editor (Stufe 3b) — Frontend
- See [flow-editor-frontend.md](flow-editor-frontend.md) for full build history: `flow.model.ts`/`flow.service.ts` contract, ED-B3 through ED-B11 (list page, pickers, config panel, canvas, debug panel, editor orchestration, final review fixes), the import button, and the `@foblex/flow` link.
- Quick facts: routes are `/flows` (list) and `/flows/:id` (editor, `unsavedChangesGuard`); error-signal convention is `error.set(err.message)` (or `err.error?.message` for structured 400 bodies) + a `*__error` banner div, used across all flow pages.
- Known pre-existing (unrelated) full-suite failure: `AppComponent`/`HeaderComponent`/`HeroComponent` specs fail with `NullInjectorError: No provider for ActivatedRoute!` — confirmed via `git stash` this predates flow-editor work; not a regression to chase.
