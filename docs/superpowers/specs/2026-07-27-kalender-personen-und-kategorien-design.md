# Kalender: Personen-Zuordnung und konfigurierbare Kategorien

**Datum:** 2026-07-27
**Status:** Design abgestimmt, Umsetzung offen

## Ziel

Der Haushaltskalender bekommt zwei Erweiterungen:

1. **Personen-Zuordnung** — ein Termin kann einer oder mehreren Personen gehören, oder
   niemandem (dann betrifft er den ganzen Haushalt).
2. **Konfigurierbare Kategorien** — die heute im Java-Enum festgeschriebene Kategorienliste
   wird zu gepflegten Stammdaten mit Name, Farbe, Icon, Reihenfolge und Aktiv-Flag,
   verwaltet auf einer eigenen Admin-Seite.

## Ausgangslage

- Termine liegen in `calendar_events`; Serien werden on-the-fly expandiert
  (`RecurrenceExpansionService`), Einzelausnahmen sind EXDATEs bzw. Override-Zeilen.
- Die Kategorie ist ein festes Enum `CalendarCategory` (`GENERAL`, `FAMILY`, `HEALTH`,
  `HOUSEHOLD`, `WORK`, `BIRTHDAY`); Labels und Farben stehen hart im Frontend
  (`CATEGORY_META` in `models/calendar-event.model.ts`).
- `CalendarReminderScheduler` schreibt den **kleingeschriebenen Enum-Namen** als State des
  Events `event.calendar_reminder`. Flows filtern darauf — die Kategorie ist damit Teil
  eines öffentlichen Vertrags.
- Nutzer existieren seit dem Usermanagement als `app_user` (`displayName`, `enabled`).
  Nutzer werden **nie gelöscht**, nur deaktiviert.
- Das Projekt verwendet **keine einzige JPA-Relation**; Beziehungen laufen konsequent über
  nackte Id-Spalten (`recurringParentId`, `Category.parentId`).

## Entscheidungen

| Frage | Entscheidung | Begründung |
|---|---|---|
| Was ist eine „Person"? | Die vorhandenen `app_user`-Zeilen | Keine zweite Stammdatenpflege; Anzeige über `displayName` |
| Wie viele pro Termin? | Beliebig viele, optional | Keine Zuordnung = Haushaltstermin; Bestandstermine bleiben ohne Migration gültig |
| Steuert die Zuordnung Sichtbarkeit? | Nein, rein informativ + Filter | Das KIOSK-Wandtablet soll den kompletten Haushaltskalender zeigen |
| Kategoriefelder | Name, Farbe, Icon, Reihenfolge, Aktiv-Flag | |
| Flow-Bezug auf Kategorien | Stabiler, unveränderlicher Schlüssel | Umbenennen darf keinen Flow brechen (Lehre aus den Vision-Personen) |
| Kategorie löschen, die genutzt wird | Blockieren (409), Deaktivieren anbieten | Kein stiller Datenverlust, keine Sackgasse im UI |
| Ort der Verwaltung | Eigene Admin-Seite `/admin/calendar-categories` | Muster der übrigen Verwaltungsseiten; `/admin` ist bereits eine große Sammelkomponente |
| Modellierung der Kategorie | Eigene Tabelle mit Fremdschlüssel | Nur so ist der Löschschutz datenbankseitig belastbar |

### Verworfene Alternativen

- **Eigene Personen-Stammdaten** (Tabelle für Bewohner ohne Login): zusätzliche Pflege ohne
  aktuellen Bedarf.
- **Enum bleibt, nur Anzeige-Overrides in einer Tabelle**: keine Migration nötig, aber neue
  Kategorien anzulegen bliebe unmöglich — das ist der Kern der Anforderung.
- **Katalogtabelle ohne Fremdschlüssel** (`category` bleibt Text): kein Datenbank-Constraint
  gegen verwaiste Schlüssel, Löschschutz allein in Java.

## Datenmodell

### Neue Tabelle `calendar_category`

| Spalte | Typ | Bemerkung |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | |
| `cat_key` | VARCHAR(50) NOT NULL UNIQUE | stabil, nach dem Anlegen unveränderlich |
| `name` | VARCHAR(100) NOT NULL | frei änderbar |
| `color` | VARCHAR(7) NOT NULL | Hex, z. B. `#64b5f6` |
| `icon` | VARCHAR(50) NULL | Material-Symbol-Name (`app-icon-picker`) |
| `sort_order` | INT NOT NULL | Reihenfolge der Auswahlliste |
| `active` | BOOLEAN NOT NULL DEFAULT TRUE | false = nicht mehr wählbar |
| `created_at` / `updated_at` | DATETIME NOT NULL | |

