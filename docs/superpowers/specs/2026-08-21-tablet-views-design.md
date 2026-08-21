# Tablet-Views: Button-Leiste im Dashboard und Temperatur-Uebersicht

Datum: 2026-08-21

## Ausgangslage

Im Tablet-Modus (`ViewModeService.isTabletView()`) blendet `app.component.html` den
Header komplett aus. Auf dem Wandtablet gibt es damit **keine Navigation** weg vom
Dashboard und - viel wichtiger - keinen Weg zurueck. Jede weitere Ansicht fuers
Tablet braucht deshalb zwingend einen eigenen Zurueck-Knopf.

Die bestehende Seite `/temperatures` zeigt bereits alle Sensoren als Kachelraster,
ist aber auf die Website-Ansicht zugeschnitten: ein Wert pro Chart mit einem
Umschalter Temperatur/Luftfeuchte. Antippen ist auf einer Wand-Anzeige laestig.

## Entwurf

### 1. Button-Leiste im Dashboard

Neue Zeile am Ende von `dashboard.component.html`, direkt hinter dem `<footer>`,
gerendert nur bei `viewMode.isTabletView()`.

Markup und Styles liegen bewusst **im Dashboard selbst** (`lumina__viewbar`,
`lumina__viewbar-btn`): die `lumina`-Styles sind in `dashboard.component.scss`
gekapselt und wuerden in einer Kind-Komponente lautlos nicht greifen (dieselbe
Falle wie bei den Tractive-/Zigbee-/Futter-Kacheln).

Die Eintraege kommen aus einem Array in `dashboard.component.ts`:

```ts
readonly tabletViews: TabletView[] = [
  { route: '/tablet/temperatures', icon: 'thermostat', label: 'Temperaturen' }
];
```

Ein weiterer View kostet spaeter genau eine Zeile. Button = Icon + Label,
Touch-Ziel mindestens 64 px hoch.

### 2. Neue Seite `pages/tablet-temperatures/`, Route `/tablet/temperatures`

- `canActivate: [authGuard]`, lazy geladen wie alle anderen Seiten.
- `GET /v1/temperatures` ist ueber die generische `GET /v1/**`-Regel KIOSK-lesbar -
  kein Eingriff in `SecurityConfig` noetig.
- Kachelraster **ohne Scrollen**: 2 Spalten, ab 6 Sensoren 3; die Zeilen teilen
  sich die Resthoehe (`grid-auto-rows: 1fr` in einem `height: 100dvh`-Container
  minus Kopfzeile). ECharts pro Kachel mit `autoResize`.
- Pro Kachel **ein** Chart mit zwei Serien und zwei Y-Achsen: Temperatur (links,
  Grad C, rot) und Luftfeuchte (rechts, %, blau). Sensoren ohne Feuchtewerte
  zeichnen nur die Temperaturlinie und blenden die rechte Achse aus.
- Kopfzeile: Titel, Zeitraum-Knoepfe (24 h / 7 Tage / 30 Tage, Default 7 Tage) und
  ein Zurueck-Knopf zum Dashboard.
- Auto-Refresh alle 5 Minuten (das Tablet haengt dauerhaft in dieser Ansicht).
  Ein fehlgeschlagener Refresh behaelt den letzten Stand; nur der Erstabruf zeigt
  eine Fehlermeldung. Der Timer wird in `ngOnDestroy` gestoppt.

### 3. Bewusst nicht Teil davon

`/temperatures` bleibt unveraendert. Geteilt wird nur der `TemperatureService`;
die Chart-Optionen sind in beiden Seiten eigene Funktionen, weil sie
unterschiedliche Ziele haben (ein Wert mit Umschalter vs. zwei Achsen ohne
Bedienung). Der Preis ist eine zweite Stelle mit Chart-Konfiguration.

### 4. Tests

`tablet-temperatures.component.spec.ts` (mit `HttpClientTestingModule`):

- Raster wird aus der Antwort gebaut,
- Sensor ohne Feuchtewerte erzeugt nur eine Serie und keine rechte Achse,
- Zeitraumwechsel loest genau einen neuen Abruf aus,
- ein fehlgeschlagener Refresh ueberschreibt vorhandene Daten nicht.

In `dashboard.component.spec.ts`: die Leiste erscheint nur im Tablet-Modus.
