# Entität „Hund ist zu Hause" (Tractive)

**Datum:** 2026-07-26
**Status:** Entwurf abgenommen, Umsetzung offen

## Ziel

Eine eigene Entität, die beantwortet: *Ist der Hund zu Hause?* Sie soll als Flow-Trigger
taugen, auf der Hundetracker-Seite als Badge und auf dem Dashboard als Kachel erscheinen.

Die bestehende Entität `sensor.tractive_<trackerId>_location` liefert bereits den
Zonennamen (`away` / `unknown` / Zonenname aus den Tractive-Geofences), beantwortet die
Frage aber nicht verlässlich: Der Zonenname wird in der Tractive-App frei vergeben und
kann jederzeit umbenannt werden — dieselbe stille Kopplungsfalle wie bei Flow #6 und den
Vision-Personennamen.

## Rahmenbedingung: Der Tracker wird zu Hause ausgeschaltet

Das prägt den gesamten Entwurf und ist der schwierigste Teil.

**Die Tractive-API kennt kein „ausgeschaltet".** Es gibt kein Statusfeld dafür. Erkennbar
ist der Zustand nur indirekt daran, dass `device_pos_report` keinen frischen Zeitstempel
mehr liefert. Damit wird aus einer Ablesung eine Heuristik — mit einer gefährlichen
Kehrseite: **Akku unterwegs leergelaufen** sieht in der API exakt aus wie **zu Hause
ausgeschaltet**. Würde man Stille pauschal als „zu Hause" werten, verstummte ein
Alarm-Flow „Hund hat das Grundstück verlassen" genau dann, wenn er gebraucht wird.

Zusätzlich stammt der letzte Positionsbericht vor dem Ausschalten möglicherweise noch von
*unterwegs* (z. B. 200 m vor der Haustür). Ein rein positionsbasiertes Urteil im engen
Home-Radius würde den Hund dann dauerhaft als „unterwegs" führen.

Die Heuristik in Regel 4 (unten) verlangt deshalb **zwei** unabhängige Belege, bevor sie
aus Stille auf „zu Hause" schließt.

## Entscheidungen

| Frage | Entscheidung | Begründung |
|---|---|---|
| Was heißt „zu Hause"? | Eigene Home-Koordinaten (`tractive.home-latitude/-longitude`) | Unabhängig davon, wie die Tractive-Geofences heißen oder ob sie lesbar sind |
| Tracker lädt | zählt als „zu Hause" | Eindeutiges Signal, keine Positionsprüfung nötig |
| Tracker zu Hause ausgeschaltet | Stille + gesunder Akku + Heimnähe im weiten Radius | Beide Belege nötig; ein einzelner wäre von „Akku unterwegs leer" nicht zu unterscheiden |
| Stille ohne diese Belege | Urteil nach der letzten bekannten Position, Attribut `stale=true` | Die letzte Position ist echte Evidenz, kein geratener Wert — und zeigt für Alarm-Flows in die sichere Richtung |
| Gar keine Daten | *keine Aussage*, kein Update → letzter Wert bleibt stehen | Nie ein geratenes `off` |
| Cloud-/Token-Ausfall | Nur die Home-Entität behält ihren Wert | `location`/`battery`/`charging` bleiben `unavailable`, sonst wirkt ein 3 Tage alter Akkuwert wie ein Live-Wert |
| Stille-Schwelle | 60 Minuten, konfigurierbar | Das echte Melde-Intervall im Tractive-Sparmodus ist gegen einen realen Account **nicht verifiziert** und kann 30–60 Min betragen. Lieber spät umschalten als bei jedem Intervall flackern |
| Enger Home-Radius | bleibt 100 m | Bewusst eng; Regel 2 und 4 fangen die Grenzfälle ab |

## Die Entität

`binary_sensor.tractive_<trackerId>_home`

