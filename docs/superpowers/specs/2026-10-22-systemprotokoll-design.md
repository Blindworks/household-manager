# Systemprotokoll im Admin-Bereich — Design

Stand: 2026-10-22

## Zweck

Der Admin möchte im Nachhinein sehen, **was im System gelaufen ist und was nicht**: welcher Flow ausgelöst wurde und wie weit er kam, welche Hintergrund-Jobs gestört sind, welche Ereignisse im Haus passiert sind und welche Warnungen oder Fehler das Backend geloggt hat. Heute ist das nicht sichtbar. Flow-Läufe werden nicht gespeichert, es gibt nur `flows.last_triggered_at` und den flüchtigen `DebugBuffer`. Node-Fehler landen nur im Container-Log (`FlowEngine.runFrom`: `log.warn(...)`). Die meisten Poller „werfen nie“ und schlucken ihre Fehler. Das Audit-Log hält nur Aktionen fest (Schalten, Modi, Nuki), aber keine Ereignisse und keine Ausfälle.

## Entscheidungen des Nutzers (2026-10-22)

- **Inhalt: alle vier Arten**: Flow-Läufe, Hintergrund-Jobs, Entity-Ereignisse, Backend-Warnungen/-Fehler.
- **„Nicht gelaufen“ umfasst alle vier Fälle**: an Bedingung gestoppt, Fehler im Lauf, vom Rate-Limit verworfen, zeitgesteuerter Termin verpasst.
- **Aufbewahrung 30 Tage** in der DB, nächtlicher Aufräumjob.
- **Entity-Ereignisse ohne Messwerte**: Events (Taster, Kalender, Bewegung …), binäre Sensoren, Schalter, Modi/Helfer, Schloss. Keine reinen Zahlenwechsel (Watt, Temperatur, Feuchte, Latenz …).
- **Jobs: Statustafel plus Wechsel.** Eine Tafel je Job zeigt letzten Lauf, letzten Erfolg und letzten Fehler. Ins Protokoll kommen nur Fehlschläge und Übergänge zwischen ok und gestört, nicht jeder erfolgreiche Minuten-Poll.
- **Ansicht: eine Zeitleiste mit Filtern** (Art, Status, Flow, Freitext). Ein Flow-Lauf lässt sich aufklappen und zeigt seinen Weg durch die Nodes.
- **„Verpasst“ nur für Cron- und Sonnen-Trigger**: Termine, die verstrichen sind, während das Backend nicht lief, werden als „verpasst“ eingetragen und **nicht** nachgefeuert (Bestandsverhalten bleibt).
- **Geschluckte Zustellfehler zählen als Fehler**: kommt eine Telegram-, Push- oder Alexa-Nachricht nicht an oder scheitert ein Licht-Befehl, ist der Lauf `ERROR` mit Grund, obwohl der Handler nicht wirft. Das Flow-Verhalten ändert sich nicht, nachgelagerte Zweige laufen weiter.
- **Abgebrochene Läufe bekommen einen eigenen Status `ABORTED`**: ein Lauf, der in einem Delay oder `forSeconds`-Timer wartet und durch Re-Deploy, Deaktivieren oder Backend-Neustart nie fortgesetzt wird. Er zählt zu „Nur Probleme“.
- **Nicht konfigurierte oder abgeschaltete Integrationen sind „inaktiv“** (grau), nicht „gestört“: kein Protokolleintrag, keine rote Kachel.

## Nicht-Ziele

- Kein Nachfeuern verpasster Termine.
- Keine Benachrichtigung (Telegram/Push) aus dem Protokoll heraus. Wer das will, baut einen Flow.
- Keine Protokollierung jedes erfolgreichen Job-Laufs und keiner reinen Messwertwechsel.
- Kein Ersatz für das Audit-Log. Das bleibt die Quelle für „wer hat was getan“. Das Systemprotokoll beantwortet „was ist passiert / was ist ausgefallen“.
- Keine Tablet-Ansicht, keine KIOSK-Lesbarkeit.
- Kein Mitprotokollieren von „Ereignis passierte, aber der Trigger-Filter eines Flows passte nicht“. Das wäre bei jedem Zustandswechsel Rauschen. Ein Trigger, der nicht feuert, ist kein Lauf.

## Anti-Ziele (was das Feature trotz „funktioniert“ wertlos machen würde)

