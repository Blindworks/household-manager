# Dog-Mode-Screen auf dem Wandtablet — Design

Stand: 2026-09-27

## Ziel

Wie der „Dog Mode" im Tesla: Solange der Modus „Toni allein" an ist, zeigt das
Wandtablet statt des Dashboards einen Vollbild-Screen mit einer Hunde-Illustration
links, der Uhrzeit groß und der Temperatur (innen groß, außen klein).

## Entscheidungen (Nutzer, 2026-09-27)

- **Nur Wandtablet:** nur in der Tablet-Ansicht (`ViewModeService.isTabletView()`).
  Die Website-Ansicht zeigt weiterhin das Dashboard.
- **Temperatur:** Innenraum groß, außen klein darunter.
- **Innensensor fest im Code:** der Wohnzimmer-Monitor (Amazon-Luftqualitätsmonitor,
  Entity `sensor.alexa_gaj2300425330047_temperature`, Anzeigename „Wohnzimmer"). Keine
  Admin-Pflege; eine Änderung ist ein Redeploy.
- **Beenden:** ein Knopf „Toni allein beenden" auf dem Screen, der den Modus ausschaltet.
  Kein vorübergehendes Einblenden des Dashboards.
- **Display-Verhalten unverändert:** die Tablet-App schwärzt den Bildschirm weiterhin,
  wenn niemand davor ist. Keine Änderung an `tablet-app/`.
- **Bild:** gezeichnete Hunde-Illustration (SVG), kein Foto.

## Verhalten

- **Sichtbar genau dann, wenn** Tablet-Ansicht **und** die Entity
  `input_boolean.manual_toni_allein` in der geladenen Modus-Liste den Zustand `on` hat.
  Ist der Modus nicht in der Liste (z. B. per NEVER aus der Modus-Leiste genommen oder
  Liste leer nach fehlgeschlagenem Erstabruf), bleibt der Screen aus — der Screen
  darf nie auf einem geratenen Zustand stehen.
- **Quelle:** die bereits vorhandene 30-s-Modusabfrage des Dashboards. Kein neuer
  Endpunkt, kein neuer Poll. Ein Ein-/Ausschalten per Flow, Telegram oder Website wird
  spätestens mit dem nächsten Refresh (≤ 30 s) sichtbar.
- **Uhr:** dieselbe `clockTime` (HH:MM) wie die Dashboard-Kopfzeile, als Input
  durchgereicht — kein zweiter Takt, die Uhren können nicht auseinanderlaufen.
- **Innentemperatur:** aus den aktuellen Temperaturmesswerten, die das Dashboard
  ohnehin lädt (`GET /v1/temperatures`), ausgewählt über die **`sensorId`**
  (`alexa:<applianceId>`) — **nicht** über den Anzeigenamen. Grund: die Temperatur-API
  liefert den per Custom-Name umbenannten Anzeigenamen; eine Namensprüfung bräche beim
  nächsten Umbenennen still. Die `applianceId` ist die Hardware-Seriennummer
  (`GAJ2300425330047`); verglichen wird **ohne Groß-/Kleinschreibung**, weil die Entity-ID
  sie kleingeschrieben führt und die Schreibweise in der Temperatur-API nicht verifiziert ist.
- **Außentemperatur:** der primäre reale Außenfühler aus `splitTemperatureReadings`
  (`shared/temperature-comfort.util.ts`); fehlt er, der DWD-Wert (`source: WEATHER`);
  fehlen beide, entfällt die Zeile.
- **Veraltete Werte:** ein Innenwert älter als 60 Minuten — dieselbe Schwelle wie die
  Klima-Kachel, über die dann exportierte `isReadingStale` aus `temperature-comfort.util.ts`,
  keine zweite Kopie — wird abgedunkelt angezeigt,
  fehlt er ganz, steht „–". Ein fehlgeschlagener Refresh behält den letzten Stand
  (Muster aller Dashboard-Kacheln).
- **Text:** „Toni ist allein zu Haus. Herrchen kommt bald zurück – keine Sorge!"
- **Knopf „Toni allein beenden":** ruft das bestehende `toggleMode()`
  (`POST /v1/modes/{entityId}/toggle`, KIOSK-erlaubt). Vorher wird der Modus aus der
  **aktuellen** Liste neu aufgelöst und nur geschaltet, wenn er dort noch `on` ist
  (Regel aus `confirmToggle`) — sonst würde ein veralteter Stand den Toggle den Modus
  wieder *ein*schalten lassen. Ausschalten ist direkt, ohne Dialog (die Aktivierungs-Checks
  gelten nur beim Einschalten). Während der Aktion ist der Knopf gesperrt; ein Fehler
  lässt den Screen stehen und zeigt eine kurze Meldung.

## Bausteine

1. **`frontend/src/app/shared/dog-mode.util.ts`** — einzige Definition der Regeln, reine
   Funktionen ohne Angular:
   - `DOG_MODE_ENTITY_ID = 'input_boolean.manual_toni_allein'`
   - `DOG_MODE_INDOOR_SENSOR_ID` (fest, siehe oben)
   - `isDogModeActive(modes)` → boolean
   - `buildDogModeClimate(readings, nowMs)` → `{ indoor: { label, stale } | null, outdoorLabel: string | null }`
     (ganze Grad, z. B. `"22°"`)
2. **`frontend/src/app/components/dog-mode-screen/`** (`.ts`/`.html`/`.scss`) — Standalone-
   Komponente, Inputs: `clockTime`, Klima-Daten, `busy`, Fehlertext; Output: `endMode`. Eigene
   Styles, **keine** `lumina`-Klassen (die sind in `dashboard.component.scss` gekapselt und
   griffen hier lautlos nicht). Farben ausschließlich über die Theme-Tokens aus
   `shared/styles/_lumina-theme.scss`, die per CSS-Vererbung vom `.lumina`-Wurzelelement
   ankommen — so stimmt der Screen im hellen wie im dunklen Design. Vollbild per
   `position: fixed; inset: 0` über dem Dashboard.
3. **`frontend/src/assets/dog-mode/dog.svg`** — Hunde-Illustration, lokal ausgeliefert,
   nie von einem CDN (das Tablet muss ohne Internet funktionieren).
4. **`DashboardComponent`** — rendert `<app-dog-mode-screen>` wenn Tablet-Ansicht und
   `isDogModeActive(modes)`; liefert Klima-Daten aus den ohnehin geladenen Messwerten;
   neue Methode `endDogMode()` mit der Neu-Auflösung aus der aktuellen Liste.

Keine Backend-Änderung, keine Migration, keine Änderung an `SecurityConfig`.

## Nebenbefund (bei der Umsetzung prüfen)

`OUTDOOR_SENSOR_NAMES` enthält `'Temperatur Aqara Garten'`, die Temperatur-API liefert
aber den Custom-Namen — der Gartenfühler heißt dort vermutlich „Garten". Dann erkennt
`splitTemperatureReadings` ihn nicht als Außenfühler, und der Dog-Screen fiele auf den
DWD-Wert zurück (das Dashboard zählte ihn zusätzlich als Innenraum). Bei der Umsetzung im
Browser gegen die echte API-Antwort prüfen; falls bestätigt, ist das ein eigener Fix
(Frontend-Konstante **und** `ventilation.outdoor-sensor-names` hängen am Namen) —
nicht Teil dieses Features.

## Tests

- `dog-mode.util.spec.ts`: aktiv nur bei `on`; Modus fehlt ⇒ inaktiv; Innenwert per
  `sensorId` gefunden, trotz abweichendem Namen; veraltet ab 60 min; Außen: realer
  Fühler vor DWD, beide fehlen ⇒ `null`.
- `dog-mode-screen.component.spec.ts`: zeigt Uhrzeit, Innen-/Außenwert, „–" ohne
  Innenwert, keine Außenzeile ohne Außenwert; Knopf emittiert `endMode`, gesperrt bei `busy`.
- `dashboard.component.spec.ts`: Screen nur in Tablet-Ansicht **und** bei `on` (je ein
  negativer Test); Knopf schaltet den Modus aus; ist der Modus in der aktuellen Liste
  bereits `off`, wird **nicht** getoggelt.

## Bewusste Grenzen

- Keine Änderung am Soft-Off der Tablet-App: der Screen ist nur zu sehen, wenn das
  Tablet durch Bewegung aufwacht.
- Bis zu 30 s Verzögerung zwischen Moduswechsel und Screen.
- Ein Innensensor, fest im Code.
