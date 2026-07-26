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

Das prägt den gesamten Entwurf. Zwei Folgen:

1. Der letzte Positionsbericht stammt möglicherweise noch von *unterwegs* (z. B. 200 m vor
   der Haustür), bevor der Tracker ausging. Rein positionsbasiert bliebe die Entität dann
   dauerhaft auf „nicht zu Hause" hängen, obwohl der Hund längst da ist.
2. Eine „unavailable"-Meldung bei fehlender Live-Position wäre für diese Entität falsch —
   der Normalzustand zu Hause ist ja gerade „kein frischer Bericht".

Deshalb zählt der **Ladezustand** als eigenständiger Beweis für „zu Hause", und die
Entität friert im Zweifel auf ihrem letzten Wert ein, statt `unavailable` zu melden.

## Entscheidungen

| Frage | Entscheidung | Begründung |
|---|---|---|
| Was heißt „zu Hause"? | Eigene Home-Koordinaten (`tractive.home-latitude/-longitude/-radius-meters`) | Unabhängig davon, wie die Tractive-Geofences heißen oder ob sie lesbar sind |
| Tracker aus, letzter Bericht unterwegs | `charging == on` zählt als „zu Hause" | Der Tracker kommt zu Hause auf die Ladeschale — schließt genau diese Lücke |
| Keine Aussage möglich | Kein Update melden → letzter Wert bleibt stehen | Nie ein geratenes `off`; ein Alarm-Flow darf nicht bei jedem GPS-Aussetzer feuern |
| Cloud-/Token-Ausfall | Nur die Home-Entität behält ihren Wert | `location`/`battery`/`charging` bleiben `unavailable`, sonst wirkt ein 3 Tage alter Akkuwert wie ein Live-Wert |
| Home-Radius | bleibt bei 100 m | Bewusst eng gelassen; die Ladeschale fängt den Grenzfall ab. Falls der Hund im Garten zu oft auf „Unterwegs" springt, ist `tractive.home-radius-meters` die eine Stellschraube |

## Die Entität

`binary_sensor.tractive_<trackerId>_home`

| Feld | Wert |
|---|---|
| `domain` | `BINARY_SENSOR` |
| `source` / `sourceRef` | `TRACTIVE` / `<trackerId>` |
| `friendlyName` | `<Name> zu Hause` |
| `state` | `on` / `off` |
| Attribut `deviceClass` | `presence` (wie beim Tablet-Präsenzsensor) |
| Attribut `basis` | `charging` oder `position` |
| Attribut `distanceMeters` | nur bei `basis=position` |
| Attribut `positionTime` | Zeitstempel des zugrunde liegenden Positionsberichts, falls vorhanden |

`basis` macht im Entity-Viewer und im Flow-Debug nachvollziehbar, **warum** die Entität
gerade so steht — sonst rätselt man bei `on` ohne GPS-Fix.

## Architektur

### `TractiveHomeResolver` (neu)

Eine Klasse, eine Frage. Signatur:

```java
Optional<HomeVerdict> resolve(TractivePetSnapshot snapshot)
```

`HomeVerdict` trägt `atHome` (boolean), `basis` (`CHARGING` / `POSITION`) und optional
`distanceMeters`. Regeln in dieser Reihenfolge:

1. **Home-Koordinaten nicht konfiguriert** → `Optional.empty()`, plus eine Warnung im Log
   (einmalig, nicht pro Poll-Zyklus). Lieber keine Entität als eine erfundene.
2. **`hardware.isCharging() == true`** → `atHome = true`, `basis = CHARGING`, ohne
   Positionsprüfung.
3. **Position mit Koordinaten vorhanden** → Haversine-Distanz zum Home-Punkt
   (`GeoZone.distanceMeters`) ≤ `home-radius-meters` ⇒ `atHome`, `basis = POSITION`.
   Der Rand zählt als innerhalb, konsistent zu `GeoZone.contains`.
4. **Sonst** → `Optional.empty()`.

`Optional.empty()` bedeutet an jeder Aufrufstelle dasselbe: *keine Aussage*. Der
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
wird kein Badge gerendert. Modell `tractive.model.ts` und
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

`TRACTIVE_HOME_LAT` und `TRACTIVE_HOME_LON` werden in `application.properties` bereits
gelesen, sind aber **nicht gesetzt** und fehlen in `docker-compose.yml`. Zum Rollout:

1. Beide Variablen in `docker-compose.yml` ergänzen und mit den Koordinaten des Hauses
   belegen.
2. Backend neu starten.

Ohne diesen Schritt entsteht die Entität nicht — sichtbar an der Warnung im Log, an einer
fehlenden Dashboard-Kachel und an einem fehlenden Badge. Das ist ein stiller Ausfall in
dem Sinn, dass nichts kaputtgeht; es passiert nur nichts.

## Tests

- **`TractiveHomeResolverTest`** (neu): Laden gewinnt über eine Position außerhalb des
  Radius; innerhalb des Radius; außerhalb; Rand zählt als innerhalb; Home-Koordinaten
  nicht konfiguriert ⇒ leer; weder Ladezustand noch Position ⇒ leer.
- **`TractiveEntityMapperTest`**: Home-Entität wird erzeugt bzw. bei fehlender Aussage
  weggelassen; Attribute `basis`/`distanceMeters` stimmen.
- **`TractivePollingServiceTest`**: Bei einem Ausfall wird die Home-Entität *nicht* auf
  `unavailable` gesetzt, die übrigen schon.
- **`TractivePetServiceTest`**: `atHome` wird korrekt befüllt, `null` bei fehlender
  Aussage.

## Bewusst nicht umgesetzt (YAGNI)

- **Kein Zonenname-Abgleich** als zweiter Weg zur Home-Erkennung. Zwei Wahrheiten für
  dieselbe Frage machen später unklar, warum die Entität gerade `off` sagt.
- **Keine Historie** der Anwesenheit. Wer sie braucht, kann sie über einen Flow schreiben.
- **Kein Altersgrenzwert** für den letzten Positionsbericht. Da der Tracker zu Hause
  bewusst tagelang aus sein kann, wäre jede Grenze willkürlich und würde genau den
  Normalfall zerstören.

## Bekannte Grenzen

- **Ausgeschaltet unterwegs, ohne Ladeschale:** Die Tractive-Cloud liefert weiterhin den
  letzten Positionsbericht, also greift Regel 3 und die Entität meldet aktiv `off` — auch
  wenn der Hund inzwischen längst zu Hause ist. Genau dafür existiert Regel 2; wer den
  Tracker zu Hause nicht auflädt, bekommt hier ein falsches „Unterwegs".
- **`Optional.empty()` ist selten:** Es greift nur, wenn gar kein Positionsbericht
  vorliegt (frischer Tracker, Cloud liefert kein Positionsobjekt) und nicht geladen wird.
  Der eingefrorene letzte Wert ist also der Ausnahme-, nicht der Normalfall.
- **Kein `off` wird je geraten** — das ist die bewusste Kehrseite: im Zweifel bleibt die
  Entität lieber veraltet als falsch alarmierend.