| Feld | Wert |
|---|---|
| `domain` | `BINARY_SENSOR` |
| `source` / `sourceRef` | `TRACTIVE` / `<trackerId>` |
| `friendlyName` | `<Name> zu Hause` |
| `state` | `on` / `off` |
| Attribut `deviceClass` | `presence` (wie beim Tablet-Präsenzsensor) |
| Attribut `basis` | `charging`, `position` oder `powered_off` |
| Attribut `stale` | `true`, wenn der Positionsbericht älter als die Schwelle ist |
| Attribut `positionAgeMinutes` | Alter des zugrunde liegenden Berichts |
| Attribut `distanceMeters` | Distanz zum Home-Punkt, wenn eine Position vorliegt |
| Attribut `positionTime` | Zeitstempel des Positionsberichts |

`basis` und `stale` machen im Entity-Viewer und im Flow-Debug nachvollziehbar, **warum**
die Entität gerade so steht — sonst rätselt man bei `on` ohne GPS-Fix.

## Architektur

### `TractiveHomeResolver` (neu)

Eine Klasse, eine Frage. Signatur:

```java
Optional<HomeVerdict> resolve(TractivePetSnapshot snapshot, Instant now)
```

`HomeVerdict` trägt `atHome` (boolean), `basis` (`CHARGING` / `POSITION` / `POWERED_OFF`),
`stale` (boolean) sowie optional `distanceMeters` und `positionAgeMinutes`.
`now` wird hereingereicht statt intern gelesen, damit die Tests die Stille-Schwelle ohne
Warterei prüfen können.

Regeln, in dieser Reihenfolge ausgewertet:

| # | Bedingung | Ergebnis | `basis` |
|---|---|---|---|
| 1 | Home-Koordinaten nicht konfiguriert | `Optional.empty()` | — |
| 2 | `hardware.isCharging() == true` | zu Hause | `CHARGING` |
| 3 | Positionsbericht frisch (Alter < `powered-off-after-minutes`) | Distanz ≤ `home-radius-meters` ? zu Hause : unterwegs | `POSITION` |
| 4 | Bericht still **und** `batteryLevel >= powered-off-min-battery-percent` **und** Distanz ≤ effektivem Ankunftsradius | zu Hause | `POWERED_OFF` |
| 5 | Bericht still, Regel 4 greift nicht | Distanz ≤ `home-radius-meters` ? zu Hause : unterwegs, `stale = true` | `POSITION` |
| 6 | Kein Positionsbericht (oder ohne Koordinaten) und lädt nicht | `Optional.empty()` | — |

Präzisierungen:

- **Regel 1** loggt einmalig eine Warnung, nicht pro Poll-Zyklus.
- **Regel 3/5** vergleichen mit `<=`; der Rand zählt als innerhalb, konsistent zu
  `GeoZone.contains`.
- **Regel 4 ist fail-safe:** Fehlt `batteryLevel` (null), gilt die Regel als nicht erfüllt.
  Fehlt der Zeitstempel des Positionsberichts, kann „still" nicht bestimmt werden — dann
  wird der Bericht als frisch behandelt (Regel 3).
- **Effektiver Ankunftsradius** = `max(home-arrival-radius-meters, home-radius-meters)`.
  Ist der Ankunftsradius kleiner konfiguriert als der enge Radius, entstünde sonst eine
  Regel, die nie greifen kann.
- **Regel 5 friert bewusst nicht ein.** Sonst bliebe die Entität auf `on` stehen, wenn der
  Hund zu Hause war, rausgeht und der Tracker weit weg ausfällt. Die letzte bekannte
  Position ist echte Evidenz.
- **`Optional.empty()` heißt an jeder Aufrufstelle dasselbe:** *keine Aussage*. Der
  Entity-Mapper meldet dann kein Update, wodurch der Entity-State-Layer den letzten Wert
  unverändert behält.

Diese Klasse ist die **einzige** Definition von „zu Hause". Entity-Mapper und
`TractivePetService` fragen dieselbe Instanz — nach dem Muster von
`PowerConsumerQueryService.findConsumer`, damit Dashboard-Kachel und Flow-Trigger nie
auseinanderlaufen können.

### `TractiveEntityMapper` (Erweiterung)

