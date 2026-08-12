# Lüftungsempfehlung im Intelligence Hub — Design

Datum: 2026-08-12
Status: vom Nutzer freigegebenes Design (Variante B: serverseitige Berechnung)

## Ziel

Eine Karte im Intelligence Hub des Dashboards, die im Sommer darauf hinweist, dass es
draußen kühler ist als in einem Raum und Lüften den Raum herunterkühlen kann. Die
Bewertung läuft im Backend, damit sie zusätzlich als Entität im Entity-State-Layer
für Flow-Trigger (z. B. Telegram- oder Alexa-Meldung) nutzbar ist.

„Sommer" ist bewusst **keine** Kalenderbedingung: Die Karte erscheint immer dann, wenn
ein Raum über der Komfortschwelle liegt und es draußen spürbar kühler ist — das greift
automatisch nur bei Hitze, auch in einem warmen Mai.

## Entscheidungen (mit dem Nutzer geklärt)

- **Bedingung:** Raum ≥ 24 °C **und** außen mindestens 2 °C kühler als dieser Raum.
- **Darstellung:** eine Sammelkarte für alle betroffenen Räume (kompakt wie die
  Müll-Karte), Räume absteigend nach Temperatur.
- **Zuschnitt:** REST-Endpunkt für die Karte **plus** `binary_sensor`-Entität für Flows.

## Architektur

### 1. Kernlogik — eine einzige Definition

Neue Klasse `VentilationRecommendationService` (Muster `TractiveHomeResolver` /
`PowerConsumerQueryService`: Endpunkt und Entität fragen **dieselbe** Klasse, damit
Karte und Flow-Trigger nie auseinanderlaufen). Datenquelle sind die aktuellen
Messwerte des bestehenden `TemperatureSeriesService`:

- Außen = jüngster Wert der Quelle `WEATHER` (DWD, „Außen").
- Innen = jüngste Werte der Quellen `ZIGBEE` und `ALEXA`.
- Messwerte älter als `stale-after-minutes` (Default 30) werden ignoriert — ein
  eingefrorener Sensor darf keine Dauer-Empfehlung erzeugen.
- Fehlt ein frischer Außenwert, gibt es **keine Aussage** (nie raten).
- Empfehlung, wenn mindestens ein frischer Innenraum ≥ `room-threshold-celsius`
  (Default 24) liegt **und** außen ≥ `min-difference-celsius` (Default 2) kühler ist
  als dieser Raum.

**Hysterese gegen Flattern:** Eine bestehende Empfehlung erlischt erst, wenn kein
Raum mehr über der Raumschwelle liegt **oder** die Differenz unter 1 °C fällt
(`off-difference-celsius`, Default 1). Ohne Hysterese schaltete die Entität bei
23,9/24,1 °C im Minutentakt, und ein darauf gebauter Telegram-Flow spammte bei jeder
`on`-Flanke.

Konfiguration in `application.properties` (wie beim Zigbee-Watchdog bewusst nicht in
der DB — kein Grund, das im laufenden Betrieb zu verstellen):

```
ventilation.room-threshold-celsius=24
ventilation.min-difference-celsius=2
ventilation.off-difference-celsius=1
ventilation.stale-after-minutes=30
```

### 2. Entität für Flows

Ein `@Scheduled`-Evaluator (alle 5 Minuten, wirft nie) meldet
`binary_sensor.insight_ventilation` (neue `EntitySource.INSIGHT`) über
`EntityStateService.reportState`:

- State `on`/`off`.
- Attribute: `outdoorTemperature`, `rooms` (Name + Temperatur, wärmster zuerst).
- Ohne frischen Außenwert → `unavailable`. Die Flow-Engine unterdrückt den Übergang
  *nach* `unavailable` ohnehin engine-weit, es entsteht also kein Fehltrigger; die
  `!=`-Falle aus CLAUDE.md gilt hier wie überall.

Flows können auf die `on`-Flanke z. B. eine Telegram-Nachricht oder Alexa-Ansage
bauen. Der Evaluator ist die einzige Schreibstelle der Entität.

### 3. REST-Endpunkt

`GET /api/v1/insights/ventilation` →

```json
{
  "recommended": true,
  "outdoorTemperature": 21.0,
  "rooms": [ { "name": "Schlafzimmer", "temperature": 26.1 } ],
  "evaluatedAt": "2026-08-12T18:40:00"
}
```

Bei fehlender Datenlage ist `recommended` **`null`** statt `false` — das Frontend
zeigt dann schlicht keine Karte, statt „kein Lüften nötig" zu behaupten. Der Endpunkt
ist über die generische `GET /v1/**`-Regel für alle Rollen lesbar (auch KIOSK /
Wandtablet); keine Security-Änderung.

### 4. Frontend

- Kleiner `InsightService` (`getVentilation()`), Abruf im bestehenden
  Klima-Refresh-Takt des Dashboards.
- Neues `ventilation-insight.util.ts` (Muster `waste-insight.util.ts`) mappt das DTO
  auf eine `HubInsight`-Sammelkarte: Icon `air`, Ton `secondary`, Titel
  „Lüften lohnt sich", Text z. B.
  „Draußen 21° — kühler als Schlafzimmer (26°), Wohnzimmer (25°)".
- Einsortiert in `rebuildInsights()` nach Müll und Terminen, vor den Platzhaltern
  (Müll/Termine sind terminlich fix, Lüften ist ein Hinweis).
- Das Dashboard rendert die Karte selbst (Style-Kapselung der `lumina`-Klassen, siehe
  `hub-insight.model.ts`).

### 5. Tests

- JUnit für `VentilationRecommendationService`: Schwellen, Hysterese, veraltete
  Messwerte, fehlender Außenwert, Sortierung der Räume.
- Controller-Test für den Endpunkt (inkl. `recommended: null`-Fall).
- Frontend-Spec für `ventilation-insight.util.ts` (Karte / keine Karte, Textformat).

## Nicht-Ziele

- Keine DB-Migration, keine neuen Tabellen.
- Keine Kalender-/Monatslogik für „Sommer".
- Kein Flow wird in diesem Schritt angelegt — die Entität macht ihn nur möglich.
- Keine Berücksichtigung von Luftfeuchte oder Fensterkontakten (späterer Ausbau
  denkbar, z. B. „Fenster ist schon offen" unterdrückt die Karte).
