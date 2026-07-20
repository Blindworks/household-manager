# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Household-Manager is a full-stack application for managing household utilities and inventory. The application is split into a separate frontend and backend with the following structure:

- **Frontend**: Angular 19 (standalone mode) with SCSS
- **Backend**: Spring Boot 3.4.1 with Java 21 and MySQL/MariaDB database

### Current Features
- **Utility Meter Readings**: Track electricity, gas, and water consumption with automatic consumption calculations
- **Utility Price Management**: Track and manage utility pricing over time
- **Smart Device Integration**:
  - TP-Link Kasa smart plugs (HS100)
  - TP-Link Tapo devices with local control
  - Tasmota electricity monitoring devices (live and historical data with automated polling)
- **Air Quality Monitoring**: Airrohr sensor integration with live data and automated polling
- **Data Visualization**: ECharts-based consumption and air quality charts
- **CSV Import**: Bulk import of historical meter readings
- **Docker Deployment**: Docker Compose setup for containerized deployment

### Planned Features
- **Phase 2**: Product inventory management system with barcode scanning for household items
- Additional household management features (TBD)

## Project Structure

```
household-manager/
├── frontend/                      # Angular 19 application
│   ├── src/app/
│   │   ├── components/           # Reusable UI components
│   │   ├── pages/                # Page-level components (routes)
│   │   ├── services/             # API services and business logic
│   │   ├── models/               # TypeScript interfaces and types
│   │   └── shared/               # Shared utilities and components
│   └── proxy.conf.json           # API proxy configuration
├── backend/                       # Spring Boot application
│   ├── src/main/java/com/household/manager/
│   │   ├── controller/           # REST API controllers
│   │   ├── service/              # Business logic services
│   │   ├── repository/           # JPA repositories
│   │   ├── model/entity/         # JPA entities
│   │   ├── dto/                  # Data Transfer Objects
│   │   ├── config/               # Spring configuration classes
│   │   ├── exception/            # Custom exceptions and handlers
│   │   ├── kasa/                 # Kasa device integration
│   │   ├── tapo/                 # Tapo device integration
│   │   └── importer/             # CSV import functionality
│   └── src/main/resources/
│       ├── application.properties  # Application configuration
│       └── db/changelog/           # Liquibase migration files
├── scripts/                       # Helper scripts (test data, etc.)
├── tablet-app/                    # Android-Kiosk-App für das Wandtablet
├── flow-mcp-server/               # MCP-Server: KI-Schnittstelle zur Flow-Engine
└── docker-compose.yml            # Docker deployment configuration
```

## Frontend (Angular 19)

### Technology Stack
- Angular 19 in standalone mode (no NgModules)
- TypeScript with separate HTML template files
- SCSS for styling
- ECharts (via ngx-echarts) for data visualization
- Component-based architecture with pages and shared components

### Development Commands

```bash
cd frontend

# Install dependencies
npm install

# Start development server (with proxy to backend)
npm start
# OR
ng serve --proxy-config proxy.conf.json

# Build for production
ng build --configuration production

# Run tests
ng test

# Run linting (if configured)
ng lint
```

**Note**: The dev server uses `proxy.conf.json` to proxy API requests to `http://localhost:8080`.

### Frontend Conventions
- Use standalone components exclusively
- Keep HTML templates in separate `.html` files (not inline)
- Use SCSS for all styling
- Follow Angular style guide for component structure
- Organize components into `components/`, `pages/`, and `shared/` directories
- Use ECharts for data visualization via `ngx-echarts`

## Backend (Spring Boot)

### Technology Stack
- Spring Boot 3.4.1
- Java 21
- MySQL/MariaDB database
- Lombok for boilerplate reduction
- Liquibase for database migrations
- Maven for dependency management
- JmDNS for local device discovery
- Apache Commons CSV for CSV import functionality

### Development Commands

```bash
cd backend

# Build the application
mvn clean install

# Run the application
mvn spring-boot:run

# Run tests
mvn test

# Run specific test
mvn test -Dtest=YourTestClass

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

### Database Setup

The application uses Liquibase for database migrations. Migration files are located in `src/main/resources/db/changelog/`.

**Local Database Setup:**
1. Install MySQL/MariaDB locally
2. Create database: `CREATE DATABASE household_manager;`
3. Update `application.properties` with local credentials (**Note**: This project uses `.properties` files, not `.yml`)
4. Liquibase will automatically apply migrations on startup

### Backend Conventions
- Use Lombok annotations (@Data, @Builder, @Slf4j, etc.) to reduce boilerplate
- All database schema changes must be done through Liquibase changesets
- Never modify the database schema manually
- Follow Spring Boot best practices for layered architecture (Controller → Service → Repository)

## Database Schema

### Current Entities
- **Meter Readings**: Tracks utility consumption (electricity, gas, water)
  - Meter type (ELECTRICITY, GAS, WATER)
  - Reading value, reading date, reading week
  - Consumption calculated between consecutive readings
  - Notes field for additional context
- **Utility Prices**: Tracks utility pricing over time
  - Meter type, price per unit, valid from/to dates
  - Price history for cost calculations
- **Tasmota Electricity Readings**: Historical readings from Tasmota devices
  - Device identification, consumption metrics
  - Timestamps for time-series analysis
- **Airrohr Readings**: Air quality sensor data
  - PM2.5, PM10, temperature, humidity measurements
  - Sensor identification and timestamps
- **Entity Tile Visibility**: Per-entity visibility rules for dashboard tiles
  - Tile key (currently `switches`), visibility (ALWAYS / WHEN_ON / NEVER; no row = AUTO)
  - Controls which switches appear on the dashboard switch tile and in which order

### Planned Entities (Phase 2)
- **Products**: Household inventory items
- **Product Stock**: Current quantities
- **Barcode**: Product identification for scanning

## Docker Deployment

The project includes a `docker-compose.yml` for containerized deployment:

```bash
# Build and start all services
docker-compose up --build

