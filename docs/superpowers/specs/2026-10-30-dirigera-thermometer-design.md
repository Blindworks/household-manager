# IKEA-Dirigera-Thermometer (TIMMERFLOTTE) — Design

Stand 2026-10-30. Spec für die Anbindung des IKEA-Dirigera-Hubs, vorerst nur zum Lesen der TIMMERFLOTTE-Temperatur-/Feuchtesensoren.

## Anlass und Ziel

Im Haus läuft seit 2026-10-30 ein IKEA-Dirigera-Hub (`192.168.1.159`, DHCP-Name `gw2-05f1aba0b770`, MAC `68:EC:8A:10:A4:9A`) mit vier TIMMERFLOTTE-Sensoren (Esszimmer, Küche, Flur, Treppenhaus). Ihre Werte sollen genauso im Household Manager ankommen wie die der BLE-Thermometer:

- Entitäten für Flows
- Historie
- Quelle in der Temperaturansicht (Website `/temperatures` und Tablet `/tablet/temperatures`)
- Klima-Kachel und `/current`, damit auch der Dog-Screen, die Lüftung und der Hausverstand sie sehen

Vorbild ist das Modul `blesensor/` samt Spec `2026-10-13-ble-thermometer-design.md`. Der Hub-Teil (manuelle IP, Identitäts- und Zertifikatsprüfung) folgt der Heizungssteuerung (`thermostat/`, `PUT /v1/thermostats/hub`).

## Ziele

- Ein Admin koppelt den Hub einmalig in der App: IP eintragen, „Koppeln“ klicken, Aktionsknopf am Hub drücken.
- Jeder TIMMERFLOTTE am Hub erscheint **automatisch**, mit dem Namen aus der IKEA-Home-App (Nutzerentscheidung). Ein Freischalten wie bei BLE entfällt, weil am eigenen Hub keine fremden Geräte hängen.
- Pro Sensor gibt es Temperatur, Luftfeuchte und Batterie. Die Historie läuft wie bei BLE, die Temperaturansicht bekommt eine neue Quelle.
- Hub weg oder Sensor still: Die Entitäten werden `unavailable`, ihre Attribute bleiben dabei erhalten.

## Nicht-Ziele (v1)

- Lampen, Steckdosen und andere IKEA-Gerätetypen schalten oder lesen (Nutzerentscheidung: vorerst nur Sensoren)
- WebSocket-Ereigniskanal des Hubs (`wss://…:8443/v1`). Polling reicht für Thermometer.
- Discovery per mDNS. Sie geht im Docker-Bridge-Netz von PROD nicht, wie bei Tapo und Kasa.
- Mehrere Hubs
- Das `windowOpen`-Attribut der Sensoren (Dirigera-interne Heizlogik, ohne Bedeutung für uns)

## Anti-Ziele

- **Kein geratener Wert.** Ein fehlendes Feld ist `null`, nie 0. Ein Sensor ohne frische Werte ist `unavailable` und behält keinen alten Wert, als wäre er aktuell.
- **Kein Token und keine Hub-Daten für das Wandtablet.** Kopplung und Hub-Einstellungen sind ADMIN-only.
- **Keine zweite Slug-Implementierung.** Entity-IDs entstehen über `EntityIds.build`.
- **Kein globales Trust-All-TLS.** Die Ausnahme vom Zertifikatscheck gilt nur für den Dirigera-Client und nur für den gemerkten Fingerabdruck.

## Verifizierte Fakten (Realtest 2026-10-30, Firmware Hub 2.975.3, Sensor 1.0.21)

Gekoppelt per Probe-Skript, die echte Antwort von `GET /v1/devices` liegt vor. Sie wird als Test-Fixture übernommen (Vorgehen siehe unten).