- **Das Protokoll darf nie den Betrieb stören.** Jeder Schreibpfad wirft nie (Muster `AuditService.record`, `PowerHistoryRecorder`), schreibt asynchron und verliert im Zweifel lieber einen Eintrag, als einen Flow, Poller oder MQTT-Thread zu blockieren.
- **Kein Spam, der das Wichtige verdeckt.** Gleichlautende Log-Warnungen im Sekundentakt (z. B. ein flatternder Poller) werden zusammengefasst, sonst stehen 5000 identische Zeilen über dem einen Feueralarm-Lauf.
- **Keine Rückkopplung.** Der Log-Appender darf seine eigenen Schreibfehler nicht wieder ins Protokoll schreiben (Endlosschleife).

## Aufbau

### Datenmodell

Eine gemeinsame Tabelle `system_log_entry` für die Zeitleiste:

| Spalte | Bedeutung |
|---|---|
| `id` | PK |
| `occurred_at` | DATETIME(3), lokale Haushaltszeit wie im übrigen Projekt |
| `kind` | `FLOW_RUN`, `JOB`, `ENTITY`, `LOG` |
| `status` | `OK`, `STOPPED` (Bedingung), `RATE_LIMITED`, `ERROR`, `MISSED`, `WAITING` (Delay läuft noch), `ABORTED` (Fortsetzung nie gekommen), `INFO`, `WARN`, `RECOVERED` |
| `source` | Flow-Id als String / Job-Schlüssel / Entity-Id / Logger-Name (gekürzt) |
| `title` | Kurztext für die Zeile (z. B. Flow-Name, „Tractive-Polling gestört“) |
| `message` | Detailtext, gekappt (z. B. 2000 Zeichen) |
| `details` | JSON, nullable (Node-Weg eines Flow-Laufs, Stacktrace-Auszug, alter/neuer Zustand) |
| `flow_id` | nullable, für den Flow-Filter |
| `run_id` | nullable, UUID eines Flow-Laufs (verbindet die Fortsetzung nach einem Delay) |
| `repeat_count` | für zusammengefasste Log-Zeilen, Default 1 |

Indizes auf `occurred_at`, `(kind, occurred_at)` und `flow_id`. Wie im Projekt üblich ein Changeset je DDL (MariaDB-DDL-Autocommit). Die Changesets laufen nie gegen die echte DB, deshalb vor dem Deploy ein Backup.

Für die Job-Statustafel gibt es eine eigene kleine Tabelle `system_job_status` (eine Zeile je Job-Schlüssel: `last_run_at`, `last_success_at`, `last_failure_at`, `last_failure_message`, `state` OK/FAILING). Sie wird per Upsert geschrieben. *Meine Entscheidung:* DB statt nur Speicher, damit die Tafel nach einem Neustart nicht leer ist.

Für die Erkennung verpasster Termine gibt es einen persistierten **Lebenszeichen-Zeitstempel** („Backend lief zuletzt um …“), minütlich geschrieben. Ein Eintrag in `application_settings` reicht, Kategorie `SYSTEM_LOG`.

### Erfassung je Art

**1. Flow-Läufe** (`FlowEngine.runFrom`)
- Startet ein Lauf an einer Trigger-Node, bekommt er eine `runId`. Sie wird als reserviertes Feld (z. B. `_runId`) in die `FlowMessage` gelegt. Delay-Fortsetzungen tragen dieselbe Message weiter, und `runFrom` erkennt daran den laufenden Lauf.
- Während der Traversierung entsteht ein **Node-Weg**: je besuchter Node Typ, Id/Label, Ergebnis (welcher Port, „kein Ausgang“, Exception-Text).
- Den Status leitet der Lauf ab:
  - `ERROR`: mindestens eine Node warf.
  - `RATE_LIMITED`: eine `rate-limit`-Node lieferte `NodeResult.none()`.
  - `STOPPED`: eine Bedingungs-Node (`entity-condition`, `time-condition`) gab auf einen **unverbundenen** Port aus.
  - `WAITING`: eine Delay-Node hat übernommen. Die Fortsetzung schreibt später einen weiteren Eintrag mit derselben `runId`, und die UI zeigt beide zusammen.
  - sonst `OK`.
  - Treten mehrere zugleich auf (Verzweigungen), gewinnt `ERROR` vor `STOPPED`/`RATE_LIMITED` vor `OK`. Der volle Weg steht in `details`.
