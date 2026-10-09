# Heizungssteuerung (Tapo KE100 am H100-Hub) – Design

Stand: 2026-10-09. Phase 1 von zwei: **Erfassung und manuelle Bedienung** (Backend + Tablet-Ansicht).
Phase 2 (smarte Regelung auf Basis der Raumthermometer) ist ausdrücklich **nicht** Teil dieser Spec.

## Ziel

Die im Haus verteilten Tapo-Heizkörperthermostate (KE100, angebunden über den Tapo-Hub H100) werden im Backend erfasst und auf dem Wandtablet in einer eigenen, schön gestalteten Ansicht bedient: Ist-/Solltemperatur, Modus, Ventilzustand, Batterie, Verlauf.

## Nicht-Ziele

- Keine Wochenprogramme/Zeitpläne in der Oberfläche (würden die spätere Regelung und die Flow-Engine doppeln).
- Keine automatische Regelung (Phase 2).
- Keine Website-Variante der Ansicht (nur Tablet; Service und API sind seitenneutral).
- Keine Unterstützung weiterer Hub-Kindgeräte (T310/T315-Sensoren etc.) – der Hub-Client wird so geschnitten, dass sie später ergänzt werden können.

## Backend

### Hub-Anbindung (`backend/.../tapo/hub/`)

- Der H100 wird wie ein bestehendes Tapo-Gerät lokal per KLAP (Fallback AES) angesprochen; Verbindungscache und Identitätsprüfung (`device_id` des Hubs) nach dem Muster von `TapoDeviceService` / `PUT /devices/{id}/address`.
- Die Hub-IP wird **von Hand** gesetzt, weil die UDP-Discovery im Docker-Bridge-Netz in PROD nichts findet (siehe Tapo-Abschnitt in CLAUDE.md). Fest reservierte IP im Router ist Voraussetzung.
- Lesen: `get_child_device_list`. Schreiben: `control_child` mit verschachteltem `set_device_info` (`target_temp`, `frost_protection_on`, `temp_offset`).
- Pro Poll-Zyklus **ein** Hub-Aufruf, nie einer je Kindgerät (Muster Meross).
- Abgeleiteter Zustand je Thermostat: Ist-Temperatur, Solltemperatur, Modus (`MANUAL` / `FROST_PROTECTION` / `OFF`), `heating` (Ventil offen, aus `trv_state`), `batteryLow`, `online`.
- Wichtige Warnung: Schreibpfad und Feldnamen stützen sich auf dokumentierte Semantik, **nicht** auf einen Realtest gegen die Hardware. Das wird in CLAUDE.md als „unverifiziert" festgehalten (wie beim Tapo-L530-Schreibpfad).

### Persistenz (Liquibase, DDL in getrennten Changesets wegen MariaDB-Autocommit)

- `thermostat`: Kind-`device_id` (Schlüssel), Hub-Referenz, Anzeigename, Raum, Aktiv-Flag.
- `thermostat_history`: Ist-/Solltemperatur und Heiz-Flag mit Zeitstempel. Verdichtung und Aufbewahrung nach dem Muster `entity_power_history` (nur **abgeschlossene** Buckets verdichten).
- Keine Fremdschlüssel auf `entity_states`.

### Entity-State-Spiegelung

Je Thermostat: `sensor.thermostat_<id>_temperature`, `sensor.thermostat_<id>_target`, `binary_sensor.thermostat_<id>_heating`. Die Ids entstehen über `EntityIds.build`. Offline/Hub nicht erreichbar ⇒ `unavailable` **mit erhaltenen Attributen** (Muster Nuki/Zigbee). **Kein `deviceClass`** auf diesen Entitäten (siehe Blink-Begründung; `deviceClass` wird projektweit nur für `door` und `power` ausgewertet).