- **API:** `https://<IP>:8443/v1`, Bearer-Token. Das Zertifikat ist selbstsigniert, SHA-256-Fingerabdruck `63:26:C5:A3:44:D0:F6:D2:C9:7D:28:C3:78:76:BC:82:A6:EB:16:05:7E:36:74:5B:81:E7:EF:3F:75:3F:3D:39`.
- **Kopplung (PKCE):**
  1. `GET /v1/oauth/authorize?audience=homesmart.local&response_type=code&code_challenge=<S256>&code_challenge_method=S256` liefert `{"code": …}`.
  2. Der Nutzer drückt den Aktionsknopf am Hub.
  3. `POST /v1/oauth/token` (Formular: `code`, `name`, `grant_type=authorization_code`, `code_verifier`) liefert `{"access_token": …}`. Vor dem Knopfdruck antwortet der Endpunkt ohne `access_token`. Das Probe-Skript hat ihn alle 2 s abgefragt und nach dem Druck den Token bekommen.
  4. Der Token läuft nach allem, was bekannt ist, nicht ab.
- **Ein physischer TIMMERFLOTTE besteht aus ZWEI Einträgen** mit gleicher `relationId`:
  - `<relationId>_1` trägt nur `attributes.currentTemperature` (Float mit Rauschen, z. B. `18.790000915527344`).
  - `<relationId>_2` trägt nur `attributes.currentRH` (ganze Zahl).
  - Beide Einträge tragen `deviceType: "environmentSensor"`, `type: "sensor"`, `customName` (gleich), `model: "TIMMERFLOTTE temp/hmd sensor"`, `productCode: "E2314"`, `batteryPercentage`, `isReachable` und `lastSeen` (ISO-UTC).
  - `room` (`name`) steht nur am `_1`-Eintrag. `serialNumber` ist `UNKNOWN_SERIAL_NUMBER` und taugt nicht als Identität.
- Der Hub selbst steht als `deviceType: "gateway"` in der Liste. Seine `id` (`05f1aba0-…_1`) und seine `serialNumber` dienen als Identität des Hubs.
- Alle vier Sensoren hatten ein `lastSeen` jünger als 3 Minuten. Das echte Meldeintervall ist darüber hinaus unbekannt.

## Entscheidungen

### Zusammenführen zu einem Sensor

Gruppiert wird über `relationId`, nicht über das Suffix der `id`. Welcher Eintrag Temperatur und welcher Feuchte liefert, entscheidet die **Feld-Präsenz** (`currentTemperature` bzw. `currentRH`), nicht `_1`/`_2`. Muster: Tapo-Fähigkeiten.

- `name` kommt aus `customName`, `room` aus dem Eintrag, der ihn trägt.
- `batteryPercent` ist das Minimum der Teilgeräte.
- `reachable` heißt: alle Teilgeräte melden `isReachable`.
- `lastSeen` ist das Maximum der Teilgeräte.
- Einträge mit anderem `deviceType` werden ignoriert. Das gilt auch für künftige Lampen. Ein `environmentSensor` ohne beide Felder (z. B. VINDSTYRKA mit anderen Feldnamen) wird trotzdem übernommen und meldet, was er hat.

**Werte runden auf zwei Nachkommastellen** (`DECIMAL(5,2)`), schon beim Parsen. Das ist dieselbe Lehre wie bei BLE: Ohne Rundung vergleicht der Recorder `18.790000915…` mit dem DB-Wert `18.79`, und die Regel „nur bei Änderung speichern“ greift nie.

### Kopplung und Hub-Einstellungen

- **Speicherort:** `application_settings`, Kategorie `DIRIGERA`, mit den Schlüsseln `hub_host`, `access_token`, `cert_sha256` und `hub_id`.
  - Der Token liegt im Klartext in der DB, wie das Tractive-Token. Das ist ein bewusster LAN-Trade-off.
  - Lesen wirft nie (Muster `TractiveHomeSettingsService`).
- **`PUT /v1/dirigera/hub` `{host}`** (ADMIN) setzt nur die Adresse.
- **`POST /v1/dirigera/pairing`** (ADMIN) läuft so:
  1. Den Zertifikats-Fingerabdruck holen und merken (Trust on first use).
  2. `authorize` aufrufen.
  3. Code und Verifier im Speicher halten, die Antwort ist `{state: "PRESS_BUTTON", expiresAt}`.
  4. Ein Hintergrund-Task fragt `oauth/token` alle 2 s ab, höchstens 90 s lang.
  5. Danach `GET /v1/devices` und die Gateway-`id` als `hub_id` speichern.
