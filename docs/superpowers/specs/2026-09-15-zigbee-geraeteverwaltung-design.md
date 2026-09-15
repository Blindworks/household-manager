# Zigbee-Geräteverwaltung: Verfügbarkeit je Gerät, Anlernen, Umbenennen, Entfernen

Datum: 2026-09-15

## Anlass

Auf der Zigbee-Seite stehen Geräte, die seit Wochen nichts mehr melden, und sehen aus
wie lebende. Am 2026-09-11 auf PROD: `Motion Büro` und `Motion Treppenhaus` seit
21.08. auf `on`, `Motion Küche` seit 30.08., `Sensor Gas` seit 15.08. — alle übrigen
Zigbee-Entitäten melden täglich. Die Seite zeigt nur `lastSeen` aus unserer eigenen
Tabelle; ob zigbee2mqtt das Gerät noch kennt, ob es als `offline` gilt, ob das
Interview je abgeschlossen wurde, ist nirgends sichtbar. Neue Geräte lassen sich nur
über die zigbee2mqtt-Oberfläche in Home Assistant anlernen.

Ziel: Die Zigbee-Seite wird zur vollständigen Geräteverwaltung — Status je Gerät
und je Entität, Anlernen, Umbenennen, Neu-Interview, Neu-Konfigurieren, Entfernen.
Für nichts davon muss man mehr in die zigbee2mqtt-Oberfläche.

### Was technisch nicht geht

**Ein „Anpingen" von Batterie-Geräten ist in Zigbee physikalisch nicht möglich.**
Schlafende Endgeräte senden nur, wenn sie selbst aufwachen; weder die
zigbee2mqtt-Oberfläche noch Home Assistant haben dafür einen Knopf, und die MQTT-API
kennt keinen Ping-Request. Was es gibt: zigbee2mqtt's eigene Verfügbarkeitsprüfung
(`availability`), die Netz-Geräte alle 10 min anpingt und Batterie-Geräte nach 25 h
Stille als `offline` markiert. Ob sie im Add-on aktiv ist, ist unbekannt — das
Backend liest es aus `bridge/info` und die Seite sagt es.

### Entscheidungen aus dem Brainstorming

| Frage | Entscheidung |
|-------|--------------|
| Verfügbarkeitsprüfung aktiv? | Unbekannt; aus `bridge/info` auslesen, Hinweis auf der Seite |
| Umfang | Volle Geräteverwaltung (Anlernen, Umbenennen, Entfernen, Interview, Konfigurieren) |
| Rollen | Alle schreibenden Aktionen ADMIN; Lesen bleibt KIOSK |
| Wahrheit der Geräteliste | zigbee2mqtt's `bridge/devices`, nicht unsere Tabelle |
| Status je Gerät | z2m-Urteil + eigene Stille-Uhr mit Schwelle je Stromquelle |
| Entfernen | z2m-Entfernen löscht bei uns nichts; eigener zweiter Schritt „Aus Household Manager entfernen" |

## Abschnitt 1: Backend — MQTT-Erweiterung und Geräteverzeichnis

### Neue Topics, die der bestehende Client mitliest

Der Filter `zigbee2mqtt/#` deckt sie schon ab; `ZigbeeMessageParser` verwirft sie
heute nur. Neu verarbeitet werden:

- **`zigbee2mqtt/bridge/devices`** (retained) → `ZigbeeDeviceRegistry` (neu, rein im
  Speicher, Muster `ZigbeeStreamMonitor`). Je Gerät: `ieeeAddress`, `friendlyName`,
  `type` (Router/EndDevice), `powerSource`, `interviewCompleted`, `supported`,
  `definition.model/vendor/description`, `networkAddress`. Der Coordinator
  (`type: Coordinator`) wird herausgefiltert.
- **`zigbee2mqtt/bridge/info`** (retained) → im Registry: `permitJoin`,
  `permitJoinEnd` (Epoch-Millis), `config.availability` (aktiv ja/nein — bei z2m
  entweder `true`, ein Objekt, oder fehlend/`false`), `version`.
