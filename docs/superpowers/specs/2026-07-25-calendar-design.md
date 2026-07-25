# Haushaltskalender — Design

**Datum:** 2026-07-25
**Status:** Freigegeben (Brainstorming mit Benedikt, abschnittsweise bestätigt)

## Ziel

Ein im Household-Manager selbst pflegbarer Kalender (eigene Seite mit Monatsansicht),
dessen nächste Termine im Intelligence Hub des Dashboards erscheinen und der über
Entity-Events an die Flow-Engine angebunden ist (Alexa/Telegram-Erinnerungen per Flow).

## Entscheidungen aus dem Brainstorming

| Frage | Entscheidung |
|---|---|
| Datenquelle | Eigener Kalender in der App (DB + CRUD), **kein** ICS-Abo |
| Wiederholungen | Volle RRULE-Unterstützung |
| Terminarten | Ganztägig **und** mit Uhrzeit (Ende optional) |
| Pflege-UI | Monatsansicht + Termindialog |
| Hub-Anzeige | Bis zu 3 eigene Einträge (einzelne Insights) |
| Erinnerungen | Anzeige + Flow-Anbindung (Entity-Events; kein Direktversand) |
| Kategorien | Feste, im Code definierte Liste mit fester Farbe |
| Architektur | Serientermin + On-the-fly-Expansion (keine Materialisierung) |

**Zeitzonen bewusst weggelassen:** Alle Zeiten sind lokale Haushaltszeit
(Europe/Berlin), wie überall sonst im Projekt. Kein TZID-Handling.

## Datenmodell

Neue Tabelle `calendar_event` (ein Liquibase-Changeset). Eine Zeile pro Termin
bzw. Serie; Ausnahmen über EXDATE und Override-Zeilen (iCal-Standardweg):

| Spalte | Typ | Bedeutung |
|---|---|---|
| `id` | bigint PK | — |
| `title` | varchar(200), not null | Terminname |
| `notes` | text, nullable | Freitext |
| `category` | varchar(30), not null | Enum: `GENERAL`, `FAMILY`, `HEALTH`, `HOUSEHOLD`, `WORK`, `BIRTHDAY` |
| `all_day` | boolean, not null | ganztägig vs. Uhrzeit-Termin |
| `start_date` | date, not null | erster Termintag |
| `start_time` | time, nullable | nur bei Uhrzeit-Terminen |
| `end_time` | time, nullable | optionales Ende (Uhrzeit-Termine) |
| `end_date` | date, nullable | mehrtägige ganztägige Termine |
| `rrule` | varchar(500), nullable | iCal-RRULE; `null` = Einzeltermin |
| `exdates` | text, nullable | JSON-Array ausgenommener Vorkommen-Daten (ISO-Datum) |
| `recurring_parent_id` | bigint FK → `calendar_event.id`, nullable | gesetzt bei Override-Zeilen |
| `recurrence_date` | date, nullable | welches Serien-Vorkommen der Override ersetzt |
| `created_at` / `updated_at` | datetime | Bestandspflege |

Semantik:

- **Einzelnes Vorkommen löschen** → Datum wird in `exdates` der Serie eingetragen.
- **Einzelnes Vorkommen ändern** → Override-Zeile (Kopie mit `recurring_parent_id`
  + `recurrence_date`); bei der Expansion ersetzt sie das berechnete Vorkommen.
- **Serie löschen** → Overrides kaskadieren mit (FK `ON DELETE CASCADE`).

## RRULE-Expansion

- Neue Abhängigkeit **`org.dmfs:lib-recur`** (kleine, reine RRULE-Iterationsbibliothek;
  bewusst kein ical4j-Schwergewicht).