**Seed** in derselben Changeset-Datei, mit exakt den Schlüsseln, die der Scheduler heute
schreibt:

| `cat_key` | `name` | `color` | `sort_order` |
|---|---|---|---|
| `general` | Allgemein | `#64b5f6` | 1 |
| `family` | Familie | `#ba68c8` | 2 |
| `health` | Gesundheit | `#e57373` | 3 |
| `household` | Haushalt | `#81c784` | 4 |
| `work` | Arbeit | `#ffb74d` | 5 |
| `birthday` | Geburtstag | `#f06292` | 6 |

Icons bleiben beim Seed leer; das Icon ist optional.

### Umbau `calendar_events`

1. Spalte `category_id BIGINT NULL` anlegen.
2. `UPDATE calendar_events SET category_id = (SELECT id FROM calendar_category WHERE cat_key = LOWER(category))`.
3. `category_id` auf `NOT NULL` setzen.
4. Fremdschlüssel auf `calendar_category(id)` mit `ON DELETE RESTRICT`.
5. Alte Spalte `category` droppen.

Bleibt in Schritt 2 eine Zeile ohne Treffer, schlägt Schritt 3 fehl und Liquibase bricht den
Start ab. Das ist gewollt — lauter Abbruch statt stiller Reparatur auf „Allgemein".

### Neue Tabelle `calendar_event_person`

| Spalte | Typ | Bemerkung |
|---|---|---|
| `calendar_event_id` | BIGINT NOT NULL | FK → `calendar_events(id)` `ON DELETE CASCADE` |
| `user_id` | BIGINT NOT NULL | FK → `app_user(id)` `ON DELETE CASCADE` |

Primärschlüssel ist das Paar. Keine Zeile für einen Termin = Haushaltstermin.

### Umsetzung in Java

`CalendarEvent` bekommt ein `Long categoryId` (kein `@ManyToOne`), die Zuordnungen liegen in
einer eigenen Entity `CalendarEventPerson` mit eigenem Repository. Das hält die Ladewege
explizit: `getOccurrences` lädt bewusst alle Terminzeilen, ein Lazy-Relationsgeflecht wäre
dort ein unsichtbares N+1-Risiko.

Beide Repositories müssen in `com.household.manager.repository` liegen — `JpaConfig`
beschränkt das Scanning auf dieses Paket.

## Backend

### Neue Bausteine

- `model/entity/CalendarCategory.java` — ersetzt das gleichnamige Enum (Name wird wiederverwendet)
- `model/entity/CalendarEventPerson.java`
- `repository/CalendarCategoryRepository.java`, `repository/CalendarEventPersonRepository.java`
- `calendar/CalendarCategoryService.java` — CRUD, Schlüsselerzeugung, Löschschutz, Audit
- `calendar/CalendarCategoryController.java` — `/v1/calendar/categories`

### Schlüsselerzeugung

Nur beim Anlegen, **nie** beim Ändern:

1. Name kleingeschrieben
2. Umlaute transliteriert: `ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`
3. Verbleibende Nicht-`[a-z0-9]`-Folgen zu je einem `_` zusammengefasst, führende und
   abschließende `_` entfernt
4. Auf 50 Zeichen gekürzt
5. Leeres Ergebnis (z. B. Name nur aus Emoji) → `kategorie`
6. Kollision → Suffix `_2`, `_3`, …

Der Schlüssel wird im Admin schreibgeschützt angezeigt: er ist der Wert, auf den ein Flow
filtert.

### API

| Endpunkt | Rolle | Bemerkung |
|---|---|---|
| `GET /v1/calendar/categories` | angemeldet (inkl. KIOSK) | Liste inkl. `active`-Flag, sortiert nach `sortOrder` |
| `POST /v1/calendar/categories` | ADMIN | erzeugt den Schlüssel |
| `PUT /v1/calendar/categories/{id}` | ADMIN | Schlüssel bleibt unverändert |
| `DELETE /v1/calendar/categories/{id}` | ADMIN | 409 mit Anzahl, falls genutzt |
| `GET /v1/users` | angemeldet | **neu**, `{id, displayName, enabled}` |

`GET /v1/users` ist nötig, weil `/v1/admin/users` ADMIN-only ist — ohne den Endpunkt könnte
ein MEMBER im Termindialog keine Person auswählen. Ausgeliefert werden ausschließlich Id,
Anzeigename und Aktiv-Flag; keine Rolle, kein Benutzername.

