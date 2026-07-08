# Design: Generische Entity-/State-Schicht (Grundlage für Automatisierungen)

**Datum:** 2026-07-08
**Status:** Entwurf zur Review

## Ziel

Der Household-Manager soll ein generisches, Home-Assistant-inspiriertes Entitätsmodell bekommen: Alle Geräte und Messwerte der bestehenden Integrationen werden als **Entitäten mit abfragbarem Zustand** abgebildet. Zustandsänderungen feuern Events. Das ist die Grundlage für eine spätere IFTTT-artige Regel-Engine (Trigger → Bedingungen → Aktionen, z. B. Alexa-Ansagen).

Das Gesamtvorhaben ist in Ausbaustufen zerlegt; **dieses Design umfasst nur Stufe 1 + 2**:

1. **Entity-/State-Schicht**: generische Entitäten mit Zustand und Attributen, gespeist aus allen bestehenden Integrationen
2. **Event-System**: Zustandsänderungen publizieren `EntityStateChangedEvent` (Spring-Events)
3. *(später, eigenes Design)* Regel-Engine: Trigger, If/Else-Bedingungen, Aktionen
4. *(später)* Regel-Editor im Frontend

## Gewählter Ansatz

**Spiegel-Schicht (nicht-invasiv):** Die bestehenden Integrationen und ihre Datenmodelle bleiben unverändert führend. Eine neue Entity-Schicht spiegelt deren Zustände; die Integrationen melden Updates über eine zentrale Facade (`EntityStateService.reportState(...)`), die Facade erkennt Änderungen, persistiert und publiziert Events.

Verworfene Alternativen:

- **Große Migration** auf ein führendes generisches Entity-Modell: eine einzige Wahrheit, aber riskanter Umbau aller Integrationen und des Frontends.
- **Integrationen publizieren rohe Spring-Events**, Entity-Schicht übersetzt per Listener: maximale Entkopplung, aber pro Integration ein Event-Typ plus Übersetzer-Listener — doppelte Strukturen ohne praktischen Gewinn.
- **Entity-Schicht pollt selbst**: dupliziert vorhandenes Polling, verzögert Zigbee-Push künstlich.

## Datenmodell (Liquibase-Changeset)

**Tabelle `entity_states`** — eine Zeile pro Entität, hält nur den **aktuellen** Zustand (Upsert). Historie bleibt in den bestehenden Fachtabellen (Tasmota-/Airrohr-/Shelly-Readings usw.); es gibt bewusst keine eigene State-Historie.

| Spalte | Bedeutung |
|---|---|
| `entity_id` | eindeutig, stabil, z. B. `sensor.zigbee_0x00158d_temperature` |
| `domain` | `SWITCH`, `SENSOR`, `BINARY_SENSOR` |
| `friendly_name` | Anzeigename, wird bei jedem Update mitaktualisiert |
| `source` | `KASA`, `TAPO`, `MEROSS`, `ZIGBEE`, `SHELLY`, `TASMOTA`, `AIRROHR`, `WEATHER`, `ANKER_SOLIX` |
| `source_ref` | stabile ID im Quellsystem (deviceId, Seriennummer, Sensor-ID) |
| `state` | aktueller Zustand als String (`"on"`, `"21.5"`, `"unavailable"`, `"unknown"`) |
| `attributes` | JSON (z. B. `unit`, `deviceClass`, Zusatzwerte) |
| `last_changed` | Zeitpunkt der letzten **Wertänderung** |
| `last_updated` | Zeitpunkt des letzten Updates (auch ohne Wertänderung) |

JPA-Entity `EntityState` in `model/entity/`, Repository in `com.household.manager.repository` (JpaConfig-Einschränkung).

### Konventionen

- **Granularität wie Home Assistant: eine Entität pro Wert.** Ein Zigbee-Sensor mit Temperatur + Luftfeuchtigkeit ergibt zwei Sensor-Entitäten; eine Tapo-Steckdose mit Energiemessung ergibt `switch.tapo_x` und `sensor.tapo_x_power`.
- **Entity-ID-Schema:** `<domain>.<source>_<stabile-ref>[_<messgröße>]` — maschinell generiert, nie aus dem änderbaren Anzeigenamen abgeleitet. Beispiele: `switch.kasa_8006a1b2`, `sensor.airrohr_esp123_pm25`, `sensor.weather_dwd_temperature`.
- **Domains:** `switch` (schaltbar), `sensor` (Messwert), `binary_sensor` (an/aus, nicht schaltbar). Erweiterbar.
- **Sonderzustände:** `unavailable` (Quelle offline/nicht erreichbar), `unknown` (noch kein Wert gemeldet).
- **Werte stringly-typed:** numerische Werte als String im `state`, Einheit in den Attributen. Die spätere Regel-Engine parst bei Bedarf.

## Architektur (Backend)

Neues Package `com.household.manager.entitystate`:

| Baustein | Verantwortung |
|---|---|
| `EntityStateService` | Facade und einzige Schreibstelle: `reportState(EntityStateUpdate)` mit Upsert-Semantik (unbekannte Entity-ID → automatische Registrierung). Change-Detection an genau einer Stelle. Query-Methoden `getAll()`, `getByEntityId()`, `getByDomain()`, `getBySource()`. |
| `EntityStateChangedEvent` | Spring-Event `(entityId, oldState, newState, attributes, timestamp)`; publiziert über `ApplicationEventPublisher` **nur bei Wertänderung**. Grundstein für die spätere Regel-Engine (`@EventListener`). In Stufe 1 einziger Konsument: ein Debug-Logger. |
| Mapper pro Integration | z. B. `SmartDeviceEntityMapper`, `ZigbeeEntityMapper`, …: übersetzen ein Fachobjekt in einen oder mehrere `EntityStateUpdate`s, kapseln Entity-ID-Bildung und Attribut-Belegung. |
| `EntityStateController` | REST-Endpunkte (siehe unten) |

**Fehlerisolierung:** `reportState` fängt intern alle Fehler und loggt sie — ein Fehler in der Spiegel-Schicht darf niemals die aufrufende Integration (Polling, MQTT-Handler, Schaltbefehl) brechen.

**Change-Detection:** alter Zustand ≠ neuer Zustand → `last_changed` setzen + Event publizieren; unverändert → nur `last_updated` aktualisieren, kein Event.

### Hook-Punkte in den bestehenden Services

Jeweils nur ein Aufruf nach vorhandener Logik, keine Umbauten:

| Integration | Hook | entstehende Entitäten |
|---|---|---|
| SmartDevice (Kasa/Tapo/Meross) | nach `refreshDeviceState`, `turnOn`/`turnOff`, `scanAndPersistDevices` | `switch.*`; offline → `unavailable` |
| Zigbee | im MQTT-Message-Handler nach dem Parsen | `sensor.*` / `binary_sensor.*` je `MeasurementType` |
| Tasmota | nach Polling-Durchlauf | `sensor.*` (Leistung, Verbrauch) |
| Shelly | nach Polling-Durchlauf | `sensor.*` |
| Airrohr | nach Polling-Durchlauf | `sensor.*` (PM2.5, PM10, Temperatur, Feuchte) |
| Wetter (DWD) | nach Polling-Durchlauf | `sensor.weather_dwd_*` |
| AnkerSolix | nach Live-Datenabruf | `sensor.*` (Solarleistung etc.) |

Erweiterbarkeit: neue Integration = ein Mapper + ein Hook; Persistenz, Events, REST-API und Frontend funktionieren automatisch.

## REST-API

```
GET    /api/entities                 → alle Entitäten; optional ?domain=sensor&source=ZIGBEE
GET    /api/entities/{entityId}      → Einzelabfrage
DELETE /api/entities/{entityId}      → manuelles Aufräumen verwaister Entitäten
```

## Frontend

Neue Seite `pages/entities/` (standalone, separate HTML/SCSS):

- Tabelle aller Entitäten: Anzeigename, Entity-ID, Domain, Quelle, Zustand (mit Einheit aus den Attributen), `last_changed` als Relativzeit
- Filter über Domain und Quelle (Dropdowns), Freitextsuche über Name/Entity-ID
- Zustands-Badges: `unavailable` ausgegraut, `on`/`off` farblich unterschieden, Messwerte neutral
- Aufklappbare Zeile zeigt Attribute als Key-Value-Liste (Debug-Nutzen)
- Aktualisierung per Polling (~10 s) über neuen Angular-Service; kein WebSocket in dieser Stufe

## Randfälle

- **Quelle verschwindet dauerhaft** (z. B. Zigbee-Sensor entfernt): Entität bleibt mit letztem Zustand bestehen; Aufräumen manuell per `DELETE`. Keine automatische Löschung — die Quelle könnte nur vorübergehend weg sein.
- **Umbenennung im Quellsystem:** `friendly_name` wird mitaktualisiert; `entity_id` bleibt stabil (darauf verlassen sich später die Regeln).
- **Neustart:** Zustände kommen aus der DB und sind sofort da — ggf. veraltet bis zum ersten Polling-Durchlauf, erkennbar an `last_updated`.
- **Gleichzeitige Updates** (MQTT-Push + Polling): Upsert über eindeutige `entity_id` mit `@Transactional`; bei Einzelhaushalts-Lastprofil ausreichend, kein zusätzliches Locking.

## Tests

- `EntityStateServiceTest`: Upsert-Verhalten, Change-Detection (Event nur bei Wertänderung, `last_changed` vs. `last_updated`), Fehler in `reportState` erreicht den Aufrufer nicht
- Mapper-Tests pro Integration: korrekte Entity-IDs, Domain-Zuordnung, `unavailable` bei offline, Attribut-Belegung
- Controller-Test für Filter-Query-Parameter