- **`GET /v1/dirigera/pairing`** liefert den Status `IDLE | PRESS_BUTTON | PAIRED | FAILED` samt Grund. Das Frontend pollt ihn, solange `PRESS_BUTTON` gilt. Ein zweiter Start während eines laufenden Kopplungsvorgangs gibt 409.
- **Identitäts- und Zertifikatsprüfung bei jedem Poll:**
  - Antwortet unter der IP ein Zertifikat mit anderem Fingerabdruck, wird nichts gelesen. Der Poll gilt als fehlgeschlagen, und die Admin-Seite zeigt „Zertifikat geändert, neu koppeln“.
  - Dasselbe gilt, wenn die Gateway-`id` in der Antwort nicht `hub_id` ist.
  - Grund: Eine DHCP-Verschiebung auf ein anderes Gerät darf nicht als „Hub“ durchgehen. Zudem bekäme ein fremdes Gerät sonst den Bearer-Token.
  - **Firmware-Updates könnten das Zertifikat erneuern.** Dann hilft nur Neukoppeln. Das ist akzeptiert und auf der Admin-Seite mit klarem Text sichtbar.
- **`DELETE /v1/dirigera/pairing`** (ADMIN) entkoppelt: Es löscht den Token und markiert alle Entitäten als `unavailable`. Sensorzeilen und Historie bleiben erhalten.
- **Fehler gegenüber dem Frontend:** Ein Hub-Fehler gibt 502, „nicht gekoppelt“ gibt 400. **Nie 401**, sonst wirft der Auth-Interceptor den Nutzer aus der Haushalts-Session (Muster Tractive/Blink). Ein 401 *vom Hub* (Token widerrufen) wird zu `IllegalStateException` → 400 „neu koppeln“.
- **Der Probe-Token aus der Session vom 2026-10-30 wird nicht übernommen.** Die App koppelt sich selbst. Der Probe-Client bleibt als verwaister Eintrag im Hub, das ist harmlos.

### Polling

- `DirigeraPollingService`, alle 60 s (`dirigera.poll-interval-ms`, `dirigera.enabled`), **ein** `GET /v1/devices` je Zyklus.
- HTTP/1.1 erzwingen: Der Hub ist kein Python-Sidecar, das Projekt macht es aber für alle LAN-Geräte so, und es schadet nicht.
- Timeouts: Connect 5 s, Read 10 s.
- Der Poller meldet sich bei der Job-Tafel des Systemprotokolls:
  - Nicht gekoppelt ⇒ `reportInactive`.
  - Hub nicht erreichbar, Zertifikat oder Identität falsch, oder Hub antwortet 401 ⇒ `reportFailure`.
  - Ein einzelner stiller Sensor ist keine Störung.
- Die Scheduled-Methode wirft nie.

### Datenhaltung

- **Tabelle `dirigera_sensor`**, eine Zeile je `relationId`:
  - `relation_id` UNIQUE, `name`, `room`, `model`
  - `last_temperature DECIMAL(5,2)`, `last_humidity DECIMAL(5,2)`, `battery_percent`
  - `last_seen_at`, `reachable`, `known_to_hub`, `created_at`, `updated_at`
- **Tabelle `dirigera_sensor_reading`** (Historie): `sensor_id` als FK mit `ON DELETE CASCADE`, `measured_at`, `temperature`, `humidity`. Dazu ein Index auf `(sensor_id, measured_at)`.
- **Changesets:** je DDL ein eigenes Changeset (MariaDB-DDL-Autocommit), Präfix `20261030-`.
- **Historie** nur bei Wertänderung oder wenn seit 5 min keine Zeile entstand. Das Muster ist `BleSensorReadingRecorder`, Zeitstempel ist `lastSeen` des Sensors.
- **Keine Retention**, wie bei BLE und `zigbee_measurement`.
- **Reihenfolge im Zyklus:** erst die Zeile aktualisieren, dann den Recorder laufen lassen, dann die Entitäten melden. Recorder und Entity-Meldung werfen nie, und ein Fehler bei einem Sensor reißt die anderen nicht mit.

