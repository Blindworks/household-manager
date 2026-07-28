# Spaziergänge-Dialog auf der Hund-Kachel (Tractive)

**Datum:** 2026-07-28
**Status:** Vom Nutzer freigegeben

## Ziel

Ein Klick auf die Hund-Status-Kachel im Dashboard-Footer öffnet einen Dialog mit den
letzten Spaziergängen des Hundes: pro Eintrag Datum, Start-/Endzeit, Dauer und grobe
Distanz.

## Datenlage (Rechercheergebnis)

Die von der Tractive-App aufgezeichneten Gassirunden (Walk-Feature) sind über keinen
bekannten API-Endpunkt abrufbar — weder `aiotractive` noch `FAXES/tractive` noch
`unofficial-tractive-rest-api` kennen einen Walks-Endpunkt; das Feature lebt nur in der
Mobil-App. Verifiziert in zwei unabhängigen Bibliotheken ist dagegen die
**Positionshistorie**: `GET /tracker/{trackerId}/positions?time_from&time_to&format=json_segments`
(Epoch-Sekunden).

**Entscheidung (Nutzer, 2026-07-28):** Spaziergänge werden beim Öffnen des Dialogs
on-the-fly aus der Positionshistorie abgeleitet (Heuristik). Keine neue DB-Tabelle,
rückwirkende Daten möglich. Bewusst akzeptierte Unschärfe: jede Abwesenheit zählt
(auch eine Autofahrt), und im Stromsparmodus meldet der Tracker selten Positionen —
kurze Runden können verschluckt werden.

## Backend

### 1. `TractiveApiClient.getPositions(trackerId, from, to)`

Neuer Aufruf `GET /tracker/{trackerId}/positions?time_from=<epoch s>&time_to=<epoch s>&format=json_segments`.
Die Antwortform ist (wie bei den Geofences) nicht vollständig gegen `aiotractive`
verifizierbar → defensives Parsen: Punkte ohne bzw. mit nicht-endlichen Koordinaten
oder ohne Zeitstempel werden verworfen (`Double.isFinite`-Pflicht an der API-Grenze);
ein fehlgeschlagener Abruf wird als Fehler gemeldet, nie geraten.

### 2. `TractiveWalkService` (Heuristik, eigene testbare Klasse)

- Ein Punkt ist „unterwegs“, wenn seine Distanz zum Zuhause > `home-radius-meters`
  ist. Zuhause-Definition wird **wiederverwendet** (`TractiveHomeSettingsService` +
  `GeoZone.distanceMeters`) — keine zweite Definition von „zu Hause“.
- Aufeinanderfolgende Unterwegs-Punkte werden zu einem Spaziergang gruppiert;
  Lücken < 10 Minuten werden überbrückt, Spaziergänge < 5 Minuten verworfen
  (GPS-Jitter am Radiusrand). Beide Schwellen als Konstanten im Service.
- Pro Spaziergang: Start (`Instant`), Ende, Dauer, grobe Distanz in Metern
  (Summe der Haversine-Abstände aufeinanderfolgender Punkte).
- Ohne konfiguriertes Zuhause: eindeutiger Fehler („Kein Zuhause konfiguriert“),
  keine leere Liste — der Dialog verweist dann auf Admin → Hundetracker-Zuhause.

### 3. Endpunkt `GET /v1/tractive/pets/{trackerId}/walks?days=7`

- `days` Default 7, Maximum 14 (Schutz vor teuren Cloud-Abfragen).
- Fällt unter die generische `GET /v1/**`-Regel (KIOSK liest — wie die Position
  selbst). Keine neue Security-Regel, `SecurityRulesTest` bleibt unberührt.
- Ergebnis wird 5 Minuten pro (Tracker, days) im Speicher gecacht.
- Cloud-Ausfall → Fehlerantwort, die das Frontend als Meldung anzeigt.

## Frontend

### 4. Hund-Kachel klickbar

`dashboard.component.html` (Kachel ab ca. Zeile 298): Muster der bestehenden
klickbaren Karte (`role="button"`, `tabindex="0"`, `(click)`, `(keydown.enter)`,
`(keydown.space)`) + `--clickable`-Hover-Optik in `dashboard.component.scss`
(lumina-Styles bleiben in der Dashboard-Komponente gekapselt — kein Kind-Component).

### 5. Spaziergänge-Dialog

Inline im Dashboard-Template nach dem Muster des Verbraucher-Verlaufs-Dialogs
(Backdrop mit `*ngIf`, `role="dialog" aria-modal="true"`, Schließen über Backdrop,
X-Button und den zentralen `onEscape`):

- Liste der Spaziergänge der letzten 7 Tage, gruppiert nach Tag; pro Eintrag
  Startzeit–Endzeit, Dauer, Distanz.
- Bei mehreren Hunden ein Abschnitt pro Hund (Abfrage pro Tracker).
- Zustände: Laden / Fehler (inkl. „Kein Zuhause konfiguriert“) / „Keine
  Spaziergänge gefunden“.
- Race-Schutz wie beim Verlaufs-Dialog: verspätete Antworten eines bereits
  geschlossenen/neu geöffneten Dialogs werden verworfen.

## Tests

- JUnit für die Heuristik: Gruppierung, Lücken-Überbrückung, Mindestdauer,
  leere/degenerierte Eingaben, defensives Parsen unplausibler Punkte.
- Kein neuer Security-Test nötig (generische GET-Regel).

## Bewusste Grenzen

- „Spaziergang“ = Abwesenheit vom Home-Radius; Autofahrten u. ä. werden mitgezählt.
- Datenqualität hängt am Meldeintervall des Trackers (Stromsparmodus).
- Der Positions-Endpunkt ist nur gegen Fremdbibliotheken verifiziert, nicht gegen
  einen echten Account (wie die Geofences) — daher defensives Parsen.