- **`zigbee2mqtt/bridge/event`** → `ZigbeeBridgeEvent` (`type`, `friendlyName`,
  `ieeeAddress`, `status` bei `device_interview`, `receivedAt`). Typen:
  `device_joined`, `device_interview` (`started`/`successful`/`failed`),
  `device_announce`, `device_leave`. Ringpuffer der letzten 50 im Registry
  **und** Push über den bestehenden SSE-Kanal `/v1/zigbee/live`.
- **`zigbee2mqtt/bridge/response/<request>`** → Antworten auf eigene Requests
  (Abschnitt 1, „Erstmals Publizieren").

`bridge/state` und `<name>/availability` bleiben, wie sie sind.

### Erstmals Publizieren

Der Client ist heute reiner Subscriber. `ZigbeeMqttConfig` bekommt
`publish(topic, payload)` hinter einem neuen Interface `ZigbeeBridgeCommands`
(Muster `ZigbeeConnectionControl` — der Service kennt den Client nicht).

`ZigbeeBridgeRequestService` kapselt den asynchronen Request/Response-Zyklus von
zigbee2mqtt:

- Jeder Request trägt ein `transaction`-Feld (UUID), das z2m in der Antwort spiegelt.
- Ausstehende Transaktionen liegen in einer `ConcurrentHashMap<String,
  CompletableFuture<BridgeResponse>>`; der MQTT-Handler löst sie bei
  `bridge/response/*` auf.
- **Timeout 10 s** ⇒ `ZigbeeBridgeUnavailableException` → 502 „zigbee2mqtt antwortet
  nicht". Ohne Timeout hinge ein HTTP-Thread ewig, wenn z2m gerade weg ist. Das
  Timeout entfernt die Transaktion aus der Map — sie wächst nicht.
- **Kein Request ohne bestehende Verbindung**: `isConnected()` wird geprüft, sonst
  sofort 502. HiveMQ würde ein Publish sonst puffern und Minuten später nachholen —
  ein verspätet geöffnetes Anlernfenster wäre eine Überraschung.
- z2m antwortet `status: "error"` ⇒ `ZigbeeBridgeRejectedException` mit z2m's
  `error`-Text → 400.

Responses laufen über denselben Handler-Thread wie Messwerte. Bei Haushaltsgröße
unkritisch; die Ein-Thread-Regel bleibt (Reihenfolge je Gerät).

### Neustart

Nach einem Backend-Neustart ist das Registry leer, bis der Broker die retained
Topics nachspielt (Sekunden). `registryLoaded` ist bis dahin `false`; die Seite zeigt
„Geräteverzeichnis wird geladen", nicht „keine Geräte".

## Abschnitt 2: Verfügbarkeit je Gerät und die Lese-API

### `ZigbeeDeviceHealthResolver`

Einzige Definition von „lebt das Gerät" (Muster `TractiveHomeResolver`). Eingaben:

1. z2m-Verfügbarkeitsmeldung — `ZigbeeStreamMonitor.deviceAvailability`, heute
   schon vorhanden
2. Alter der letzten **nicht-retained** Gerätenachricht — `ZigbeeStreamMonitor`
   bekommt eine Map `friendlyName → lastMessageAt` (heute nur global). Retained
   Nachrichten setzen die Geräte-Uhr **nicht** — dieselbe Regel wie beim globalen
   Watchdog; sonst sähe nach jedem Reconnect jedes Gerät frisch aus
3. Fallback nach Neustart: `zigbee_device.lastSeen` aus der DB — sonst stünde nach
   jedem Deploy 25 h lang alles auf „unbekannt"
4. Stromquelle und Interview-Status aus dem Registry

Regeln in dieser Reihenfolge, die erste zutreffende gewinnt:

| # | Bedingung | Status | `basis` |
|---|-----------|--------|---------|
| 1 | z2m meldet `offline` | `OFFLINE` | `zigbee2mqtt` |
| 2 | `interviewCompleted == false` | `INTERVIEW` | `registry` |
| 3 | Nie eine Nachricht gehört (weder Speicher noch DB) | `UNKNOWN` | — |
| 4 | Still länger als Schwelle | `SILENT` | `last-message` |
| 5 | sonst | `ACTIVE` | `last-message` |

Schwelle nach Stromquelle, beide in `application.properties`:

- `zigbee.device-health.battery-silent-after-hours` = **25** (z2m-Default für
  passive Geräte; nicht am eigenen Gerätepark verifiziert — nach einigen Tagen gegen
  die echten Meldeabstände nachziehen)
- `zigbee.device-health.mains-silent-after-minutes` = **15** (= Watchdog
  `stale-after-minutes`; eine Zahl, nicht zwei — der Default referenziert dieselbe
  Property)

Ist die z2m-Verfügbarkeitsprüfung aus, fällt Regel 1 nie an — die Einstufung läuft
über 2–5. Der Resolver wirft nie; fehlende Eingaben ergeben `UNKNOWN`.

Ergebnis: `DeviceHealth(status, basis, lastHeardAt, silentFor)`.

### Endpunkte

Alle unter `/v1/zigbee`, Lesen über die generische `GET /v1/**`-Regel KIOSK:

- **`GET /devices`** — umgebaut. Grundlage ist das Registry; unsere Tabelle liefert
  Ergänzungen. Je Gerät:
  - aus dem Registry: `ieeeAddress`, `friendlyName`, `type`, `powerSource`,
    `interviewCompleted`, `supported`, `model`, `vendor`, `description`
  - aus `zigbee_device`: `lastBatteryPercent`, `lastLinkQuality`, `lastSeen`
  - `health` aus dem Resolver
  - `knownToBridge`: `false` für Geräte, die nur in unserer Tabelle stehen (sie
    bleiben sichtbar, siehe Abschnitt 4)
  - `entities`: `[{entityId, displayName, domain, state, lastChanged, lastUpdated}]`
    — alle `entity_states` mit `source = ZIGBEE` und `sourceRef = friendlyName`
- **`GET /bridge`** — `permitJoin`, `permitJoinEnd`, `availabilityCheckEnabled`,
  `version`, `registryLoaded`
- **`GET /bridge/events`** — die letzten 50 `ZigbeeBridgeEvent`, neueste zuerst
- **`GET /flow-references?friendlyName=`** — Flows, die eine Entität des Geräts
  verwenden (Abschnitt 3)
- **`/live` (SSE)** — pusht zusätzlich `bridge-event` und `bridge-info`
  (bei jeder Änderung aus `bridge/info`, darin auch das Anlernfenster)

`GET /devices/{friendlyName}/measurements` bleibt unverändert.

## Abschnitt 3: Geräteverwaltung — Aktionen, Security, Audit, Flow-Warnung

### Aktionen

Alle ADMIN, alle über `ZigbeeBridgeRequestService` mit 10-s-Timeout:

| Aktion | Endpunkt | z2m-Request |
|--------|----------|-------------|
| Anlernen öffnen | `POST /bridge/permit-join` `{seconds: 1..254}` | `permit_join {time}` |
| Anlernen schließen | `DELETE /bridge/permit-join` | `permit_join {time: 0}` |
| Umbenennen | `PUT /devices/{ieee}/name` `{friendlyName}` | `device/rename {from, to}` |
| Neu-Interview | `POST /devices/{ieee}/interview` | `device/interview {id}` |
| Neu-Konfigurieren | `POST /devices/{ieee}/configure` | `device/configure {id}` |
| Aus z2m entfernen | `DELETE /devices/{ieee}?force=false` | `device/remove {id, force}` |
| Aus HM entfernen | `DELETE /devices/local/{id}` | — (nur DB) |

**Geräte werden über die IEEE-Adresse adressiert**, nicht über den Friendly Name:
der ist genau das, was Umbenennen ändert, und z2m erlaubt `/` im Namen — im Pfad ein
Problem. Der Service löst die IEEE-Adresse gegen das Registry (für z2m-Requests) bzw.
gegen `zigbee_device.ieeeAddress` (für den lokalen Pfad) auf; unbekannt ⇒ 404.
Ausnahme: „Aus Household Manager entfernen" adressiert über die DB-Id, weil
Bestandszeilen keine IEEE-Adresse tragen.

`friendlyName` beim Umbenennen: nicht leer, max. 64 Zeichen, kein `/`, kein `+`,
kein `#` (MQTT-Wildcards) — 400 bei Verstoß, bevor irgendetwas gesendet wird.

### Entfernen mit `force`

Ein normales `remove` schickt dem Gerät einen Leave-Befehl und braucht es wach. Bei
einem toten Batterie-Sensor — genau der Fall, um den es geht — antwortet z2m mit
Fehler. Der Dialog bietet dann **„Erzwingen"** an (`force: true` löscht nur den
Eintrag in z2m). Erst als zweite Stufe, nie als Default: `force` auf ein lebendes
Gerät hinterlässt ein Gerät, das weiter im Netz funkt und beim nächsten Anlernen
wieder auftaucht.

