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
- **Pet Food Inventory**: Toni-Futtervorrat mit automatischem Fütterungsabzug und Füllstandsanzeige
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
- **Switch Confirmation**: `confirm_required` flag on `entity_states`, applied in three places — the dashboard switch tile, the device page, and the helpers page (`/custom-entities`, `INPUT_BOOLEAN`)
  - UI-only guard for switching **OFF** only: turning a guarded device ON is always direct (accidental ON is harmless, accidental OFF of a fridge or router is not). Flows, Telegram and the API keep switching directly in both directions, unchanged
  - **Interaction differs on purpose between the surfaces:** the dashboard confirms via a live switch row inside the dialog (`app-switch-list`, the user taps the switch itself); the device page and the helpers page instead show a static warning text with an explicit red "Ausschalten" button. Don't "fix" one to match the other — they were built independently and this is not drift
  - `GET /api/devices` enriches each device with `confirmRequired` from the mirrored switch entity, so the device page needs no second request. `SmartDeviceEntityMapper.entityId(device)` is the single definition of that entityId — both the mirroring path and this enrichment call it, so they cannot drift apart (same pattern as `TractiveHomeResolver`/`PowerConsumerQueryService.findConsumer`)
  - The device page and the helpers page each have their own dialog markup and styles on purpose — reusing the dashboard's would render silently unstyled, because the `lumina`-styles are encapsulated in `dashboard.component.scss` and don't reach outside it (see Tractive/Zigbee tiles above)
  - All three surfaces re-resolve the entity by id from the current list right before confirming (for the dashboard's "Alle Schalter" dialog that list, `allSwitches`, is loaded once in `openSwitchDialog` and not touched by the 30 s refresh, so it can be minutes stale), and only proceed if it is still on — a background refresh while the dialog is open must never end up switching the thing back ON via the "Ausschalten" button (`DashboardComponent.confirmToggle`, `SmartDeviceListComponent.confirmTurnOff`, `CustomEntitiesComponent.confirmTurnOff`). `DashboardComponent.confirmToggle` additionally falls back to the dialog's held reference if the switch turns up in neither list (emptied by a failed refresh, or pushed out of the top-4 tile limit) — not found is not evidence it's off, since the dialog only ever opens for a switch that was on
  - **Accepted dashboard hole:** the guard is evaluated against the client's last-fetched state, and tiles refresh only every ~30 s. If a guarded switch is turned on elsewhere within that window, the dashboard still sees `off`, skips the dialog, and `SwitchCommandService.toggleDevice` — which picks the direction from its own current state (everything except `"on"` switches ON) — turns it off unconfirmed. This is the price of a UI-only guard; the dashboard's direction rule duplicates the backend's, so both must change together
  - The device page has no such hole: it calls explicit `/on`/`/off` endpoints rather than a toggle, so a stale client state there can only cause a redundant turn-ON, never an unconfirmed turn-OFF
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
- **Manuelles Hinzufuegen per IP (`POST /devices/kasa`, `{"ip": ...}`):** PROD laeuft im Docker-Bridge-Netz und kann keine UDP-Subnetz-Broadcasts senden, deshalb findet die Kasa-Discovery dort nichts (0 Geraete in PROD gegenueber 8 in Dev, verifiziert 2026-08-17); Unicast-TCP zur Steckdose funktioniert aus dem Container dagegen (getestet gegen 192.168.1.116:9999). Der Endpunkt fragt deshalb per Unicast dasselbe `get_sysinfo` ab wie die Discovery und legt das Geraet ueber denselben Upsert-Pfad an (Schluessel bleibt die Hardware-`deviceId`, die IP ist nur Kommunikationsadresse). **Wichtige Kehrseite:** ohne funktionierende Discovery findet das System eine per DHCP geaenderte IP nicht selbst nach — fuer manuell angelegte Kasa-Geraete gehoert eine feste IP-Reservierung im Router dazu, sonst schaltet ein darauf gebauter Flow ins Leere und das Geraet erscheint offline.
- **Nicht jedes in der Kasa-App verwaltete Geraet spricht auch das Kasa-Protokoll:** modernere Leuchtmittel (z. B. die Tapo-L530-Serie) laufen zwar unter dem Kasa-Konto, sprechen intern aber Tapo/KLAP ueber HTTP statt TCP 9999 — siehe TP-Link Tapo unten fuer den Befund und die Unterscheidung.
- **Legacy-Kasa-Leuchtmittel (z. B. KL110) sprechen dagegen echtes Kasa-Protokoll, keine Tapo-Bruecke:** gemessen am 2026-08-18 gegen 192.168.1.101 (`KL110(EU)`, `mic_type: IOT.SMARTBULB`) — TCP **9999 offen**, TCP **80 zu**, also das exakte Gegenteil des L530-Befunds oben. Faehigkeiten kommen **explizit** aus `is_dimmable`/`is_color`/`is_variable_color_temp` (1/0) direkt in `get_sysinfo` (`KasaCapabilityMapper.deriveCapabilities`) — anders als bei Tapo keine Feld-Praesenz-Rateraterei, dafuer zusaetzlich gegen `light_state`-Praesenz abgesichert: ein Kasa-**Wanddimmer** (HS220/KS220/KP405) meldet `is_dimmable: 1` **ohne** `light_state` und bekommt deshalb bewusst keine BRIGHTNESS-Faehigkeit — er spricht ein anderes, hier nicht implementiertes Dimm-Protokoll. Eine Bulb-sysinfo hat **kein** `relay_state`; das On/Off-Flag steht in `light_state.on_off`, und je nach Zustand liegen Helligkeit/Farbe entweder direkt in `light_state` (an) oder unter `light_state.dft_on_state` (aus — der Wert, auf den das Geraet beim Einschalten zurueckfaellt).
- **Schalten/Dimmen laeuft ueber `smartlife.iot.smartbulb.lightingservice.transition_light_state`, nicht `system.set_relay_state`** (`KasaService`). Gemessen (2026-08-19, echter KL110, Zustand danach zurueckgesetzt): ein reiner Helligkeitswert ohne `on_off` ist ein **stiller No-op** (Geraet bleibt aus, Helligkeit unveraendert, trotzdem `err_code: 0`); `on_off: 1` allein schaltet zwar ein, ignoriert aber jeden mitgeschickten Wert und wendet den geraeteseitig gespeicherten Default an (ebenfalls `err_code: 0`!) — erst **`ignore_default: 1`** zusammen mit `on_off: 1` wendet einen Wert tatsaechlich an. `KasaService.setLightState` sendet deshalb bei jeder Lichtaenderung **immer** beide Felder. Das Geraet meldet echte Fehler (`err_code: -10000` bei Helligkeit > 100) — wird geprueft und als `KasaCommunicationException` mit `err_msg` geworfen —, aber **`err_code: 0` beweist nicht, dass ein Wert ankam**: eine Farbanfrage an dieses nicht-farbfaehige Geraet liefert `err_code: 0` bei unveraendertem `hue`. Schutz davor ist die Faehigkeitspruefung in `SmartDeviceService.validateLightStateRequest` (400 vor jedem Geraetezugriff), nicht die Geraeteantwort; zusaetzlich liest `KasaService.setLightState` die von der Antwort **tatsaechlich gemeldeten** Werte zurueck (nie die angefragten), `SmartDeviceService` persistiert genau die — ganz ohne zweiten Statusabruf, da die Schreibantwort dieselbe `light_state`/`dft_on_state`-Verschachtelung traegt wie `get_sysinfo`.
- **`PUT /devices/{id}/light` akzeptiert seit diesem Fund auch `DeviceType.KASA`** — aber nur, wenn das gespeicherte `kasaBulb`-Metadata-Flag gesetzt ist (strukturell aus `light_state`-Praesenz beim letzten Scan/Probe/Refresh abgeleitet, `SmartDeviceService.isKasaBulb`); ein Kasa-Wanddimmer faellt sonst auf dieselbe 400-Ablehnung wie ein Meross-Geraet. **Rollout-Falle:** ein VOR diesem Fund bereits in der DB stehendes Bulb-Geraet hat noch kein `kasaBulb`-Flag in seiner Metadata (Default `false`) und wird bis zum naechsten Scan/Refresh weiterhin ueber die Steckdosen-Payload angesprochen — schaltet dabei vermutlich gar nicht, bis einmal neu gescannt oder aufgefrischt wurde.

### TP-Link Tapo
- Remote control via TP-Link Cloud API with token-based authentication
- Device discovery via cloud API (lists all devices registered in Tapo account)
- Automatic token management with 24-hour caching
- Full device control: on/off, brightness, color, color temperature, energy usage
- Implementation in `backend/src/main/java/com/household/manager/tapo/`
- **Moderne Geraete sprechen Tapo, unabhaengig davon, unter welcher App sie verkauft wurden:** ein vom Nutzer in der Kasa-App verwaltetes Geraet entpuppte sich als Tapo-L530-Gluehbirne ("Flur"). Gemessen am 2026-08-18 gegen 192.168.1.114: TCP **9999 zu**, TCP **80 offen**, `POST /app` antwortet `{"error_code":1003}` — waehrend die klassische Kasa-Steckdose unter .116 Port 9999 offen hat. Ein solches Geraet laesst sich als "Kasa" grundsaetzlich nicht ansprechen. Der KLAP-Handshake gelang dabei mit den bereits konfigurierten `TAPO_EMAIL`/`TAPO_PASSWORD` — kein zweiter Account noetig.
- **Faehigkeiten kommen aus der Geraete-Selbstauskunft, nicht aus einer Modell-Tabelle** (`TapoCapabilityMapper.deriveCapabilities`, Ergebnis z. B. `SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP` — komma-separiert, stabile Reihenfolge, in der bestehenden `capabilities`-Spalte gespeichert, keine Migration noetig): jedes Geraet ist mindestens `SWITCH`; `brightness` im `get_device_info` ergibt `BRIGHTNESS`, `hue`+`saturation` ergeben `COLOR`, `color_temp` ODER `color_temp_range` ergibt `COLOR_TEMP`. Entscheidend ist die **Feld-Praesenz, nicht der Wert** — eine farbfaehige Bulb im Farbmodus meldet `color_temp: 0`; wuerde 0 als "fehlt" gewertet, verloere sie COLOR_TEMP genau in dem Moment, in dem eine Farbe gewaehlt wird. Der reale L530 meldet `brightness`, `hue`, `saturation`, `color_temp` und `color_temp_range: [2500,6500]`; `nickname` kommt Base64-kodiert (`"Rmx1cg=="` = "Flur").
- **`PUT /devices/{id}/address`** setzt die IP eines Tapo-Geraets von Hand und probt sie sofort (KLAP, Fallback AES) — Pendant zu `POST /devices/kasa`. Grund: in PROD (Docker-Bridge-Netz) findet die lokale UDP-Discovery nie ein Tapo-Geraet, alle neun Bestandsgeraete saessen deshalb dauerhaft offline. Zwei eigenstaendige Fehler waren dafuer ursaechlich, beide behoben: `upsertTapoDevice` schrieb `ipAddress`/`authProtocol` bisher nur, wenn die lokale Discovery in derselben Scan-Runde etwas fand — `scanTapoDevices` fuehrt Cloud- und lokale Geraeteliste deshalb jetzt ueber die `deviceId` zusammen statt die Cloud-Liste zu filtern; und `buildMetadata` baute die Metadata bei jedem Scan komplett neu aus dem Cloud-DTO auf und loeschte damit ein zuvor manuell gesetztes `authProtocol` still — jetzt wird gemergt statt ersetzt. **Identitaetspruefung ist Pflicht:** bei mehreren fast identischen Geraeten im selben LAN wuerde eine vertippte-aber-gueltige IP ein *anderes* physisches Geraet treffen und dessen Name/Zustand ueber die falsche DB-Zeile schreiben; `TapoAddressProbeResult` traegt deshalb die vom Geraet selbst gemeldete `device_id`, eine Abweichung von der bearbeiteten Zeile wird mit 400 abgelehnt, bevor irgendetwas geschrieben wird (dasselbe Muster wie bei `addKasaDeviceByIp`). Nach einer erfolgreichen Adressaenderung wird der Verbindungscache invalidiert (`TapoDeviceService.clearLocalConnection`) — er ist auf `deviceId:protocol` geschluesselt und ignoriert die IP, ein alter Eintrag haette sonst weiter gegen die alte Adresse geschaltet.
- **`PUT /devices/{id}/light`** setzt Helligkeit/Farbe/Farbtemperatur (`TapoDeviceService.setLightState`); eine Faehigkeit, die das Geraet nicht meldet, wird mit 400 abgelehnt statt still ignoriert. Farbe und Farbtemperatur sind **exklusive Modi** — eine Farbanfrage sendet `color_temp: 0` zusammen mit `hue`/`saturation`, eine Farbtemperatur-Anfrage nur `color_temp` ohne Farbfelder; das ist an genau einer Stelle kodiert, `TapoDeviceService.buildSetDeviceInfoParams`, und der erste Ort bei unerwartetem Geraeteverhalten. Die Farbtemperatur wird gegen den vom Geraet selbst gemeldeten `color_temp_range` geprueft (Fallback 2500-6500K ohne gespeicherten Bereich). **Unverifiziert:** `set_device_info` wurde nie gegen die physische Bulb ausgefuehrt, nur `get_device_info` — der Schreibpfad stuetzt sich auf dokumentierte Feldsemantik, nicht auf einen Realtest.
- Beide neuen PUT-Endpunkte haengen unter `/devices/**`; dort steht nur `GET /devices/**` in der KIOSK-Whitelist, PUT faellt durch auf `anyRequest` -> MEMBER (`SecurityConfig`, beide Richtungen in `SecurityRulesTest` festgehalten).
- Flow-Node `light-set` (`LightSetNodeHandler`) ruft denselben `setLightState`-Pfad wie der Endpunkt. Anders als `switch-device` schluckt er Geraetefehler (nicht erreichbar, Faehigkeit/Bereich vom Geraet abgelehnt) bewusst — eine unerreichbare Lampe darf einen nachgelagerten Telegram-/Push-Zweig im selben Flow nicht mit abbrechen; der Fehler landet nur als Warnung im Log.
- Frontend: Helligkeits-/Farbtemperatur-Regler und Farbwaehler in der Geraeteliste sind pro Geraet ueber `hasCapability` ein-/ausgeblendet, senden erst beim Loslassen (`change`, nicht bei jeder Mausbewegung) und bleiben bei einem Fehler auf dem zuletzt gewaehlten Wert stehen statt zurueckzuspringen; dazu ein inline "IP setzen"-Formular fuer `PUT /devices/{id}/address`.

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
- **`Optional.empty()` aus `TractiveHomeResolver.resolve()` hat zwei Gründe, die verschieden behandelt werden:** „keine Positionsdaten" (Tracker still) ⇒ kein Update, der letzte Wert bleibt — zu Hause ist Stille der Normalzustand. „Kein Zuhause hinterlegt" (Koordinaten nie gesetzt oder im Admin entfernt) ⇒ die Entität meldet `unavailable`. Ohne diese Unterscheidung würde die Entität nach dem Entfernen der Koordinaten für immer „zu Hause" behaupten, während Badge und Kachel schon verschwunden sind — und ein darauf gebauter Flow handelte weiter nach einem Wert, den niemand mehr sehen oder korrigieren kann. `TractiveHomeResolver.isHomeConfigured()` trennt die beiden Fälle
- **Die Home-Entität wird bei einem Cloud-Ausfall bewusst *nicht* `unavailable`** (die übrigen schon): zu Hause ist „keine Daten" der Normalzustand, und der letzte Wert ist genau die gewünschte Aussage. **Kehrseite (bewusst akzeptiert 2026-07-26):** Tractive gibt kein Refresh-Token aus — meldet sich niemand neu an, friert die Entität unbegrenzt auf ihrem letzten Wert ein, ohne jedes Anzeichen. Ein fünfminütiger Aussetzer und eine seit Wochen vergessene Anmeldung sind an ihr nicht unterscheidbar, und ein darauf gebauter Alarm-Flow wäre in diesem Zustand wirkungslos. Wird je ein sicherheitsrelevanter Flow darauf gebaut, ist eine Zeitgrenze in `TractivePollingService.markUnavailable` die erste Stelle zum Nachziehen
- **`unknown` vs. `away` ist sicherheitskritisch:** ohne verwertbare Koordinaten oder ganz ohne Zoneninformation bleibt der Zustand `unknown` — nie `away`. Die Location-Entität soll einen „Hund hat die Zone verlassen"-Flow triggern; ein geratenes `away` würde bei jedem Neustart einen Fehlalarm auslösen
- Zonen: die Tractive Virtual Fences (`GET /tracker/{trackerId}/geofences`) werden zu Kreiszonen (Haversine, `GeoZone`). **Einziger Endpunkt, dessen Antwortform nicht gegen `aiotractive` verifizierbar war** (die Library kennt keine Geofences) — deshalb bewusst defensives Parsen: inaktive, nicht-kreisförmige, oder unplausible Zonen (Radius `<= 0`/nicht endlich, fehlende/unplausible Koordinaten) werden verworfen statt geraten; ein fehlgeschlagener Abruf loggt eine Warnung und liefert eine leere Liste statt den Poll-Zyklus zu kippen. Der Typ-Check ist bewusst **fail-safe**: ein fehlender `type` wird akzeptiert, nur ein explizit nicht-kreisförmiger Typ wird abgelehnt, damit ein unerwarteter Feldname nicht alle Zonen stillschweigend abschaltet. Sind keine Zonen lesbar, greift die im Admin-Bereich gepflegte Home-Zone (Kategorie `TRACTIVE_HOME`, siehe oben); ist auch die nicht gesetzt, bleibt der Zustand `unknown`. **Umgekehrt heißt das:** solange Geofences lesbar sind, bleibt der dort gepflegte Zonenname wirkungslos — `TractiveZoneResolver.homeZone()` wird nur bei leerer Zonenliste überhaupt befragt
- Polling: `TractivePollingService`, alle 60 s, alle Trackable Objects des Accounts. Fehler pro Haustier sind isoliert, ein kaputter Tracker stoppt die anderen nicht. Cloud-Ausfall markiert die zuletzt gemeldeten Entitäten `unavailable`; die Scheduled-Methode wirft nie
- **Bewusster Trade-off:** bei einem Ausfall liefert `GET /api/v1/tractive/pets` weiterhin die **letzte bekannte** Position (liest den Cache des Pollers, der nicht geleert wird), während die Entity-Ebene `unavailable` meldet. Das ist Absicht — bei einem Haustiertracker ist die letzte bekannte Position genau das, was man bei einem Ausfall sehen will —, das Frontend macht das Alter über „Zuletzt gesehen: <Zeitstempel>" sichtbar
- Frontend: Leaflet + OpenStreetMap-Kacheln, Seite `pages/pets/` unter Route `/pets` („Hundetracker", Navi unter „Smart Home"); Zu-Hause-Badge auf der Tierkarte und eine Kachel im Dashboard-Footer neben dem Türschloss. Das Kachel-Markup steht **direkt in `dashboard.component.html`** — die `lumina`-Styles sind dort gekapselt und würden in einer Kind-Komponente lautlos nicht greifen. Tiere ohne Aussage (`atHome` fehlt im JSON, `@JsonInclude(NON_NULL)`) werden weggelassen statt geraten. Die Leaflet-Standard-Marker-Icons werden lokal ausgeliefert (`angular.json`-Assets-Glob → `assets/leaflet`), **nie von einem CDN** — das Dashboard muss ohne Internet funktionieren
- **Spaziergänge-Dialog:** Klick auf die Hund-Kachel im Dashboard-Footer öffnet einen Dialog mit den Spaziergängen der letzten 7 Tage. Es gibt keinen Walks-Endpunkt in der Tractive-API (das App-Feature ist nicht reverse-engineert) — `GET /v1/tractive/pets/{trackerId}/walks?days` leitet sie stattdessen on-the-fly aus der Positionshistorie ab (`GET /tracker/{id}/positions`, wie die Geofences unverifiziert → defensives Parsen; **Abruf zwingend in Tages-Häppchen, neueste zuerst** — die Cloud lehnt größere Fenster mit Code 7500 HISTORY ab (real beobachtet bei 7 Tagen) **und** rate-limitiert die Positions-Ressource (429, Code 4006, real beobachtet). Abgeschlossene Tage werden deshalb dauerhaft im Speicher gecacht (sie ändern sich nie mehr; nur der angebrochene Tag hat eine 5-min-TTL), beim ersten 429 stoppen alle weiteren Cloud-Aufrufe für 60 s und der Dialog zeigt das Teilergebnis der schon geladenen Tage. Einzelne fehlgeschlagene Häppchen werden toleriert (Basic-Abo: nur 24 h Historie) — ein Fehler erscheint erst, wenn kein einziger Tag Daten liefert): **Einschalt-Indikator dieses Haushalts** (`TractiveWalkDetector`, Entscheidung 2026-07-28): Der Tracker ist zu Hause aus und wird nur für die Runde eingeschaltet — ein Spaziergang ist deshalb ein zusammenhängender Block von Positionsberichten zwischen zwei Funkpausen > 30 min (Blockränder ≈ Ein-/Ausschalten, deckt sich mit den App-Gassirunden), nicht erst die Zeit außerhalb des Home-Radius. Blöcke < 5 min werden verworfen; ein Block ohne einen einzigen Punkt außerhalb des Home-Radius zählt nicht (sonst würde die Ladeschale zum Spaziergang). **Kehrseite:** bleibt der Tracker unterwegs dauerhaft an, wird der ganze Zeitraum ein einziger langer „Spaziergang" — ändert sich die Ein/Aus-Gewohnheit des Haushalts, muss der Detector zurück auf die Home-Radius-Logik. Ergebnis 5 min pro (Tracker, days) gecacht; ohne konfiguriertes Zuhause 400 mit klarer Meldung
- **Ein 429 hat den Ausfall vom 2026-07-30 verursacht und war unsichtbar:** `collectPet` fing **jede** Ausnahme pro Tier ab und lieferte `Optional.empty()`. Scheiterten alle Tiere (real: Rate-Limit der Cloud), war das Ergebnis „Abruf erfolgreich, 0 Tiere" — von einem Konto ohne Tracker nicht unterscheidbar. Deshalb: `collectPet` **wirft** jetzt und `Optional.empty()` bedeutet ausschließlich „kein `device_id` zugeordnet"; `PollOutcome` zählt Objekte, Treffer und Gründe, und `refreshNow()` wirft bei 0 Tieren mit den gesammelten Gründen. Ein `TractiveRateLimitException` bricht den Durchlauf sofort ab (jedes weitere Tier würde das Limit hochschaukeln), behält seinen Typ durch das äußere `catch` und wird zu **429** (eigener Handler im `GlobalExceptionHandler`, nicht 502). Der erzwungene Abruf hat einen Mindestabstand von 15 s und nach einem gemeldeten Limit eine 60-s-Sperre — genau dieser Knopf wird bei ausbleibenden Daten sonst im Sekundentakt gedrückt
- **Erzwungener Abruf (`POST /v1/tractive/pets/refresh`, Knopf „Jetzt aktualisieren" auf der Seite):** Der Scheduler schluckte jeden Fehlschlag im Log, deshalb behauptete die Seite bei einem stillen Ausfall dauerhaft „noch keine Daten", ohne die Ursache zu verraten. `TractivePollingService.refreshNow()` reicht sie deshalb an den Aufrufer durch: 400 bei „Anbindung deaktiviert"/„keine Tractive-Anmeldung", 502 bei Cloud-Fehler, jeweils mit Klartext im Frontend. **Eine `TractiveAuthException` darf dabei niemals nach außen** — sie wird als 401 abgebildet, und der Auth-Interceptor des Frontends wirft den Nutzer daraufhin aus der *Haushalts*-Session, obwohl nur die Tractive-Anmeldung fehlt; `refreshNow()` übersetzt sie deshalb in eine `IllegalStateException`. Der Endpunkt steht bewusst in der KIOSK-POST-Whitelist (er zieht nur Daten, schaltet nichts) — sonst wäre der Knopf auf dem Wandtablet tot. Ein fehlendes Token loggt der Scheduler nur auf `debug`, weil es ohne Anmeldung der Dauerzustand ist — genau deshalb war der reale Ausfall im Log unsichtbar
- Konfiguration: `tractive.enabled`, `tractive.base-url`, `tractive.client-id` sowie Poll-/Timeout-Einstellungen — mehr steht nicht mehr in `application.properties`. Die Home-Definition liegt in der Datenbank (siehe oben). Keine Zugangsdaten in der Konfiguration — Login läuft in der App
- **Noch offen:** Verifikation gegen einen echten Tractive-Account steht aus (keine Zugangsdaten verfügbar); insbesondere die Geofence-Antwortform ist unbestätigt
- Implementierung in `backend/src/main/java/com/household/manager/tractive/`; Frontend unter `frontend/src/app/pages/pets/`

### Zigbee-Ausfallerkennung und MQTT-Härtung
- **Anlass (2026-07-28):** Alle 28 Zigbee-Entitäten standen in PROD über 22 Stunden auf demselben Wert, während das Backend nachweislich lief — **kein** Log-Eintrag, **kein** `unavailable`, **keine** Meldung. Vier der fünf aktiven Flows hängen an Zigbee-Sensorik (Türkontakte, Fensterkontakt, Temperatur, Luftfeuchte) und liefen in dieser Zeit wirkungslos ins Leere; #2 („Tür offen bei Abwesenheit") und #4 („Feuer-Verdacht") sind sicherheitsrelevant. Entwurf: `docs/superpowers/specs/2026-07-28-zigbee-ausfallerkennung-design.md`
- **`ZigbeeStreamMonitor` ist die einzige Definition von „die Anbindung lebt"** (Muster `TractiveHomeResolver`): Watchdog, Health-Endpunkt, Frontend-Banner und Meldungstext fragen dieselbe Klasse, damit sie nicht auseinanderlaufen können. Rein im Speicher, kein DB-Zugriff — der Zustand überlebt einen Neustart bewusst **nicht**, sonst löste jeder Deploy sofort einen Fehlalarm aus
- `ZigbeeAvailabilityWatchdog` (`@Scheduled`, minütlich, wirft nie) fährt drei Zustände: bei Stille ≥ `stale-after-minutes` zuerst ein **erzwungener Reconnect ohne jede Meldung** (ein kurzer Aussetzer soll nicht nachts das Handy wecken); kommt binnen `recover-grace-minutes` immer noch nichts, werden die Entitäten `unavailable` und `event.zigbee_bridge_status` feuert mit State `failed`. Danach ist **Ruhe** — **einmal** melden, nicht minütlich wiederholen, sonst wird die Warnung stummgeschaltet und hilft beim nächsten Mal nicht mehr. Kommt wieder etwas an, feuert `recovered`
- **Reihenfolge im Alarmfall ist nicht beliebig:** erst das Event, dann `unavailable`. Das Markieren liest über `entityStateService.find()` aus der DB und ist die einzige Stelle, die werfen kann; stünde sie vorn, verschluckte ein DB-Timeout die Ausfallmeldung — und weil `FAILED` nie wiederholt wird, gäbe es sie nie
- **Die Telegram-Warnung ist ein Flow auf `event.zigbee_bridge_status`, kein Java-Code** (Wortlaut und Empfänger ohne Redeploy änderbar, keine zweite Benachrichtigungsschiene). **Offengelegter Preis:** die Ausfallmeldung hängt damit selbst an der Flow-Engine. Für einen Zigbee-Ausfall trägt das, weil das Backend dabei läuft; für einen künftigen *Backend*-Ausfall wäre dieser Weg untauglich — das darf später niemand als gegeben annehmen
- **`unavailable` darf die Attribute nicht löschen:** `EntityStateWriter.upsert` überschreibt `attributes` bei **jedem** Update, deshalb liest der Watchdog die gespeicherten Attribute aus der DB zurück und gibt sie unverändert mit. Ohne das verlören alle Zigbee-Entitäten beim Ausfall `unit`, `deviceClass` und `batteryPercent`
- **EVENT-Entitäten sind vom `unavailable` ausgenommen** (ein Ereignis hat keinen fortdauernden Zustand). Die Melde-Entität `event.zigbee_bridge_status` wird davon **transitiv** miterfasst — eine zweite, gezielte Prüfung gibt es bewusst nicht. Wer den EVENT-Filter je einschränkt oder die Melde-Entität in eine andere Domain legt, lässt den Watchdog seinen eigenen Meldekanal als tot markieren, und zwar lautlos
- `bridgeState` aus `zigbee2mqtt/bridge/state` wird **fail-safe** ausgewertet (Muster wie bei den Tractive-Geofences): nur ein explizites `offline` löst `BRIDGE_OFFLINE` aus, jeder unerwartete Text wird fürs Urteil ignoriert (bleibt aber im Status sichtbar) — sonst löste ein künftiger Zwischenzustand bei laufender Anbindung einen Daueralarm aus. Und eine Offline-Meldung **verfällt**, sobald danach noch eine Gerätenachricht kam; ohne diesen Verrast-Schutz bliebe das Urteil nach einer einmal verlorenen `online`-Meldung dauerhaft auf „offline" stehen
- **Retained-Nachrichten setzen die Stille-Uhr nicht zurück** (`recordMessage` nur bei `!isRetain()`): nach einem Re-Subscribe spielt der Broker den letzten retained Wert jedes Geräts erneut aus, ohne dass eine einzige frische Funk-Nachricht kam — ein zappelnder Client ließe die Anbindung sonst beliebig lange „lebendig" erscheinen und verdeckte genau den Ausfall, um den es hier geht. **Kehrseite:** die übrige Verarbeitung läuft für retained Nachrichten weiter, also bekommen nach einem Reconnect alle Zigbee-Entitäten frische `lastUpdated`-Stempel mit **alten** Werten — die Kachel sieht dann gesünder aus, als die Anbindung ist (`lastChanged` bleibt ehrlich, weil der Wert sich nicht ändert)
- **Selbstheilung genau einmal pro Ausfall:** der erzwungene Reconnect passiert nur beim Übergang `HEALTHY → RECOVERING`. Nach `FAILED` wird **nie wieder** einer versucht; dann hilft nur noch HiveMQs Auto-Reconnect — der ausgerechnet den Hauptverdächtigen „verbunden, aber ohne Subscription" nicht abdeckt, weil dabei nie etwas getrennt wird
- **Nach der Erholung bleiben die Entitäten `unavailable`, bis jedes Gerät selbst wieder meldet.** Der Watchdog setzt sie nicht zurück (er kennt die echten Werte nicht). Für Temperatursensoren sind das Sekunden, für Tür-/Fensterkontakte potenziell **Tage** — ein Kontakt, der sich nicht bewegt, sendet nichts. Ausnahme: läuft die Erholung über einen echten Reconnect, spielt der Broker retained Werte nach und stellt die Kontakte sofort wieder her; ausgerechnet im Hauptverdachtsfall „verbunden, aber ohne Subscription" passiert das aber nicht
- **MQTT-Härtungen** (`ZigbeeMqttConfig`, alle drei waren eigenständige Ursachenkandidaten): (1) fehlgeschlagenes Subscribe wird mit Backoff unbegrenzt wiederholt statt einmal geloggt — vorher konnte der Client dauerhaft verbunden bleiben, ohne je wieder ein Topic zu abonnieren; (2) die Verarbeitung läuft auf **genau einem** eigenen Thread statt auf dem Netty-Event-Loop, damit eine hängende DB nicht Keepalive und Reconnect blockiert — ein *Pool* wäre falsch, er könnte Nachrichten desselben Geräts umsortieren und bei einem Türkontakt „offen"/„zu" vertauschen; (3) Verarbeitungsfehler auf `warn` mit Stacktrace statt `debug`, plus ein `addDisconnectedListener`, damit Reconnect-Zyklen überhaupt sichtbar werden
- **Der Resubscribe braucht Generationszähler *und* `isConnected()`-Prüfung:** HiveMQ lässt ein `subscribe()` während eines laufenden Auto-Reconnects nicht scheitern, sondern **puffert** es intern und liefert es erst beim nächsten CONNACK aus — dabei ändert sich die Generation nicht, weil kein `ConnectedListener`-Aufruf stattfindet. Ein alter Retry-Versuch käme in diesem Fenster durch den Generations-Check und landete zusätzlich zur frischen Subscription in der Warteschlange: zwei aktive Subscriptions, jede Nachricht doppelt verarbeitet (doppelte DB-Zeilen, doppelt feuernde Flows)
- `GET /api/v1/zigbee/health` liefert `ZigbeeStreamMonitor.status()` (inkl. `lastBridgeStateAt`, damit sichtbar ist, wie alt eine `bridgeState`-Aussage ist); Banner auf der Zigbee-Seite und Hinweis im Dashboard-Footer. Das Kachel-Markup steht **direkt in `dashboard.component.html`** — die `lumina`-Styles sind dort gekapselt und griffen in einer Kind-Komponente lautlos nicht
- Konfiguration: `zigbee.watchdog.enabled`, `stale-after-minutes` (15), `recover-grace-minutes` (5) — bewusst in `application.properties`, nicht in der DB: anders als bei der Tractive-Home-Definition gibt es keinen Grund, das im laufenden Betrieb zu verstellen. **Die Schwellen sind unverifiziert:** die 15 Minuten sind aus einem einzigen beobachteten Zeitfenster abgeleitet und sollten nach einigen Tagen Betrieb gegen die tatsächlichen Melde-Abstände nachgezogen werden
- **Bewusst nicht Teil davon:** eine Retention für `zigbee_measurement`. Die Tabelle wächst unbegrenzt, das bremst langfristig die Schreibpfade und verschärft genau den Ursachenkandidaten „Verarbeitung blockiert" — ein echtes, aber eigenständiges Problem
- **Dev-Falle (real passiert 2026-08-15):** Der Default von `zigbee.mqtt.host` zeigt auf den ECHTEN Broker (192.168.1.150), und der Broker trennt bei doppelter Client-ID die bestehende Verbindung (MQTT-Spec). Ein lokal gestartetes Backend mit der PROD-Client-ID kickt PROD damit in eine gegenseitige Reconnect-Schleife — Symptom: „Zigbee gestört" auf PROD, solange Dev läuft. Deshalb lokal immer `ZIGBEE_MQTT_CLIENT_ID=household-manager-zigbee-dev` (oder `ZIGBEE_MQTT_ENABLED=false`) setzen; mit eigener Client-ID darf Dev gefahrlos mitlesen (Pub/Sub). Achtung trotzdem: aktive Flows in der Dev-DB reagieren dann auf echte Sensordaten

### Flow-Engine: `unavailable` als Zustandsübergang
- **Unterdrückt wird ausschließlich der Übergang NACH `unavailable`** (`EntityStateTriggerHandler`, engine-weit für alle Quellen): der Ausfall selbst ist kein Ereignis der beobachteten Größe, sonst löste er bei `operator: "!="` und `operator: "changed"` bei jedem Aussetzer aus. Ein laufender `forSeconds`-Timer wird dabei storniert
- **Der Übergang AUS `unavailable` heraus feuert normal.** Die erste Fassung unterdrückte beide Richtungen — das war sicherheitsgefährdend: Flow #4 („Feuer-Verdacht", `Temperatur > 40`) bliebe nach jedem Zigbee-Ausfall entwaffnet, bis die Temperatur erst unter 40 fällt und wieder steigt. Ein **während** des Ausfalls ausgebrochenes Feuer würde nie gemeldet. Der Preis ist eine mögliche **Doppelmeldung** (Flow #2: eine Tür, die bei der Erholung offen ist, war in diesem Moment tatsächlich offen — Dopplung, keine Falschmeldung), und eine Dopplung wiegt leichter als ein verschluckter Brandalarm
- Der `forSeconds`-Ablauf prüft **zusätzlich selbst**, ob der aktuelle Zustand `unavailable` ist, und emittiert dann nicht: `future.cancel(false)` stoppt eine bereits gestartete Task nicht mehr, und `matches("unavailable", "!=", "on")` wäre wahr — der Timer würde also genau den Ausfall emittieren, den der Guard verhindern soll
- **Die `!=`-Ausnahme, bewusst nicht behoben:** `StateComparator` vergleicht nicht-numerische Werte als String, `unavailable != on` ist damit **wahr**. Bei `operator: "!="` ist `beforeMatched` also schon während des Ausfalls wahr, und die Erholungsflanke feuert **nicht**. Konkret: ein Flow „Schloss nicht verriegelt" (`lock.nuki_… != locked`) meldet nach einem Nuki-Cloud-Ausfall **nicht**, dass das Schloss offen ist. Dieselbe Falle gilt für `entity-condition`: „Tür ist nicht offen" wertet bei einem Ausfall als **erfüllt**. Numerische Operatoren sind nicht betroffen (`unavailable` parst nicht als Zahl → immer `false`)
- **Wiederholtes Feuern bei flatternden Quellen:** `ShellyPollingService`, `SmartDeviceEntityMapper` (Kasa/Tapo/Meross), `NukiPollingService` und `TractivePollingService` schreiben bei **jedem** fehlgeschlagenen Poll `unavailable`. Bei `power > 500` ergibt die Folge `700` → `unavailable` → `700` deshalb ein erneutes Auslösen. Mit dem `rate-limit`-Node beherrschbar — aber man muss es wissen
- **Ein Trigger mit `value: "unavailable"` kann nie feuern**, denn genau die Richtung, die dabei zählt, ist unterdrückt. Das ist das Naheliegendste, was eine KI zu einem Ausfall-Feature autoren würde: der Flow validiert, deployt, lässt sich aktivieren — und ist tot, ohne Fehler und ohne Log. Ausfälle werden über `event.zigbee_bridge_status` gemeldet, nicht über einen `unavailable`-Trigger

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
- API `/api/v1/calendar`: `events?from&to` (expandierte Vorkommen), `upcoming?limit`, CRUD unter `events/{id}`, Occurrence-Endpoints `events/{id}/occurrences/{date}`, Stammdaten unter `categories`; Fenster ≤ 1 Jahr, Expansion ≤ 1000 Vorkommen
- **Kategorien sind Stammdaten** (`calendar_category`; Admin-Seite „Kalender-Kategorien", Route `admin/calendar-categories`: Name, Farbe, Icon, Reihenfolge, Aktiv-Flag). Der Schlüssel `cat_key` entsteht **einmal** beim Anlegen aus dem Namen (`CalendarCategoryKeyGenerator`: Umlaute/ß transliteriert, alles übrige Nicht-`[a-z0-9]` wird `_`, Kollision → `_2`) und wird danach nie neu berechnet — er ist der State (`action`) von `event.calendar_reminder`, auf den Flows filtern. Umbenennen und Deaktivieren sind deshalb gefahrlos; **Löschen** lässt einen darauf filternden Flow still ins Leere laufen — ohne Fehler, ohne Log. Der Audit-Eintrag führt deshalb den Schlüssel, nicht nur den Namen
- Die sechs Bestandskategorien wurden mit genau den Schlüsseln geseedet, die der Scheduler vorher als State schrieb (`general`, `family`, `health`, `household`, `work`, `birthday`) — bestehende Flows haben die Umstellung nicht bemerkt. **Diese sechs tragen englische Schlüssel bei deutschen Namen.** Ein Löschen ist deshalb auch durch Wiederanlegen nicht heilbar: „Haushalt" neu angelegt bekommt den Schlüssel `haushalt`, nicht `household` — der Flow bleibt tot, obwohl die Kategorie sichtbar wieder da ist
- **Es gibt keinen Schutz gegen das Deaktivieren der letzten aktiven Kategorie** (anders als beim letzten aktiven Admin in der Nutzerverwaltung). Danach lässt sich kein Termin mehr anlegen: der Dialog öffnet, das Speichern scheitert an „Es ist keine Kategorie ausgewaehlt.", und die API würde ein fehlendes `categoryId` mit 400 ablehnen
- Löschen geht nur, solange kein Termin die Kategorie nutzt: 409 mit der Anzahl (`ON DELETE RESTRICT` ist die Datenbank-Hälfte, die Vorabprüfung liefert die verständliche Meldung); die Admin-Seite bietet dann das Deaktivieren als Ausweg an
- Deaktivierte Kategorien sind **nur im UI** nicht mehr wählbar — die API akzeptiert sie weiter, sonst schlüge jede Änderung an einem Bestandstermin unerwartet fehl (Muster `confirm_required`). Im Termindialog steht eine bereits gesetzte deaktivierte Kategorie weiter zur Auswahl, beschriftet als „… (deaktiviert)"
- Ein `PUT` ohne `active` gilt serverseitig als „aktiv" (wie der Spalten-Default). Die Admin-Seite sendet das Feld deshalb **immer** mit: ein Teil-PUT würde eine deaktivierte Kategorie stillschweigend reaktivieren
- Die Migration `20260727-0044` ist bewusst in mehrere Changesets zerlegt (Details im Kommentar dort): MariaDB committet jedes DDL implizit, ein gebündeltes Changeset stünde nach dem gewollten Abbruch bei unbekanntem Enum-Text halb angewendet in der Tabelle, aber nicht in `DATABASECHANGELOG` — und wäre dauerhaft nicht mehr wiederholbar
- **Termine gehören optional Personen** (`calendar_event_person`, n:m auf `app_user`, Migration `20260727-0045`): keine Zeile = Haushaltstermin, der Normalfall. Die Zuordnung steuert **keine Sichtbarkeit** — jeder sieht alles, auch das Wandtablet
- Der Personenfilter über dem Monatsraster („Alle | Meine | <Person>", reine Anzeige, nicht persistiert) zeigt bei gewählter Person zusätzlich **immer** die Termine ohne Zuordnung — sonst verschwände die Müllabfuhr genau dann, wenn jemand auf sich selbst filtert. Einzige Definition: `matchesPersonFilter` in `shared/calendar-day-view.util.ts`
- **Personenzuordnungen räumt ausschließlich `ON DELETE CASCADE` ab** (auf `calendar_event_id`), es gibt keinen Java-seitigen Ersatz: an derselben Kaskade hängen auch die Zuordnungen der beim Löschen entfernten Override-Zeilen, die von `update` bei geänderter RRULE verworfenen und die von `deleteOccurrence`. Ein künftiges Changeset, das diesen Fremdschlüssel neu anlegt, **muss die Kaskade mitbringen** — kein Test fängt das
- `PUT` auf einen Termin **ersetzt** die Zuordnungen vollständig; ein fehlendes `personUserIds` löscht sie still. `CalendarEventPersonService.replace` arbeitet als Differenz statt „alles löschen, alles neu schreiben": denselben Primärschlüssel in einer Transaktion zu löschen und sofort wieder anzulegen wäre eine Wette auf Hibernates Flush-Reihenfolge — und genau das passiert beim bloßen Umbenennen eines Termins mit gleichbleibenden Personen
- Ein geändertes Serien-Vorkommen hat **eigene** Personen: `replace` läuft immer gegen die Id der geschriebenen Zeile (die der Override-Zeile), auch wenn die Antwort als `eventId` die Master-Id ausweist
- Intelligence Hub zeigt die nächsten bis zu 3 Termine als eigene Einträge (Muster Müllabfuhr; `calendar-insight.util.ts`)
- **Bewusste v1-Kompromisse:** `getOccurrences` lädt per `findAll()` alle Kalenderzeilen und filtert in Java — eine repository-seitige Einschränkung ginge bei Serien nicht zuverlässig, weil deren `startDate` beliebig weit in der Vergangenheit liegen kann und trotzdem Vorkommen im Fenster erzeugt. `getUpcoming` expandiert dafür ein Jahresfenster und kürzt erst danach auf `limit`. Bei Haushaltsgrößen unkritisch; wird die Tabelle je groß, ist das die erste Stelle zum Nachziehen (es gibt keinen Aufräumjob für alte Termine)
- Flow-Anbindung: `CalendarReminderScheduler` (minütlich) feuert `event.calendar_reminder` — Uhrzeit-Termine zum Start, ganztägige um 08:00 (Konstante); `action` = `cat_key` der Kategorie (**nicht** der Anzeigename), Attribute `title`/`date`/`time`/`allDay`/`eventId`/`personIds`/`persons`. Nach Neustart werden verpasste Erinnerungen bewusst nicht nachgefeuert
- Das Erinnerungs-Event trägt Personen **doppelt**: `personIds` (stabil, zum Filtern) und `persons` (Anzeigenamen, zum Ansagen). Ein Flow, der auf dem Anzeigenamen filtert, bräche beim nächsten Umbenennen still
- **Hochwassermarke des Schedulers ist ein `Instant`, keine lokale Wandzeit:** Bei der Zeitumstellung im Oktober würde eine Wandzeit-Marke zurückspringen und die wiederholte Stunde ein zweites Mal auslösen
- `buildRrule` (Frontend) erzeugt bei „monatlich am selben Wochentag" mit Startdatum am Monatsende die iCal-Negativform (`BYDAY=-1TU` = letzter Dienstag), weil ein „fünfter Dienstag" in den meisten Monaten nicht existiert und der Termin sonst ausfiele
- Beim Bearbeiten eines Serien-Vorkommens ist das Datumsfeld im Dialog mit dem Datum des angeklickten Vorkommens vorbelegt; wählt der Nutzer „Ganze Serie", ohne das Feld anzufassen, bleibt der ursprüngliche Serienstart erhalten
- Zeiten sind lokale Haushaltszeit (Europe/Berlin), kein TZID-Handling

### Toni-Futtervorrat
- Modul `backend/src/main/java/com/household/manager/petfood/`; Tabellen `pet_food_stock` (Ein-Zeilen-Bestand: `cans_remaining` in 0,5-Schritten, `target_cans` Default 48, `deduction_marker`) und `pet_food_transaction` (Journal: FEEDING/PURCHASE/CORRECTION mit tatsächlich wirksamem Betrag und Bestand danach)
- Automatischer Abzug: minütlicher Scheduler (`PetFoodFeedingScheduler` → `PetFoodService.applyDueFeedings`), Fütterungszeiten 7:00 und 16:00 Europe/Berlin je 0,5 Dosen (`FeedingSchedule`); persistierte Hochwassermarke als **Instant** (Zeitumstellungs-sicher), verpasste Fütterungen werden nach Neustarts nachgeholt; die Marke wird **sekundengenau abgeschnitten** gespeichert — MariaDB würde DATETIME-Bruchsekunden runden und die Marke in die Zukunft schieben (verlorene Fütterung); NULL-Marke = Erstinbetriebnahme: setzt nur die Marke (kein Nachholen ab Epoche) und spiegelt die Entität sofort; Bestand klemmt bei 0; Abzug+Journal+Marke laufen in **einer** Transaktion (Idempotenz), bewusst kein `@Version` (Haushalts-Scale, eine Korrektur heilt jeden Verlierer)
- Entität `sensor.pet_food_toni_cans` (`EntitySource.PET_FOOD`, State = Dosenzahl via `stripTrailingZeros`/`toPlainString` → „34" statt „34.0", Attribute `targetCans`/`percent`/`unit`); wird nie `unavailable` (lokale Daten, keine externe Quelle)
- API `/api/v1/pet-food`: GET Status (`percent`, `daysRemaining`), GET `/transactions?limit` (Kappung 1..200), POST `/purchases`, POST `/corrections` (absoluter Ist-Bestand, Journal = Differenz), PUT `/target`; Validierung: 0,5-Raster per BigDecimal (NaN strukturell unmöglich), 400 bei Verstoß; Lesen KIOSK (generische `GET /v1/**`-Regel), Schreiben MEMBER (`anyRequest`-Regel — bewusst **keine** eigene Zeile in `SecurityConfig`, `SecurityRulesTest` hält beide Richtungen fest); Audit: `petfood.purchase`/`petfood.correction`/`petfood.target.update`
- **Warnschwelle 7 Dosen existiert an DREI Stellen:** Telegram-Flow (`sensor.pet_food_toni_cans < 7`, wird beim Rollout via flow-mcp angelegt), `criticalCans` in `pet-food.component.ts` und hart kodiert in `petFoodTone` in `dashboard.component.ts` — beim Ändern alle drei nachziehen
- Frontend: Seite „Futtervorrat" (`pages/pet-food/`, Route `/pet-food`, Navi unter Smart Home) mit Füllstandsbalken/Reichweite/Journal; Dashboard-Footer-Kachel direkt in `dashboard.component.html` (lumina-Kapselung, siehe Tractive/Zigbee); die Kachel lädt alle 10 Minuten nach (`startPetFoodRefresh`, Wandtablet hat das Dashboard dauerhaft offen) — schlägt schon der Erstabruf fehl, bleibt sie weg, spätere Fehler behalten den letzten Stand. Klick auf die Kachel öffnet den **Erfassungs-Dialog** (Füllstand + Einkauf zubuchen + Bestand korrigieren; Buchungen sind MEMBER — auf dem KIOSK-Wandtablet öffnet der Dialog, das Speichern liefert aber 403); Historie und Zielbestand nur auf der Seite (Link im Dialog)
- Rollout: Deploy (Liquibase seedet Bestand 0/Ziel 48; erster Scheduler-Lauf setzt die Marke ohne Abzug und erzeugt den Sensor) → realen Bestand per Korrektur erfassen → **danach** den Telegram-Warnflow via flow-mcp anlegen (create → deploy → enable); kein Trigger auf `value: "unavailable"` (tote-Trigger-Falle, hier ohnehin irrelevant)

### Web-Push-Benachrichtigungen (PWA)
- Standard-Web-Push in die installierte PWA via `nl.martijndwars:web-push` (BouncyCastle muss **explizit** in der pom stehen — das Maven-POM der Library bringt nur jose4j mit, BouncyCastle steht nur in den Gradle-Modul-Metadaten); Zustellung laeuft immer ueber die Push-Dienste von Apple/Google (`web.push.apple.com` etc.), Payload E2E-verschluesselt (`aes128gcm`) — bewusste Erweiterung des LAN-only-Trade-offs. iOS: erst ab 16.4 und **nur in der zum Home-Bildschirm hinzugefuegten PWA**; Berechtigungsanfrage nur aus einer Nutzer-Geste
- **Das Encoding ist nicht optional:** `PushService.send(notification)` der Library nutzt als Default das alte `AESGCM` — Apple lehnt das ab. `MartijnDwarsWebPushClient` ruft deshalb `sendAsync(notification, Encoding.AES128GCM)` und begrenzt den Wait auf 10 s; ohne dieses Limit wuerde ein haengender Push-Dienst den Flow-Executor blockieren (der laeuft effektiv mit zwei Threads)
- **VAPID-Schluesselpaar erzeugt sich beim ersten Zugriff selbst** und liegt in `application_settings` (Kategorie `PUSH_VAPID`) — bewusst keine Env-Variable, kein Rollout-Schritt. Der private Schluessel wird als kanonischer 32-Byte-Skalar gespeichert (`BigIntegers.asUnsignedByteArray`), nicht ueber `Utils.encode` (das liefert vorzeichenbehaftet mal 33, mal weniger Bytes). VAPID-Subject ist `mailto:benedikt.lind@gmail.com`
- Tabelle `push_subscription` (ein Geraet = eine Zeile, Upsert per `endpoint`, `user_id` mit `ON DELETE CASCADE`); antwortet der Push-Dienst 404/410, wird die Zeile geloescht (iOS laesst Subscriptions verfallen — Selbstbereinigung). Ein erneutes Abonnieren desselben Endpoints unter einem anderen Nutzer **uebernimmt** die Zeile (Audit-Detail haelt das fest). `PushNotificationService` wirft nie (Muster Telegram) — auch die Repository-Zugriffe sind gekapselt, sonst koennte ein DB-Fehler den Flow-Zweig abbrechen und eine nachgelagerte Telegram-Nachricht verschlucken. Alle Library-Spezifika stecken hinter dem Interface `WebPushClient`
- Flow-Node `push-send` (analog `telegram-send`): `message` Pflicht, `title` optional (Default „Household Manager"), `userId` optional (leer = alle Geraete); Platzhalter `{entityId}`/`{newState}`/`{oldState}` in Titel und Text. Klick auf die Benachrichtigung oeffnet das Dashboard (ngsw-Notification-Schema, `onActionClick` -> `openWindow`)
- API `/api/v1/push`: `GET /vapid-public-key`, `GET/POST /subscriptions`, `DELETE /subscriptions/{id}`, `POST /test`. Lesen KIOSK (generische `GET /v1/**`-Regel), Schreiben MEMBER (`anyRequest`) — bewusst keine eigene Security-Zeile (`SecurityRulesTest` haelt beide Richtungen fest). Nutzeraufloesung via `CurrentUserService`; ein Service-Token hat keine Nutzer-Id und bekommt **400** (nicht 401 — ein 401 wuerde den Auth-Interceptor des Frontends ausloggen, siehe Tractive)
- Frontend: Seite „Benachrichtigungen" (`pages/notifications/`, Route `/notifications`, Navi unter Smart Home) mit Aktivieren-Button, Geraeteliste und Testnachricht; `services/push.service.ts` um `SwPush`. Der vorhandene `ngsw-worker.js` zeigt Nachrichten selbst an — kein eigener Service-Worker-Code. **Zwei nicht offensichtliche Fallen sind dort geloest:** `swPush.subscription` emittiert auf einer uncontrolled page (harter Reload) **nie**, deshalb laeuft der Abruf gegen ein 3-s-Timeout und die Geraeteliste wird vorher geladen; und der VAPID-Key wird **vorab** geholt, weil ein HTTP-Roundtrip zwischen Klick und `requestSubscription` auf iOS die Nutzer-Geste verbraucht. Auf dem KIOSK-Wandtablet (Android-WebView, keine Notification-API) zeigt die Seite „nicht unterstuetzt"
- **Nach dem Prod-Deploy:** bestehende aktive Telegram-Flows via flow-mcp um einen parallelen `push-send`-Zweig ergaenzen (vorher kennt `flow_deploy` den Node-Typ nicht). Voraussetzung fuers iPhone: PWA-/HTTPS-Rollout (ca.crt + :4443) und PWA-Installation

### Benutzerverwaltung & API-Sicherheit
- Spring Security mit Server-Sessions (HttpOnly-Cookie) + Remember-Me-Cookie (90 Tage, überlebt Backend-Neustarts nur mit gesetztem `REMEMBER_ME_KEY`); kein JWT — bewusste Entscheidung für LAN-only-Betrieb
- **Remember-Me ist TokenBased (stateless, MD5-Signatur über Passwort+Key):** Logout löscht nur das Cookie im Browser — ein exfiltriertes Cookie bleibt bis zu 90 Tage gültig; serverseitiger Widerruf nur über Passwortänderung (pro Nutzer) oder Rotation von `REMEMBER_ME_KEY` (alle Nutzer). Bewusster LAN-only-Trade-off gegen eine zusätzliche Token-Tabelle
- **3 feste Rollen** mit Hierarchie ADMIN > MEMBER > KIOSK: KIOSK (Wandtablet) darf lesen, Schalter/Modi schalten und Nuki nur **verriegeln** (Body-abhängige Prüfung im `NukiController`, nicht per URL-Regel); MEMBER zusätzlich Tür öffnen, Kalender/Zähler/Ansagen; ADMIN alles (Flows, Nutzer, Tokens, Audit, Preise, Vision, Alexa-Login; auch `/v1/tractive/login|logout` sind ADMIN — Credential-Endpunkte wie der Alexa-/Blink-Login). Autoritative Regelliste: `SecurityConfig.filterChain` — die Reihenfolge der Matcher ist relevant
- **`GET /v1/users`** liefert jedem Angemeldeten `{id, displayName, enabled}` für die Personenauswahl im Kalender — `/v1/admin/users` ist und bleibt ADMIN-only. Bewusst mager (keine Rolle, kein Benutzername), weil auch das KIOSK-Wandtablet ihn liest; die Leseerlaubnis kommt aus der generischen `GET /v1/**`-Regel, eine eigene Regel gibt es absichtlich nicht
- `/v1/auth/me` enthält seit der Kalender-Personenzuordnung die eigene `id`; bei Anmeldung per Service-Token ist sie `null` — der Filter „Meine" auf der Kalenderseite entfällt dann
- Schreibzugriffe auf `/v1/calendar/categories` sind ADMIN, Lesen ist für alle Angemeldeten offen. Die Matcher dafür müssen **methodenspezifisch** bleiben — ein methodenloser Matcher würde das Lesen fürs Wandtablet mitsperren und den Kalender dort farblos machen (`SecurityRulesTest` hält beide Richtungen fest)
- **Service-Tokens** (Header `X-API-Token`, SHA-256-gehasht in `service_token`, einzeln widerrufbar): blink-vision (Env `API_TOKEN`), Tablet-Presence (App-Einstellung), flow-mcp-server (`HOUSEHOLD_API_TOKEN`). Die reinen Maschinen-Endpunkte (Vision-Webhook/Embeddings, tablet-presence) verlangen die SERVICE-Authority — eine Browser-Session kommt dort nicht ran. Nach dem Rotieren eines Tokens muss der flow-mcp-server-Prozess (bzw. die Claude-Code-Session) neu gestartet werden — die Env wird beim Prozessstart gelesen
- **Audit-Log** (`audit_log`): Login/Logout, Schalter, Modi, Nuki, Flow-/Nutzer-/Token-/Kalender-Änderungen. Aktor-Auflösung: ThreadLocal-Override (Telegram: `TELEGRAM:<chatId>`, Flow-Läufe: `FLOW:<id>`, beides SYSTEM-Typ) > SecurityContext (USER/SERVICE) > SYSTEM (Scheduler/Polling ohne Override: `system`). `AuditService.record` wirft nie — Audit darf die Aktion nicht brechen
- **Bootstrap:** Erster Start ohne Nutzer legt `admin` mit Passwort `changeit` an; `must_change_password` erzwingt den Wechsel beim ersten Login (UI-geführt, Endpunkt `POST /v1/auth/password` erneuert Session-Principal und Remember-Me-Cookie). Der letzte aktive Admin kann nicht deaktiviert/degradiert werden
- Deaktivierte Nutzer verlieren sofort den Zugang (`DisabledUserSessionFilter`, eine DB-Abfrage pro Session-Request — Haushaltsgröße)
- **Rollout-Reihenfolge beachten:** Erst Tokens über die Admin-Seite „API-Tokens“ anlegen, dann Envs setzen (`VISION_API_TOKEN`, `HOUSEHOLD_API_TOKEN`, Tablet-Einstellung), dann Sidecars neu starten — sonst fallen Vision-Webhooks und Tablet-Presence **still** aus
- Frontend: Login-Seite (`pages/login/`), 401-Interceptor, `authGuard`/`adminGuard`, Admin-Seiten `admin/users`, `admin/service-tokens`, `admin/audit-log`, `admin/calendar-categories`; Header filtert Menüpunkte nach Rolle
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
