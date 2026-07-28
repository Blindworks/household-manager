# Zigbee-Ausfallerkennung und -härtung

**Datum:** 2026-07-28
**Status:** Entwurf, abgestimmt

## Anlass

Am 2026-07-28 um 14:37 standen alle 28 Zigbee-Entitäten in PROD auf dem Stand von
**2026-07-27 16:24:41** — über 22 Stunden ohne eine einzige Nachricht. Das Backend lief
dabei nachweislich (Meross meldete 14:37:29). Es gab **keine** Meldung, **keinen**
Log-Eintrag und **kein** `unavailable`: der Ausfall war ausschließlich daran erkennbar,
dass jemand die `lastChanged`-Zeitstempel von Hand verglich.

Vier der fünf aktiven Flows hängen an Zigbee-Sensoren und liefen in dieser Zeit ohne
Fehlermeldung ins Leere:

| Flow | Abhängigkeit |
|---|---|
| #2 Tür offen bei Abwesenheit | Türkontakte |
| #3 Badfenster offen bei Regen | Fensterkontakt |
| #4 Feuer-Verdacht wenn Toni allein | Temperatursensoren |
| #5 Badfenster nach dem Duschen schließen | Luftfeuchte |

#2 und #4 sind sicherheitsrelevant. Ein lautloser Dauerausfall genau dieser Sensorik ist
die Lücke, die dieser Entwurf schließt.

## Ursachenkandidaten im Bestandscode

Welcher davon diesmal zugeschlagen hat, ist ohne PROD-Logs nicht entscheidbar. Alle drei
sind real und werden unabhängig von der Diagnose behoben.

1. **Fehlgeschlagenes Subscribe wird nie wiederholt** —
   `ZigbeeMqttConfig.subscribe()` loggt einen Fehlschlag als Warnung und versucht es nie
   erneut. Nach einem Reconnect kann der Client damit dauerhaft verbunden bleiben, ohne je
   wieder ein Topic zu abonnieren. Lautloser Dauertod, passt exakt zum Symptom.
2. **Nachrichtenverarbeitung blockiert den Netty-Event-Loop** — `.callback(this::handle)`
   läuft ohne eigenen Executor, führt aber pro Nachricht eine synchrone DB-Transaktion aus.
   Hängt die Datenbank, steht der gesamte MQTT-Client still, inklusive Keepalive und
   Reconnect-Logik.
3. **Fehler sind unsichtbar** — Verarbeitungsfehler werden auf `debug` geloggt, ohne
   Stacktrace. In PROD sieht man davon nichts.

Dazu zwei strukturelle Lücken: es existiert kein Staleness-Watchdog (`unavailable` kommt im
gesamten Zigbee-Paket nicht vor), und die von zigbee2mqtt ohnehin publizierten Topics
`zigbee2mqtt/bridge/state` sowie `<gerät>/availability` werden vom Parser verworfen
(`isDeviceTopic` lehnt jedes Topic mit `/` ab) — verschenktes Ausfallsignal.

## Ziel

Ein Ausfall dieser Art wird binnen Minuten sichtbar, aktiv gemeldet und nach Möglichkeit
selbst geheilt — und erzeugt beim Wiederanlaufen keinen Fehlalarm.

## Gewählter Ansatz

Zeit-Watchdog **plus** Auswertung der zigbee2mqtt-Signale.

Der Zeit-Watchdog ist das tragende Element, weil er als einziger den *eigenen* Ausfall
erkennt: ist unsere Subscription oder der Broker weg, kommt auch keine Availability-Nachricht
mehr an — ein reines Signalverfahren wäre in genau diesem Fall blind. Die zigbee2mqtt-Signale
kommen als präzise Ergänzung dazu: sie unterscheiden, *wer* weg ist (steht zigbee2mqtt selbst,
oder ist nur eine Batterie leer) und liefern diese Unterscheidung direkt in den Meldungstext.

Verworfen wurde ein generischer Heartbeat-Watchdog im Entity-State-Layer: Tractive, Blink und
Tablet lösen das bereits jeweils selbst, Zigbee ist die letzte Lücke. Der Umbau stünde in
keinem Verhältnis zum Nutzen.

