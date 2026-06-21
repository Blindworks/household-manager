# Zigbee-Integration via zigbee2mqtt + eigener MQTT-Broker

**Datum:** 2026-06-21
**Status:** Entwurf (zur Review)
**Branch:** `feature/zigbee-mqtt-integration`

## Ziel

Zigbee-Sensoren (Temperatur/Luftfeuchte/Luftdruck, Tür-/Fensterkontakte, Bewegung,
Wasserleck, Helligkeit) im Household-Manager **lesend** erfassen: Live anzeigen und
historisch speichern — analog zur bestehenden Airrohr-/Tasmota-Strom-Funktionalität.

Steuerung von Zigbee-Geräten ist **nicht** Teil dieses Vorhabens (ausdrücklich nur lesend).

## Ausgangslage / Kontext

- Die Sonoff ZBBridge (`192.168.1.121`) läuft mit Tasmota im **seriellen Netzwerk-Brücken-Modus**
  (Template „Sonoff ZHABridge", Tasmota-Zigbee-Treiber `XDRV_23` ist bewusst inaktiv: `!23`).
  Tasmota tunnelt nur den seriellen Port des EFR32-Zigbee-Chips über TCP.
- Aktuell macht **Home Assistant (ZHA)** den kompletten Zigbee-Stack. HA wird **demnächst
  abgelöst** und darf nicht als Datenquelle dienen.
- Der serielle Port der Bridge kann immer nur von **einem** Dienst genutzt werden.
- Das Backend besitzt bereits einen MQTT-Client (HiveMQ MQTT Client) in der Meross-Integration
  (`backend/src/main/java/com/household/manager/meross/lib/MqttConnection.java`).

## Architektur / Datenfluss

```
Zigbee-Sensoren → ZBBridge (Tasmota, seriell über TCP, 192.168.1.121)
  → zigbee2mqtt   (Docker, NEU)   – ersetzt die Zigbee-Rolle von Home Assistant
     → Mosquitto  (Docker, NEU)   – eigener Broker, neben der App
        → Spring-Backend (HiveMQ-Client, abonniert zigbee2mqtt/#)
           → Parser → ZigbeeDevice-Register + ZigbeeMeasurement (Historie)
              → SSE-Live  +  REST-History
                 → Angular-Seite (Live-Kacheln + ECharts-Verlauf)
```

## Komponenten

### 1. Docker (docker-compose.yml)

- **`mosquitto`** (Image `eclipse-mosquitto`):
  - Port `1883` exponiert.
  - Ein MQTT-Benutzer + Passwort (kein anonymer Zugriff), Credentials via Env/Passwortdatei.
  - Persistenz-Volume + Config-Datei (`mosquitto.conf`).
- **`zigbee2mqtt`** (Image `koenkk/zigbee2mqtt`):
  - Serial-Adapter `ezsp` für den EFR32 der Sonoff ZBBridge, Port `tcp://192.168.1.121:8888`
    (Tasmota muss den seriellen TCP-Bridge-Port via `TCPStart 8888` bereitstellen; der exakte
    Port wird beim Cutover gegen die laufende Bridge bestätigt).
  - MQTT-Ziel `mqtt://mosquitto:1883` mit obigen Credentials.
  - Eigenes Config-/Data-Volume (Pairings, State).
- Das Backend erhält Broker-Host/User/Pass via Env:
  - Dev: `localhost:1883`
  - Prod/Docker: Service-Name `mosquitto:1883`

> **Constraint (Cutover):** zigbee2mqtt kann die Bridge erst übernehmen, wenn Home Assistant
> den seriellen Port freigibt (HA stoppen). End-to-End-Tests mit echten Geräten sind erst nach
> diesem Cutover möglich.

### 2. Backend — neues Package `com.household.manager.zigbee`

| Klasse | Verantwortung |
|---|---|
| `config/ZigbeeMqttConfig` | HiveMQ-Client aufbauen, verbinden, `zigbee2mqtt/#` abonnieren, Auto-Reconnect |
| `service/ZigbeeMessageParser` | Steuer-Topics filtern, JSON zerlegen, Felder → `MeasurementType` mappen |
| `service/ZigbeeReadingService` | Geräte-Register upserten, Messwerte persistieren, an Live broadcasten |
| `service/ZigbeeLiveService` | SSE-Broadcast (push-getrieben aus MQTT; Muster wie `TasmotaElectricityLiveService`) |
| `controller/ZigbeeController` | REST + SSE-Endpunkte |
| `model/entity/ZigbeeDevice`, `ZigbeeMeasurement`, `MeasurementType` | Domänenmodell |
| `repository/ZigbeeDeviceRepository`, `ZigbeeMeasurementRepository` | Persistenz |
| `dto/ZigbeeDeviceResponse`, `ZigbeeMeasurementResponse`, `ZigbeeLiveResponse` | API-Verträge |

**Topic-Verarbeitung:**
- Abonniert `zigbee2mqtt/#`.
- **Ignoriert** Steuer-/Meta-Topics: `zigbee2mqtt/bridge/*`, sowie Suffixe `/availability`,
  `/set`, `/get`.
- **Optional** wird das retained Topic `zigbee2mqtt/bridge/devices` ausgewertet, um das
  Geräte-Register mit Metadaten (Modell, Hersteller, Typ) anzureichern.
- Gerätedaten kommen aus `zigbee2mqtt/<friendly_name>` mit JSON-Payload, z. B.
  `{"temperature":21.5,"humidity":55,"battery":90,"linkquality":120}`.

### 3. REST-/SSE-API (Basis `/api/v1/zigbee`)

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/v1/zigbee/devices` | Alle bekannten Geräte mit letztem Status |
| GET | `/v1/zigbee/devices/{addr}/measurements?type=&from=&to=` | Historie je Gerät/Messgröße |
| GET | `/v1/zigbee/live` (SSE) | Live-Stream eintreffender Messwerte |

## Datenmodell (Ansatz A — generische Messwerte, Liquibase)

### `zigbee_device`
- `id` (PK)
- `zb_address` / `ieee_address` (unique) — eindeutige Geräteadresse
- `friendly_name`
- `device_type` (nullable)
- `model` (nullable)
- `last_battery_percent` (nullable)
- `last_link_quality` (nullable)
- `last_seen`
- `created_at`

### `zigbee_measurement`
- `id` (PK)
- `device_id` (FK → `zigbee_device`)
- `measurement_type` (Enum)
- `value` (decimal)
- `unit`
- `measured_at`
- `created_at`
- Index: `(device_id, measurement_type, measured_at)`

### `MeasurementType` → Feld-Mapping (zigbee2mqtt → Enum, Einheit)

| zigbee2mqtt-Feld | MeasurementType | Einheit |
|---|---|---|
| `temperature` | TEMPERATURE | °C |
| `humidity` | HUMIDITY | % |
| `pressure` | PRESSURE | hPa |
| `contact` (bool) | CONTACT | 0/1 |
| `occupancy` (bool) | OCCUPANCY | 0/1 |
| `illuminance` / `illuminance_lux` | ILLUMINANCE | lx |
| `water_leak` (bool) | WATER_LEAK | 0/1 |
| `battery` | → `last_battery_percent` am Gerät | % |
| `linkquality` | → `last_link_quality` am Gerät | LQI |

- Booleans werden als `0`/`1` gespeichert.
- **Nicht-numerische Felder** (z. B. `action` von Buttons) sind in **v1 ausgeklammert**
  (höchstens als optionales `last_action`-Textattribut am Gerät). Dank Ansatz A jederzeit
  erweiterbar, ohne bestehendes Schema zu ändern.
- Unbekannte Felder werden ignoriert (debug-geloggt), nicht als Fehler behandelt.

## Frontend — neue Page `pages/zigbee`

- `services/zigbee.service.ts` — REST + SSE-Anbindung.
- `models/zigbee.model.ts` — TypeScript-Interfaces (Device, Measurement, Live-Event).
- **Live-Übersicht:** Kachel pro Gerät mit aktuellem Wert je Messgröße, Batteriestand,
  LinkQuality und „zuletzt gesehen".
- **Verlauf/Detail:** ECharts-Zeitreihe je Messgröße (Muster der bestehenden Consumption-Charts),
  mit Zeitraumauswahl.
- Route + Navigationseintrag ergänzen.

## Fehlerbehandlung & Resilienz

- Backend startet auch dann sauber, wenn der Broker (noch) nicht erreichbar ist; HiveMQ
  reconnectet automatisch.
- Malformte/leere/nicht-numerische Payload-Felder werden geloggt (debug) und übersprungen.
- Unbekannte Geräte werden beim ersten Empfang automatisch im Register angelegt.

## Tests

- **`ZigbeeMessageParser`** (Unit, AAA): echte zigbee2mqtt-Beispiel-JSONs je Sensortyp
  (Klima, Kontakt, Bewegung, Wasserleck, Helligkeit) → korrekte `MeasurementType`-Zuordnung,
  Bool→0/1, Batterie/LinkQuality am Gerät, Filterung der Steuer-Topics.
- **`ZigbeeReadingService`** (Unit/Integration): Register-Upsert (Neuanlage + Update) und
  Persistenz der Messwerte.

## Bewusste Annahmen / Scope-Grenzen

1. **Nur lesend** — keine Gerätesteuerung in diesem Vorhaben.
2. Parser wird gegen das **dokumentierte zigbee2mqtt-JSON-Format** gebaut; reale End-to-End-Verifikation
   erst nach dem HA-Cutover.
3. Wiederverwendung des vorhandenen **HiveMQ MQTT Clients** (konsistent mit Meross), kein neuer
   MQTT-Stack.
4. Buttons/`action` und andere nicht-numerische Ereignisse sind v1 out-of-scope.