### Entitäten

- `sensor.dirigera_<relationId ohne Bindestriche>_temperature` / `_humidity` (`EntitySource.DIRIGERA`).
  - `DirigeraEntityMapper.entityId` ist die einzige Id-Definition.
  - Die Id hängt an der `relationId`. Umbenennen in der IKEA-App ist für Flows gefahrlos, der neue Name kommt mit dem nächsten Poll.
  - Ein Entfernen und Neu-Anlernen am Hub erzeugt eine neue `relationId` und damit eine neue Entität. Ein darauf gebauter Flow liefe dann still ins Leere (dieselbe Falle wie bei Thermostaten).
- Attribute: `unit`, `batteryPercent`, `room`, `lastSeenAt`, `model`.
- **Kein `deviceClass`**, Begründung siehe Blink.
- **`unavailable` (mit erhaltenen Attributen, genau einmal)** in diesen Fällen:
  - Der Hub ist nicht erreichbar oder liefert eine falsche Identität. Das betrifft alle Sensoren.
  - Ein Sensor meldet `isReachable: false`.
  - `lastSeen` ist älter als `dirigera.stale-after-minutes` (Default 60, **unverifiziert**, nach einigen Tagen anhand der echten Abstände nachziehen).
  - Ein Sensor taucht nicht mehr in der Hub-Liste auf. Dann wird zusätzlich `known_to_hub = false` gesetzt.
- Eine schon `unavailable` Entität wird nicht erneut gemeldet.
- Kein Trigger auf `value: "unavailable"` (tote-Trigger-Falle).

### Temperaturansicht und Klima

- Neue Quelle `dirigera:<id>` in `TemperatureSeriesService`, analog zu `ble:`:
  - Serien aus `dirigera_sensor_reading`
  - Aktuelle Werte aus den Letztwerten der Zeile
  - `entityId` aus dem Mapper (ein Test hält Mapper und `temperatureEntityId` gleich)
- Aufgenommen werden nur Sensoren mit `known_to_hub = true`.
- Angezeigt wird der Name aus der IKEA-App. „Temperatur Esszimmer“ wird über `shortenSensorName` zu „Esszimmer“.
- Kein TIMMERFLOTTE ist ein Außenfühler, beide Außenfühler-Listen bleiben unverändert.

### Admin-Seite

Route `admin/dirigera` („IKEA Dirigera“, Wurzelklasse in `$admin-scopes` eintragen):

- IP-Feld
- Knopf „Koppeln“ mit dem Hinweis „Jetzt den Aktionsknopf an der Unterseite des Hubs drücken“ und einem Countdown aus `expiresAt` des Servers
- Kopplungsstatus, letzter erfolgreicher Poll, Fehlertext
- „Entkoppeln“
- Sensorliste (Name, Raum, Temperatur, Feuchte, Batterie, zuletzt gesehen)

**Löschen** ist nur für Zeilen mit `known_to_hub = false` möglich (sonst 409). Es entfernt Zeile, Historie (Kaskade), Entitäten und deren `entity_tile_visibility`-Zeilen. Muster: Zigbee-Purge.

### Sicherheit und Audit

- `/v1/dirigera/**` ist ein **methodenloser ADMIN-Matcher vor** der generischen `GET /v1/**`-Regel. Das Wandtablet braucht davon nichts, es sieht die Werte über `/v1/temperatures` und die Entitäten.
- `SecurityRulesTest` hält MEMBER-403 und ADMIN-200 fest.
- Audit-Einträge, jeweils nach Erfolg: `dirigera.hub.update`, `dirigera.pair`, `dirigera.unpair`, `dirigera.sensor.delete`. Dazu je eine Regel in `AuditEntryDescriber` (und `searchHints`), falls Ids im Detail stehen.

### Neukoppeln und Hub-Wechsel (Entscheidung des Spec-Autors)

