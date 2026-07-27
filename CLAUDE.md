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
- **Switch Confirmation**: `confirm_required` flag on `entity_states`
  - UI-only guard: the dashboard shows a confirmation dialog (with the real switch row) before toggling; flows and the API keep switching directly
- **Switch Power Display**: switch rows show live wattage (no schema change)
  - `GET /api/v1/switches` enriches each switch with `powerWatts` from the matching `sensor.<source>_<ref>_power` entity (same source + sourceRef)
  - Only shown while the switch is on and the reading is fresh (<5 min); display-only, no threshold logic; today effectively Meross (Shelly has no switch entities)
- **Meross Electricity Polling**: polls *all* metering plugs of the account, no curated device list
  - One cloud login + one device-list call per cycle (the list call is uncached — never fetch it per device)
  - Offline devices are skipped; devices whose MQTT answer shows no `Appliance.Control.Electricity` ability are remembered and skipped from then on, so only real metering plugs cost an MQTT connect per cycle
- **Power Consumer Tile**: `GET /api/v1/power-consumers` lists all `deviceClass: power` sensors, biggest first (no schema change)
  - Excluded via `NON_CONSUMER_SOURCES`: `TASMOTA` and `ANKER_SOLIX` (house balance, not single devices) and `SHELLY` (attached to the balcony solar plants — it measures generation, not consumption)
  - `PowerConsumerQueryService.findConsumer(entityId)` is the single definition of "counts as a consumer" — the tile list and the history endpoint both ask it, so they can never drift apart
- **Power Consumption History**: `entity_power_history` stores the power curve of every power sensor
  - Written by `PowerHistoryRecorder`, an `@EventListener` on `EntityStateChangedEvent` — source-agnostic, so any integration reporting `deviceClass: power` gets a history without its own code; the event only fires on value changes, so an idle device costs nothing
  - `power_watts = NULL` deliberately marks a measurement gap (sensor `unavailable`); the chart breaks the line there (`connectNulls: false`) instead of faking continuity
  - **Caveat:** this only triggers when a source actually reports `unavailable`. `MerossElectricityPollingService` deliberately does *not* (a transient read error must not cause a state transition, or edge-triggered flows misfire) — offline plugs simply drop out of the polled list. So for today's only consumer source, an outage produces no row at all and the chart draws straight through it. Making Meross report `unavailable` would fix the chart but risks firing flows like #5 — decide deliberately before changing it
  - `PowerHistoryAggregationJob` compacts to minute averages after 10 min, to hour averages after 2 days, and deletes after 30 days. It only compacts **completed** buckets (upper window bound rounded down via `truncatedTo`) — otherwise an already-compacted point would keep getting re-averaged with each newly-aged raw point and the "average" would degrade into the last value
  - `GET /api/v1/power-consumers/{entityId}/history?range=DAY|WEEK|MONTH` returns the series; clicking a consumer row on the dashboard opens the chart dialog
  - The chart is empty right after deployment and fills over time — there is no retroactive data

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

### Nuki Smart Lock (Web API)
- Nuki Smart Lock Pro über die Cloud-API `https://api.nuki.io` (Bearer-Token von web.nuki.io, env `NUKI_API_TOKEN`); bewusste Entscheidung gegen lokales MQTT
- Polling alle 30 s (`NukiPollingService`); Zustände als `lock.nuki_<smartlockId>` (locked/unlocked/unlatched/jammed/…) und optional `binary_sensor.nuki_<smartlockId>_door` (on = offen); Cloud-Ausfall → `unavailable`
- Aktionen: verriegeln/entsperren/Tür öffnen via `POST /v1/nuki/locks/{smartlockId}/actions`; nach jeder Aktion sofortiges Nachpollen
- Flow-Engine: Zustands-Trigger über den Entity-State-Layer, Aktions-Node `nuki-lock-action` (smartlockId als String!)
- Dashboard: Türschloss-Kachel im Footer (ersetzt die statische „System gesichert“-Karte); Verriegeln direkt, Entsperren/Tür öffnen mit Bestätigungsdialog
- Implementierung in `backend/src/main/java/com/household/manager/nuki/`

