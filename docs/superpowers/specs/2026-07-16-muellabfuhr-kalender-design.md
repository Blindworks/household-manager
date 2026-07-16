# Müllabfuhr-Kalender — Design

**Datum:** 2026-07-16
**Status:** Entwurf, abgestimmt

## Ziel

Müllabfuhr-Termine aus einem Google Kalender auslesen, sie wenige Tage vorher auf dem
Dashboard anzeigen und am Vorabend per Alexa-Durchsage darauf aufmerksam machen.

## Getroffene Entscheidungen

| Frage | Entscheidung | Begründung |
|---|---|---|
| Kalenderzugriff | **iCal/ICS-Abo-URL** (Googles „Privatadresse im iCal-Format") | Lesezugriff genügt. Kein OAuth, keine Google-Cloud-Credentials, keine Token-Erneuerung. |
| Art der Erinnerung | **Dashboard-Kachel *und* Alexa-Durchsage** | Die Kachel informiert passiv über die nächsten Tage, die Durchsage ist der aktive Hinweis am Vorabend. |
| Architektur | **Eigenes Modul mit DB-Persistenz** | Robust gegen ICS-Ausfälle, saubere Deduplizierung der Durchsage, folgt dem bestehenden Polling-Muster. |
| Entity-State | **Ja**, nächste Abholung als Sensor melden | Konsistent mit `WeatherPollingService`; macht die Abholung später als Flow-Trigger nutzbar. |
| Konfiguration | **Eigene Einstellungsseite** im Frontend | Die ICS-URL und die Alexa-Geräte sollen ohne `curl` pflegbar sein. |

Verworfen: Google Calendar API mit OAuth2 (unverhältnismäßiger Aufwand für reines Lesen),
Auslösung über die Flow-Engine (bräuchte einen Zeit-Trigger, zu viel Indirektion für ein
festes Feature), ICS-Live-Abruf ohne DB (fragil bei Ausfall, umständliche Dedup-Logik).

## Datenmodell

### Tabelle `waste_collection_events`

Liquibase-Changeset `20260716-0034-create-waste-collection-events-table.xml`, eingebunden in
`db.changelog-master.xml`. (`0033` ist bereits durch `create-entity-usage-table` aus dem
parallel entwickelten Schalter-Feature belegt.)

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | BIGINT, PK, auto | |
| `collection_date` | DATE, not null | Tag der Abholung |
| `label` | VARCHAR(255), not null | Bezeichnung aus dem Termin, z. B. „Biotonne" |
| `created_at` | TIMESTAMP, default CURRENT_TIMESTAMP | |
| `updated_at` | TIMESTAMP, default CURRENT_TIMESTAMP | |

- Unique-Constraint auf `(collection_date, label)`
- Index auf `collection_date`
- Rollback: `dropTable`

**Sync-Strategie:** Bei jedem Abruf werden alle Zeilen mit `collection_date >= heute`
gelöscht und die geparsten Termine neu eingefügt. Das behandelt verschobene und abgesagte
Termine korrekt, ohne Abhängigkeit von instabilen ICS-UIDs. Vergangene Zeilen bleiben als
Historie erhalten.

### Konfiguration

In `application_settings`, Kategorie `WASTE_COLLECTION`, mit Seed-Defaults im selben
Changeset:

| `setting_key` | Default | Bedeutung |
|---|---|---|
| `enabled` | `false` | Feature aktiv (aus, bis die URL hinterlegt ist) |
| `ics_url` | `` (leer) | Die geheime iCal-URL |
| `lookahead_days` | `3` | Vorschau-Fenster auf dem Dashboard (siehe unten) |
| `reminder_enabled` | `true` | Abend-Durchsage aktiv |
| `reminder_time` | `19:00` | Uhrzeit der Durchsage |
| `reminder_alexa_serials` | `` (leer) | Ziel-Geräte, kommasepariert |
| `last_announced_date` | `` (leer) | interner Merker gegen Doppel-Ansagen |

**Definition des Vorschau-Fensters:** `lookahead_days` zählt Tage **einschließlich heute**.
Die Abfrage lautet `collection_date BETWEEN heute AND heute + (lookahead_days - 1)`. Der
Default `3` umfasst also heute, morgen und übermorgen. Ein Wert `< 1` wird auf `1` angehoben.

`last_announced_date` liegt bewusst in den Settings und nicht als Flag an der Event-Zeile —
der Resync würde ein solches Flag wegräumen.

`setting_value` ist VARCHAR(500) und damit für die ICS-URL ausreichend dimensioniert.

## Komponenten (Backend)

Paketaufteilung wie beim nächstverwandten Feature (Wetter), das dieselbe Form hat —
geplanter Abruf einer externen Quelle mit Persistenz und Entity-State:

- Services → `com.household.manager.service`
- Entity → `com.household.manager.model.entity`
- Repository → `com.household.manager.repository` (zwingend: `JpaConfig` scannt nur dort)
- DTOs → `com.household.manager.dto`
- Controller → `com.household.manager.controller`

| Klasse | Aufgabe | Abhängigkeiten |
|---|---|---|
| `WasteCalendarIcsClient` | Lädt den ICS-Text von der URL (HTTP-Timeout 10 s). Sonst nichts. | HTTP-Client |
| `WasteCalendarIcsParser` | ICS-Text → `List<ParsedWasteEvent(date, label)>`. Rein funktional, kein Netz, keine DB. Löst Serientermine (RRULE) über das Fenster heute bis +12 Monate auf, liest `DTSTART` (Ganztagestermin → `LocalDate`) und `SUMMARY`. Filtert Vergangenes. | biweekly |
| `WasteCalendarPollingService` | Orchestriert: Settings → Client → Parser → Resync. `@Scheduled`, `getStatus()`, `triggerOnce()`, `safePoll()` mit Catch-all. Meldet den Entity-State. | die obigen, `WasteCollectionResyncService`, Repository, `ApplicationSettingsService`, `EntityStateService`, `TaskScheduler` |
| `WasteCollectionResyncService` | Ersetzt das Zukunftsfenster (Delete ab heute + Insert) in **einer eigenen Transaktion**, dedupliziert vorher. Eigene Bean, weil der abgeleitete Delete eine aktive Transaktion braucht und `@Transactional` bei einem Selbstaufruf innerhalb derselben Bean wirkungslos bliebe. So bleibt zudem der HTTP-Abruf ausserhalb der Transaktion. | Repository |
| `WasteCollectionService` | Leseseite: Termine im Fenster, Termine für ein Datum. Genutzt von Controller und Erinnerung. | Repository, `Clock` |
| `WasteReminderService` | Prüft minütlich die Ansage-Bedingungen und löst die Durchsage aus. | `WasteCollectionService`, `ApplicationSettingsService`, `AlexaAnnouncementService`, `WasteAnnouncementTextBuilder`, `Clock` |
| `WasteAnnouncementTextBuilder` | Labels → Ansagetext. Rein funktional. | — |
| `WasteCollectionRepository` | JPA, in `com.household.manager.repository`. | — |

**Neue Maven-Abhängigkeit:** `net.sf.biweekly:biweekly:0.6.8` (Java-Package `biweekly`).

Gewählt statt ical4j, weil `VEvent.getDateIterator(timezone)` Serien- **und** Einzeltermine
über denselben Codepfad liefert (bei einem Einzeltermin genau ein Datum) und die API über
die 3.x/4.x-Umbauten von ical4j hinweg stabiler dokumentiert ist. Der Iterator einer
Serie ohne `UNTIL`/`COUNT` ist unendlich — er wird über `advanceTo(from)` positioniert, beim
Überschreiten des Fensterendes abgebrochen und zusätzlich durch eine Iterationsobergrenze
abgesichert.

**Ergänzung am Bestand:** `ApplicationSettingsService` bekommt
`getString(category, key, default)` — vorhanden sind bislang nur `getBoolean`/`getInt`/`getLong`.

### Polling

```
@Scheduled(
  fixedDelayString  = "${waste.polling.interval-ms:86400000}",     // täglich
  initialDelayString = "${waste.polling.initial-delay-ms:30000}"   // kurz nach Start
)
```

Ist `enabled=false` oder `ics_url` leer, wird der Lauf mit einem Log-Hinweis übersprungen.
Fehler landen in `lastError` und werden nicht nach oben geworfen.

### Entity-State

`EntitySource` bekommt einen neuen Wert `WASTE`. Nach jedem erfolgreichen Abruf meldet der
Polling-Service die nächste anstehende Abholung analog zu `WeatherPollingService`:

- `entityId`: `EntityIds.build(EntityDomain.SENSOR, EntitySource.WASTE, "calendar", "next_collection")`
- `state`: Datum der nächsten Abholung (ISO) oder `unknown`, wenn keine bekannt ist
- `attributes`: `label`, `daysUntil`
- Die Meldung läuft — wie beim Wetter — in einem eigenen `try/catch`, damit ein Fehler in
  der Entity-Schicht den Abruf nicht scheitern lässt.

### Erinnerung

`@Scheduled(fixedDelayString = "${waste.reminder.check-interval-ms:60000}")`.

Ein Prüf-Intervall statt `@Scheduled(cron=...)`, weil `reminder_time` zur Laufzeit änderbar
sein soll; ein statischer Cron-Ausdruck kann die Settings nicht lesen. Die Minutenprüfung
kostet einen Settings-Read plus eine indizierte Datumsabfrage und übersteht Neustarts ohne
Sonderlogik.

Angesagt wird nur, wenn **alle** Bedingungen zutreffen:

1. `enabled` und `reminder_enabled` sind gesetzt,
2. mindestens eine Ziel-Seriennummer ist konfiguriert,
3. die aktuelle Uhrzeit liegt im Fenster `reminder_time` bis `reminder_time` + 60 Minuten,
4. `last_announced_date` ist nicht der heutige Tag,
5. für morgen existiert mindestens ein Termin.

Bedingung 3 verhindert, dass ein Neustart um 23:00 Uhr noch eine Durchsage auslöst.
Nach erfolgreicher Ansage wird `last_announced_date` auf heute gesetzt; schlägt die Ansage
fehl, bleibt der Merker unverändert und der nächste Lauf versucht es innerhalb des Fensters
erneut.

Ausgabe über `AlexaAnnouncementService` im Modus `ANNOUNCE` (mit Signalton).

### Ansagetext

> „Erinnerung: Morgen wird abgeholt: Biotonne."
> „Erinnerung: Morgen wird abgeholt: Biotonne und Restmüll."
> „Erinnerung: Morgen wird abgeholt: Biotonne, Restmüll und Gelber Sack."

Bewusst neutral formuliert statt „Morgen wird **die** Biotonne geleert": Die Bezeichnungen
kommen wortwörtlich aus dem Kalender, Genus und Artikel sind daher unbekannt („der Restmüll",
„die Biotonne", „der Gelbe Sack"). Mehrere Labels werden mit „, " verkettet, das letzte mit
„ und ".

## API

Die Anwendung nutzt `server.servlet.context-path=/api`; Controller mappen daher auf `/v1/...`
und die effektiven URLs lauten `/api/v1/...`.

| Endpoint | Zweck |
|---|---|
| `GET /api/v1/waste-collection/upcoming?days=N` | Termine als `WasteCollectionEventResponse` (`date`, `label`, `daysUntil`). Ohne `days` gilt `lookahead_days` aus den Settings (so ruft das Dashboard auf); die Einstellungsseite ruft mit `days=60` auf, um einen längeren Ausblick zu zeigen. |
| `GET /api/v1/waste-collection/settings` | Konfiguration lesen |
| `PUT /api/v1/waste-collection/settings` | Konfiguration schreiben; `last_announced_date` ist nicht überschreibbar (interner Zustand) |
| `GET /api/v1/admin/waste-polling` | `lastPollTime`, `lastError`, `schedule`, Anzahl bekannter Termine |
| `POST /api/v1/admin/waste-polling/trigger` | Abruf sofort auslösen |

Die Polling-Endpoints liegen unter `/v1/admin/...`, weil `WeatherPollingAdminController` es
genau so hält (`/v1/admin/weather-polling`).

`WasteCollectionController` für die ersten drei (Muster: `AnkerSolixController` exponiert
seine Settings selbst, es gibt keinen zentralen Settings-Controller),
`WastePollingAdminController` für die beiden Polling-Endpoints (Muster:
`WeatherPollingAdminController`). Der Trigger-Endpoint erlaubt es, den Abruf nach dem
Eintragen der URL sofort anzustoßen, statt bis zum nächsten Tageslauf zu warten.

### Settings-DTO

Die Settings werden als **typisiertes DTO** übertragen, nicht als rohe `Map<String,String>` —
so hält es `AnkerSolixController` mit `AnkerSolixAutoControlSettings`, und die Seite bekommt
dadurch echte Typen statt String-Hantiererei:

```
WasteCollectionSettings {
  enabled: boolean
  icsUrl: string                  // leer erlaubt
  lookaheadDays: number           // >= 1
  reminderEnabled: boolean
  reminderTime: string            // "HH:mm"
  reminderAlexaSerials: string[]  // in der DB kommasepariert abgelegt
}
```

Der Service übersetzt zwischen DTO und den String-Werten in `application_settings`.

**Validierung** beim `PUT`, mit 400 und verständlicher Meldung bei Verstoß:

- `icsUrl` ist leer oder eine gültige `http(s)`-URL,
- `lookaheadDays >= 1`,
- `reminderTime` ist als `HH:mm` parsebar.

Die Validierung sitzt am Endpoint. Die defensive Fallback-Logik im Scheduler (siehe
Fehlerbehandlung) bleibt als zweite Verteidigungslinie trotzdem bestehen — für Werte, die
per Seed oder direktem DB-Zugriff an dieser Prüfung vorbei in die Tabelle gelangen.

Die ICS-URL ist ein Geheimnis, wird von `GET` aber im Klartext zurückgegeben — sonst ließe
sie sich auf der Seite nicht bearbeiten. Das entspricht dem Zuschnitt der Anwendung als
unauthentifizierte Heimnetz-Anwendung, in der auch andere Zugangsdaten so gehandhabt werden.

## Frontend

- `models/waste-collection.model.ts` — Interfaces `WasteCollectionEvent`,
  `WasteCollectionSettings`, `WasteCollectionPollingStatus`.
- `services/waste-collection.service.ts` — `getUpcoming(days?)`, `getSettings()`,
  `updateSettings()`, `getPollingStatus()`, `triggerPoll()`.
- `components/waste-collection-tile/waste-collection-tile.component.{ts,html,scss}` —
  standalone, TS/HTML/SCSS getrennt.
- `pages/waste-collection/waste-collection.component.{ts,html,scss}` — die Einstellungsseite.

### Dashboard-Kachel

Die Kachel sitzt im `lumina__rooms`-Grid neben der Klima-Kachel und folgt dem
`lumina-card`-Stil. Sie lädt selbst stündlich nach
(`interval(3600000).pipe(startWith(0), switchMap(...), catchError(() => of([])))`, wie die
übrigen Kacheln). Aufbau: Icon `delete`, Titel „Müllabfuhr", darunter je Termin eine Zeile
mit relativer Tagesangabe („Heute", „Morgen", „Übermorgen", sonst der Wochentag) und der
Bezeichnung.

Eine **eigene Komponente** statt Logik in `DashboardComponent`: Diese trägt bereits Uhr,
Wetter, Live-Energie, Anker Solix und Klima; eine weitere Sorge würde eine ohnehin große
Datei weiter aufblähen. Die Kachel ist so außerdem unabhängig testbar.

Zwei bewusste UI-Entscheidungen:

- **Keine Kachel, wenn nichts ansteht** (`*ngIf` auf die Liste). Die Termine sollen wenige
  Tage vorher erscheinen — eine dauerhafte „Keine Abholung"-Kachel wäre nur Rauschen.
- **Die „Morgen"-Zeile wird farblich hervorgehoben** — was abends angesagt wird, sticht auch
  visuell heraus.

### Einstellungsseite

Route `waste-collection` → `WasteCollectionComponent`, lazy geladen wie alle übrigen Seiten,
Titel `'Muellabfuhr - Household Manager'` (ASCII-Transliteration wie im Bestand). Der
Navigationseintrag kommt in `navLinks` der `HeaderComponent` unter die Gruppe **Umwelt**
(zu Luftqualitaet/Wetter/Temperaturen) als `{ path: '/waste-collection', label: 'Muellabfuhr' }`.

Aufbau nach dem Vorbild von `battery-control` (`FormsModule`, `[(ngModel)]`, Laden im
`ngOnInit`, Speichern mit `isSaving`/`saveSuccess`/`saveError`), drei Abschnitte:

1. **Nächste Termine** — Liste aus `getUpcoming(60)`, rein informativ. Sie ist die
   Erfolgskontrolle: Nach dem Eintragen der URL siehst du sofort, ob der Kalender passt.
2. **Einstellungen** — `enabled` (Checkbox), `icsUrl` (Textfeld), `lookaheadDays` (Zahl),
   `reminderEnabled` (Checkbox), `reminderTime` (`<input type="time">`), Ziel-Geräte
   (Geräte-Picker, siehe unten). Dazu ein Speichern-Button.
3. **Abruf-Status** — `lastPollTime`, `lastError`, Anzahl bekannter Termine, plus Button
   „Jetzt abrufen" (`triggerPoll()`), der anschließend Status und Terminliste neu lädt.

**Wiederverwendung statt Neubau:** Für die Alexa-Zielgeräte gibt es bereits
`AlexaDevicePickerComponent` — eine standalone Mehrfachauswahl, deren Wert exakt ein
`string[]` von `serialNumbers` ist, also genau unser Feld. Sie hängt nur an `AlexaService`
und `AlexaDevice`, enthält nichts Flow-Spezifisches. Statt Seriennummern abtippen zu lassen,
nutzen wir sie.

Sie liegt allerdings unter `pages/flows/pickers/`. Mit dieser Seite bekommt sie ihren
**zweiten Konsumenten außerhalb der Flows**, deshalb wandert sie nach
`components/alexa-device-picker/`; der Import im Flow-Editor und die relativen Service-Pfade
werden mitgezogen, der Doc-Kommentar wird vom Flow-Feldtyp `ALEXA_DEVICE_LIST` gelöst. Das
ist ein kleiner, gezielter Umzug im Dienst dieser Aufgabe — keine Gelegenheits-Refaktorierung.

## Fehlerbehandlung

Leitgedanke: Ein kaputter ICS-Abruf darf weder das Dashboard leeren noch die Anwendung stören.

| Fall | Verhalten |
|---|---|
| ICS nicht erreichbar / Timeout | `lastError` setzen, Tabelle unangetastet lassen. Die Kachel zeigt weiter die zuletzt bekannten Termine. |
| Parse-Fehler (z. B. HTML-Fehlerseite statt ICS) | Resync läuft erst nach vollständigem Parsen und in einer Transaktion — es wird nichts gelöscht. |
| Parse liefert null Termine | Resync **überspringen**, Warnung loggen. Trade-off: Werden wirklich alle Termine im Kalender gelöscht, bleiben die alten stehen, bis sie zeitlich durchlaufen — das harmlosere Fehlverhalten gegenüber einer unerklärlich leeren Kachel. |
| Alexa-Ansage schlägt fehl | Loggen, `last_announced_date` unverändert lassen, im Fenster erneut versuchen. |
| Kaputte Settings (z. B. `reminder_time` = „abends") | Defensiv parsen, auf Default zurückfallen, Warnung loggen. Ein Tippfehler darf den Scheduler nicht lahmlegen. |
| Entity-State-Meldung schlägt fehl | Eigenes `try/catch`, Abruf gilt trotzdem als erfolgreich. |
| Frontend-Fehler | `catchError` → leere Liste → Kachel blendet sich aus. Kein Fehler-Popup für eine Dashboard-Kachel. |

## Tests

Reine Unit-Tests mit Mockito, **ohne** `@SpringBootTest`/DB, damit sie unabhängig von einer
lokalen Datenbank laufen. `WasteReminderService` und `WasteCollectionService` bekommen eine
injizierte `java.time.Clock` statt `LocalDate.now()` — sonst sind „morgen" und das
Ansage-Fenster nicht deterministisch testbar. AAA-Muster, beschreibende Testnamen.

| Testklasse | Fälle |
|---|---|
| `WasteCalendarIcsParserTest` | ICS-Fixtures unter `src/test/resources`: einfacher Ganztagestermin; Serientermin (RRULE, 14-tägig); mehrere Termine am selben Tag; Termin in der Vergangenheit wird gefiltert |
| `WasteAnnouncementTextBuilderTest` | ein, zwei, drei Labels |
| `WasteReminderServiceTest` | sagt an, wenn alle Bedingungen erfüllt sind; nicht außerhalb des Zeitfensters; nicht zweimal am selben Tag; nicht ohne Termin morgen; `last_announced_date` unverändert bei Alexa-Fehler |
| `WasteCalendarPollingServiceTest` | überspringt bei `enabled=false`; setzt `lastError` bei Fetch-Fehler; löscht nichts bei leerem Parse |
| `WasteCollectionServiceTest` | Fenster-Abfrage mit fixer `Clock` |
| `waste-collection-tile.component.spec.ts` | Kachel versteckt bei leerer Liste; „Morgen"-Zeile hervorgehoben; korrekte relative Tageslabels |
| `waste-collection.component.spec.ts` | Settings werden geladen und angezeigt; Speichern sendet das DTO; Fehler beim Speichern setzt `saveError`; „Jetzt abrufen" lädt Status und Termine neu |
| `WasteCollectionControllerTest` | `PUT` weist ungültige `icsUrl`, `lookaheadDays < 1` und unparsebare `reminderTime` mit 400 ab; `last_announced_date` bleibt unangetastet; `upcoming` ohne `days` nutzt `lookahead_days` |

Hinweis zum Ausführen: Für den Backend-Build muss `JAVA_HOME` auf das JDK 21 zeigen.

## Nicht enthalten (YAGNI)

- Schreibzugriff auf den Kalender
- Mehrere Kalenderquellen
- Push-/Mobile-Benachrichtigungen
- Historien-Auswertung („wie oft wurde die Biotonne geleert")
- Manuelles Anlegen oder Bearbeiten einzelner Termine auf der Einstellungsseite — die
  Termine sind eine Spiegelung des Kalenders, die Quelle der Wahrheit bleibt Google
