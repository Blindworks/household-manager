# Tablet-Ansicht „Toni" (Futtervorrat und Spaziergänge)

Datum: 2026-08-22
Branch: `feature/tablet-toni`

## Ziel

Eine weitere Unteransicht des Wandtablets, die alles zum Hund an einer Stelle
zeigt: Futtervorrat, Spaziergänge, Tracker-Status und Position. Vorbild ist
`/tablet/temperatures`: alles gleichzeitig sichtbar, kein Scrollen, aus einigen
Metern Entfernung lesbar.

Die Ansicht ist **rein anzeigend**. Buchungen (Einkauf, Korrektur, Zielbestand)
bleiben der Seite `/pet-food` und dem Dashboard-Dialog vorbehalten — auf dem
Wandtablet läuft die KIOSK-Rolle, Futter-Buchungen sind MEMBER und würden dort
ohnehin mit 403 scheitern.

## Route und Einbindung

- Neue Seite `frontend/src/app/pages/tablet-toni/`
- Route `/tablet/toni`
- Ein Eintrag mehr in `TABLET_VIEWS` (`shared/tablet-views.ts`), Icon `pets`,
  Label „Toni"

`TABLET_VIEWS` ist die einzige Definition der Ansichtsleiste — der Eintrag
erscheint damit automatisch sowohl im Dashboard-Footer als auch in der Leiste
jeder anderen Tablet-Unteransicht.

Die Seite steckt in `<app-tablet-shell heading="Toni">` und bekommt dadurch die
Uhr-/Wetterzeile, die Ansichtsleiste und den Rückweg zum Dashboard. **Ohne die
Shell wäre das Tablet in der Seite gefangen** — im Tablet-Modus blendet
`app.component.html` den Header samt Navigation komplett aus.

## Layout: 2×2-Raster

Vier gleich große Kacheln:

| | |
|---|---|
| **Futtervorrat** | **Spaziergänge** |
| **Tracker-Status** | **Karte** |

Die Höhe kommt über eine **durchgehende Flex-Kette** von `.app-layout` (100vh)
über `:host` der Seite, die Shell und das Raster bis zum Chart-Element. Fehlt
`flex: 1` oder `min-height: 0` an einer einzigen Stelle, fällt alles darunter
auf Inhaltshöhe zurück und der Graph schrumpft auf fast null. Genau das ist bei
`/tablet/temperatures` passiert, weil das Host-Element der Seite die Regel
zunächst nicht hatte.

## Datenquellen — keine neue API

| Kachel | Quelle |
|---|---|
| Futtervorrat | `PetFoodService.getStatus()` |
| Spaziergänge | `TractiveService.getWalks(trackerId, days)` |
| Tracker-Status | `TractiveService.getPets()` |
| Karte | `TractiveService.getPets()` |

Alle drei Endpunkte sind über die generische `GET /v1/**`-Regel KIOSK-lesbar;
die Ansicht braucht **keine eigene Zeile in `SecurityConfig`** und funktioniert
unverändert auf dem Wandtablet.

**Mehrere Tiere:** Die Ansicht zeigt das erste Tier aus `getPets()`. Bei genau
einem Hund ist das Toni. Bei mehreren Tieren wäre die 2×2-Aufteilung ohnehin
hinfällig — das wäre eine eigene Entscheidung, keine stille Erweiterung.

## Die vier Kacheln im Einzelnen

### Futtervorrat