Der Watchdog ist bewusst **global** und nicht pro Gerät: ein Türkontakt schweigt legitim
tagelang, aber dass alle sieben Temperatursensoren gleichzeitig 15 Minuten still sind, ist
definitiv ein Ausfall. Einzelgeräte-Ausfälle deckt stattdessen die `availability` von
zigbee2mqtt ab, die dafür das verlässlichere Signal ist als eine geratene Schwelle.

## Komponenten

### 1. `ZigbeeStreamMonitor`

Neu in `zigbee/service/`. Rein im Speicher, kein DB-Zugriff.

Hält fest:
- `lastMessageAt` — wann kam zuletzt *irgendeine* Gerätenachricht
- `bridgeState` + `lastBridgeStateAt` — aus `zigbee2mqtt/bridge/state`
- pro Gerät: letzter Kontakt und zuletzt gemeldete `availability`

`status()` liefert daraus ein Urteil: `OK`, `STILL` (mit Stille-Dauer) oder `BRIDGE_OFFLINE`,
plus die Liste der als offline gemeldeten Geräte.

Diese Klasse ist die **einzige** Definition von „die Zigbee-Anbindung lebt" — Watchdog,
Health-Endpunkt, Dashboard-Kachel und Meldungstext fragen alle dieselbe Klasse, nach dem
Vorbild von `TractiveHomeResolver`. So können sie nicht auseinanderlaufen.

Der Zustand überlebt einen Neustart bewusst **nicht**: die Uhr startet bei jedem Deploy neu.
Andernfalls löste jeder Neustart sofort einen Fehlalarm aus.

### 2. `ZigbeeAvailabilityWatchdog`

`@Scheduled`, jede Minute. Automat mit drei Zuständen, damit ein kurzer Aussetzer nicht
nachts das Handy weckt:

| Übergang | Auslöser | Reaktion |
|---|---|---|
| `HEALTHY` → `RECOVERING` | Stille ≥ `stale-after-minutes` | Verbindung trennen und neu aufbauen inkl. Resubscribe. Keine Meldung, kein `unavailable`. |
| `RECOVERING` → `HEALTHY` | Nachricht binnen `recover-grace-minutes` | Info-Log, sonst nichts. Selbst geheilt. |
| `RECOVERING` → `FAILED` | nichts binnen `recover-grace-minutes` | Entitäten auf `unavailable`, **einmalig** Alarm-Event. |
| `FAILED` → `HEALTHY` | Nachricht kommt an | Entwarnungs-Event. |

Einmalig melden, nicht minütlich wiederholen — das ist der Unterschied zwischen einer
hilfreichen Warnung und einer, die stummgeschaltet wird.

Die Scheduled-Methode wirft nie (Muster `TractivePollingService`).

### 3. Meldeweg

Der Watchdog feuert die EVENT-Entität `event.zigbee_bridge_status`:

- State: `failed` bzw. `recovered`
- Attribute: `reason` (`stream_silent` | `bridge_offline`), `silentMinutes`, `offlineDevices`

Die Telegram-Warnung wird als **Flow** gebaut (`entity-event-trigger` → `telegram-send`),
nicht als Java-Code: Wortlaut und Empfänger sind ohne Redeploy änderbar, der Weg ist derselbe
wie bei der Vision-Anbindung, und es entsteht keine zweite konkurrierende
Benachrichtigungsschiene.

**Offengelegter Preis:** Die Ausfallmeldung hängt damit selbst an der Flow-Engine. Für Zigbee
trägt das, weil das Backend in diesem Szenario läuft. Für einen künftigen *Backend*-Ausfall
wäre dieser Weg untauglich — das darf später niemand als gegeben annehmen.

### 4. Entitäten auf `unavailable`

Über das vorhandene `EntityStateService.find(null, EntitySource.ZIGBEE)`; keine
Repository-Erweiterung nötig.

**Ausgenommen:**
- EVENT-Entitäten (Taster) — ein Ereignis hat keinen fortdauernden Zustand, `unavailable`
  wäre dort bedeutungslos
- `event.zigbee_bridge_status` selbst — sonst markierte der Watchdog seinen eigenen
  Meldekanal als tot

### 5. Erholungs-Edge in der Flow-Engine

Ohne Gegenmaßnahme macht der Watchdog die Lage an einer Stelle **schlechter**:
`EntityStateTriggerHandler` feuert bei `nowMatches && !beforeMatched`. Ein Türkontakt, der
beim Ausfall „offen" war und bei der Erholung von `unavailable` auf `on` zurückspringt,
erfüllt genau diese Bedingung — Flow #2 sendete im Modus „Abwesend" einen Fehlalarm.