- **Das Koppeln übernimmt immer die neue Identität**, also `hub_id` und `cert_sha256` des gerade gekoppelten Geräts. Es gibt kein 409 bei abweichender Gateway-`id`. Begründung: Ein Token gibt nur das Gerät heraus, an dem jemand physisch den Knopf gedrückt hat. Das Koppeln *ist* damit der Identitätsnachweis. Eine erneuerte Firmware-Zertifizierung und ein getauschter Hub heilen sich so ohne eigenen Reset-Pfad.
- **Der DHCP-Schutz bleibt beim Polling bestehen**, also überall außer beim expliziten Koppeln.
- `PUT /hub` mit neuer IP behält `hub_id` und `cert_sha256`. Damit schützt die Prüfung genau gegen einen Tippfehler bei der IP.
- `DELETE /pairing` löscht Token, `hub_id` und `cert_sha256`. `hub_host` bleibt stehen.
- **Sensorzeilen eines früheren Hubs** tauchen nicht mehr auf und werden mit dem ersten erfolgreichen Poll `known_to_hub = false` (Entitäten einmal `unavailable`). Gelöscht werden sie nur von Hand.

### Freshness in `/current` und der Temperaturansicht (Entscheidung des Spec-Autors)

- Das BLE-Muster wird übernommen: `measuredAt` ist `lastSeen`, und die Verbraucher beurteilen das Alter selbst (`isReadingStale`, 60 min, gleich wie der Default von `stale-after-minutes`).
- **Zusätzlich** fehlen Sensoren mit `reachable = false` oder `known_to_hub = false` in `/current`. Diese Information hat sonst kein Verbraucher.
- Die Historienserien bleiben in der Temperaturansicht sichtbar.

### Test-Fixture

Die echte Antwort von `GET /v1/devices` liegt **bereits bereinigt** in `backend/src/test/resources/dirigera/devices.json`:

- Sie ist ein **nacktes JSON-Array** ohne Hülle.
- `lastSeen` und `createdAt` sind ISO-8601-Strings in UTC mit Millisekunden (`"2026-10-30T08:53:36.000Z"`). Sie werden in die Zone des `Clock`-Beans umgerechnet.
- Beim `gateway`-Eintrag sind Koordinaten, Zeitzone, Sonnenzeiten, Länder- und Backend-Angaben entfernt. Er enthält keinen Token.

**Token-Endpunkt vor dem Knopfdruck:** Das Probe-Skript hat Status und Body nicht aufgezeichnet. Regel (Entscheidung des Spec-Autors): Jede Antwort ohne `access_token` gilt bis zur 90-s-Frist als „wartet“, danach als `FAILED` mit dem zuletzt gesehenen Status und Body als Grund. Ein Verbindungsfehler zählt ebenfalls als „wartet“.

Parser- und Gruppierungstests laufen gegen diese Datei. Zusätzlich gibt es synthetische Fälle:

- Sensor mit nur einem Teilgerät
- `isReachable: false`
- Fremder `deviceType`
- Fehlende Felder

## Dem Builder überlassen

- Paket- und Klassenschnitt innerhalb von `dirigera/`, DTO-Form der Admin-API
- Wie der Hintergrund-Task der Kopplung umgesetzt wird (TaskScheduler, eigener Executor), solange er den HTTP-Thread nicht blockiert und ein Neustart einen laufenden Kopplungsvorgang verwirft
- Die Umsetzung von Fingerabdruck-Pinning im Java-`HttpClient` (eigener `X509TrustManager`, der nur den gemerkten SHA-256 akzeptiert). Hostname-Verifikation für diesen Client abschalten, weil das Zertifikat nicht auf die IP lautet.
- Wie das Frontend den Kopplungsstatus pollt

## Rollout

1. Backup der DB (die Changesets sind nie gegen die echte DB gelaufen)
2. Deploy
3. Im Router eine **DHCP-Reservierung** für den Hub setzen (`68:EC:8A:10:A4:9A` → `192.168.1.159`). Sonst greift nach einem IP-Wechsel die Identitätsprüfung, und alles steht auf `unavailable`.
4. Admin → IKEA Dirigera: IP eintragen, koppeln, Knopf drücken
5. Temperaturansicht prüfen
6. Nach einigen Tagen `stale-after-minutes` gegen die echten `lastSeen`-Abstände prüfen
7. CLAUDE.md-Abschnitt ergänzen