### Umbenennen migriert keine Entitäten

Die Entity-IDs entstehen aus dem Friendly Name (`EntityIds.build`). Nach dem
Umbenennen legt die nächste Nachricht neue Entitäten an; die alten bleiben stehen,
bis „Aus Household Manager entfernen" sie aufräumt (dazu bleibt die alte
`zigbee_device`-Zeile als `knownToBridge: false` sichtbar). Bewusst so: eine
automatische Umbenennung von Entity-States, Tile-Visibility, Power-History und
Flow-Definitionen wäre ein eigenes Projekt mit eigenen Fallen. Stattdessen warnt der
Dialog.

### Flow-Warnung

`GET /flow-references?friendlyName=` durchsucht `deployedDefinition` und
`draftDefinition` aller Flows nach den Entity-IDs des Geräts und liefert
`[{flowId, name, enabled}]`. **String-Suche im JSON**, bewusst: Node-Configs sind
eine freie Map, eine strukturierte Suche hinkte bei jedem neuen Node-Typ nach. Ein
falsch-positiver Treffer (`…_temperature` trifft auch `…_temperature_2`) ist bei
einer Warnung akzeptabel, ein falsch-negativer nicht. Rename- und Remove-Dialoge
holen das **vor** dem Bestätigen; es warnt, blockiert nicht.