- Ergänzt die Home-Entität, wenn `TractiveHomeResolver.resolve` ein Ergebnis liefert.
- Bekommt eine Methode `boolean isHomeEntity(EntityStateUpdate update)`. Der Mapper baut
  die Entity-IDs, also gehört ihm auch diese Frage — der Poller soll nicht auf
  String-Suffixe prüfen.

### `TractivePollingService` (Erweiterung)

`markUnavailable()` filtert die Home-Entität über `mapper.isHomeEntity(...)` heraus. Alle
anderen Tractive-Entitäten werden unverändert `unavailable`.

### Doppelrolle der Home-Koordinaten

`tractive.home-latitude/-longitude` sind heute nur der *Fallback*, wenn die
Tractive-Geofences nicht lesbar sind. Ab jetzt sind sie zusätzlich die verbindliche
Definition von „zu Hause". Das ist gewollt — es gibt genau einen Ort im System, an dem
„Zuhause" definiert wird —, muss aber in `CLAUDE.md` festgehalten werden.

## API & Frontend

### DTO

`TractivePetDto` bekommt `Boolean atHome` (nullable, `null` = keine Aussage). Befüllt
wird es in `TractivePetService` über denselben `TractiveHomeResolver`.

`TractivePetService` liest den Poller-Cache, der bei einem Ausfall bewusst nicht geleert
wird — die REST-Antwort zeigt also weiterhin den letzten bekannten Stand. Das ist
konsistent zum bestehenden Trade-off der Integration und zum Verhalten der Home-Entität.

### Hundetracker-Seite (`pages/pets/`)

Badge in der `pet-card`: „Zu Hause" (grün) bzw. „Unterwegs" (amber). Bei `atHome == null`
wird kein Badge gerendert. Modell `tractive.model.ts` sowie
`pets.component.html`/`.scss` werden entsprechend ergänzt.

### Dashboard-Kachel

Neue Kachel im Footer neben der Türschloss-Kachel: Hunde-Icon, Name, Status
„Zu Hause"/„Unterwegs", bei mehreren Tieren eine Zeile je Tier.

Das Markup kommt **direkt in `dashboard.component.html`**, keine Kind-Komponente: die
`lumina`-Styles sind in `dashboard.component.scss` gekapselt und würden in einer
Kind-Komponente lautlos nicht greifen. Daten kommen über den vorhandenen
`TractiveService`. Sind keine Haustiere vorhanden (Tractive aus, nicht angemeldet, noch
kein Poll), entfällt die Kachel komplett.

## Konfiguration & Rollout

Neue Properties in `application.properties`:

```properties
tractive.home-arrival-radius-meters=500
tractive.powered-off-after-minutes=60
tractive.powered-off-min-battery-percent=15
```

`TRACTIVE_HOME_LAT` und `TRACTIVE_HOME_LON` werden bereits gelesen, sind aber **nicht
gesetzt** und fehlen in `docker-compose.yml`. Zum Rollout:

1. Beide Variablen in `docker-compose.yml` ergänzen und mit den Koordinaten des Hauses
   belegen.
2. Backend neu starten.

Ohne diesen Schritt entsteht die Entität nicht — sichtbar an der Warnung im Log, an einer
fehlenden Dashboard-Kachel und an einem fehlenden Badge. Es geht nichts kaputt; es
passiert nur nichts.

**Nach dem ersten echten Betrieb nachjustieren:** `powered-off-after-minutes` ist auf 60
gesetzt, weil das reale Melde-Intervall unbekannt ist. Sobald `positionAgeMinutes` im
Entity-Viewer über einige Tage beobachtet wurde, sollte der Wert auf knapp über das
tatsächliche Intervall gesenkt werden — sonst dauert es unnötig lange, bis die Kachel nach
dem Heimkommen umspringt.

## Tests