- **Zustellfehler ohne Exception:** `NodeContext` bekommt einen Rückkanal (z. B. `ctx.reportFailure(reason)`), über den ein Handler „hat nicht geklappt“ meldet, ohne zu werfen. `light-set`, `telegram-send`, `push-send` und `alexa-announce` nutzen ihn. Dazu müssen `TelegramNotificationService`/`PushNotificationService`/der Alexa-Ansagedienst ein Ergebnis zurückgeben (gesendet/fehlgeschlagen + Grund), statt nur zu loggen. Ihr Vertrag „wirft nie“ bleibt. Ein gemeldeter Fehler macht den Lauf `ERROR`, die Traversierung läuft unverändert weiter.
- **Abgebrochene Läufe:** Wartende Läufe (Delay, `forSeconds`) werden mit `runId`, Flow und Node in einer kleinen Tabelle `system_flow_pending_run` gemerkt und beim Fortsetzen gelöscht. Storniert `FlowRegistry` sie beim Re-Deploy oder Deaktivieren, entsteht sofort ein `ABORTED`-Eintrag („durch Re-Deploy abgebrochen“). Beim Start werden alle noch vorhandenen Zeilen zu `ABORTED` („Backend-Neustart“) und gelöscht. Damit braucht es keinen Lebenszeichen-Abgleich für Delays.
- Abbruch am Hop-Limit gilt als `ERROR`.
- **Test-Injects** (`flow_inject`) werden mitprotokolliert und als solche markiert.
- `FlowTriggerRecorder` und `last_triggered_at` bleiben unverändert.

**2. Verpasste Termine**
- Beim Start (nach dem Flow-Bootstrap) berechnet ein Dienst für jeden **aktiven** Flow mit `schedule-trigger` oder `sun-trigger` die Termine zwischen dem letzten Lebenszeichen und jetzt. Für Sonnen-Trigger fragt er `SunTimesService`, für Cron rechnet er in der Zone des `Clock`-Beans (wie der Trigger selbst). Jeder solche Termin wird als `MISSED` eingetragen („Backend lief nicht von … bis …“).
- Obergrenze: höchstens 7 Tage zurück und höchstens 50 Einträge je Flow, damit ein lange gestoppter Server nicht tausende Zeilen erzeugt. Darüber kommt ein Sammeleintrag.
- Ohne vorhandenes Lebenszeichen (erster Start nach dem Deploy) wird nichts eingetragen.

**3. Hintergrund-Jobs**
- Ein `SystemJobMonitor` mit `recordSuccess(jobKey)` / `recordFailure(jobKey, reason)`.
- **Automatisch für alle `@Scheduled`-Methoden** über einen Aspekt: letzter Lauf, Dauer und unbehandelte Exceptions. Das deckt die rund 37 Jobs ohne Einzelanpassung ab, sieht aber nur Fehler, die tatsächlich geworfen werden.
- **Explizit** in den Pollern, die Fehler schlucken (Tractive, Nuki, Meross, Tapo-Power, Shelly, Blink, Thermostat, Charging, Wetter, Airrohr, Alexa-Luftqualität, Smart-Device-State, Zigbee-Watchdog …): an der Stelle, wo heute nur geloggt wird, zusätzlich `recordFailure`. Welche genau das sind, bestimmt der Builder beim Durchgehen. Ziel ist: jeder Poller, der eine externe Quelle abfragt, meldet seine Störung.
- Dritter Zustand **`INACTIVE`**: `recordInactive(jobKey, reason)` für „nicht konfiguriert / abgeschaltet“ (Tractive ohne Token, EnBW ohne Key, Agent aus, Telegram deaktiviert, `*.enabled=false`). Grau auf der Tafel, kein Protokolleintrag, kein Übergang OK↔FAILING. Jeder angebundene Poller muss diese Unterscheidung treffen. Ein 401 gegen eine *konfigurierte* Quelle (z. B. abgelaufenes Tractive-Token) ist dagegen `FAILING`.
- Der Aspekt meldet keinen Erfolg, wenn im selben Aufruf `recordFailure` oder `recordInactive` lief. Sonst wechselte die Tafel jede Minute. `last_run_at` wird im Speicher gepuffert und nur bei Zustandswechseln bzw. periodisch geschrieben. Die AOP-Abhängigkeit (`spring-boot-starter-aop`) kommt in die pom.
- Protokolleintrag nur beim Übergang OK→FAILING (`ERROR`) und FAILING→OK (`RECOVERED`, mit Störungsdauer). Wiederholte Fehlschläge aktualisieren nur die Tafel.
- `TelegramPollingService` läuft nicht über `@Scheduled` und wird explizit angebunden.

**4. Entity-Ereignisse**
- `@EventListener` auf `EntityStateChangedEvent` und `EntityEventFired`, asynchron geschrieben.
- **Aufgenommen:** alle `EntityEventFired`, alle Zustandswechsel außer **reinen Zahlenwechseln**. Regel: alter und neuer Zustand parsen beide als Zahl ⇒ verwerfen. Ein Wechsel nach oder aus `unavailable` wird damit auch bei Messsensoren protokolliert, was für „was ist ausgefallen“ gewollt ist. `sensor.sun` (`day/dusk/…`) und Tractive-Zonen kommen rein.
- **Kehrseite:** Wechsel wie `sensor.network_internet` werden aufgenommen, `sensor.network_latency_ms` nicht. Ein Sensor, der nicht-numerische Texte im Minutentakt wechselt, würde fluten. Der Builder prüft deshalb nach dem ersten Betriebstag die Mengen je Entity und ergänzt bei Bedarf eine Ausschlussliste in `application.properties`.

