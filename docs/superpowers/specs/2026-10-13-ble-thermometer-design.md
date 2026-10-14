# Bluetooth-Thermometer (SensorBlue/ThermoBeacon) über das Wandtablet – Design

Stand: 2026-10-13.

## Ziel

Zwei SensorBlue-Thermometer, die in Reichweite des Wandtablets stehen, werden ins System aufgenommen: Live-Werte als Entitäten (Flows, Klima-Kachel, Hausverstand) **und** Historie in der Temperaturansicht (`/temperatures`, `/tablet/temperatures`), gleichberechtigt mit den Zigbee-Fühlern. Empfänger ist die bestehende Android-Kiosk-App; der Server hat kein Bluetooth (Docker-Bridge-Netz).

SensorBlue, Brifit, Oria und ThermoPlus teilen das **ThermoBeacon**-Protokoll (so auch in Home Assistant: die SensorBlue-Integration ist ein Alias der ThermoBeacon-Integration). Die Geräte senden Temperatur, Luftfeuchte und Batteriespannung dauerhaft und unverschlüsselt als BLE-Advertisement; eine Verbindung ist nie nötig.

## Nicht-Ziele

- Keine anderen BLE-Protokolle (BTHome, Xiaomi, Govee). Der Parser ist so geschnitten, dass ein zweites Format später ergänzt werden kann.
- Kein eigenes BLE-Gateway (ESP32/MQTT) und kein Foreground-Service in der App – die Kiosk-App liegt dauerhaft im Vordergrund.
- Keine Retention der Historie in v1 (wie `zigbee_measurement`); die Schreibregel begrenzt das Wachstum auf höchstens einen Punkt pro Minute und Sensor.
- Kalibrierungen aus der SensorBlue-App wirken nicht: sie gelten nur für das Display, im Funkpaket steht der Rohwert.
- Keine eigene Batterie-Entität (Batterie ist Attribut, wie bei Zigbee).

## Entscheidungen aus dem Brainstorming

| Frage | Entscheidung |
|---|---|
| Nur Live oder mit Historie? | **Mit Historie** – vierte Quelle im `TemperatureSeriesService` |
| Woher kommt der Name? | **Admin-Seite** – implizite Registrierung je MAC, Benennen im Backend |
| Neuer Sensor? | **Erst freischalten** – fremde Geräte in Funkreichweite bleiben stumm in der Liste |
| Scan-Architektur | **Scan in der Kiosk-Activity, Sammelmeldung pro Minute** |

## Tablet-App (`tablet-app/`)

### Berechtigungen (Manifest)

- `BLUETOOTH_SCAN` mit `android:usesPermissionFlags="neverForLocation"` und `BLUETOOTH_CONNECT` (nur zum Lesen des Adapterzustands), beide Laufzeitberechtigungen ab Android 12.
- Für minSdk 29–30 zusätzlich `BLUETOOTH` und `BLUETOOTH_ADMIN` mit `maxSdkVersion="30"`. Auf Android 10/11 bräuchte ein Scan zusätzlich Standort; **das wird bewusst nicht unterstützt** – das Zieltablet läuft auf Android 17, der Scanner meldet auf älteren Versionen ohne Standortberechtigung lediglich eine Warnung.
- Kein Standort, kein `BLUETOOTH_ADVERTISE`.
- Die Anfrage erfolgt direkt nach der Kamera-Berechtigung in `KioskActivity`; Verweigerung ist fail-safe (Warnung im Log, Rest der App unberührt).

### `ThermoBeaconScanner`

- `BluetoothLeScanner.startScan(filters, settings, callback)` mit genau einem `ScanFilter` auf die Service-UUID `0000fff0-0000-1000-8000-00805f9b34fb` und `SCAN_MODE_LOW_POWER`. Der Filter läuft im Bluetooth-Chip, der Dauerscan kostet deshalb kaum Akku.
- Der Scan läuft **dauerhaft** und wird nicht pro Zyklus neu gestartet: Android drosselt Apps, die den Scan öfter als fünfmal in 30 s starten, stillschweigend.
- Je MAC wird nur das zuletzt geparste Paket samt Empfangszeit (`SystemClock.elapsedRealtime()`) und RSSI gehalten (`ConcurrentHashMap`).
- Fail-safe wie bei der Kamera: kein Adapter, Adapter aus, Berechtigung verweigert oder `startScan` wirft ⇒ einmalige Warnung, kein Scan. Ein `BroadcastReceiver` auf `BluetoothAdapter.ACTION_STATE_CHANGED` startet den Scan neu, wenn Bluetooth wieder angeht, und stoppt ihn beim Ausschalten.
- Lebenszyklus: Start in `onCreate` (nach erteilter Berechtigung), Stop in `onDestroy`. Das Soft-Off-Overlay ändert den Activity-Zustand nicht; der Scan läuft dabei weiter (Realtest-Punkt).

