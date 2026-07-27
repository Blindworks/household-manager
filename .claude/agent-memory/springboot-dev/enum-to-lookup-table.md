---
name: enum-to-lookup-table
description: Muster fuer die Migration einer @Enumerated-Spalte zu einer Stammdatentabelle in diesem Projekt (ddl-auto=validate, Flow-Vertrag ueber Schluessel)
metadata:
  type: project
---

Wenn ein festes Java-Enum zu gepflegten Stammdaten wird (erstmals gemacht fuer
`CalendarCategory` am 2026-07-27, Branch `feature/calendar-persons-categories`):

**Migration und Entity-Aenderung gehoeren in EINEN Commit.**
**Why:** `spring.jpa.hibernate.ddl-auto=validate` prueft beim Start, ob die Entities
exakt zum Schema passen. Ein Commit mit nur der Migration (oder nur der Entity) ist
nicht startbar — der Branch waere an dieser Stelle nicht bisectierbar.

**Der Seed vergibt als Schluessel exakt die alten Enum-Namen kleingeschrieben.**
**Why:** Enum-Namen sickern in externe Vertraege. Hier schrieb
`CalendarReminderScheduler` `enum.name().toLowerCase()` als State von
`event.calendar_reminder`; deployte Flows filtern darauf. Ein anders benannter
Schluessel haette Flows still wirkungslos gemacht.
**How to apply:** Vor der Migration grepen, wo der Enum-Name in einen String
konvertiert wird (`.name()`, `toString()`, Jackson-Serialisierung nach aussen).

**Beziehung als nackte Id, nie als `@ManyToOne`** — das Projekt hat keine einzige
JPA-Relation.

**Antworten tragen die Kategorie eingebettet** (`CalendarCategoryView`-Record), nicht
nur die Id: das Frontend rendert ohne Nachschlagen, und ein Termin mit inzwischen
deaktivierter Kategorie behaelt seine Farbe. Im Service einmal
`categoryRepository.findAll()` in eine Map ziehen statt pro Termin nachzuschlagen.

**Datenkonvertierung fail-loud:** `UPDATE ... SET fk = (SELECT id ... WHERE key = LOWER(alt))`
gefolgt von `addNotNullConstraint`. Bleibt eine Zeile ohne Treffer, bricht Liquibase
den Start ab — gewollt, statt still auf einen Default zu reparieren.

**Ein fail-loud-Schritt zwingt zur Aufteilung in mehrere Changesets — aber die
Trennlinie ist WIEDERHOLBARKEIT, nicht "ein Schritt pro Changeset".**
MariaDB/MySQL committen jedes DDL implizit; ein Changeset mit mehreren DDL-Schritten
laesst sich nach einem Fehler in der Mitte nicht zurueckrollen. Gebuendelt bliebe die
neue Spalte stehen, ohne dass das Changeset in `DATABASECHANGELOG` landet: der naechste
Start scheitert an "Duplicate column name" — auch nach korrigierten Daten, also dauerhaft
ohne manuelles `ALTER TABLE`.

Richtige Aufteilung (hier `-b1`, `-b3`, `-b4`):
- eigenes Changeset fuer alles, was bei einer **Wiederholung scheitert**: `addColumn`, `dropColumn`
- **zusammen** in das abbrechende Changeset: `UPDATE` + `addNotNullConstraint` + `addForeignKeyConstraint` (bei Wiederholung alle unschaedlich)

**Warum das UPDATE nicht allein davor stehen darf** (Review-Befund 2026-07-27, erst in
der zweiten Runde gefunden): Als eigenes Changeset waere es nach dem Abbruch als gelaufen
vermerkt. Die naheliegende Reparatur — den unbekannten Wert in der ALTEN Spalte
berichtigen — bliebe dann wirkungslos, die FK-Spalte bliebe NULL und der Start scheiterte
endlos erneut. Durchkaeme nur, wer die FK-Spalte direkt setzt.
**Merksatz:** Nicht nur fragen "bleibt etwas Halbfertiges stehen?", sondern
"laeuft die *naheliegende* Reparatur danach wirklich durch?"

Siehe auch [[liquibase-changeset-id-planning]] (freie Changeset-Id immer am
Verzeichnis pruefen, nicht aus dem Plan uebernehmen).