**5. Backend-Warnungen und -Fehler**
- Ein eigener Logback-Appender ab Level WARN, programmatisch von einem Spring-Bean registriert (keine logback.xml im Projekt). Er übergibt an eine begrenzte Warteschlange, ein eigener Thread schreibt in die DB.
- **Zusammenfassen:** gleicher Logger + gleiche formatierte Nachricht binnen 10 Minuten ⇒ `repeat_count` der letzten Zeile erhöhen statt einer neuen Zeile.
- **Keine Rückkopplung:** Meldungen aus dem eigenen Paket und von Hibernate/JDBC während des Schreibens werden nicht erneut erfasst (Thread-Kennzeichnung oder Logger-Ausschluss). Ist die Warteschlange voll, wird verworfen und ein Zähler „n Einträge verworfen“ beim nächsten Erfolg vermerkt.
- Stacktrace: die ersten ~20 Zeilen in `details`.

### Aufbewahrung

`SystemLogRetentionJob` nachts (z. B. 03:30, nicht zur vollen Stunde), löscht `system_log_entry` älter als 30 Tage als `@Transactional @Modifying @Query`-Bulk-Delete (Lehre aus `NetworkHistoryRetentionJob`: abgeleitete Deletes ohne eigene Transaktion sind still wirkungslos). Die Jobtafel wird nicht gelöscht.

### API

`/api/v1/admin/system-log`, **ADMIN**, als methodenloser Matcher vor der generischen `GET /v1/**`-Regel. Die Einträge enthalten Gewohnheiten des Haushalts und Flow-Interna, das Wandtablet darf sie nicht lesen. `SecurityRulesTest` hält die Richtungen fest.
- `GET /entries?from&to&kind&status&flowId&q&page&size`: seitenweise, neueste zuerst. Ein Flow-Lauf und seine Delay-Fortsetzungen kommen als Gruppe über `runId`.
- `GET /jobs`: die Statustafel.
- Keine schreibenden Endpunkte. Löschen nur über die Aufbewahrung.

### Frontend

Neue Admin-Seite „Systemprotokoll“ (`pages/admin-system-log/`, Route `admin/system-log`, `adminGuard`, Wurzelklasse in `$admin-scopes` eintragen).
- **Oben die Job-Tafel**: kompakte Kacheln je Job, gestörte zuerst und rot, mit „gestört seit …“ und Fehlertext, inaktive grau mit Grund. Gesunde und inaktive sind einklappbar.
- **Darunter die Zeitleiste**: Filterleiste (Art als Mehrfachauswahl, Status, Flow-Dropdown, Zeitraum Heute/24 h/7 Tage/30 Tage, Freitext). Jede Zeile hat Zeit, Art-Icon, Status-Badge, Titel und Kurztext. Flow-Läufe lassen sich zum Node-Weg aufklappen (je Node ein Häkchen, Stopp-Symbol oder Fehler mit Text). Log-Einträge klappen zum Stacktrace auf und zeigen „×n“ bei Wiederholungen.
- Schnellfilter „Nur Probleme“ (ERROR/STOPPED/RATE_LIMITED/MISSED/ABORTED/WARN).
- Neu laden per Knopf, kein Auto-Refresh. *Meine Entscheidung*, damit die Liste beim Lesen nicht springt.

## Offene Punkte für den Builder

- Welche Poller ihre Fehler schlucken und deshalb `recordFailure` brauchen (siehe oben).
- Genaues Format des Node-Wegs in `details` und die Darstellung paralleler Zweige.
- Wie `runFrom` erkennt, dass eine Bedingungs-Node auf einen unverbundenen Port ausgab (Graph-Abfrage `targetsOf` ist leer), und wie sich das von einer Aktions-Node ohne Nachfolger unterscheidet. Nur Bedingungs-/Rate-Limit-Handler zählen. Eine Kennzeichnung am Handler (Interface/Marker wie `TriggerNodeHandler`) ist der saubere Weg.
- Mengenabschätzung nach dem ersten Betriebstag (Entity-Ereignisse, WARN-Zeilen) und ggf. Ausschlussliste.
- CLAUDE.md-Abschnitt nach dem Bau ergänzen.