### `ThermoBeaconParser` (reines Kotlin, unit-getestet)

Übernimmt die Regeln des Home-Assistant-Parsers (`thermobeacon-ble`, ursprünglich `bleparser`):

1. Für jeden Eintrag der `manufacturerSpecificData` des `ScanRecord`: Hersteller-ID als 2 Byte little-endian **vor** die Nutzdaten stellen.
2. Gesamtlänge muss **20** sein. 22 Byte ist das Metadatenpaket und wird verworfen; andere Längen ebenfalls.
3. Byte 0 (die Hersteller-ID) muss in `{0x10, 0x11, 0x14, 0x15, 0x18, 0x1B, 0x30}` liegen; sonst kein ThermoBeacon.
4. Bytes 10–15 little-endian: Spannung mV (`uint16`), Temperatur (`int16`) / 16, Feuchte (`uint16`) / 16.
5. Temperatur > 100 oder Feuchte > 100 ⇒ Paket verwerfen.
6. Ergebnis: `ThermoBeaconReading(mac, temperature, humidity, batteryMillivolts)`. Das Batterieprozent rechnet das Backend.

Testfixtures: die Beispielpakete aus der Home-Assistant-Testsuite (`tests/test_parser.py` von `thermobeacon-ble`) plus Negativfälle (22 Byte, falsche ID, Länge 19, Feuchte 101).

### `SensorReporter`

- Alle 60 s `POST {backendBaseUrl}/v1/ble-sensors/readings` mit `X-API-Token` (derselbe Service-Token wie die Präsenz, keine neue Einstellung), Timeouts 5 s wie `PresenceReporter`.
- Body: `tabletId` und eine Liste aus `mac`, `temperature`, `humidity`, `batteryMillivolts`, `rssi`, `ageSeconds` (Alter des Pakets zum Sendezeitpunkt, aus `elapsedRealtime`-Differenz). Sensoren mit Paket älter als 5 min fehlen; eine leere Liste wird nicht gesendet.
- Fehler werden nur geloggt. Keine Wiederholung – die nächste Minute bringt ohnehin frische Werte.

### Einstellung

Schalter „Bluetooth-Thermometer melden" im Settings-Screen (`AppSettings.bleReportingEnabled`, Default `true`). Aus ⇒ kein Scan, kein Reporter.

### Realtest-Punkte (nur mit echter Hardware prüfbar)

- Tatsächliches Sendeintervall der Sensoren (Erwartung: wenige Sekunden).
- Ob der Bluetooth-Chip des Lenovo Tab M11 den UUID-Scanfilter sauber umsetzt (manche Chips liefern mit Filter nichts; dann Rückfall auf ungefilterten Scan mit App-seitigem Filter – als Folgeänderung, nicht vorab gebaut).
- Ob der Scan durchläuft, während das Display per Soft-Off dunkel ist.
- Ob die Sensoren eine feste MAC senden (ThermoBeacon-Geräte tun das laut Home-Assistant-Integration).

## Backend (`backend/.../blesensor/`)

Repositories wie immer unter `com.household.manager.repository` (JpaConfig schränkt das Scanning ein).

### Persistenz (Liquibase; jedes DDL in eigenem Changeset wegen MariaDB-Autocommit)

**`ble_sensor`** – eine Zeile je gesehener MAC:

| Spalte | Typ | Bedeutung |
|---|---|---|
| `id` | BIGINT PK | |
| `mac` | VARCHAR(17) UNIQUE | normalisiert `aa:bb:cc:dd:ee:ff` (kleingeschrieben) |
| `name` | VARCHAR(100) NULL | NULL bis zur Übernahme |
| `active` | BOOLEAN NOT NULL DEFAULT false | übernommen |
| `tablet_id` | VARCHAR(100) | meldendes Tablet (zuletzt) |
| `last_seen_at` | DATETIME | Empfangszeit der letzten Meldung minus `ageSeconds` |
| `last_temperature`, `last_humidity` | DECIMAL(5,2) NULL | |
| `last_battery_mv` | INT NULL | |
| `last_rssi` | INT NULL | |
| `created_at` | DATETIME NOT NULL | |

Die Letztwerte stehen in dieser Tabelle, damit die Admin-Liste auch **nicht** übernommene Sensoren mit aktuellem Wert zum Wiedererkennen zeigt, ohne dass sie eine Historie bekommen.

**`ble_sensor_reading`:** `id`, `sensor_id` (FK auf `ble_sensor`, `ON DELETE CASCADE`), `measured_at`, `temperature` DECIMAL(5,2), `humidity` DECIMAL(5,2), `battery_mv` INT. Index `(sensor_id, measured_at)` in eigenem Changeset.

Keine Fremdschlüssel auf `entity_states`.

### Verarbeitung einer Meldung (`BleSensorIngestService`)

Je Eintrag der Liste:

1. MAC normalisieren; `measured_at = now − ageSeconds` (die Uhr des Tablets wird nicht benutzt).
2. Unplausible Werte (Temperatur außerhalb −40…100, Feuchte außerhalb 0…100, Spannung außerhalb 0…5000 mV) ⇒ Eintrag überspringen und loggen, die übrigen Einträge derselben Meldung laufen weiter (Muster Blink-Bewegungs-Webhook).
3. Upsert der `ble_sensor`-Zeile (Letztwerte, `tablet_id`, `last_seen_at`); neue MAC ⇒ Zeile mit `active = false`, `name = NULL`.
4. **Nur bei `active`:** Historienpunkt über `BleSensorReadingRecorder` und Entity-Update über `BleSensorEntityMapper`.

Ein Fehler bei einem Sensor reißt die anderen nicht mit (try/catch je Eintrag, Muster `TractivePollingService`).

### Schreibregel der Historie (`BleSensorReadingRecorder`)

Ein Punkt entsteht nur, wenn sich Temperatur **oder** Feuchte gegenüber dem letzten gespeicherten Punkt geändert haben **oder** der letzte Punkt älter als 5 min ist (Muster `ThermostatHistoryRecorder`). Höchstens ein Punkt pro Minute und Sensor; keine Retention in v1 (dokumentiert in CLAUDE.md).

### Entitäten (`EntitySource.BLE`, `BleSensorEntityMapper`)

- `sensor.ble_<mac12>_temperature` und `sensor.ble_<mac12>_humidity`, wobei `<mac12>` die MAC ohne Doppelpunkte ist; Bildung ausschließlich über `EntityIds.build` in `BleSensorEntityMapper.entityId(sensor, measurement)` – **die einzige Id-Definition** (auch der Temperatur-Service fragt sie).
- Nur für **aktive** Sensoren. `friendlyName` = vergebener Name (+ „Temperatur"/„Luftfeuchte"). Die Id hängt an der MAC: Umbenennen ist für Flows gefahrlos.
- Attribute: `unit` (`°C` / `%`), `batteryPercent`, `batteryMillivolts`, `rssi`, `tabletId`.
- `batteryPercent` stückweise linear aus der Spannung (dieselbe Kurve wie Home Assistant): ≥ 3000 mV ⇒ 100; ≥ 2600 ⇒ 60 + (mV − 2600) · 0,1; ≥ 2500 ⇒ 40 + (mV − 2500) · 0,2; ≥ 2450 ⇒ 20 + (mV − 2450) · 0,4; sonst 0. Einzige Definition: `BleBatteryEstimator`.
- **Kein `deviceClass`** (projektweit nur `door` und `power` ausgewertet; siehe Blink-Begründung).

### Ausfall (`BleSensorAvailabilityWatchdog`, `@Scheduled` minütlich, wirft nie)