### „Aus Household Manager entfernen"

Löscht in einer Transaktion (`ZigbeeDevicePurgeService`):

1. `zigbee_measurement` des Geräts (hängt per FK am Gerät)
2. `zigbee_device`
3. alle `entity_states` mit `source = ZIGBEE` und `sourceRef = friendlyName`
4. deren `entity_tile_visibility`-Zeilen — sonst bleibt die verwaiste Zeile stehen
   und greift beim nächsten gleichnamigen Gerät wieder (die Falle aus dem
   Helfer-Kapitel)

**Nur erlaubt, wenn das Gerät nicht im z2m-Registry steht** (409 sonst) — die
nächste Nachricht legte sonst alles wieder an und der Knopf wäre eine Lüge. Die
Antwort nennt die Zahl gelöschter Messwerte und Entitäten.

### Security

Ein pauschaler Matcher auf `/v1/zigbee/devices/*/**` reicht nicht —
`GET /devices/{friendlyName}/measurements` liegt darunter und muss KIOSK bleiben.
Also **methodenspezifische** ADMIN-Matcher (Muster Kalender-Kategorien), **vor** der
generischen `GET /v1/**`-Regel:

- `POST` und `DELETE /v1/zigbee/bridge/permit-join`
- `PUT /v1/zigbee/devices/*/name`
- `POST /v1/zigbee/devices/*/interview`, `POST /v1/zigbee/devices/*/configure`
- `DELETE /v1/zigbee/devices/*`
- `DELETE /v1/zigbee/devices/local/*` (ADMIN)
- `GET /v1/zigbee/flow-references` (ADMIN)