# Stop all services
docker-compose down
```

**Configuration**:
- Backend runs on port 8080
- Frontend runs on port 4200 (served via nginx)
- Connects to external MariaDB network (`mariadb_default`)
- Environment variables configured in docker-compose.yml

## Development Workflow

1. **Frontend Development**: Work in `frontend/` directory with Angular CLI
2. **Backend Development**: Work in `backend/` directory with Spring Boot
3. **API Communication**:
   - In development: Frontend uses proxy (`proxy.conf.json`) to forward API requests to `http://localhost:8080`
   - In production: Backend runs on port 8080, CORS configured for frontend origin
4. **Database Changes**: Create Liquibase changelog files in `src/main/resources/db/changelog/changes/` for any schema modifications
5. **Smart Device Features**: Ensure local network access for Kasa/Tapo/Tasmota/Airrohr device integrations

## Testing

- **Frontend**: Karma/Jasmine tests with `ng test`
- **Backend**: JUnit tests with Spring Boot Test
- Test coverage for both utility tracking and future product management features

## Key Technical Decisions

- **Standalone Angular**: Modern Angular without NgModules for better tree-shaking and simpler architecture
- **Separate HTML/TS**: Better separation of concerns and easier template editing
- **Liquibase**: Version-controlled database migrations for consistent schema across environments
- **Lombok**: Reduced boilerplate in Java entities and DTOs
- **Cloud-Based Device Control**: TP-Link Cloud API for Tapo devices with automatic token management; local control for Kasa and Tasmota
- **JmDNS Discovery**: Automatic discovery of local network devices
- **ECharts**: Professional charting library for consumption and sensor data visualization
- **Scheduled Polling**: Automated data collection from Tasmota and Airrohr devices using Spring's `@Scheduled`
- **CSV Import**: Support for bulk historical data import
- **Docker Compose**: Containerized deployment with external MariaDB network

## Smart Device Integrations

### TP-Link Kasa (HS100)
- Local TCP communication with proprietary encryption
- Device discovery via UDP broadcast
- Turn devices on/off and get status
- Implementation in `backend/src/main/java/com/household/manager/kasa/`

### TP-Link Tapo
- Remote control via TP-Link Cloud API with token-based authentication
- Device discovery via cloud API (lists all devices registered in Tapo account)
- Automatic token management with 24-hour caching
- Full device control: on/off, brightness, color, color temperature, energy usage
- Implementation in `backend/src/main/java/com/household/manager/tapo/`

### Tasmota Electricity Monitoring
- HTTP REST API for energy consumption data
- Live readings and historical data
- Automated polling service with configurable intervals
- Stores readings in database for historical analysis
- Implementation in `backend/src/main/java/com/household/manager/service/TasmotaElectricity*`

### Airrohr Air Quality Sensor
- HTTP JSON API for PM2.5, PM10, temperature, humidity
- Automated polling service
- Historical data storage and visualization
- Implementation in `backend/src/main/java/com/household/manager/service/Airrohr*`

### Amazon Alexa (Text-to-Speech)
- Inofficial integration with `alexa.amazon.<domain>` (same approach as alexa-remote-control / alexa_media_player); there is no official push-TTS API
- Login as an in-app flow (email/password + MFA, optional captcha); only the refresh token is persisted, never the credentials
- Manual announcements, scheduled announcements, and an internal `AlexaAnnouncementService` building block for future automatic notifications
- TTS via `/api/behaviors/preview`: `Alexa.Speak` (single device, no chime) and `AlexaAnnouncement` (one or more devices, with chime)
- Device identity via stable `serialNumber`; the whole Amazon-specific, brittle flow is isolated in `AlexaAuthService`/`AlexaApiClient`
- Implementation in `backend/src/main/java/com/household/manager/alexa/`; frontend page under `frontend/src/app/pages/announcements/`