- Aktiver Sensor mit `last_seen_at` älter als **10 min** ⇒ beide Entitäten `unavailable` **mit erhaltenen Attributen** (`EntityStateWriter.upsert` überschreibt `attributes` sonst komplett, Muster Zigbee-Watchdog). Die nächste Meldung stellt sie wieder her.
- Die Schwelle steht in `application.properties` (`ble-sensor.stale-after-minutes`, Default 10).
- Deaktivieren meldet einmal `unavailable` (Muster entfernter Ladesäulen-Favorit).
- Ein Flow-Trigger auf `value: "unavailable"` kann nie feuern (engine-weit unterdrückter Übergang) – Hinweis in CLAUDE.md wie bei allen Quellen.

### Übernahme, Umbenennen, Löschen (`BleSensorAdminService`)

- Aktivieren verlangt einen nicht-leeren Namen (400 sonst).
- Umbenennen wirkt beim nächsten Entity-Update (`friendlyName`), Id unverändert.
- Löschen nur bei `active = false` (409 sonst, erst deaktivieren). Löscht Zeile samt Historie (Kaskade) **und** die eventuell vorhandenen Entitäten eines vorher aktiven Sensors – sonst stünden sie für immer auf `unavailable`. Ein gelöschter Sensor, der weiter funkt, entsteht mit der nächsten Meldung neu als „nicht übernommen" (akzeptiert).
- Audit: `ble-sensor.update` (MAC, Name, aktiv) und `ble-sensor.delete` (MAC), jeweils nach Erfolg.

## API `/api/v1/ble-sensors`

| Methode | Pfad | Rolle | Zweck |
|---|---|---|---|
| POST | `/readings` | **SERVICE** | Sammelmeldung des Tablets, 204 |
| GET | `/` | **ADMIN** | alle Sensoren (übernommen oder nicht) mit Letztwerten, `batteryPercent`, `lastSeenAt`, `tabletId`, `active` |
| PUT | `/{id}` | **ADMIN** | Voll-PUT `{name, active}` |
| DELETE | `/{id}` | **ADMIN** | nur inaktiv, 409 sonst |

- `POST /readings`-Body: `{ "tabletId": "wandtablet", "readings": [ { "mac", "temperature", "humidity", "batteryMillivolts", "rssi", "ageSeconds" } ] }`. Strukturfehler (fehlende Liste, > 50 Einträge, ungültige MAC-Syntax, fehlende `tabletId`) ⇒ 400 für die ganze Meldung. Einzelne unplausible Werte werden übersprungen (siehe Ingest).
- `PUT` ist bewusst ein **Voll-PUT**: ein Teil-PUT würde `active` serverseitig als „aktiv" lesen (Muster Netzwerk-Geräte). `active: true` ohne Namen ⇒ 400.
- Fehler sind 400, 404, 409 oder 500 – **nie 401** (der Auth-Interceptor des Frontends würde den Nutzer aus der Haushalts-Session werfen).

### Security (`SecurityConfig`, Reihenfolge ist tragend)

- `POST /v1/ble-sensors/readings` in den **SERVICE**-Block (neben `/v1/tablet-presence/**`); eine Browser-Session kommt dort nicht ran.
- **Methodenloser** ADMIN-Matcher `/v1/ble-sensors/**` **vor** der generischen `GET /v1/**`-Regel – die Liste enthält MACs fremder Geräte in Funkreichweite, das Wandtablet darf sie nicht lesen. Die Temperaturansicht braucht den Endpunkt nicht (sie liest `/v1/temperatures`).
- `SecurityRulesTest` hält fest: SERVICE 204 und MEMBER 403 auf dem POST; ADMIN 200, MEMBER 403 und KIOSK 403 auf dem GET; MEMBER 403 auf PUT und DELETE.

## Temperaturansicht (`TemperatureSeriesService`)

Vierte Quelle `ble` mit demselben Dreierpack wie Zigbee, jeweils in `safe("ble", …)` gekapselt und nur für aktive Sensoren:

- `getSeries`: je aktiver Sensor eine `TemperatureSensorSeries` mit `sensorId = "ble:<id>"`, `source = "BLE"`, Name über `temperatureName(EntitySource.BLE, mac, name)`, Temperatur und Feuchte aus `ble_sensor_reading`, über den `SeriesDownsampler` gemittelt.
- `getCurrent`: aktive Sensoren mit Punkt in den letzten 7 Tagen (`CURRENT_LOOKBACK_DAYS`); die `entityId` kommt aus `BleSensorEntityMapper.entityId`, **nicht** aus einer zweiten Bildung – ein Test hält beide gleich, sonst bricht die Außenfühler-Erkennung still.
- `getSensorSeries("ble:<id>")`: wie Zigbee, 404 bei unbekannter oder inaktiver Id.

