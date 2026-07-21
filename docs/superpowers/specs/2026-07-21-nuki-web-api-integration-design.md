# Design: Nuki-Smart-Lock-Integration über die Nuki Web API

**Datum:** 2026-07-21
**Status:** Entwurf genehmigt

## Ziel

Das Nuki Smart Lock Pro (4. Generation) in den Household-Manager integrieren:

1. **Status anzeigen** – Schlosszustand, Türsensor und Batterie als Entitäten im System und als Dashboard-Kachel.
2. **Sperren/Entsperren** – Verriegeln, Entsperren und Tür öffnen (Falle ziehen) aus dem UI.
3. **Flow-Anbindung** – Zustandsänderungen als Flow-Trigger, Schloss-Aktionen als Flow-Aktion.

Keine historische Datenspeicherung (bewusst außerhalb des Umfangs).

## Entscheidung: Web API statt lokalem MQTT

Gewählt wurde die **Nuki Web API** (`https://api.nuki.io`, Cloud) mit **Polling**.
Alternativen (natives MQTT des Pro-Modells über den vorhandenen Mosquitto, Bridge-HTTP-API)
wurden verworfen; die Entscheidung fiel bewusst auf die Web API.

- Zustandsabruf per Polling alle 30 s (konfigurierbar). Webhooks (Advanced API) sind ein
  möglicher späterer Ausbau, falls die Trigger-Latenz stört; sie erfordern öffentliche
  Erreichbarkeit des Backends.
- Auth über einen persönlichen API-Token von https://web.nuki.io
  (Berechtigungen: Smartlock lesen + bedienen), hinterlegt als Umgebungsvariable
  `NUKI_API_TOKEN`. Der Token wird nie geloggt.

## Backend: neues Package `nuki/`

Aufbau analog zu `shelly/` und `tapo/`:

| Klasse | Verantwortung |
|---|---|
| `NukiProperties` | `nuki.enabled`, `nuki.api-token` (env `NUKI_API_TOKEN`), `nuki.base-url` (Default `https://api.nuki.io`), `nuki.poll-interval` (Default 30 s) |
| `NukiApiClient` | Dünner HTTP-Client: `GET /smartlock` (Liste inkl. Zustand), `POST /smartlock/{smartlockId}/action` (1 = entsperren, 2 = verriegeln, 3 = Tür öffnen). `Bearer`-Auth. |
| `NukiPollingService` | `@Scheduled`-Polling; meldet Zustände an den Entity-State-Layer. Cloud-Fehler werden geloggt, nicht eskaliert. |
| `NukiLockService` | Geschäftslogik: Aktionen ausführen, danach sofortiges Nachpollen, damit UI/Entitäten nicht bis zu 30 s hinterherhängen. |
| `NukiController` | `GET /v1/nuki/locks`, `POST /v1/nuki/locks/{smartlockId}/actions` (Body: `{ "action": "LOCK" \| "UNLOCK" \| "UNLATCH" }`) |
| `NukiException` | Fachliche Fehler (Cloud nicht erreichbar, Aktion abgelehnt) mit sauberer Meldung ans Frontend. |

Regeln:

- Geräteidentität ausschließlich über die stabile `smartlockId` (Lehre aus der
  Kasa-Integration: nie über veränderliche Merkmale wie IP).
- DTOs mit `@JsonIgnoreProperties(ignoreUnknown = true)` – die Cloud-API darf Felder
  hinzufügen, ohne uns zu brechen.
- Kein Liquibase-Changeset nötig: keine Persistenz, nur Live-Zustand im Entity-State-Layer.

## Entity-State-Layer

- Neue Domain **`LOCK`** in `EntityDomain`, neue Source **`NUKI`** in `EntitySource`.
- `lock.nuki_<smartlockId>`:
  - State: `locked` / `unlocked` / `unlatched` / `jammed` / `uncalibrated`
    (gemappt aus den numerischen Nuki-States; unbekannte Werte → `unknown`).
  - Attribute: Name, Batteriestand (%), `batteryCritical`.
- Türsensor (falls vorhanden): `binary_sensor.nuki_<smartlockId>_door` mit der
  bestehenden on=offen-Semantik.
- Poll-Fehler / Cloud down → Entitäten werden `unavailable`.
- Mapping in `NukiEntityMapper` nach dem etablierten Hook-Muster:
  try/catch um das komplette Mapping, damit ein Mapping-Fehler nie das Polling stoppt.

## Flow-Engine

- **Trigger:** kommt ohne neuen Code – `EntityStateTriggerHandler` reagiert auf die
  neuen `lock.*`-Entitäten (z. B. „wenn `unlocked` → Alexa-Ansage").
- **Aktion:** neuer `NukiLockActionNodeHandler` (NodeHandler-Bean analog
  `SwitchDeviceNodeHandler`) mit Feldern `smartlockId` und `action`
  (`lock` / `unlock` / `unlatch`). Erscheint automatisch im
  `flow_node_types`-Katalog des Flow-MCP-Servers.

## Frontend

- **`NukiService`** (Angular) für die beiden Endpoints; Modelle unter `models/`.
- **Dashboard-Kachel „Türschloss“:**
  - Zustand als Icon + Text, Batteriewarnung bei `batteryCritical`.
  - **Verriegeln:** ein Tap, ohne Rückfrage.
  - **Entsperren / Tür öffnen:** Bestätigungsdialog (Wandtablet-Fehlbedienungsschutz).
  - Cloud nicht erreichbar → Kachel zeigt „nicht erreichbar“, Buttons deaktiviert.
- Styling: lumina-Klassen leben ausschließlich in `dashboard.component.scss`;
  die Kachel bekommt ihre Styles dort bzw. eigene gekapselte Styles.
- Aktualisierung im selben Rhythmus wie die übrigen Dashboard-Daten.

## Fehlerbehandlung

- Aktionen schlagen sichtbar fehl (Fehlermeldung im UI) – bei einem Türschloss darf
  es keinen stillen Fehlschlag geben.
- Polling-Fehler: Log-Warnung + Entitäten `unavailable`; kein Crash, kein Retry-Sturm
  (nächster regulärer Poll genügt).

## Tests

- `NukiEntityMapper`: Mapping aller Lock-States, Türsensor, `unavailable`-Fall.
- `NukiLockActionNodeHandler`: Aktion wird mit korrekten Parametern ausgelöst,
  Fehlerpfad bricht den Flow sauber ab.
- `NukiApiClient`: gegen gemockte HTTP-Responses (inkl. unbekannter JSON-Felder).

## Einmalige Voraussetzung (Benutzerseite)

Auf https://web.nuki.io einen API-Token erzeugen (Smartlock lesen + bedienen) und
als `NUKI_API_TOKEN` in der Umgebung (lokal / docker-compose) hinterlegen.