**Zusätzlich nötig: die eigene Id kennen.** Der Filter „Meine" muss den angemeldeten Nutzer
in der Personenliste wiederfinden. Heute liefert weder `AppUserPrincipal` noch
`CurrentUserResponse` (`/v1/auth/me`) eine Id — nur `username`, `displayName`, `role`,
`mustChangePassword`. Deshalb bekommt `AppUserPrincipal` ein `Long id` aus dem `AppUser` und
`CurrentUserResponse` ein zusätzliches Feld `id`.

Bei einer Anmeldung per Service-Token ist der Principal kein `AppUserPrincipal`; dort bleibt
`id` `null`, genau wie `displayName` heute schon auf den Token-Namen zurückfällt. Das
Frontend blendet den Filter „Meine" in diesem Fall aus, statt eine leere Auswahl anzuzeigen.
Die Alternative — den Benutzernamen mit ausliefern und darüber vergleichen — wurde verworfen:
sie gäbe dem Wandtablet die Benutzernamen aller Bewohner ohne Gegenwert.

**Matcher-Reihenfolge in `SecurityConfig`:** Die Kategorie-Regeln müssen *vor* der
bestehenden generischen `/v1/calendar/**`-Regel stehen, sonst greift diese zuerst.
`SecurityRulesTest` hält das fest.

### DTO-Änderungen

- `CalendarEventRequest`: `category` (Enum) → `categoryId: Long`; neu `personUserIds: List<Long>`
- `CalendarEventResponse` und `CalendarOccurrenceResponse`: eingebettetes
  `category {id, key, name, color, icon}` sowie `persons: [{id, displayName}]`

Eingebettet statt nur der Id, damit das Monatsraster ohne Nachschlagen rendert — und damit
ein Termin mit inzwischen deaktivierter Kategorie weiterhin in seiner Farbe erscheint.

### Laden ohne N+1

`getOccurrences` lädt zusätzlich **einmal** alle Kategorien und **einmal** alle
Personenzuordnungen in je eine Map. Zwei zusätzliche Abfragen pro Fensterabruf, unabhängig
von der Terminanzahl — dieselbe Haushaltsgrößen-Annahme, unter der `findAll()` dort bereits
steht.

### Validierung

- `categoryId` muss existieren, sonst 400.
- Unbekannte `personUserIds` ergeben 400; Duplikate werden entfernt.
- Eine **inaktive** Kategorie bleibt über die API setzbar. Das Deaktivieren ist ein
  UI-Schutz, kein API-Vertrag — dasselbe Muster wie `confirm_required` bei den Schaltern.
  Andernfalls schlüge jede Änderung an einem alten Termin unerwartet fehl.
- Override-Zeilen übernehmen die Personen des Requests (der Dialog sendet sie ohnehin mit).

### Audit

`calendar-category.create`, `calendar-category.update`, `calendar-category.delete` über den
bestehenden `AuditService`.

## Flow-Anbindung

`CalendarReminderScheduler` schreibt künftig `cat_key` statt des kleingeschriebenen
Enum-Namens in den State von `event.calendar_reminder`. Für die sechs migrierten Kategorien
ist das derselbe String wie heute — **bestehende Flows bleiben unverändert lauffähig.**

Das Event bekommt zwei neue Attribute:

- `personIds` — stabile Nutzer-Ids, zum Filtern in Flows
- `persons` — Anzeigenamen, für Telegram- und Alexa-Formulierungen („Termin für Anna")

Beides zusammen, weil ein umbenannter Anzeigename sonst still einen Filter bricht — genau
die Falle, die bei den Vision-Personen bereits einmal zugeschlagen hat.

**Dokumentierte Restkopplung:** Wird eine Kategorie *gelöscht* (nur möglich, solange sie
unbenutzt ist), läuft ein Flow, der auf ihren Schlüssel filtert, danach still ins Leere.
Umbenennen und Deaktivieren sind dagegen gefahrlos. Das gehört in `CLAUDE.md`, nicht in eine
Laufzeitprüfung — Flow-Configs liegen als JSON in einer anderen Domäne.

## Frontend

### Modelle und Services

- neu `models/calendar-category.model.ts`
- in `models/calendar-event.model.ts` entfallen der Union-Typ `CalendarCategory` und
  `CATEGORY_META` ersatzlos
- neu `services/calendar-category.service.ts`
- neu `services/household-user.service.ts` für `GET /v1/users`

### Kalenderseite

**Termindialog:** Kategorie-Auswahl aus den aktiven Kategorien, ergänzt um die aktuell
gesetzte (falls die inzwischen deaktiviert wurde — sonst würde das Speichern still
umkategorisieren). Darunter die Personen als anklickbare Chips aus den aktiven Nutzern;
nichts ausgewählt wird als „Ganzer Haushalt" beschriftet.

**Monatsraster:** Chip-Farbe und -Icon kommen aus dem eingebetteten Kategorie-Objekt des
Vorkommens. Zugeordnete Personen erscheinen als Initialen am Chip — bei der bestehenden
Zellbreite und `DAY_CHIP_LIMIT = 3` die einzige Darstellung, die nicht überläuft; die vollen
Namen stehen in der Detailansicht.

**Filterleiste** über dem Raster: „Alle" (Vorgabe), „Meine", je eine aktive Person. Rein
clientseitig auf den bereits geladenen Vorkommen, kein zusätzlicher Abruf. „Meine" vergleicht
gegen die `id` aus `/v1/auth/me` und wird ausgeblendet, wenn dort keine Id steht. Der
Filterzustand wird nicht gespeichert und steht nach jedem Laden wieder auf „Alle".

> **Ein Personenfilter zeigt zusätzlich immer die Termine ohne Zuordnung.** „Meine Termine"
> bedeutet „mir zugeordnet **oder** den ganzen Haushalt betreffend". Sonst verschwände die
> Müllabfuhr genau dann aus dem Blick, wenn jemand auf sich selbst filtert — der Fall, in dem
> sie am ehesten übersehen wird.

**Fehlerbehandlung:** Schlägt der Kategorien-Abruf fehl, zeigt die Seite ein Fehlerbanner und
öffnet den Termindialog gar nicht. Ein Formular mit leerer Auswahlliste sieht aus wie „es
gibt keine Kategorien" und verleitet zu falschen Eingaben (dieselbe Lehre wie beim
Tractive-Admin, Commit ef1aed2).