- Alle Bibliotheksspezifika leben ausschließlich in **`RecurrenceExpansionService`**
  (Projektmuster „brittle Fremd-API in eine Klasse sperren", vgl. `blink_client.py`).
- Validiert RRULEs beim Speichern (ungültig → 400 mit deutscher Meldung).
- Expandiert beim Abfragen mit Sicherheitskappe: Fenster ≤ 1 Jahr, ≤ 1000 Vorkommen
  pro Abfrage — eine pathologische Regel kann weder API noch Scheduler festfahren.

## Backend-Architektur

Schichten wie im Projekt üblich, Package `com.household.manager.calendar`
(Repository wegen JpaConfig-Scanning in `com.household.manager.repository`):

- `CalendarEventController` — REST, Basis `/v1/calendar`
- `CalendarEventService` — CRUD + Occurrence-Auflösung (nutzt Expansion)
- `RecurrenceExpansionService` — RRULE-Validierung und -Expansion (einzige lib-recur-Stelle)
- `CalendarReminderScheduler` — feuert Entity-Events (siehe Flow-Anbindung)
- `CalendarEventRepository` — JPA (in `com.household.manager.repository`)
- DTOs trennen API-Vertrag von der Entity (`CalendarEventRequest`,
  `CalendarEventResponse`, `CalendarOccurrenceResponse`)

### REST-API

| Endpoint | Zweck |
|---|---|
| `GET /v1/calendar/events?from=…&to=…` | Expandierte Vorkommen im Zeitraum (Serien aufgelöst, Overrides eingerechnet, EXDATEs gefiltert). Felder je Vorkommen: `eventId`, `occurrenceDate`, `title`, `category`, `allDay`, `startTime`, `endTime`, `endDate`, `recurring` |
| `GET /v1/calendar/upcoming?limit=3` | Nächste N Vorkommen ab jetzt, inkl. `daysUntil` (Hub) |
| `POST /v1/calendar/events` | Anlegen (RRULE-Validierung) |
| `PUT /v1/calendar/events/{id}` | Serie/Einzeltermin ändern |
| `DELETE /v1/calendar/events/{id}` | Termin/Serie löschen (Overrides kaskadieren) |
| `DELETE /v1/calendar/events/{id}/occurrences/{date}` | Nur dieses Vorkommen löschen (EXDATE) |
| `PUT /v1/calendar/events/{id}/occurrences/{date}` | Nur dieses Vorkommen ändern (Override) |

## Flow-Anbindung

Muster der Vision-Events (`EntityStateService.reportEvent` → `EntityEventFired`
→ Flow-Engine; der bestehende `entity-event-trigger`-Node funktioniert ohne
neuen Node-Typ):

- EVENT-Entität **`event.calendar_reminder`**.
- `CalendarReminderScheduler` (`@Scheduled`, minütlich):
  - Uhrzeit-Vorkommen → Event **zum Startzeitpunkt**.
  - Ganztägige Vorkommen → Event morgens um **08:00** (Konstante, keine Settings-UI in v1).
- `action` = Kategorie kleingeschrieben (z. B. `health`) — darüber filtern Flows.
  Attribute: `title`, `date`, `time`, `allDay`, `eventId`.
- Doppelzündungsschutz: Scheduler merkt sich das zuletzt verarbeitete Minutenfenster
  **in-memory**. Nach einem Neustart werden verpasste Minuten bewusst nicht
  nachgefeuert — ein verspäteter Reminder wäre irreführender als keiner.

## Frontend

### Kalenderseite (`pages/calendar/`, Route `calendar`, Link in der Navigation)

- **Monatsraster selbstgebaut** (kein npm-Paket — überschaubare Datums-Arithmetik,
  konsistent mit den handgebauten Lumina-Seiten). Kopfzeile mit Monat/Jahr,
  ‹ › Navigation, „Heute"-Button. Tageszelle: bis zu 3 Termin-Chips in
  Kategoriefarbe, darüber hinaus „+n weitere".
- **Termindialog** (Klick auf Tag = Anlegen, Klick auf Chip = Bearbeiten):
  Titel, Kategorie, Ganztägig-Toggle, Datum/Zeiten, Notizen, Wiederholungs-Builder.
- **Wiederholungs-Builder:** Keine / Täglich / Wöchentlich (Wochentags-Checkboxen) /
  Monatlich (am Tag X oder am n-ten Wochentag) / Jährlich — je mit Intervall
  („alle 2 Wochen") und Ende (nie / bis Datum / nach N Malen). Der Builder erzeugt
  die RRULE. **„Erweitert"-Modus** mit rohem RRULE-Textfeld für Exoten
  (Validierung macht das Backend). So ist volle RRULE-Mächtigkeit erreichbar,
  ohne dass die UI jeden Fall abbildet.
- Bearbeiten/Löschen eines Serien-Vorkommens fragt: **„Nur diesen Termin"** oder
  **„Ganze Serie"** — mappt direkt auf die Occurrence-Endpoints.
- `CalendarService` (Angular) kapselt die API; Models in
  `models/calendar-event.model.ts`. Kategoriefarben als Konstante im Frontend.

### Intelligence Hub

- Neues Util `shared/calendar-insight.util.ts` (analog `waste-insight.util.ts`):
  baut aus den `upcoming`-Vorkommen **bis zu 3 einzelne `IntelligenceItem`s** —
  Titel = Terminname, Text = „Heute 14:30" / „Morgen" / „Mittwoch, 14:30",
  Icon `event`, Tonfarbe nach den Müll-Schwellen (heute/morgen rot, übermorgen
  gelb, sonst blau).
- Das Dashboard pollt `getUpcoming(3)` im selben Rhythmus wie den Müllkalender
  und komponiert: Müll-Insight → Kalender-Insights → Platzhalter.
- Wegen der Lumina-Style-Kapselung rendert das Dashboard die Einträge selbst
  (kein Kind-Komponenten-Rendering).

## Fehlerbehandlung

- Ungültige RRULE, Ende vor Start, leerer Titel → 400 mit deutscher Fehlermeldung
  (Stil `WasteCollectionController.validate`); der Dialog zeigt die Meldung an.
- Expansion hart gekappt (s. o.).
- Hub-Polling mit `catchError` (wie Müll); Scheduler mit try/catch nach dem
  Hook-Muster des Entity-Layers (ein Kalenderfehler darf nie anderes Polling reißen).

## Tests

**Backend:**
- `RecurrenceExpansionService` — Kernstück: RRULE-Fälle (täglich/wöchentlich/
  monatlich/jährlich, Intervalle, COUNT/UNTIL, „jeder 2. Dienstag"), EXDATE,
  Overrides, Kappung, ungültige Regeln.
- `CalendarEventService` — CRUD, Occurrence-Semantik (löschen/ändern einzelner
  Vorkommen), Zeitraumabfragen.
- `CalendarReminderScheduler` — feuert genau einmal pro Vorkommen, Ganztages-08:00-Regel.

**Frontend:**
- `calendar-insight.util.spec.ts` (analog Waste-Util-Tests).
- Monatsgrid-Datumslogik (Monatsgrenzen, Wochenbeginn Montag).
- RRULE-Builder (Auswahl → erzeugte RRULE-Strings).

## Bewusst NICHT im Scope (v1)

- ICS-Import/-Abo externer Kalender
- Wochenansicht mit Stundenraster
- Pro-Termin konfigurierbare Erinnerungszeiten oder Direktversand (Alexa/Telegram
  laufen über Flows)
- Frei pflegbare Kategorien
- Zeitzonen-Unterstützung