### Amazon Smart Air Quality Monitor
- No official/local API; values are read via the inofficial Alexa API through the alexa-remote2 sidecar
- Discovery via the behaviors "entities" API (`getSmarthomeEntities`), filtered to `deviceType === 'AIR_QUALITY_MONITOR'` (the older `/api/phoenix` discovery returns only `{success:true}`); state via `querySmarthomeDevices(entityIds, 'ENTITY')`
- Amazon exposes the sensors as bare numbered `Alexa.RangeController` instances with NO asset labels; the instance→sensor mapping is fixed in the sidecar (`RANGE_INSTANCE_SENSORS` in `alexa-sidecar/smarthome.js`), verified against the Alexa app. IAQ is 0-100 where HIGHER is better
- Sidecar endpoints: `GET /smarthome/air-quality-monitors` (discovery), `GET /smarthome/air-quality-monitors/state` (normalized flat values), `GET /smarthome/raw` (debug)
- Backend polls every 5 minutes (`AlexaAirQualityPollingService`), stores readings in `alexa_air_quality_readings`, reports entity states (`EntitySource.ALEXA`) usable as flow triggers
- Sensors: IAQ score, PM2.5, VOC, CO, temperature, humidity; device identity via the stable hardware serial (`applianceId`)
- Frontend: indoor section on the air quality page (`alexa-air-quality-section.component`)

### Flow-Engine: KI-Autoring via MCP
- Flows werden primär durch eine KI erstellt und gepflegt (Entscheidung 2026-07-20); der visuelle Editor bleibt als Viewer/Debug-Werkzeug, wird aber nicht weiter ausgebaut
- `flow-mcp-server/` (Node ≥20, stdio) wrappt die REST-API `/api/v1/flows` als MCP-Tools; Registrierung für Claude Code in `.mcp.json` (Server-Name `household-flows`), Setup: `cd flow-mcp-server && npm install`
- Tools: `flow_list/get/create/update/deploy/set_enabled/delete`, `flow_node_types` (Katalog), `flow_inject` + `flow_debug_entries` (Testen), Lookups `flow_list_entities` (entityId), `flow_list_switch_devices` (deviceId), `flow_list_alexa_devices` (deviceSerials)
- Typischer Ablauf: `flow_node_types` + Lookups → `flow_create` (entsteht deaktiviert, nicht deployt) → `flow_deploy` (liefert ValidationResult; 400 wird als fachliches Ergebnis durchgereicht) → `flow_set_enabled`
- Flow-JSON-Format: `docs/flows/flow-import-format.md`; Design: `docs/superpowers/specs/2026-07-20-flow-mcp-server-design.md`

### Wandtablet (Präsenzerkennung)
- Eigene Android-Kiosk-App in `tablet-app/` (Kotlin, minSdk 29): Dashboard im Vollbild-WebView, Anwesenheitserkennung per Frontkamera (CameraX: Bewegung weckt, ML-Kit-Gesicht hält wach), Soft-Off via schwarzem Overlay + Helligkeit 0
- Präsenz-Meldung an `POST /v1/tablet-presence/{tabletId}`; Spiegelung als `binary_sensor.tablet_<id>_presence` (`EntitySource.TABLET`) im Entity-State-Layer, nutzbar als Flow-Trigger; ausbleibender Heartbeat → `unavailable`
- Backend-Implementierung in `backend/src/main/java/com/household/manager/tablet/`

## Code Quality Standards

This project follows **Clean Code** principles across both frontend and backend:

### General Clean Code Principles
- **Meaningful Names**: Use intention-revealing names for variables, functions, and classes
- **Single Responsibility**: Each class/function should have one clear purpose
- **Small Functions**: Keep functions focused and concise
- **DRY (Don't Repeat Yourself)**: Extract common logic into reusable components/services/utilities
- **Comments**: Code should be self-documenting; only comment when necessary to explain "why", not "what"

### Backend (Java/Spring Boot)
- Use meaningful names for entities, services, and repositories (e.g., `MeterReadingService`, not `MRS`)
- Keep controllers thin - business logic belongs in service layer
- Use DTOs for API requests/responses to separate API contract from domain models
- Leverage Lombok thoughtfully - don't hide complex logic behind annotations
- Write focused methods that do one thing well
- Use Optional for nullable return types
- Proper exception handling with custom exceptions where appropriate
- Use `@Slf4j` for logging instead of manual logger instantiation
- For scheduled tasks, use `@Scheduled` annotation with proper configuration
- Smart device integrations should handle connection failures gracefully

### Frontend (Angular/TypeScript)
- Components should be focused on presentation, delegate logic to services
- Services should handle business logic and API communication
- Use meaningful component/service names (e.g., `MeterReadingFormComponent`, `MeterReadingService`)
- Keep TypeScript methods small and focused
- Avoid complex logic in templates - move to component methods
- Use TypeScript types and interfaces properly (avoid `any`)
- Reactive programming with RxJS observables for asynchronous operations

### Testing
- Write tests that are readable and maintainable
- Use descriptive test names that explain what is being tested
- Follow AAA pattern (Arrange, Act, Assert)
- Test business logic thoroughly, especially consumption calculations and inventory tracking