### Admin-Seite

`pages/admin-calendar-categories/`, Route `/admin/calendar-categories` mit `adminGuard`,
Menüeintrag „Kalender-Kategorien" im Header unter „Admin" — dasselbe Muster wie Nutzer,
API-Tokens und Audit-Log.

Tabelle sortiert nach `sortOrder`: Icon, Name, Farbfeld, Schlüssel (schreibgeschützt, in
Monospace), Reihenfolge, Aktiv-Schalter, Aktionen. Anlegen und Bearbeiten über ein
Inline-Formular mit dem vorhandenen `app-icon-picker` und einem nativen Farbwähler.

Beim Löschen einer genutzten Kategorie antwortet die API mit `409` und der Anzahl betroffener
Termine; die Seite zeigt „Wird von 4 Terminen genutzt" und bietet direkt das Deaktivieren als
Alternative an.

### Bewusst außen vor

Der Intelligence Hub auf dem Dashboard zeigt weiterhin die nächsten Termine ohne
Personen-Initialen; `calendar-insight.util.ts` rührt die Kategorie ohnehin nicht an, und die
Kachel ist auf knappe Zeilen ausgelegt.

## Tests

**Backend**

- `CalendarCategoryServiceTest`: Schlüsselerzeugung (Umlaute, Sonderzeichen, leerer Rest,
  Kollisionssuffix); Schlüssel bleibt beim Umbenennen stabil; Löschen einer genutzten
  Kategorie ergibt 409 mit Anzahl; Deaktivieren berührt Bestandstermine nicht
- `CalendarEventServiceTest` (erweitern): Personen speichern und lesen; Duplikate entfernt;
  unbekannte Nutzer-Id ergibt 400; Override-Zeile übernimmt die Personen des Requests;
  Löschen eines Termins räumt die Zuordnungen ab
- `CalendarReminderSchedulerTest`: Event-State ist der Schlüssel; `personIds`/`persons` gesetzt
- `SecurityRulesTest` (erweitern): KIOSK darf Kategorien lesen, nicht schreiben;
  `GET /v1/users` nur angemeldet
- `CurrentUserResponse`: `/v1/auth/me` liefert die Id des angemeldeten Nutzers; bei einem
  Service-Token-Principal bleibt sie `null` statt zu werfen

**Frontend**

- `calendar.component.spec`: Filterregel inklusive „Haushaltstermine bleiben sichtbar";
  Dialog mit inzwischen deaktivierter Kategorie
- neu `admin-calendar-categories.component.spec`: Liste; Löschkonflikt mit Deaktivieren-Angebot
- `calendar-category.service.spec`

**Baseline:** Die Frontend-Suite hat drei vorbestehende Fehlschläge (App/Hero) und einen
bekannten Karma-Flake in `SmartDeviceList`. Die zählen nicht als Regression.

## Deployment

Der Umbau ist nicht rückwärtskompatibel: Backend und Frontend müssen gemeinsam deployt
werden, was `docker-compose up --build` ohnehin tut. Nach dem Deployment ist keine manuelle
Nacharbeit nötig — die sechs Bestandskategorien sind sofort vorhanden und alle Termine
zugeordnet.
