---
name: join-table-replace-pattern
description: Zuordnungstabellen mit Verbundschluessel nie "alles loeschen, alles neu schreiben" — als Differenz pflegen, sonst haengt es an Hibernates Flush-Reihenfolge
metadata:
  type: project
---

Beim Neusetzen der Zeilen einer Zuordnungstabelle mit fachlichem Verbundschluessel
(`@IdClass`, z. B. `calendar_event_person`) **nicht** `deleteByParentId(...)` gefolgt von
`saveAll(...)` schreiben, sondern die Differenz bilden: nur wegfallende Zeilen loeschen,
nur neue Zeilen anlegen, unveraenderte stehen lassen.

**Why:** In einer Transaktion denselben Primaerschluessel zu loeschen und sofort wieder
anzulegen ist eine Wette auf Hibernates Flush-Reihenfolge — die ActionQueue fuehrt
Inserts vor Deletes aus, und Spring Datas abgeleitetes `deleteBy...` laedt die Zeilen
zuvor in den Persistence Context (`em.remove`), sodass ein anschliessendes `saveAll`
(bei zugewiesener Id ein `merge`, kein `persist`) auf eine als geloescht markierte
Instanz trifft. Der Fehler tritt genau im haeufigsten Fall auf — Termin umbenennen,
Personen unveraendert — und **kein Unit-Test mit Mock-Repositories kann ihn sehen**.
Im Projekt gibt es keine Integrationstests gegen eine echte DB, die das auffangen wuerden.

**How to apply:** Immer wenn eine Fassade "ersetze die Zuordnungen von X" anbietet.
Ein Test der Form „unveraenderte Zuordnung loest weder deleteAll noch saveAll aus"
haelt die Entscheidung fest (siehe `CalendarEventPersonServiceTest`).
Nebeneffekt: deutlich weniger Schreiblast beim blossen Bearbeiten anderer Felder.

Verwandt: [[liquibase-changeset-id-planning]]
