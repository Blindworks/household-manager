# Angular 21 Frontend Development - Project Memory

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
1. **HeaderComponent** - Navigation with Home, Zählerstände, Preise links
2. **MeterReadingFormComponent** - Meter reading entry form
3. **UtilityPriceFormComponent** - Price entry form with date range validation
4. **IconComponent** - Custom Lucide SVG icons (euro, calendar-check, save, trash-2, etc.)

## Page Components
1. **DashboardComponent** (`/`) - Meter overview cards
2. **MarketingComponent** (`/marketing`) - Landing page
3. **MeterReadingsComponent** (`/meter-readings`) - Reading management
4. **UtilityPricesComponent** (`/utility-prices`) - Price management with grouped tables

## Services
1. **MeterReadingService** - CRUD for meter readings
2. **UtilityPriceService** - CRUD for utility prices (getAllPrices, getPricesByMeterType, getCurrentPrice, createPrice, deletePrice)

## Models
- **MeterReading** + **UtilityPrice** interfaces
- **MeterType** enum: ELECTRICITY, GAS, WATER
- **MeterTypeUtils** - Static helpers (getLabel, getIcon, getUnit)

## Utility Price Feature
- Grouped tables by meter type, current/historical badges
- 4 decimal prices, de-DE date format
- Delete with confirmation dialog
- Two-column layout: sticky form + scrolling history