### API `/api/v1/thermostats`

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/` | Liste mit Zustand |
| PUT | `/{id}/target` | Solltemperatur setzen |
| PUT | `/{id}/mode` | Modus setzen |
| POST | `/bulk` | Mehrere/alle auf einen Sollwert |
| GET | `/{id}/history?range=DAY\|WEEK` | Verlauf (Downsampling über `SeriesDownsampler`) |

- Sollwerte werden serverseitig auf **5–30 °C** in 0,5-°C-Schritten validiert (400 sonst; NaN/Unendlich über `Double.isFinite` abfangen).
- Hub nicht erreichbar ⇒ 502, nie 401 (der Auth-Interceptor würde sonst die Haushalts-Session beenden).
- Audit: `thermostat.target`, `thermostat.mode`, `thermostat.bulk` – jeweils **nach** erfolgreicher Aktion.

### Sicherheit

- Lesen: KIOSK über die generische `GET /v1/**`-Regel, keine eigene Zeile.
- Schreiben: MEMBER über `anyRequest`; zusätzlich stehen die drei Schreibpfade in der **KIOSK-Whitelist**, weil der Nutzen das Wandtablet ist (Muster Schalter/Blink). Das ist ein reiner UI-Schutz (Bestätigung bei der Sammelaktion), keine serverseitige Sperre – bewusste Entscheidung, in CLAUDE.md festzuhalten.
- `SecurityRulesTest` bekommt beide Richtungen je Pfad.

## Frontend

### Einbindung

- Route `/tablet/heating`, Seite `pages/tablet-heating/`, achter Eintrag in `TABLET_VIEWS` (`shared/tablet-views.ts`; Label „Heizung", Symbol `thermostat`).
- Inhalt steckt in `<app-tablet-shell heading="Heizung">`; Farben nur über lumina-Tokens (hell/dunkel automatisch). Es entsteht **keine** neue hartkodierte Farbe.
- Prüfen: Ansichtsleiste mit acht Einträgen (scrollt bereits seitlich) bleibt bedienbar.

### Layout

- Kopfzeile (`[shellActions]`): Zeitraum 24 h / 7 Tage; Menü „Alle auf …" mit festen Stufen (z. B. 17 °C, 21 °C) und **Bestätigungsdialog**. Der Dialog löst die Ziel-Thermostate beim Bestätigen aus der aktuellen Liste neu auf (Regel `confirmToggle`).
- Raster: 2 Spalten, ab 5 Thermostaten 3. Die Höhe kommt über eine durchgehende Flex-Kette (`flex: 1` und `min-height: 0` an jedem Glied).

### Raumkachel

- Kopf: Raumname; Chips „heizt", „Batterie niedrig", „offline".
- Hauptelement: **Ring-Zifferblatt** – Bogen bis zur Soll-Temperatur, große Zahl = Ist, kleine Zahl = Soll. Warm (orange), solange `heating`, sonst kühl (blau).
- Steuerung: große Touch-Knöpfe −/+ in 0,5-°C-Schritten, **entprellt (~800 ms)**; die Anzeige springt optimistisch um und fällt bei Fehler auf den Serverwert zurück.
- Modus-Segment: Manuell / Frostschutz / Aus.
- Sparkline: Ist-Linie plus Soll-Stufe. Chart-Optionen sind **Felder**, keine Methodenaufrufe im Template (sonst Dauer-Redraw durch `notMerge`).

### Ehrliche Zustände

- Offline oder ohne Wert: „–" und abgedunkelte Kachel mit Grund, nie eine geratene Zahl.
- Nur der Erstabruf meldet Fehler; ein fehlgeschlagener Hintergrund-Refresh (alle 60 s) behält stumm die letzten Werte.
- Zeitraumwechsel bestellt den laufenden Abruf ab (`pendingRequest`).

### Mock-Schicht

`ThermostatService` bezieht seine Daten über eine austauschbare Quelle. Solange das Backend nicht verdrahtet ist, liefert ein `ThermostatMockBackend` fünf Beispielräume, damit das Design vorab im Browser prüfbar ist. Der Mock wird vor dem Merge entfernt oder per Flag abgeschaltet.

## Tests

- Backend: Hub-Client gegen aufgezeichnete Fixtures bzw. Fake-Hub (wie das Fake-KLAP-Gerät), Validierung der Sollwerte, Bulk, Historien-Verdichtung (nur abgeschlossene Buckets), `unavailable` mit erhaltenen Attributen, `SecurityRulesTest`.
- Frontend: Höhenketten-Test (Messung bei 900 und 1200 px, weil die Kachel festen Inhalt trägt), Entprellen/Optimismus mit Rollback, Zustände (offline, Frostschutz, heizt), Modus-Wechsel, Sammelaktion mit Bestätigung und Neuauflösung.

## Rollout und offene Punkte

- Hub-IP von Hand eintragen, im Router fest reservieren.
- Migrationen laufen erstmals nie gegen die echte DB → Backup vor dem Deploy.
- **Offen bis zum Realtest:** exakte Feldnamen von `get_child_device_list`/`control_child` beim KE100, ob der H100 lokal KLAP oder AES spricht, Temperatur-Offset-Semantik, Melde-Intervall der Kindgeräte (bestimmt die sinnvolle Poll-Frequenz).
- Phase 2 (Regelung) baut auf den gespiegelten Entitäten und der Verlaufstabelle auf; dafür ist hier nichts vorzuhalten.
