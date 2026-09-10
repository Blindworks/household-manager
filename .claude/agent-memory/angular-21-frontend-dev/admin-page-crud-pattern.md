---
name: admin-page-crud-pattern
description: Standard-Struktur fuer pflegbare Admin-Listenseiten (Tabelle + Inline-Formular), am Beispiel Kalender-Kategorien und Netzwerk-Geraete
metadata:
  type: project
---

Admin-CRUD-Seiten liegen **nicht** unter `pages/admin/<name>/`, sondern flach unter
`pages/admin-<name>/admin-<name>.component.*` (z. B. `pages/admin-calendar-categories/`,
`pages/admin-network-devices/`). Klasse `Admin<Name>Component`, Selector
`app-admin-<name>`. Vor jeder neuen Admin-Seite per Grep nach einer bestehenden Route
(`admin/xyz`) suchen statt den Task-Text zur Ablage woertlich zu nehmen — der nennt oft
ein plausibles, aber falsches Verzeichnis.

Wiederkehrendes Skelett (Referenz: `admin-calendar-categories.component.ts`, uebernommen
1:1 fuer `admin-network-devices.component.ts`):
- Signals `devices`/`categories`, `loading` (nur beim allerersten Abruf true), `loadFailed`
  (blendet die Tabelle aus, damit sie nicht "leer" vortaeuscht), `saving`, `errorMessage`.
- Eigenes `DeviceFormState`/`CategoryFormState`-Interface, NICHT `extends Request` — jedes
  Zahlenfeld ist im Formular `number | null` (Angulars NumberValueAccessor macht aus einem
  geleerten Feld `null`, nie 0), im Request dagegen `number`. Umwandlung an genau einer
  Stelle (`toRequest`).
- Reihenfolge-Vorschlag (`SORT_ORDER_STEP = 10`, `nextSortOrder()`, `proposeSortOrder()`):
  neue Zeile bekommt automatisch "hinter allen bestehenden", bleibt aber unangetastet,
  sobald der Nutzer selbst etwas eintraegt oder ein Bestandseintrag bearbeitet wird.
- `setActive()` sendet IMMER den kompletten Request (`requestFrom`), nie ein Teil-PUT —
  ein fehlendes `active` liest der Server als "aktiv", ein Teil-PUT reaktivierte sonst
  still.
- `remove()` nutzt den Browser-`confirm()`-Dialog, keinen eigenen Dialog.
- Löschen ohne Backend-FK-Konflikt (z. B. Netzwerk-Geräte) braucht KEIN
  Konflikt-Banner-Muster — das ist nur bei Kalender-Kategorien noetig (409 durch
  referenzierende Termine). Vor dem Uebernehmen des Blocked-Patterns pruefen, ob der
  Service ueberhaupt einen FK-Konflikt werfen kann (Backend-Service lesen).

Fehlerbehandlung zweigleisig, je nach Service: `CalendarCategoryService` wirft eine
eigene `XApiError`-Klasse mit `.status` (fuer die 409-Unterscheidung); `NetworkService`
hat KEIN `catchError` und liefert rohe `HttpErrorResponse` — Consumer lesen direkt
`error.error?.message ?? '<Fallback>'` (siehe `tablet-network.component.ts`). Vor dem
Schreiben einer neuen Seite den tatsaechlichen Service pruefen statt das
Fehlerbehandlungsmuster vom naechstbesten Vorbild zu kopieren.

Test-Falle (aus `admin-calendar-categories.component.spec.ts` uebernommen): `whenStable()`
nach JEDEM `detectChanges()`, das ein `<form>` mit `ngModel` neu befuellt (z. B. nach
"Bearbeiten"-Klick) — NgForm registriert ValueAccessors erst im naechsten Microtask, sonst
liest der Test den alten Formularwert. `httpMock.expectOne(...)`/`expectNone(...)` allein
zaehlt bei Jasmine NICHT als Expectation — "has no expectations"-Warnung vermeiden, indem
zusaetzlich ein echtes `expect(...)` auf die Response/den Request folgt (z. B.
`expect(request.method).toBe('DELETE')`).

Siehe auch [[template-pitfalls]] fuer generelle Template-Fallstricke.
