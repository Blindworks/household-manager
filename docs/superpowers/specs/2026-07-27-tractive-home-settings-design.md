# Tractive-Home-Einstellungen in der Datenbank, pflegbar im Admin-Bereich

**Datum:** 2026-07-27
**Status:** Entwurf abgenommen, Umsetzung offen

## Ziel

Die Definition von „zu Hause" für den Hundetracker soll in der Datenbank liegen und über
eine Admin-Seite gepflegt werden, statt über Umgebungsvariablen und
`application.properties`.

Heute steht sie in `TractiveProperties` und wird beim Start aus
`TRACTIVE_HOME_LAT`/`TRACTIVE_HOME_LON` gelesen. Jede Änderung — auch das bloße
Nachjustieren einer Schwelle — erfordert damit einen Redeploy. Zwei der Werte sind im
Vorgänger-Spec ausdrücklich als „nach dem Realbetrieb nachziehen" markiert
(`powered-off-after-minutes` ist geraten, `home-radius-meters` ist die Stellschraube, wenn
der Hund im Garten als „unterwegs" gilt). Genau diese Werte per Redeploy zu ändern ist der
Reibungspunkt, den diese Arbeit beseitigt.

## Entscheidungen

| Frage | Entscheidung | Begründung |
|---|---|---|
| Umfang | Koordinaten **und** alle vier Schwellen, plus der Zonenname | Die Schwellen sind der eigentliche Grund für die Seite; die Koordinaten allein zu verlagern hätte den Redeploy nur verschoben |
| Umgebungsvariablen | **Ersatzlos entfernt**, die DB ist die einzige Quelle | Ein Fallback auf Env erzeugt zwei Wahrheiten: bei einem falschen Wert müsste man erst herausfinden, welche gerade gewinnt |
| Eingabe der Koordinaten | Leaflet-Karte zum Anklicken, Zahlenfelder zusätzlich | Ein vertippter Breitengrad bricht alles lautlos; ein sichtbarer Kreis zeigt sofort, ob Punkt und Radius plausibel sind |
| Wirksamkeit | Beim nächsten Poll (≤ 60 s), **kein** Sofort-Poll beim Speichern | Kachel und Badge rechnen ohnehin bei jedem Abruf frisch gegen die zwischengespeicherten Positionsdaten, übernehmen neue Schwellen also praktisch sofort. Nur die Entität wartet auf den Poll. Ein synchroner Trigger würde den Request für die Dauer mehrerer Cloud-Aufrufe blockieren |
| Speicherform | Bestehende Tabelle `application_settings`, neue Kategorie | Das Muster existiert (`WasteCollectionSettingsService`); eine eigene Tabelle für sieben Werte wäre Ballast |

## Speicherung

Kategorie `TRACTIVE_HOME` in `application_settings`:

| Schlüssel | Typ | Default | Bedeutung |
|---|---|---|---|
| `home_latitude` | Double | *leer* | Ohne Koordinaten entsteht keine Home-Entität |
| `home_longitude` | Double | *leer* | |
| `home_radius_meters` | double | 100 | Enger Radius (Regeln 3 und 5) |
| `home_arrival_radius_meters` | double | 500 | Weiter Radius für die Ausschalt-Heuristik (Regel 4) |
| `powered_off_after_minutes` | long | 60 | Ab wann ein ausbleibender Bericht als „Tracker aus" gilt |
| `powered_off_min_battery_percent` | int | 15 | Untergrenze für die Ausschalt-Deutung |
| `home_zone_name` | String | „Zuhause" | Name der Fallback-Zone des Standort-Sensors |

**Kein Liquibase-Changeset und keine Datenmigration.** Die Tabelle existiert, und da
`TRACTIVE_HOME_LAT`/`TRACTIVE_HOME_LON` im Deployment nie gesetzt waren, gibt es nichts zu
übernehmen. Eine fehlende Zeile bedeutet „nicht konfiguriert" — exakt der Zustand, den
`TractiveHomeResolver` bereits behandelt (Regel 1: keine Entität, einmalige Warnung).

## Backend

### `TractiveHomeSettings` (neu)

Ein Record mit den sieben Werten. `homeLatitude` und `homeLongitude` sind `Double` und
dürfen `null` sein; alles andere ist immer belegt (Default oder gespeicherter Wert).

### `TractiveHomeSettingsService` (neu)

Typisierte Fassade über `ApplicationSettingsService`, nach dem Muster von
`WasteCollectionSettingsService`. Zwei Eigenschaften sind wesentlich:

- **`getSettings()` liest die ganze Kategorie in einer Abfrage** (`getSettingsByCategory`),
  nicht Schlüssel für Schlüssel.
- **Defensives Auslesen.** Ein unparsbarer Wert fällt auf den Default zurück und loggt
  eine Warnung. Der Poller läuft jede Minute; eine `NumberFormatException` aus einem
  Tippfehler in der DB darf ihn nicht lahmlegen. Gleiches gilt für unplausible
  gespeicherte Werte (Radius ≤ 0), die die Validierung eigentlich verhindert, die aber
  über einen direkten DB-Zugriff trotzdem entstehen können.

`saveSettings(...)` schreibt alle Werte in einem einzigen `saveSettings`-Aufruf, damit ein
Fehler mitten drin keine halb aktualisierte Konfiguration hinterlässt.

### Anpassung der Resolver

`TractiveHomeResolver` und `TractiveZoneResolver` bekommen `TractiveHomeSettingsService`
statt `TractiveProperties` injiziert.

**`TractiveHomeResolver.resolve()` holt die Einstellungen genau einmal am Anfang** in eine
lokale Variable und rechnet dann nur noch damit. Das ist dieselbe Disziplin wie beim
`Instant now`: eine Bewertung sieht einen konsistenten Satz Werte. (Anders als beim
Zeitpunkt genügt hier ein Lesen pro Aufruf: zwei Tiere desselben Poll-Zyklus könnten
theoretisch unterschiedliche Werte sehen, wenn genau dazwischen gespeichert wird — beide
Antworten wären für ihren Moment korrekt, und der nächste Zyklus gleicht das ab. Das
`now`-Problem war ein *systematischer*, dauerhafter Widerspruch, nicht ein Rennen von
Mikrosekunden.)

### `TractiveProperties` (Bereinigung)

Verliert `homeLatitude`, `homeLongitude`, `homeRadiusMeters`, `homeArrivalRadiusMeters`,
`poweredOffAfterMinutes`, `poweredOffMinBatteryPercent` und `homeZoneName`. Behält
`enabled`, `baseUrl`, `clientId`, Poll-Intervall und Timeouts. Die entsprechenden Zeilen
verschwinden aus `application.properties`, die beiden Env-Variablen aus
`docker-compose.yml`.

### API

`TractiveHomeSettingsController` mit `GET` und `PUT` auf `/v1/tractive/home-settings`.

**Sicherheit:** `SecurityConfig` hat eine generische Regel
`GET /v1/**` → `hasRole("KIOSK")` (Zeile 157). Ohne eine davor stehende explizite Regel
könnte das Wandtablet die Einstellungen lesen. Der neue Pfad kommt deshalb in den
bestehenden ADMIN-Block (Zeile 146–147), der vor der generischen Regel steht —
**die Reihenfolge der Matcher ist die eigentliche Sicherheitszusage**, nicht die
Annotation am Controller.

**Validierung serverseitig**, nicht nur im Formular: Breite in [-90, 90], Länge in
[-180, 180], beide Radien und `powered_off_after_minutes` > 0,
`powered_off_min_battery_percent` in [0, 100]. Ungültiges wird mit 400 abgelehnt.
Koordinaten dürfen nur gemeinsam gesetzt oder gemeinsam leer sein — eine halbe
Koordinate ist keine Position und würde in `TractiveHomeResolver` stumm zu „nicht
konfiguriert" führen, während das Formular einen Wert anzeigt.

**Audit-Log:** Jede Änderung wird protokolliert. Wer verschiebt, was „zu Hause" bedeutet,
ändert das Verhalten eines darauf gebauten Alarm-Flows; das gehört nachvollziehbar
festgehalten, wie Flow-, Nutzer- und Token-Änderungen auch.

## Frontend

Neue Seite `pages/admin-tractive/`, Route `admin/tractive` mit `adminGuard`, Menüpunkt im
Admin-Bereich des Headers — nach dem Muster von `admin/users`, `admin/service-tokens` und
`admin/audit-log`.

- **Leaflet-Karte:** Klick setzt den Marker; zwei Kreise zeigen Home- und Ankunftsradius
  und folgen den Zahlenfeldern live. Die Standard-Marker-Icons kommen lokal aus
  `assets/leaflet`, **nie von einem CDN** — das gilt im ganzen Projekt, damit die Anzeige
  ohne Internet funktioniert.
- **Zahlenfelder** für alle sieben Werte, beidseitig mit der Karte synchron.
- **Hinweisbanner**, solange keine Koordinaten gespeichert sind: ohne sie existiert die
  Zu-Hause-Entität nicht, und weder Badge noch Dashboard-Kachel erscheinen.
- Karte direkt in der Komponente, **keine gemeinsame Kartenkomponente** mit der
  Hundetracker-Seite. Die beiden haben unterschiedliche Aufgaben (Tiere anzeigen vs. einen
  Punkt wählen); eine geteilte Abstraktion wäre hier Ballast.

## Tests

- **`TractiveHomeSettingsServiceTest`:** Defaults ohne gespeicherte Zeile; Roundtrip
  speichern/lesen; **unparsbare und unplausible Werte in der DB werfen nicht**, sondern
  fallen auf den Default zurück; nur eine der beiden Koordinaten gespeichert ⇒ gilt als
  nicht konfiguriert.
- **`TractiveHomeSettingsControllerTest`:** Validierung lehnt Breite 480, Länge 200,
  Radius 0, Akku 150 und eine halbe Koordinate ab; gültige Werte werden gespeichert.
- **Nachziehen:** `TractiveHomeResolverTest` und `TractiveZoneResolverTest` bauen heute
  `TractiveProperties` direkt und müssen auf den neuen Service umgestellt werden. Dabei
  bleiben alle bestehenden Zusicherungen erhalten — insbesondere die sicherheitskritischen
  (`unknown` statt geratenem `away`, Fail-safe bei fehlendem Akkustand, Regel 2 vor
  Regel 6, `max(...)` beim Ankunftsradius).
- **Sicherheitstest:** `GET /v1/tractive/home-settings` als KIOSK ergibt 403. Ohne diesen
  Test wäre die Matcher-Reihenfolge nur eine Behauptung.

## Bewusst nicht umgesetzt (YAGNI)

- **Kein Sofort-Poll beim Speichern** (siehe Entscheidungstabelle).
- **Kein Cache** vor `ApplicationSettingsService`. Eine Abfrage pro `resolve()`-Aufruf sind
  bei einem Haustier rund zwei Abfragen pro Minute; ein Cache brächte
  Invalidierungsaufwand ohne messbaren Gewinn.
- **Keine Historie** der Einstellungsänderungen über das Audit-Log hinaus.
- **Kein Import der bisherigen Env-Werte.** Sie waren nie gesetzt.

## Bekannte Grenzen

- **Nach dem Deployment ist die Home-Entität zunächst weg**, bis jemand die Admin-Seite
  ausfüllt. Da sie mangels gesetzter Env-Variablen bisher ohnehin nicht existierte, ändert
  sich praktisch nichts — es ist aber der Grund, warum die Seite ein deutliches
  Hinweisbanner braucht statt nur leerer Felder.
- **Die Karte hilft gegen vertippte Koordinaten, nicht gegen falsch gewählte.** Wer den
  Marker auf das Nachbarhaus setzt, bekommt eine plausibel aussehende Konfiguration mit
  falscher Bedeutung.
- **Die Ausschalt-Heuristik bleibt unverifiziert.** Ob `device_hw_report` bei
  ausgeschaltetem Tracker noch einen Akkustand liefert, ist weiterhin offen (siehe
  `2026-07-26-tractive-hund-zuhause-design.md`). Diese Arbeit macht das Nachjustieren
  bequemer, beantwortet die Frage aber nicht.