### Telegram-KI-Assistent
- Telegram-Bot direkt im Spring-Backend (`backend/src/main/java/com/household/manager/telegram/`); Long-Polling gegen die Bot-API — keine Portfreigabe nötig
- Sprachverständnis über die Anthropic Messages API mit Tool-Use (`TELEGRAM_AGENT_MODEL`, Default Haiku); Tools sind dünne Wrapper um bestehende Services (Schalter, Entity-States, Verbraucher, Zähler, Modi, Nuki)
- **Sicherheit:** Allowlist über `TELEGRAM_ALLOWED_CHAT_IDS` (fremde Chats werden komplett ignoriert); das Nuki-Tool kann ausschließlich verriegeln — unlock/unlatch existiert im Tool-Vertrag nicht (Code-Garantie, kein Prompt-Schutz)
- Secrets nur per Env: `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`; ohne vollständige Konfiguration startet das Polling nicht
- Gesprächskontext pro Chat in-memory (TTL 30 Min); keine DB-Änderung, kein Frontend
- Push-Richtung: Flow-Node `telegram-send` (Nachricht an alle erlaubten oder einen bestimmten Chat), Platzhalter wie beim Alexa-Node
- Bot-Setup: Bot bei @BotFather anlegen → Token als `TELEGRAM_BOT_TOKEN`; eigene Chat-ID z. B. über @userinfobot ermitteln → `TELEGRAM_ALLOWED_CHAT_IDS`