**Revidiert am 2026-07-28 nach dem Code-Review — die erste Fassung dieser Regel war
sicherheitsgefährdend.**

Ursprünglich war vorgesehen, *beide* Richtungen zu unterdrücken. Das Review hat gezeigt,
dass das einen Alarm verschluckt, der wichtiger ist als die vermiedene Dopplung:

Prod-Flow #4 („Feuer-Verdacht") triggert auf `Temperatur > 40`. Bei beidseitiger
Unterdrückung wird der Übergang `unavailable → 41` verworfen. Bricht ein Feuer *während*
eines Zigbee-Ausfalls aus, kommt der Sensor mit 41 °C zurück — und der Alarm feuert
**nie**, bis die Temperatur erst unter 40 fällt und erneut steigt.

Der Fehlalarm auf der Gegenseite ist zudem schwächer als angenommen: Ein Türkontakt, der
bei der Erholung auf `on` springt, bedeutet, dass die Tür in diesem Moment *tatsächlich
offen* ist, während „Abwesend" aktiv ist. Das ist eine **Dopplung**, keine Falschmeldung.
Eine Dopplung ist ärgerlich, ein verschluckter Brandalarm nicht.

**Gültige Regel, engine-weit:**

- **Kein Feuern, wenn `newState` gleich `unavailable` ist** — der Ausfall selbst ist kein
  Ereignis der beobachteten Größe. Nötig, weil sonst `operator: "!="` und
  `operator: "changed"` bei jedem Ausfall auslösten. Ein laufender `forSeconds`-Timer wird
  dabei storniert.
- **Der Übergang aus `unavailable` heraus feuert normal.** Beim Wiederanlaufen wird der
  erste echte Wert regulär bewertet.
- **Der `forSeconds`-Ablauf prüft zusätzlich selbst**, ob der aktuelle Zustand
  `unavailable` ist, und emittiert dann nicht. `StateComparator` vergleicht
  nicht-numerische Werte als String, `matches("unavailable", "!=", "on")` wäre sonst
  **wahr** — und `future.cancel(false)` stoppt eine bereits gestartete Task nicht mehr.

Bewusst in Kauf genommen: Nach einem Ausfall kann eine bereits gemeldete Bedingung ein
zweites Mal melden.

`EntityConditionHandler` bleibt unverändert. Geprüft: `StateComparator` kann
`"unavailable"` nicht als Zahl parsen, numerische Operatoren liefern damit korrekt
`false`. **Bekannte Falle, bewusst nicht geändert:** `!=` vergleicht nicht-numerisch als
String, `unavailable != on` ist deshalb **wahr** — eine Bedingung „Tür ist nicht offen"
gilt bei einem Ausfall als erfüllt.

**Nebeneffekt auf andere Quellen, der dokumentiert gehört:** `ShellyPollingService`,
`SmartDeviceEntityMapper` (Kasa/Tapo), `NukiPollingService` und `TractivePollingService`
schreiben `unavailable` bei *jedem* fehlgeschlagenen Poll. Die Unterdrückung der
Hin-Richtung betrifft damit auch kurze, routinemäßige Aussetzer dieser Quellen — für
`changed`- und `!=`-Trigger ist das erwünscht, für alles andere folgenlos.

### 6. Härtungen am MQTT-Client

- **Resubscribe wiederholen** statt einmal zu warnen (Backoff, unbegrenzt).
- **Verarbeitung vom Netty-Event-Loop entkoppeln:** `.callback(handler).executor(handlerExecutor)`
  mit einem **einzelnen** Thread — bewusst kein Pool: mehrere Threads könnten Nachrichten
  desselben Geräts umsortieren, und bei einem Türkontakt wäre ein vertauschtes „offen"/„zu"
  fatal.
  **Korrigiert nach der Umsetzung:** Die erste Fassung sah eine *begrenzte* Queue vor, die bei
  Überlauf laut loggt. Das wäre eine Zusage gewesen, die der Code nicht halten kann: HiveMQ
  wickelt den Executor in `Schedulers.from(...)`, und dessen `ExecutorWorker` hält seine eigene
  unbeschränkte Queue und submittet sich selbst nur, wenn nichts läuft — in unserer Queue steht
  damit immer höchstens **eine** Task. Sie liefe nie voll, die Warnung wäre toter Code. Das echte
  Backpressure kommt aus RxJavas `observeOn`-Puffer. Deshalb: unbeschränkte Queue und der
  tatsächliche Mechanismus im Javadoc, statt einer inszenierten Kapazitätsgrenze.