`SecurityRulesTest`: je Zeile ein MEMBER-403-Test, dazu KIOSK-200 für
`GET /devices`, `GET /bridge`, `GET /bridge/events` und `GET /devices/*/measurements`.

### Audit

`zigbee.permit-join.open` (Sekunden) / `.close`, `zigbee.device.rename` (alt → neu),
`.interview`, `.configure`, `.remove` (mit `force`), `.purge` (Zahl der Messwerte
und Entitäten) — jeweils **nach** erfolgreicher z2m-Antwort bzw. Transaktion (Muster
`NukiLockService`); ein fehlgeschlagener Versuch erzeugt keinen Eintrag.

## Abschnitt 4: Frontend — die Zigbee-Seite

Die bestehende Seite `pages/zigbee/` wird umgebaut, keine neue Route. Die Seite ist
keine Dashboard-Kind-Komponente; die `lumina`-Kapselung spielt hier keine Rolle,
Dialoge bekommen eigene Markup und Styles in der Komponente.

### Kopfbereich — Bridge-Status

Neben dem bestehenden Störungsbanner eine Statuszeile: z2m-Version, Registry-Zustand.
Bei `availabilityCheckEnabled === false` ein gelber Hinweis: „Die
Verfügbarkeitsprüfung ist in zigbee2mqtt nicht aktiviert. Der Status je Gerät beruht
nur auf der letzten empfangenen Nachricht. (Einstellungen → Verfügbarkeit in der
zigbee2mqtt-Oberfläche)".

Rechts für ADMIN **„Anlernen (4 min)"** (`seconds: 240`). Während des Fensters wird
er zum Countdown mit „Beenden". Der Countdown rechnet aus `permitJoinEnd` vom Server,
nicht aus einem lokalen Timer — ein per z2m-Oberfläche geöffnetes Fenster wird so
ebenfalls angezeigt.

### Ereignisliste

Die letzten Bridge-Ereignisse live per SSE, initial aus `GET /bridge/events`.
Ausgeklappt während eines Anlernfensters und 2 min danach, sonst eingeklappt.
Texte: „0x00124b… beigetreten", „Interview läuft…", „Interview erfolgreich: SNZB-03
(SONOFF)", „Interview fehlgeschlagen", „Gerät hat das Netz verlassen". Ein frisch
beigetretenes Gerät bekommt in der Zeile einen **„Benennen"**-Link, der den
Umbenennen-Dialog öffnet — sonst bleibt es unter seiner IEEE-Adresse stehen.

### Gerätekarten

Sortierung: erst kranke (`OFFLINE`, `SILENT`, `UNKNOWN`, `INTERVIEW`), dann `ACTIVE`,
innerhalb alphabetisch nach Friendly Name. Je Karte:

- Status-Badge oben rechts: `ACTIVE` grün, `SILENT` gelb, `OFFLINE` rot,
  `UNKNOWN`/`INTERVIEW` grau; Text „zuletzt gehört vor 21 Tagen" / „Interview läuft".
  Das Badge-Mapping hat **kein `default`** — ein sechster Status wird zum
  Compilerfehler (Muster `presenceRingClass`)
- Modell, Hersteller, Stromquelle-Symbol (Batterie/Stecker), Batterie %, LQ
- **Entitätenliste** statt der heutigen Messwert-Liste: je Entität Anzeigename,
  Zustand, `lastUpdated` als relative Zeit; ist `lastUpdated` älter als die Schwelle
  des Geräts, wird die Zeile gedämpft