Damit erscheinen die Thermometer ohne weitere Änderung auf `/temperatures`, in `/tablet/temperatures`, in der Klima-Kachel und können per Entity-ID in die beiden Außenfühler-Listen aufgenommen werden (`ventilation.outdoor-sensor-entity-ids` und `OUTDOOR_SENSOR_ENTITY_IDS`), falls je eines draußen steht. Im Plan ist zu prüfen, ob das Frontend die Quelle `BLE` irgendwo als Icon oder Filter kennen muss.

## Frontend: Admin-Seite „Bluetooth-Thermometer"

- `pages/admin-ble-sensors/`, Route `admin/ble-sensors`, Eintrag im Admin-Menü; `adminGuard`.
- Tabelle, nicht übernommene Sensoren zuerst: Name oder MAC, Status-Badge (Aktiv / Inaktiv / Nicht übernommen), letzte Temperatur und Feuchte, Batterie in Prozent, „Zuletzt gesehen" relativ (`–` wenn nie), meldendes Tablet.
- Aktionen: **Übernehmen** (Dialog fragt den Namen, sendet `active: true`), **Umbenennen**, **Deaktivieren**, **Löschen** (nur inaktiv, mit Bestätigung). Alle Dialoge lösen den Sensor beim Bestätigen aus der aktuellen Liste neu auf (Regel `confirmToggle`).
- Refresh alle 30 s, damit man beim Rollout am aktuellen Wert erkennt, welches Thermometer welches ist (eins in die Hand nehmen, Wert steigt). Ein fehlgeschlagener Refresh behält den letzten Stand, nur der Erstabruf meldet einen Fehler.
- Wurzelklasse in `$admin-scopes` (`styles.scss`) eintragen, sonst stehen Knöpfe und Felder unstilisiert da.

## Tests

- **Kotlin:** `ThermoBeaconParserTest` gegen die Fixture-Bytes aus der Home-Assistant-Testsuite plus Negativfälle; `SensorReporterTest` (Alter > 5 min fällt weg, leere Liste sendet nicht) soweit ohne Android-Laufzeit testbar.
- **Backend:** Ingest (Upsert, Überspringen unplausibler Werte, nur aktive bekommen Punkte/Entitäten, Fehlerisolierung je Eintrag), Recorder-Dedupe (Änderung / 5 min), Batteriekurve, Watchdog (10 min, Attribute erhalten, Erholung), Entity-Ids (Mapper = Temperatur-Service), Admin-Regeln (Name Pflicht, Löschen nur inaktiv, Entitäten weg), Security-Richtungen, vierte Quelle im `TemperatureSeriesService`.
- **Frontend:** Karma-Spec der Admin-Seite (Sortierung, Badges, Dialoge lösen neu auf, Fehlerverhalten).

## Rollout

1. Backend deployen (DB-Backup vorher, Migrationen laufen beim Start).
2. APK bauen und installieren; beim ersten Start Bluetooth-Berechtigung gewähren. Der bestehende Tablet-Service-Token reicht.
3. Admin-Seite öffnen, die zwei Sensoren anhand des aktuellen Werts identifizieren, benennen und übernehmen.
4. Temperaturansicht prüfen; Realtest-Punkte aus dem Abschnitt Tablet-App festhalten (Sendeintervall, Scanfilter, Soft-Off).
5. Steht ein Sensor draußen: Entity-ID in beide Außenfühler-Listen eintragen.

## Bewusste Grenzen v1

- Keine Retention der Historie.
- Kalibrierung aus der SensorBlue-App wirkt nicht.
- Nur ThermoBeacon-Protokoll.
- Ein Sensor wird nur über das Tablet empfangen, das ihn zuletzt gemeldet hat; mehrere Tablets überschreiben sich gegenseitig die `tablet_id` (bei einem Tablet irrelevant).
- Android 10/11 ohne Standortberechtigung scannt nicht (Zieltablet: Android 17).
