# Angular 21 Frontend Development - Project Memory

## Project Structure
- **Location**: `C:\Users\bened\IdeaProjects\Household-Manager\household-manager-frontend\household-manager`
- **Architecture**: Angular 21 standalone components (no NgModules)
- **Styling**: SCSS with global variables in `src/styles.scss`
- **Components Location**: `src/app/components/` for shared components, `src/app/pages/` for page components

## Key Patterns Established

### Component Architecture
- All components use `standalone: true`
- Separate files for .html, .ts, and .scss (no inline templates/styles)
- Use signals for reactive state (e.g., `signal<boolean>()` in header for mobile menu)
- RouterLink and RouterLinkActive for navigation

### Global Styling System
- **CSS Variables** defined in `:root` for consistent theming
  - Primary color: `--color-primary: #2563eb`
  - Secondary color: `--color-secondary: #10b981`
  - Spacing scale: `--spacing-xs` to `--spacing-2xl`
  - Typography scale: `--font-size-sm` to `--font-size-4xl`
  - Shadows, transitions, border-radius all defined as variables
- **Utility Classes**: `.btn`, `.btn--primary`, `.btn--secondary`, `.container`, `.text-center`, margin helpers
- **Responsive**: Mobile-first approach with `@media (min-width: 768px)` breakpoints

### Component Inventory

#### Shared Components
1. **HeaderComponent** (`src/app/components/header/`)
   - Sticky header with navigation
   - Mobile-responsive with hamburger menu
   - Uses signals for menu state
   - RouterLink integration with active states

2. **HeroComponent** (`src/app/components/hero/`)
   - Gradient background with pattern overlay
   - Two-column layout (text + visual)
   - Call-to-action buttons
   - Animated statistics display

3. **FeaturesComponent** (`src/app/components/features/`)
   - Grid layout (1 col mobile, 2 col tablet, 3 col desktop)
   - Feature interface with icon, title, description, color, availability
   - "Demnächst" badge for unavailable features
   - Color-coded icons with background tinting

4. **FooterComponent** (`src/app/components/footer/`)
   - Multi-column layout with brand, quick links, support, legal
   - Social media icons
   - Dynamic copyright year
   - Dark background theme

#### Page Components
1. **DashboardComponent** (`src/app/pages/dashboard/`)
   - Main application dashboard (home page at `/`)
   - Displays meter reading overview cards (Strom, Gas, Wasser)
   - Card-based layout with icons, colors, and trend indicators
   - Quick action buttons for navigation
   - Info box with helpful tips
   - Responsive grid: 1 col mobile, 2 col tablet, 3 col desktop
   - Mock data with MeterCardData interface (TODO: replace with API)

2. **MarketingComponent** (`src/app/pages/marketing/`)
   - Marketing/landing page at `/marketing`
   - Combines Hero + Features sections (original home page content)

3. **MeterReadingsComponent** (`src/app/pages/meter-readings/`)
   - Placeholder for meter reading management at `/meter-readings`
   - Shows "In Development" message with planned features
   - Will implement: reading entry, history, charts, export

4. **HomeComponent** (`src/app/pages/home/`) - DEPRECATED
   - Legacy component that redirects to dashboard
   - Kept for backwards compatibility only

### Routing Configuration
- Lazy loading pattern: `loadComponent: () => import(...).then(m => m.Component)`
- Page titles configured per route
- Wildcard route redirects to home
- **Current Routes**:
  - `/` → DashboardComponent (main app page)
  - `/marketing` → MarketingComponent (promotional content)
  - `/meter-readings` → MeterReadingsComponent (meter management)

## Best Practices Applied
- **TypeScript**: Strict typing, readonly properties for constants, interface definitions
- **Accessibility**: ARIA labels, semantic HTML, keyboard navigation support
- **Performance**: Lazy loading, animations with CSS only, optimized asset loading
- **Responsive Design**: Mobile-first with progressive enhancement
- **Modern Angular**: Control flow syntax (`@if`, `@for`), signals, inject function ready

## Dashboard Design Patterns
- **Card-Based Layout**: Used for meter display with hover effects (translateY, shadow)
- **Trend Indicators**: Visual feedback with color-coded backgrounds (up=red, down=green, stable=gray)
- **Icon Systems**: Emoji icons with colored circular backgrounds (20% opacity of main color)
- **Responsive Grids**: `grid-template-columns: repeat(auto-fit, minmax(300px, 1fr))`
- **German Localization**: All labels in German, number formatting with `'de-DE'` locale

## Next Steps / TODO
- Implement meter-readings detail page with forms
- Connect dashboard to backend API (replace mock data)
- Add form components for data entry
- Add charts/graphs for consumption visualization
- Add state management if complexity grows
- Implement authentication/authorization