- `knownToBridge: false`: Karte gedämpft, Badge „in zigbee2mqtt nicht bekannt",
  einziger Knopf „Aus Household Manager entfernen"
- ADMIN-Aktionen als Menü (⋯): Umbenennen, Neu-Interview, Neu-Konfigurieren, Aus
  zigbee2mqtt entfernen. Sichtbar nur bei `authService.isAdmin()`; der Server prüft
  ohnehin

Live-Events (Messwerte und Bridge-Ereignisse) fließen weiter in die Entitätenliste
ein und lösen den in „Aktualisierung" unten beschriebenen gedrosselten Reload aus;
`lastSeen` wird wie heute bei jedem Live-Event hochgezogen.

### Dialoge

- **Umbenennen**: Feld mit neuem Namen; darunter die Flow-Warnung (beim Öffnen
  geladen; schlägt der Abruf fehl, steht „Flow-Verwendung konnte nicht geprüft
  werden" — kein stilles Grün) und der Hinweis „Entitäten bekommen neue IDs; Flows
  und Dashboard-Kacheln müssen nachgezogen werden"
- **Aus zigbee2mqtt entfernen**: Flow-Warnung, roter Knopf. Schlägt das normale
  Entfernen fehl, zeigt derselbe Dialog die z2m-Fehlermeldung und bietet
  **„Erzwingen"** als zweiten roten Knopf an. Hinweis bei Batterie-Geräten: „Gerät
  wecken (z. B. Reset-Taste kurz drücken), sonst schlägt das Entfernen fehl"
- **Neu-Interview / Neu-Konfigurieren**: Bestätigung mit demselben Weck-Hinweis bei
  Batterie-Geräten
- **Aus Household Manager entfernen**: nennt die Zahl der Entitäten, roter Knopf.
  Die Zahl der Messwerte ist vorab nicht bekannt — sie steht erst nach dem Löschen
  fest und kommt in der Antwort des Endpunkts zurück

Jeder Dialog löst sein Gerät beim Bestätigen aus der **aktuellen** Liste neu auf
(Regel aus `confirmToggle`); ist es verschwunden, passiert nichts und der Dialog
schließt mit Hinweis.

### Aktualisierung

Die Komponente lädt Geräteliste und Health alle 30 s per eigenem Timer neu; zusätzlich
lösen Live-Events (Messwerte und Bridge-Ereignisse) einen gedrosselten Reload aus —
höchstens einmal je 5 s, garantiert binnen 5 s nach dem ersten Event. So bleibt die
Health-Einstufung auch bei stiller Anbindung aktuell (der Timer) und folgt einer
Änderung trotzdem zeitnah (der Live-Reload). Bridge-Ereignisse und Permit-Join-Wechsel
weiterhin sofort per SSE. Ein fehlgeschlagener
Reload behält den letzten Stand; nur der Erstabruf meldet einen Fehler. Der
Countdown-Intervall läuft nur, solange ein Anlernfenster offen ist.

Das Verlauf-Diagramm unten bleibt unverändert.

## Abschnitt 5: Fehlerbehandlung, Tests, bewusste Grenzen

### Fehlerbehandlung

- z2m `status: "error"` ⇒ 400 mit z2m's `error`-Text. Timeout/keine Verbindung
  ⇒ 502. **Nie 401** — der Auth-Interceptor würde den Nutzer aus der
  Haushalts-Session werfen (Tractive-Falle).
- Ein unparsebares `bridge/devices` oder `bridge/info` (künftige z2m-Version) wird
  geloggt, das **alte** Registry bleibt stehen — ein Format-Bruch darf die Seite
  nicht leeren. Unbekannte Felder werden ignoriert.
- Der MQTT-Handler bleibt in `try/catch`; ein Fehler in der Bridge-Verarbeitung darf
  keinen Messwert verschlucken.
- Verwaiste Transaktionen räumt das Timeout ab.
- 404 für unbekannte IEEE-Adresse, 409 für Purge eines noch bekannten Geräts.

### Tests

- `ZigbeeDeviceHealthResolverTest`: jede Regel einzeln; Reihenfolge (`offline`
  schlägt „frisch gehört"); Schwelle je Stromquelle; Neustart-Fall mit DB-`lastSeen`;
  retained setzt die Geräte-Uhr nicht; fehlende Eingaben ⇒ `UNKNOWN`, nie Exception
- `ZigbeeBridgeRequestServiceTest`: Antwort trifft Transaktion; Timeout ⇒ Exception
  und Map leer; `status: error` ⇒ Fehlertext; kein Publish ohne Verbindung;
  Transaktions-ID wird mitgesendet
- `ZigbeeMessageParserTest` erweitert: `bridge/devices`, `bridge/info` (mit
  `availability: true`, als Objekt, fehlend), `bridge/event` alle vier Typen,
  `bridge/response` — mit Payload-Beispielen aus der z2m-Doku; Coordinator gefiltert;
  unbekannte Felder ignoriert; kaputtes JSON lässt das alte Registry stehen
- `ZigbeeDevicePurgeServiceTest`: löscht Messwerte, Gerät, Entitäten,
  Tile-Visibility; 409 bei Gerät im Registry
- Flow-Referenzsuche: findet in Draft und Deployed; Test hält den bewusst
  falsch-positiven Teilstring-Treffer fest
- `SecurityRulesTest`: je ADMIN-Matcher MEMBER-403; KIOSK-200 für alle vier
  Lese-Endpunkte
- Frontend (`zigbee.component.spec.ts`): Sortierung kranke-vor-aktive; ADMIN-Menü
  fehlt für MEMBER; Countdown aus `permitJoinEnd`; Dialog löst Gerät neu auf und
  tut nichts, wenn es fehlt; Flow-Warnung bei Abruffehler nicht grün; Hinweis bei
  `availabilityCheckEnabled === false`

### Bewusste Grenzen (v1)

- **Kein Ping von Batterie-Geräten** — physikalisch unmöglich. „Neu-Interview" auf
  einem schlafenden Gerät wartet in z2m, bis es aufwacht, und läuft in unser
  10-s-Timeout; der Dialog sagt das vorher.
- **Umbenennen migriert keine Entitäten** (Abschnitt 3).
- **Kein Flow-Trigger auf Geräte-Gesundheit.** Wer `SILENT` per Telegram will,
  bekommt später eine `event.zigbee_device_health`-Entität; der Resolver ist dafür
  schon die eine Stelle.
- **z2m-Optionen je Gerät** (Retain, Debounce, Occupancy-Timeout) bleiben in der
  z2m-Oberfläche.
- **Schwelle 25 h ist z2m's Default**, nicht am eigenen Gerätepark verifiziert —
  nach einigen Tagen Betrieb nachziehen (`application.properties`, Redeploy).
- **Registry und Ereignisse leben im Speicher**: nach einem Backend-Neustart ist die
  Ereignisliste leer, das Registry füllt sich aus den retained Topics.
- **Keine Schema-Änderung** — es gibt kein Liquibase-Changeset in diesem Vorhaben.

## Rollout

1. Deploy. Die Seite zeigt sofort das z2m-Registry; die vier hängenden Geräte
   erscheinen als `SILENT` (oder `OFFLINE`, falls die Prüfung aktiv ist).
2. Zeigt die Seite den Hinweis „Verfügbarkeitsprüfung nicht aktiviert": in der
   z2m-Oberfläche einschalten — ab dann kommt zusätzlich z2m's Urteil.
3. Tote Geräte: Batterie prüfen, ggf. neu anlernen (gleicher Friendly Name behält
   Historie und Entitäten) oder entfernen (z2m, dann HM).
4. Nach einigen Tagen die 25-h-Schwelle gegen die echten Meldeabstände prüfen.