Großer Füllstandsbalken, darüber die Dosenzahl („34 Dosen"), darunter die
Reichweite („reicht noch ca. 17 Tage", aus `daysRemaining`).

Farbgebung nach derselben Regel wie die Seite `/pet-food`:
kritisch unter 7 Dosen, Warnung unter 25 % Füllstand, sonst normal.

Die Schwelle 7 existiert heute **dreifach** (Telegram-Flow auf
`sensor.pet_food_toni_cans`, `criticalCans` in `pet-food.component.ts`, hart
kodiert in `petFoodTone` in `dashboard.component.ts`). Eine vierte Kopie wird
hier **nicht** angelegt: die Konstante und die Ton-Ableitung wandern nach
`shared/pet-food-level.util.ts`, die beiden bestehenden Stellen delegieren
dorthin. Der Telegram-Flow bleibt zwangsläufig eine getrennte Wahrheit — er
lebt in der Flow-Engine, nicht im Frontend.

### Spaziergänge

**Oben** ein ECharts-Balkendiagramm: eine Säule je Tag des gewählten Zeitraums,
Höhe = Gesamtdauer in Minuten. Tage ohne Runde bleiben als **leere Säule**
stehen — eine fehlende Säule sähe aus wie ein Datenloch, eine leere ist eine
Aussage.

**Darunter** die letzten drei Runden im Klartext, Format wie im
Dashboard-Dialog: `Heute 07:12–07:48 Uhr · 36 Min · 2,1 km`.

**Zeitraum-Knöpfe 7 / 14 / 30 Tage** in der Kopfzeile, projiziert über den
`[shellActions]`-Slot der Shell. Default 7 Tage. Ein Wechsel löst genau einen
neuen Abruf aus.

### Tracker-Status

- Badge „Zu Hause" / „Unterwegs" aus `atHome`. Fehlt das Feld
  (`@JsonInclude(NON_NULL)` lässt es weg, wenn keine Aussage möglich ist), wird
  **kein Badge** gezeigt statt geraten — dasselbe Verhalten wie auf der Seite
  `/pets`.
- Akkustand in Prozent, mit Ladesymbol wenn `charging`.
- „Zuletzt gesehen: 14:22" aus `lastSeen`. Bei einem Tractive-Ausfall liefert
  `GET /v1/tractive/pets` bewusst weiter die **letzte bekannte** Position; der
  Zeitstempel ist die einzige Stelle, an der das Alter sichtbar wird.

### Karte

Leaflet mit OpenStreetMap-Kacheln, ein Marker auf Tonis Position, fester Zoom
(wie `/pets`). Die Standard-Marker-Icons kommen aus `assets/leaflet`, **nie von
einem CDN**.

Ohne Koordinaten zeigt die Kachel einen Hinweistext statt einer leeren grauen
Fläche.

**Offengelegt:** Die Kartenkacheln selbst kommen von den OSM-Servern, also aus
dem Internet. Fällt die Internetverbindung aus, bleibt die Kachel grau, während
die anderen drei weiterlaufen. Die Karte wird nur bei Positionsänderung neu
gezeichnet, nicht bei jedem Refresh.

## Wiederverwendung: `shared/walk-format.util.ts`

`walkDuration`, `walkDistance`, `walkTimeRange` und `groupWalksByDay` stehen
heute als Methoden in `dashboard.component.ts` (Zeilen 1568–1599) und werden vom
Walks-Dialog gebraucht. Die neue Ansicht braucht exakt dieselbe Logik.

Sie wandern als reine Funktionen (keine Angular-Abhängigkeit) nach
`shared/walk-format.util.ts`; das Dashboard delegiert dorthin. Das ist das
Muster von `shortenSensorName` und `matchesPersonFilter` — eine Definition,
mehrere Oberflächen.

**Verworfene Alternativen:**

- *Kopieren:* zwei Wahrheiten für „1,4 km" vs. „1400 m". Die driften.
- *Gemeinsame Kind-Komponente:* scheitert an der `lumina`-Kapselung. Die Styles
  liegen in `dashboard.component.scss` und erreichen ein Kind nicht — es
  renderte lautlos ungestylt. Aus demselben Grund stehen die Tractive-, Zigbee-
  und Futter-Kacheln direkt im Dashboard-Markup.

## Aktualisierung

Selbst-Refresh alle 5 Minuten (`REFRESH_INTERVAL_MS`), wie die bestehenden
Tablet-Ansichten — das Tablet hängt dauerhaft in der Seite.

Ein fehlgeschlagener **Hintergrund**abruf behält die zuletzt bekannten Werte und
zeigt keine Fehlermeldung; auf einer Wandanzeige sind alte Zahlen mehr wert als
gar keine. Nur der **Erst**abruf meldet einen Fehler.

Die vier Quellen sind voneinander unabhängig: fällt Tractive aus, steht der
Futtervorrat trotzdem da, und umgekehrt.

## Backend-Änderung

Genau eine: `TractiveWalkService.MAX_DAYS` von 14 auf 30.

Ohne sie wäre der 30-Tage-Knopf **stumm wirkungslos** — `getWalks` klemmt
`days` per `Math.clamp(days, 1, MAX_DAYS)` und lieferte weiterhin 14 Tage, ohne
Fehler und ohne Hinweis.

Der Rest der Kette trägt das bereits: abgeschlossene Tage werden dauerhaft im
Speicher gecacht (sie ändern sich nie mehr), beim ersten 429 stoppen alle
weiteren Cloud-Aufrufe für 60 Sekunden und der Aufrufer bekommt das
Teilergebnis der schon geladenen Tage.

**Offengelegte Preise:**

- Der erste 30-Tage-Klick löst bis zu 30 einzelne Cloud-Abrufe aus (die Cloud
  lehnt größere Fenster mit Code 7500 HISTORY ab) und trifft damit realistisch
  das Rate-Limit. Die Kachel zeigt dann die Tage, die durchkamen.
- Je nach Tractive-Abo reicht die Positionshistorie nicht 30 Tage zurück (beim
  Basic-Abo nur 24 Stunden). Dann bleiben die hinteren Säulen dauerhaft leer —
  ohne Fehler, weil einzelne fehlgeschlagene Häppchen bewusst toleriert werden.
- Der Cache hält damit bis zu 30 Tage je Tracker im Speicher statt 14. Bei
  Haushaltsgröße unkritisch.

## Tests

- **Höhenkette** (`tablet-toni.component.spec.ts`): Chart-Höhe bei 600 px und
  900 px Fensterhöhe messen. Der Host wird dafür in ein **selbst erzeugtes
  `div`** umgehängt, nicht in `host.parentElement` — das Fixture hängt in Karma
  direkt im `<body>`, zusammen mit Karmas eigenen Elementen und den
  Wurzelknoten schon gelaufener Suiten; ein Flex-Body ließe den Graphen je nach
  Suite-Reihenfolge winzig werden.
- **`walk-format.util.spec.ts`**: Meter/Kilometer-Grenze bei 1000 m,
  Stunden/Minuten-Formatierung, Tagesgruppierung über Mitternacht hinweg.
- **`pet-food-level.util.spec.ts`**: Schwellen 7 Dosen und 25 %.
- **Komponententest**: Zeitraumwechsel löst genau einen neuen Abruf aus; ein
  fehlgeschlagener Hintergrund-Refresh behält die alten Werte und setzt keine
  Fehlermeldung; ein fehlgeschlagener Erstabruf setzt eine.
- **Backend**: `TractiveWalkService`-Test, der belegt, dass `days = 30` nicht
  mehr auf 14 geklemmt wird.

## Bewusst nicht Teil davon

- Keine Buchungen und keine Schaltvorgänge auf dieser Seite.
- Kein Umbau der Seite `/pets` und keiner der Seite `/pet-food` — beide bleiben
  unverändert, geteilt werden nur die Services und die neuen Utils.
- Keine Retention für die Positionshistorie und kein neuer Endpunkt.
