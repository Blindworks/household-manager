---
name: mode-quick-access-admin-page
description: Admin-Seite fuer Modus-Zeitfenster (admin/mode-quick-access) — Plantext-Widerspruch zwischen Komponentencode und eigenem Test
metadata:
  type: project
---

`pages/admin-mode-quick-access/` (Task 8 aus `docs/superpowers/plans/2026-09-09-modus-schnellzugriff.md`,
Branch `feature/modus-schnellzugriff`, Commit `2d973c9c`). Folgt 1:1 [[admin-page-crud-pattern]]
(Netzwerk-Geraete-Skelett): Signals `windows`/`modes`/`loading`/`loadFailed`/`saving`/`errorMessage`,
eigenes `WindowFormState`, Voll-PUT beim Aktiv-Toggle, `confirm()` beim Loeschen.

**Korrektur (2026-09-09, eigener Task):** Die urspruengliche Ausloesung dieses Widerspruchs
— `this.loadModes()` zusaetzlich in den `next`-Callbacks von `save()`/`setActive()`/`remove()`
aufzurufen — war die FALSCHE Richtung und wurde wieder entfernt. Der Modus-Katalog
(`HouseModes.CATALOG`) ist backend-seitig **statisch** und dient auf dieser Seite ausschliesslich
dem Dropdown im Formular; `quickAccess` wird hier **nirgends angezeigt** (nur die Dashboard-Karte
zeigt es). Ein neues/geaendertes/geloeschtes Zeitfenster aendert am Katalog nichts — das
Nachladen war ein Request ohne jede Wirkung. Die drei betroffenen Tests wurden entsprechend
angepasst (kein zweiter `MODES_URL`-Request nach einer Mutation mehr erwartet); `ngOnInit` laedt
die Modi weiterhin einmalig. Alle 10 Tests bleiben gruen, `httpMock.verify()` bestaetigt: kein
Test brauchte an anderer Stelle doch noch einen `/v1/modes`-Request.

**Lehre fuer kuenftige Tasks mit vollstaendig abgedrucktem Code:** Komponentencode und Testcode
im selben Plan-Abschnitt koennen trotzdem auseinanderlaufen (Copy-Paste-Fehler beim Verfassen
des Plans). Bei einem Testfehlschlag nach wortgetreuer Umsetzung erst pruefen, ob die
Testerwartung fachlich Sinn ergibt (was liefert der nachgeladene Endpunkt hier ueberhaupt, und
wird das Ergebnis irgendwo verwendet?), bevor man den Code an den Test anpasst — ein Test kann
selbst die falsche Erwartung kodieren.