### Blink-Gesichtserkennung (blink-vision-Sidecar)
- Python-Sidecar `blink-vision/` (FastAPI + blinkpy + InsightFace `buffalo_s` auf CPU): pollt Local-Storage-Clips der Blink-Türkamera (Sync Module 2 + USB; der Abruf läuft trotzdem über die Blink-Cloud, Latenz 15–45 s), erkennt Gesichter und meldet Ergebnisse per Webhook ans Backend
- Login als In-App-Flow (E-Mail/Passwort + 2FA-PIN); persistiert wird nur das Session-Token im Volume, **nie** Zugangsdaten — `blink.save()` würde das Klartext-Passwort mitschreiben, deshalb eigenes `_save_session()` mit Filter
- **Alle blinkpy-Spezifika leben ausschließlich in `blink-vision/app/blink_client.py`.** Verifiziert gegen blinkpy 0.25.9 (`blink-vision/BLINKPY-API.md`); die Paket-README ist veraltet. Fallstricke: kein `key_required` (2FA kommt als `BlinkTwoFARequiredError`), `send_2fa_code()` statt `send_auth_key()`, Manifest ist aufsteigend sortiert (`reversed()` für den neuesten Clip), und `start()`/`send_2fa_code()` melden Login-Fehler per `return False` statt per Exception
- Backend `vision/`: Personen + Referenzfotos führend in der DB (`vision_person`, `vision_person_photo`, `vision_recognition`); Embeddings werden an den Sidecar gepusht, der sie beim Start zusätzlich über `GET /v1/vision/embeddings` zieht
- Jede Erkennung feuert `EntityEventFired` auf `event.vision_blink_door_person` — State = Personenname oder `unknown`, Attribute `personId`/`confidence`/`unknownFaces`; ausbleibender Heartbeat → `unavailable`
- Auto-Unlock-Flow (#6): `entity-event-trigger` filtert über `action` = Personenname (so kann `unknown` die Tür nie öffnen) → `rate-limit` 60 s → `nuki-lock-action` unlatch. Ist deployt, aber bewusst **deaktiviert**; pro weiterem Bewohner einen zusätzlichen Trigger mit dessen exaktem Namen ergänzen
- **Kopplung beachten:** Wird eine Person umbenannt, greift Flow #6 für sie nicht mehr — der Trigger filtert auf den exakten `vision_person.name`. Nach jedem Umbenennen den Flow nachziehen
- Das Foto-Spoofing-Risiko (2D-Kamera kann keine Lebenderkennung) ist dokumentiert und vom Nutzer bewusst akzeptiert
- Frontend-Seite „Gesichtserkennung" (`pages/vision/`): Blink-Login, Personenverwaltung mit Foto-Upload, Erkennungshistorie
- Der Sidecar hat **keine Authentifizierung** und ist deshalb absichtlich nicht ins LAN gemappt (nur `app_net`)
- Kameraauswahl: `BLINK_CAMERA_NAME` gewinnt (Rand-Leerzeichen werden toleriert — Blink-Namen wie `'Wohnzimmer '` haben oft eins), sonst die Kamera mit `camera_type == doorbell`, sonst die einzige vorhandene. Ist die Wahl mehrdeutig, wird **nichts** ausgewertet statt geraten — sonst liefe eine Innenraumkamera durch die Gesichtserkennung und könnte die Haustür auslösen
- Die Backend-URLs im Sidecar brauchen den Kontextpfad `/api` (`server.servlet.context-path`); ohne ihn antwortet das Backend mit 404 und die Richtung Sidecar → Backend ist still tot

### Tractive-Hundetracker
- Inoffizielle Cloud-API `https://graph.tractive.com/4` (kein Sidecar, reines Java in `backend/src/main/java/com/household/manager/tractive/`), verifiziert gegen `aiotractive` (Referenzimplementierung der Home-Assistant-Integration). Pflicht-Header `x-tractive-client` auf allen Requests, authentifizierte Requests zusätzlich `Authorization: Bearer <token>` **und** `x-tractive-user: <userId>`. Der Hardware-Endpunkt `/device_hw_report/{trackerId}/` braucht einen abschließenden Slash, der Positions-Endpunkt nicht
- **Tractive kennt kein Refresh-Token** — `POST /auth/token` liefert nur `access_token` + `expires_at`. Login als In-App-Flow; persistiert wird ausschließlich das Token, nie die Zugangsdaten, in der Ein-Zeilen-Tabelle `tractive_auth` (Liquibase `20260725-0041`). Ein Token mit unter einer Stunde Restlaufzeit gilt als abgelaufen (wie bei `aiotractive`); läuft es ab, werden die Entitäten `unavailable` und das Frontend zeigt wieder das Login-Formular
- Entitäten pro Tracker (`EntitySource.TRACTIVE`, `entitystate/mapper/TractiveEntityMapper.java`): `sensor.tractive_<trackerId>_location` (State = Zonenname, `away` oder `unknown`; Attribute `latitude`/`longitude`/`accuracy`/`sensorUsed`/`positionTime`), `sensor.tractive_<trackerId>_battery`, `binary_sensor.tractive_<trackerId>_charging`
- **„Ist der Hund zu Hause?"** ist `binary_sensor.tractive_<trackerId>_home` (`on`/`off`, `deviceClass: presence`, Attribute `basis`/`stale`/`distanceMeters`/`positionAgeMinutes`/`positionTime`). `TractiveHomeResolver` ist die **einzige** Definition von „zu Hause" — Entity-Mapper und `/v1/tractive/pets` fragen dieselbe Klasse, damit Dashboard-Kachel und Flow-Trigger nicht auseinanderlaufen können — die API bewertet dabei den Zeitpunkt des letzten erfolgreichen Polls (`lastPolledAt`), nicht `jetzt`, sonst würde ein eingefrorener Snapshot während eines Ausfalls über die Stille-Schwelle laufen und Kachel und Entität würden dauerhaft widersprechen. Regeln in dieser Reihenfolge: keine Home-Koordinaten ⇒ keine Entität; `charging` ⇒ zu Hause (vor der Positionsprüfung, weil der Tracker auf der Ladeschale oft keinen frischen Bericht hat); frischer Bericht ⇒ Distanz ≤ `home-radius-meters`; Bericht still (≥ `powered-off-after-minutes`) **und** Akku ≥ `powered-off-min-battery-percent` **und** Distanz ≤ `max(home-arrival-radius-meters, home-radius-meters)` ⇒ zu Hause (`basis=powered_off`); sonst letztes Positionsurteil mit `stale=true`. Ohne jede Aussage wird **kein Update gemeldet** — der Entity-State-Layer behält den letzten Wert, ein Zustand wird nie geraten
- **Der Tracker wird zu Hause ausgeschaltet, und die API kennt dafür kein Statusfeld** — erkennbar nur an einem ausbleibenden Positionsbericht. Weil „Akku unterwegs leergelaufen" in der API identisch aussieht, verlangt diese Deutung zwei unabhängige Belege (gesunder Akku **und** Heimnähe im weiten Radius) und ist fail-safe: fehlt `batteryLevel`, greift sie nie. Ein Bericht *aus der Zukunft* (Uhren-Versatz zur Cloud) wird auf Alter 0 geklemmt und geloggt — ein dauerhafter Versatz würde die Regel sonst unerreichbar machen. **Offen:** ob `device_hw_report` bei ausgeschaltetem Tracker überhaupt noch einen Akkustand liefert; tut es das nicht, greift die Regel nie und der zuletzt gesehene Akkustand müsste im Poller zwischengespeichert werden
- **Was „zu Hause" heißt, steht in der Datenbank, nicht in der Konfiguration** (`application_settings`, Kategorie `TRACTIVE_HOME`; Fassade `TractiveHomeSettingsService`, gepflegt unter Admin → Hundetracker-Zuhause, Route `admin/tractive`, Leaflet-Karte zum Anklicken). Pflegbar sind Koordinaten, Home-Radius, Ankunftsradius, Stille-Schwelle, Mindest-Akkustand und der Zonenname. Diese Werte haben eine **Doppelrolle**: Fallback-Zone für den Location-Sensor *und* verbindliche Home-Definition. Ohne Koordinaten fehlen Entität, Badge und Dashboard-Kachel — sichtbar nur an einer einmaligen Warnung im Log, deshalb hat die Admin-Seite ein Hinweisbanner. `TRACTIVE_HOME_LAT`/`TRACTIVE_HOME_LON` und die zugehörigen `tractive.*`-Properties gibt es **nicht mehr**. `powered-off-after-minutes` steht konservativ auf 60, weil das reale Melde-Intervall im Sparmodus unverifiziert ist; nach ein paar Tagen Betrieb anhand von `positionAgeMinutes` nachziehen — das geht jetzt ohne Redeploy
- **Lesen wirft nie.** `TractiveHomeSettingsService` parst defensiv: unlesbare oder unplausible Werte (auch ein per Hand eingetragener Radius jenseits von `MAX_RADIUS_METERS` = 100 km) fallen auf den Default zurück und werden geloggt. Der Poller läuft jede Minute; ein Tippfehler in der DB darf ihn nicht lahmlegen. `TractiveHomeResolver.resolve()` liest die Einstellungen **einmal pro Aufruf** in eine lokale Variable, damit eine Bewertung einen konsistenten Satz sieht
- **Validierung an der API-Grenze braucht `Double.isFinite`:** Jeder Vergleich mit `NaN` ergibt `false`, und Jackson erzeugt aus dem String `"NaN"` klaglos ein `Double.NaN`. Bei den *einseitigen* Koordinatenprüfungen (`Math.abs(x) > 90`) ist die `isFinite`-Prüfung deshalb tragend; bei den *beidseitigen* Radius-Schranken ist sie redundant, steht aber bewusst da. Ohne sie landet ein `NaN` in der DB, wird beim Lesen zu „nicht konfiguriert" — und die Entität verschwindet wortlos
- **Sicherheit hängt an der Matcher-Reihenfolge:** `/v1/tractive/home-settings` steht im ADMIN-Block von `SecurityConfig`, der **vor** der generischen Regel `GET /v1/**` → KIOSK steht. Ohne diese Reihenfolge könnte das Wandtablet die Home-Definition lesen; `SecurityRulesTest` hält das fest. Der Matcher ist methodenlos und deckt damit auch `PUT` ab. Änderungen landen im Audit-Log (`tractive.home-settings.update`)
- Neue Einstellungen wirken **beim nächsten Poll** (≤ 60 s). Kachel und Badge rechnen bei jedem Abruf frisch gegen die zwischengespeicherten Positionsdaten und übernehmen Schwellenänderungen praktisch sofort; nur die Entität wartet auf den Poll
- **Die Home-Entität wird bei einem Cloud-Ausfall bewusst *nicht* `unavailable`** (die übrigen schon): zu Hause ist „keine Daten" der Normalzustand, und der letzte Wert ist genau die gewünschte Aussage. **Kehrseite (bewusst akzeptiert 2026-07-26):** Tractive gibt kein Refresh-Token aus — meldet sich niemand neu an, friert die Entität unbegrenzt auf ihrem letzten Wert ein, ohne jedes Anzeichen. Ein fünfminütiger Aussetzer und eine seit Wochen vergessene Anmeldung sind an ihr nicht unterscheidbar, und ein darauf gebauter Alarm-Flow wäre in diesem Zustand wirkungslos. Wird je ein sicherheitsrelevanter Flow darauf gebaut, ist eine Zeitgrenze in `TractivePollingService.markUnavailable` die erste Stelle zum Nachziehen
- **`unknown` vs. `away` ist sicherheitskritisch:** ohne verwertbare Koordinaten oder ganz ohne Zoneninformation bleibt der Zustand `unknown` — nie `away`. Die Location-Entität soll einen „Hund hat die Zone verlassen"-Flow triggern; ein geratenes `away` würde bei jedem Neustart einen Fehlalarm auslösen
- Zonen: die Tractive Virtual Fences (`GET /tracker/{trackerId}/geofences`) werden zu Kreiszonen (Haversine, `GeoZone`). **Einziger Endpunkt, dessen Antwortform nicht gegen `aiotractive` verifizierbar war** (die Library kennt keine Geofences) — deshalb bewusst defensives Parsen: inaktive, nicht-kreisförmige, oder unplausible Zonen (Radius `<= 0`/nicht endlich, fehlende/unplausible Koordinaten) werden verworfen statt geraten; ein fehlgeschlagener Abruf loggt eine Warnung und liefert eine leere Liste statt den Poll-Zyklus zu kippen. Der Typ-Check ist bewusst **fail-safe**: ein fehlender `type` wird akzeptiert, nur ein explizit nicht-kreisförmiger Typ wird abgelehnt, damit ein unerwarteter Feldname nicht alle Zonen stillschweigend abschaltet. Sind keine Zonen lesbar, greift die konfigurierte Home-Zone (`tractive.home-latitude`/`home-longitude`/`home-radius-meters`); ist auch die nicht gesetzt, bleibt der Zustand `unknown`
- Polling: `TractivePollingService`, alle 60 s, alle Trackable Objects des Accounts. Fehler pro Haustier sind isoliert, ein kaputter Tracker stoppt die anderen nicht. Cloud-Ausfall markiert die zuletzt gemeldeten Entitäten `unavailable`; die Scheduled-Methode wirft nie
- **Bewusster Trade-off:** bei einem Ausfall liefert `GET /api/v1/tractive/pets` weiterhin die **letzte bekannte** Position (liest den Cache des Pollers, der nicht geleert wird), während die Entity-Ebene `unavailable` meldet. Das ist Absicht — bei einem Haustiertracker ist die letzte bekannte Position genau das, was man bei einem Ausfall sehen will —, das Frontend macht das Alter über „Zuletzt gesehen: <Zeitstempel>" sichtbar
- Frontend: Leaflet + OpenStreetMap-Kacheln, Seite `pages/pets/` unter Route `/pets` („Hundetracker", Navi unter „Smart Home"); Zu-Hause-Badge auf der Tierkarte und eine Kachel im Dashboard-Footer neben dem Türschloss. Das Kachel-Markup steht **direkt in `dashboard.component.html`** — die `lumina`-Styles sind dort gekapselt und würden in einer Kind-Komponente lautlos nicht greifen. Tiere ohne Aussage (`atHome` fehlt im JSON, `@JsonInclude(NON_NULL)`) werden weggelassen statt geraten. Die Leaflet-Standard-Marker-Icons werden lokal ausgeliefert (`angular.json`-Assets-Glob → `assets/leaflet`), **nie von einem CDN** — das Dashboard muss ohne Internet funktionieren
- Konfiguration: `tractive.enabled`, `tractive.base-url`, `tractive.client-id`, Poll-/Timeout-Einstellungen sowie die optionale Home-Zonen-Fallback-Konfiguration. Keine Zugangsdaten in der Konfiguration — Login läuft in der App
- **Noch offen:** Verifikation gegen einen echten Tractive-Account steht aus (keine Zugangsdaten verfügbar); insbesondere die Geofence-Antwortform ist unbestätigt
- Implementierung in `backend/src/main/java/com/household/manager/tractive/`; Frontend unter `frontend/src/app/pages/pets/`

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

### Haushaltskalender
- Pflegbarer Kalender (Seite `pages/calendar/`, Route `calendar`): Monatsraster + Termindialog mit Wiederholungs-Builder; volle RRULE-Mächtigkeit über den „Erweitert"-Modus (Roh-RRULE)
- Eine DB-Zeile pro Termin/Serie (`calendar_events`); Serien werden on-the-fly expandiert (`RecurrenceExpansionService`, einzige `org.dmfs:lib-recur`-Stelle; Achtung: dmfs-`DateTime` zählt Monate 0-basiert). Einzelvorkommen löschen = EXDATE, ändern = Override-Zeile (`recurring_parent_id` + `recurrence_date`)
- **`org.dmfs:lib-recur` statt des bereits vorhandenen `biweekly`:** Letzteres verankert Ganztagestermine beim Parsen fest in der JVM-Zeitzone und ist auf `VEvent`-Objekte zugeschnitten; für reine `LocalDate`-Arithmetik müssten synthetische Events gebaut werden
- **Ändert sich beim Bearbeiten einer Serie die RRULE**, werden Ausnahmen dieser Serie (EXDATEs und Override-Zeilen) verworfen — sie wären der neuen Regel nicht mehr zuzuordnen. Bleibt die RRULE gleich (z. B. nur der Titel ändert sich), bleiben Ausnahmen erhalten
- Ein Einzelvorkommen ändern gewinnt gegenüber einer früheren Löschung: `updateOccurrence` entfernt ein vorhandenes EXDATE für dieses Datum, damit ein Datum nie gleichzeitig gelöscht und geändert ist. `updateOccurrence` prüft außerdem, ob das Datum überhaupt ein Vorkommen der Serie ist — sonst entstünde eine Override-Zeile, die dauerhaft unabhängig vom Master im Kalender auftaucht
- API `/api/v1/calendar`: `events?from&to` (expandierte Vorkommen), `upcoming?limit`, CRUD unter `events/{id}`, Occurrence-Endpoints `events/{id}/occurrences/{date}`; Fenster ≤ 1 Jahr, Expansion ≤ 1000 Vorkommen
- Intelligence Hub zeigt die nächsten bis zu 3 Termine als eigene Einträge (Muster Müllabfuhr; `calendar-insight.util.ts`)
- **Bewusste v1-Kompromisse:** `getOccurrences` lädt per `findAll()` alle Kalenderzeilen und filtert in Java — eine repository-seitige Einschränkung ginge bei Serien nicht zuverlässig, weil deren `startDate` beliebig weit in der Vergangenheit liegen kann und trotzdem Vorkommen im Fenster erzeugt. `getUpcoming` expandiert dafür ein Jahresfenster und kürzt erst danach auf `limit`. Bei Haushaltsgrößen unkritisch; wird die Tabelle je groß, ist das die erste Stelle zum Nachziehen (es gibt keinen Aufräumjob für alte Termine)
- Flow-Anbindung: `CalendarReminderScheduler` (minütlich) feuert `event.calendar_reminder` — Uhrzeit-Termine zum Start, ganztägige um 08:00 (Konstante); `action` = Kategorie kleingeschrieben, Attribute `title`/`date`/`time`/`allDay`/`eventId`. Nach Neustart werden verpasste Erinnerungen bewusst nicht nachgefeuert
- **Hochwassermarke des Schedulers ist ein `Instant`, keine lokale Wandzeit:** Bei der Zeitumstellung im Oktober würde eine Wandzeit-Marke zurückspringen und die wiederholte Stunde ein zweites Mal auslösen
- `buildRrule` (Frontend) erzeugt bei „monatlich am selben Wochentag" mit Startdatum am Monatsende die iCal-Negativform (`BYDAY=-1TU` = letzter Dienstag), weil ein „fünfter Dienstag" in den meisten Monaten nicht existiert und der Termin sonst ausfiele
- Beim Bearbeiten eines Serien-Vorkommens ist das Datumsfeld im Dialog mit dem Datum des angeklickten Vorkommens vorbelegt; wählt der Nutzer „Ganze Serie", ohne das Feld anzufassen, bleibt der ursprüngliche Serienstart erhalten
- Zeiten sind lokale Haushaltszeit (Europe/Berlin), kein TZID-Handling

### Benutzerverwaltung & API-Sicherheit
- Spring Security mit Server-Sessions (HttpOnly-Cookie) + Remember-Me-Cookie (90 Tage, überlebt Backend-Neustarts nur mit gesetztem `REMEMBER_ME_KEY`); kein JWT — bewusste Entscheidung für LAN-only-Betrieb
- **Remember-Me ist TokenBased (stateless, MD5-Signatur über Passwort+Key):** Logout löscht nur das Cookie im Browser — ein exfiltriertes Cookie bleibt bis zu 90 Tage gültig; serverseitiger Widerruf nur über Passwortänderung (pro Nutzer) oder Rotation von `REMEMBER_ME_KEY` (alle Nutzer). Bewusster LAN-only-Trade-off gegen eine zusätzliche Token-Tabelle
- **3 feste Rollen** mit Hierarchie ADMIN > MEMBER > KIOSK: KIOSK (Wandtablet) darf lesen, Schalter/Modi schalten und Nuki nur **verriegeln** (Body-abhängige Prüfung im `NukiController`, nicht per URL-Regel); MEMBER zusätzlich Tür öffnen, Kalender/Zähler/Ansagen; ADMIN alles (Flows, Nutzer, Tokens, Audit, Preise, Vision, Alexa-Login; auch `/v1/tractive/login|logout` sind ADMIN — Credential-Endpunkte wie der Alexa-/Blink-Login). Autoritative Regelliste: `SecurityConfig.filterChain` — die Reihenfolge der Matcher ist relevant
- **Service-Tokens** (Header `X-API-Token`, SHA-256-gehasht in `service_token`, einzeln widerrufbar): blink-vision (Env `API_TOKEN`), Tablet-Presence (App-Einstellung), flow-mcp-server (`HOUSEHOLD_API_TOKEN`). Die reinen Maschinen-Endpunkte (Vision-Webhook/Embeddings, tablet-presence) verlangen die SERVICE-Authority — eine Browser-Session kommt dort nicht ran. Nach dem Rotieren eines Tokens muss der flow-mcp-server-Prozess (bzw. die Claude-Code-Session) neu gestartet werden — die Env wird beim Prozessstart gelesen
- **Audit-Log** (`audit_log`): Login/Logout, Schalter, Modi, Nuki, Flow-/Nutzer-/Token-/Kalender-Änderungen. Aktor-Auflösung: ThreadLocal-Override (Telegram: `TELEGRAM:<chatId>`, Flow-Läufe: `FLOW:<id>`, beides SYSTEM-Typ) > SecurityContext (USER/SERVICE) > SYSTEM (Scheduler/Polling ohne Override: `system`). `AuditService.record` wirft nie — Audit darf die Aktion nicht brechen
- **Bootstrap:** Erster Start ohne Nutzer legt `admin` mit Passwort `changeit` an; `must_change_password` erzwingt den Wechsel beim ersten Login (UI-geführt, Endpunkt `POST /v1/auth/password` erneuert Session-Principal und Remember-Me-Cookie). Der letzte aktive Admin kann nicht deaktiviert/degradiert werden
- Deaktivierte Nutzer verlieren sofort den Zugang (`DisabledUserSessionFilter`, eine DB-Abfrage pro Session-Request — Haushaltsgröße)
- **Rollout-Reihenfolge beachten:** Erst Tokens über die Admin-Seite „API-Tokens“ anlegen, dann Envs setzen (`VISION_API_TOKEN`, `HOUSEHOLD_API_TOKEN`, Tablet-Einstellung), dann Sidecars neu starten — sonst fallen Vision-Webhooks und Tablet-Presence **still** aus
- Frontend: Login-Seite (`pages/login/`), 401-Interceptor, `authGuard`/`adminGuard`, Admin-Seiten `admin/users`, `admin/service-tokens`, `admin/audit-log`; Header filtert Menüpunkte nach Rolle
- **CSRF-Fallstricke (beide real aufgetreten, kosten sonst Stunden):** (1) Das `XSRF-TOKEN`-Cookie muss `Path=/` haben — der Spring-Default wäre der Kontextpfad `/api`, den `document.cookie` der unter `/` laufenden SPA nie sieht (`SecurityConfig`; ein Altcookie mit `/api` räumt `LegacyCsrfCookieCleanupFilter` ab, weil der Browser sonst beide sendet und der Server das falsche liest). (2) Spring setzt das Cookie erst als **Antwort auf einen Request** — eine Route ohne Guard feuert vorher keinen GET, also fehlt das Cookie und jeder POST endet in 403. Die Login-Seite holt es deshalb in `ngOnInit` über `AuthService.primeCsrfToken()`. **Jede neue Route ohne Guard, die schreibend zugreift, braucht dasselbe.**
- GlobalExceptionHandler hat explizite 401/403-Handler — ohne sie würde der Catch-all `AccessDeniedException` in 500 verwandeln

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