- **`TractiveHomeResolverTest`** (neu), je ein Test pro Regel:
  Home-Koordinaten fehlen ⇒ leer; Laden gewinnt über eine Position weit außerhalb;
  frische Position innerhalb / außerhalb / genau auf dem Rand; Stille + gesunder Akku +
  Heimnähe ⇒ zu Hause mit `basis=POWERED_OFF`; Stille + leerer Akku ⇒ Positionsurteil mit
  `stale=true`; Stille + gesunder Akku, aber weit weg ⇒ Positionsurteil `off`;
  `batteryLevel == null` ⇒ Regel 4 greift nicht; Zeitstempel fehlt ⇒ als frisch behandelt;
  Ankunftsradius kleiner als Home-Radius ⇒ Home-Radius gewinnt; keine Position, lädt
  nicht ⇒ leer.
- **`TractiveEntityMapperTest`**: Home-Entität wird erzeugt bzw. bei fehlender Aussage
  weggelassen; Attribute `basis`, `stale`, `distanceMeters` stimmen.
- **`TractivePollingServiceTest`**: Bei einem Ausfall wird die Home-Entität *nicht* auf
  `unavailable` gesetzt, die übrigen schon.
- **`TractivePetServiceTest`**: `atHome` wird korrekt befüllt, `null` bei fehlender
  Aussage.

## Bewusst nicht umgesetzt (YAGNI)

- **Kein Zonenname-Abgleich** als zweiter Weg zur Home-Erkennung. Zwei Wahrheiten für
  dieselbe Frage machen später unklar, warum die Entität gerade `off` sagt.
- **Keine Historie** der Anwesenheit. Wer sie braucht, kann sie über einen Flow schreiben.
- **Kein Hysterese-/Entprellmechanismus** über die 60-Minuten-Schwelle hinaus. Erst mit
  echten Daten lässt sich beurteilen, ob es Flattern gibt.

## Bekannte Grenzen

- **Regel 4 ist geraten, nicht gemessen.** Fällt der Tracker unterwegs mit gesundem Akku
  innerhalb von 500 m um das Haus aus (kein Netz, Defekt, verloren), meldet die Entität
  fälschlich „zu Hause". Das ist der bewusst akzeptierte Preis dafür, dass das Ausschalten
  zu Hause überhaupt erkannt wird.
- **Ein Alarm-Flow sollte auf `off` triggern, nicht auf das Ausbleiben von `on`.** Bei
  fehlenden Daten (Regel 1/6) wird gar nichts gemeldet, und die Entität behält ihren alten
  Wert.
- **Die 60-Minuten-Schwelle ist unverifiziert**, siehe Rollout-Abschnitt.
- **Ein dauerhafter Cloud-Ausfall friert die Home-Entität unbegrenzt ein — bewusst akzeptiert (Entscheidung 2026-07-26).**
  Tractive gibt kein Refresh-Token aus; ein abgelaufenes Token verlangt eine manuelle
  Neuanmeldung. Bis dahin läuft der Poller jede Minute in den Ausfallpfad. `location`,
  `battery` und `charging` werden dabei sichtbar `unavailable`, die Home-Entität per
  Konstruktion nicht — sie behält ihren letzten Wert, ohne jedes Anzeichen, dass er alt
  ist. Ein fünfminütiger Netzaussetzer und eine seit Wochen vergessene Anmeldung sind an
  der Entität nicht unterscheidbar. **Konsequenz für Flows:** ein „Hund hat das Grundstück
  verlassen"-Flow auf dieser Entität ist in genau diesem Zustand wirkungslos, weil sich der
  Zustand gar nicht mehr ändert. Eine Zeitgrenze (nach N Stunden Ausfall auch die
  Home-Entität `unavailable` melden) wurde erwogen und zugunsten der einfacheren Logik
  verworfen. Wird später ein sicherheitsrelevanter Flow darauf gebaut, ist das die erste
  Stelle zum Nachziehen
- **Die gesamte Tractive-Integration ist noch nicht gegen einen echten Account getestet.**
  Insbesondere ist offen, ob `device_hw_report` bei ausgeschaltetem Tracker überhaupt noch
  einen Akkustand liefert. Liefert es `null`, greift Regel 4 fail-safe nie — dann muss der
  zuletzt *gesehene* Akkustand im Poller zwischengespeichert werden. Das ist der erste
  Punkt, der bei der Verifikation zu prüfen ist.