- **Logging:** `handle`-Fehler von `debug` auf `warn` mit Stacktrace; zusätzlich ein
  `addDisconnectedListener`, damit Reconnect-Zyklen überhaupt sichtbar werden.

### 7. Health-Endpunkt und Frontend

`GET /api/v1/zigbee/health` gibt `ZigbeeStreamMonitor.status()` zurück. Banner auf der
Zigbee-Seite, Hinweis im Dashboard-Footer. Das Kachel-Markup steht direkt in
`dashboard.component.html` — die `lumina`-Styles sind dort gekapselt und griffen in einer
Kind-Komponente lautlos nicht.

## Parser-Erweiterung

`ZigbeeMessageParser` muss zusätzlich erkennen:
- `zigbee2mqtt/bridge/state` → Bridge-Zustand
- `zigbee2mqtt/<gerät>/availability` → Geräte-Verfügbarkeit

Beides fließt in den `ZigbeeStreamMonitor`, **nicht** in `ZigbeeReadingService` — es sind
keine Messwerte. Gerätewert-Topics müssen unverändert weiter funktionieren, und `bridge`
darf weiterhin nicht als Gerät durchgehen.

## Konfiguration

| Property | Default |
|---|---|
| `zigbee.watchdog.enabled` | `true` |
| `zigbee.watchdog.stale-after-minutes` | `15` |
| `zigbee.watchdog.recover-grace-minutes` | `5` |

Bewusst in `application.properties` und nicht in der Datenbank: anders als bei der
Tractive-Home-Definition gibt es keinen Grund, diese Werte im laufenden Betrieb zu verstellen.

Die 15 Minuten sind aus den PROD-Daten abgeleitet — die sieben Temperatursensoren melden
erkennbar im Minutenabstand, totale Stille über 15 Minuten ist damit sicher ein Ausfall.
Verifiziert ist das allerdings nur über das eine beobachtete Zeitfenster; der Wert sollte
nach einigen Tagen Betrieb gegen die tatsächlichen Melde-Abstände nachgezogen werden.

## Tests

- `ZigbeeStreamMonitorTest` — Urteile `OK` / `STILL` / `BRIDGE_OFFLINE`, Geräte-Availability
- `ZigbeeAvailabilityWatchdogTest` — Zustandsübergänge, Selbstheilung, und dass im Zustand
  `FAILED` nur **einmal** gemeldet wird
- `EntityStateTriggerHandlerTest` — kein Feuern bei `oldState == unavailable`, für beide
  Operator-Fälle
- `ZigbeeMessageParserTest` — `bridge/state` und `availability` werden erkannt, Gerätewerte
  weiterhin korrekt geparst, `bridge` nicht als Gerät

## Reihenfolge der Umsetzung

1. **Punkt 6** (MQTT-Härtungen) — behebt womöglich bereits die Ursache
2. **Punkt 5** (Erholungs-Edge) — muss **vor** dem Scharfschalten von `unavailable` stehen,
   sonst bauen wir den Fehlalarm erst ein
3. **Parser-Erweiterung** (`bridge/state`, `availability`) — Voraussetzung für den Monitor
4. **Punkte 1–4** (Monitor, Watchdog, Meldeweg, `unavailable`), anschließend der
   Telegram-Flow über den flow-mcp-server anlegen und aktivieren
5. **Punkt 7** (Health-Endpunkt, Frontend)

## Bewusst nicht Teil dieses Entwurfs

- **Generischer Heartbeat-Layer im Entity-State-Layer** — siehe Begründung oben.
- **Retention für `zigbee_measurement`** — es gibt keinen Aufräumjob, die Tabelle wächst
  unbegrenzt. Das bremst langfristig die Schreibpfade und verschärft Ursachenkandidat 2. Ein
  echtes, aber eigenständiges Problem; getrennt anzugehen, statt hier zwei Dinge gleichzeitig
  zu bauen.
