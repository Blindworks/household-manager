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

**Ein fail-loud-Schritt zwingt zur Aufteilung in mehrere Changesets.**
MariaDB/MySQL committen jedes DDL implizit — ein Changeset mit mehreren DDL-Schritten
laesst sich nach einem Fehler in der Mitte nicht zurueckrollen. Gebuendelt bliebe die
neue Spalte stehen, ohne dass das Changeset in `DATABASECHANGELOG` landet: der naechste
Start scheitert an "Duplicate column name" — auch nach korrigierten Daten, also dauerhaft
ohne manuelles `ALTER TABLE`. Deshalb je ein Changeset fuer addColumn / UPDATE /
NOT-NULL+FK / dropColumn (hier `-b1` bis `-b4`), Rollback-Bloecke entsprechend aufgeteilt.
Beim UPDATE-Changeset ein leeres `<rollback/>` setzen — sonst scheitert ein Rollback an
"no rollback defined" fuer `<sql>`.
**Merksatz:** Sobald ein Changeset absichtlich abbrechen kann, muss jeder Schritt davor
einzeln als gelaufen vermerkbar sein.

Siehe auch [[liquibase-changeset-id-planning]] (freie Changeset-Id immer am
Verzeichnis pruefen, nicht aus dem Plan uebernehmen).
