# Sonnenstand für Flows: Entität `sensor.sun`, Node `sun-trigger`, Sonnenausdrücke in `time-condition`

Datum: 2026-09-21

## Anlass

Die Flow-Engine kennt Zeit nur als Cron-Ausdruck (`schedule-trigger`) und festes
Uhrzeitfenster (`time-condition`). „Licht an bei Dämmerung" oder „Taster nur, wenn es
dunkel ist" lassen sich damit nicht ausdrücken — die Sonnenzeiten wandern übers Jahr
um mehr als vier Stunden. Es soll beides möglich werden: **zu** einem Sonnenereignis
etwas tun (Trigger, auch mit Versatz davor) und **prüfen**, ob gerade Tag oder Nacht
ist (Bedingung).

Drei Bauformen standen zur Wahl:

- **nur Entität** `sensor.sun` — fügt sich in alle bestehenden Nodes, kann aber keinen
  negativen Versatz („30 min vor Untergang");
- **nur Nodes** (`sun-trigger`, Sonnenausdrücke in `time-condition`) — voller Versatz,
  aber die Sonne wäre nirgends sonst sichtbar (Telegram, Entity-Katalog);
- **beides** — gewählt, mit der Regel, dass **eine** Klasse rechnet und alle anderen sie
  fragen, damit zwei Definitionen von „Sonnenuntergang" nie auseinanderlaufen.

Die Berechnung übernimmt `org.shredzone.commons:commons-suncalc` (Apache-2.0, keine
Abhängigkeiten, kennt Dämmerungsstufen und Polarfälle) statt eines nachgebauten
NOAA-Algorithmus.

## 1. Kern: `SunTimesService` — einzige Definition der Sonnenzeiten

Neues Paket `backend/src/main/java/com/household/manager/sun/`. Nur dieses Paket
importiert `commons-suncalc` (Muster `RecurrenceExpansionService` als einzige
`lib-recur`-Stelle).

- `SunTimesService.timesFor(LocalDate)` → `Optional<SunTimes>` mit Record
  `SunTimes(dawn, sunrise, sunset, dusk)` als `ZonedDateTime` in der Zone des
  `Clock`-Beans (Europe/Berlin). `sunrise`/`sunset` mit Twilight `VISUAL`, `dawn`/`dusk`
  mit `CIVIL` (Sonne 6° unter dem Horizont).
- **Koordinaten kommen aus `TractiveHomeSettingsService.getSettings()`** — bei jedem
  Aufruf frisch gelesen, nicht gecacht: ändert der Admin das Hunde-Zuhause, gilt es beim
  nächsten Minutenlauf (Muster `TractiveHomeResolver`). Nutzerentscheidung 2026-09-21;
  die Kopplung ist bewusst und wird auf der Admin-Seite sichtbar gemacht (Abschnitt 5).
- `Optional.empty()`, wenn `hasHomeCoordinates()` falsch ist oder die Bibliothek für
  ein Ereignis `null` liefert (Polarfall — in Deutschland unmöglich, aber die API kann
  es, und ein NPE im Scheduler wäre schlimmer). **Lesen wirft nie.**
- `phaseAt(ZonedDateTime)` → `Optional<SunPhase>` mit `DAY`, `DUSK`, `NIGHT`, `DAWN`,
  aus den vier Zeiten **desselben Tages**, halboffen wie `TimeWindow`:
  `[sunrise, sunset)` = DAY, `[sunset, dusk)` = DUSK, `[dawn, sunrise)` = DAWN, sonst
  NIGHT.
- `SunEvent`-Enum (`DAWN`, `SUNRISE`, `SUNSET`, `DUSK`) mit `fromKey(String)` für die
  Schlüsselwörter `dawn`/`sunrise`/`sunset`/`dusk` — die vier Wörter stehen genau einmal,
  Trigger und Ausdrucks-Parser fragen dasselbe Enum.

Wer fragt: die Entität (2), der Trigger (3), `time-condition` (4).

## 2. Entität `sensor.sun`

`SunEntityPublisher` (`@Scheduled`, jede Minute, wirft nie — Muster
`PresencePollingService`). Neue `EntitySource.SUN`.

- Entity-Id `sensor.sun`, State = Phase in Kleinbuchstaben (`day`/`dusk`/`night`/`dawn`).
- Attribute: `dawn`, `sunrise`, `sunset`, `dusk` (heute, `HH:mm`), `nextSunrise`,
  `nextSunset` (ISO-Zeitstempel des jeweils **nächsten** Ereignisses nach jetzt — heute,
  wenn es noch bevorsteht, sonst morgen; für Ansagen „Sonnenuntergang um 19:12"), `elevation`
  (Sonnenhöhe in Grad, eine Nachkommastelle — für einen späteren Blend-Flow, ohne jetzt
  mehr zu bauen).
- Der Entity-State-Layer feuert `EntityStateChangedEvent` nur bei Wertänderung, also
  viermal am Tag; die minütliche Meldung kostet sonst nichts. „Bei Sonnenuntergang" ist
  damit `entity-state-trigger sensor.sun changed to dusk`; ein Versatz **danach** geht
  über den vorhandenen `delay`-Node.
- **Kein Zuhause konfiguriert** ⇒ `unavailable` **mit erhaltenen Attributen** (Muster
  Zigbee/Blink: `EntityStateWriter.upsert` überschreibt sie sonst), nie eine geratene
  Phase. Die engine-weite Unterdrückung des Übergangs nach `unavailable` verhindert
  Fehlfeuer. Die Entität existiert ab dem ersten Lauf auch ohne Koordinaten, damit im
  Entity-Katalog sichtbar ist, *dass* etwas fehlt.
- **Neustart über eine Flanke:** der erste Lauf sieht z. B. DB-Wert `dusk` und rechnet
  `night` → der Übergang feuert einmal, um die Deploy-Dauer verspätet. Bewusst
  akzeptiert und an `lastChanged` ablesbar.
- **Kein `deviceClass`** (siehe Blink-Absatz in `CLAUDE.md`: die beiden Auswerter fragen
  `door`/`power`, nichts davon passt). Keine Dashboard-Kachel, kein Frontend — die
  Entität erscheint über den bestehenden Entity-Katalog und in `flow_list_entities`.

## 3. Trigger-Node `sun-trigger`

`SunTriggerHandler implements TriggerNodeHandler` in `flowengine/nodes/`, Muster
`ScheduleTriggerHandler`. Ein Ausgang, `watchedEntityId` leer.

| config | Pflicht | Wert |
|--------|---------|------|
| `event` | ja | `dawn` / `sunrise` / `sunset` / `dusk` |
| `offsetMinutes` | nein | ganze Zahl −240 … +240, Default 0 (`sunset`, `-30` = 30 min vor Untergang) |

- **Einplanung:** `register()` fragt `SunTimesService` für heute, morgen und übermorgen,
  nimmt den ersten Zeitpunkt `event + offset`, der **nach jetzt** liegt (Clock-Bean), und
  plant ihn über `ctx.scheduler().schedule(runnable, Instant)`. Drei Tage decken auch
  einen negativen Versatz ab, der das heutige Ereignis schon in die Vergangenheit
  schiebt.
- **Feuern:** emittiert auf Port 0 mit `sunEvent`, `offsetMinutes`, `scheduledFor`,
  `timestamp`, `triggerNodeId` (Muster Cron-Trigger) und plant sich **selbst neu** für
  das nächste Vorkommen. Ein `Instant` statt einer Wandzeit ist zeitumstellungssicher;
  weil nach jedem Feuern neu gerechnet wird, greifen geänderte Koordinaten spätestens
  am Folgetag.
- **Cleanup** (Undeploy/Re-Deploy) storniert das aktuelle `ScheduledFuture` und setzt
  ein `cancelled`-Flag im `ctx.state()`; das Feuer-Runnable prüft das Flag, **bevor** es
  neu plant — sonst hinterließe ein Re-Deploy genau zur Flanke einen Geister-Timer, der
  den alten Flow-Stand weiter füttert. Das Future wird per `state().put` abgelegt — es
  gibt nie zwei Schreiber gleichzeitig (das nächste Future entsteht erst nach dem Feuern
  des vorigen), `compute` brächte hier nichts.
- **`ctx.emit` ist in try/catch gekapselt, die Neuplanung läuft immer** (Review-Befund
  2026-09-21): ein Einmal-Task des `TaskScheduler` propagiert eine Exception nur ins nie
  abgefragte Future — ohne den Fang wäre der Trigger nach einer vollen Executor-Queue
  lautlos für immer tot. Ein ungültiger `offsetMinutes` lässt `register` werfen statt
  still auf 0 zu fallen, weil `setEnabled`/Bootstrap ohne erneute Validierung deployen.
- **Kein Zuhause konfiguriert** beim Registrieren ⇒ Warnung im Log und ein erneuter
  Versuch in 60 Minuten. Kein Deploy-Abbruch — sonst ließe sich der Flow nicht anlegen,
  bevor die Koordinaten stehen (dieselbe Reihenfolge-Toleranz wie `helper-set`, das die
  Helfer-Existenz beim Deploy nicht prüft).
- **Validierung beim Deploy:** unbekanntes `event`; `offsetMinutes` nicht ganzzahlig oder
  außerhalb ±240 (schützt vor `-3000` als Tippfehler, der den Trigger auf den Vortag
  würfe).
- **Verpasste Ereignisse** während eines Backend-Ausfalls werden nicht nachgefeuert —
  wie beim Cron-Trigger. Wer „Licht muss an sein" auch nach einem Ausfall braucht, baut
  zusätzlich auf `sensor.sun`, dessen Übergang nach dem Neustart nachfeuert. Das ist die
  Arbeitsteilung der beiden: **Entität für Zustand, Trigger für Zeitpunkt mit Versatz.**

## 4. `time-condition` versteht Sonnenausdrücke

Kein zweiter Bedingungs-Node. `from`/`to` akzeptieren eine kleine Grammatik:

```
HH:mm  |  dawn | sunrise | sunset | dusk  [ +N | -N ]      (N = Minuten, |N| ≤ 240)
```

Beispiele: `sunset-30` bis `23:00`, `dusk` bis `dawn` („dunkel"), `07:00` bis `sunrise+60`.

- **Parser** `SunTimeExpression.parse(String)` im Paket `sun/` → sealed
  `TimeExpression` mit `Fixed(LocalTime)` und `SunEvent(event, offsetMinutes)`;
  `resolve(LocalDate, SunTimesService)` → `Optional<LocalTime>`. Leerzeichen um den
  Ausdruck werden toleriert (wie heute), innerhalb nicht.
- **Laufzeit:** beide Ausdrücke für **heute** auflösen, daraus `TimeWindow` bauen,
  `contains(now)`. Die Fensterregel (halboffen, Mitternacht überspannend) bleibt
  unverändert die von `TimeWindow`.
- **Nicht auflösbar** (kein Zuhause konfiguriert) ⇒ **Port 1 „falsch"** plus
  Debug-Eintrag an der Node. „Nicht prüfbar" darf nicht als „erfüllt" gelten, sonst
  schaltete „Licht wenn dunkel" mittags — die umgekehrte Falle zur `!=`-Ausnahme bei
  `unavailable`, hier bewusst konservativ gelöst.
- **Validierung:** unparsbarer Ausdruck ist Fehler; identische Ausdrücke
  (`sunset`/`sunset`, `20:00`/`20:00`) bleiben verboten (leeres Fenster). Ein
  gemischtes Paar, das an einem Tag zufällig zusammenfällt (`18:00` und `sunset` im
  März), ergibt an diesem Tag ein leeres Fenster → falsch; das ist korrekt und
  minutengenau selten.
- **Unberührt:** `ModeQuickAccessResolver` und `mode_quick_access` bleiben bei festen
  `HH:mm` — ein Schnellzugriff „ab Dämmerung" wäre ein eigener Wunsch mit DB-Änderung.

## 5. Randbedingungen, Frontend, Doku

- `org.shredzone.commons:commons-suncalc` in der `backend/pom.xml`; nur `sun/`
  importiert sie.
- **Kopplung ans Hunde-Zuhause:** Hinweiszeile auf der Admin-Seite `admin/tractive`
  („Auch die Sonnenzeiten der Flows rechnen mit diesen Koordinaten"). Kein weiteres
  Frontend.
- Node-Katalog: eine Zeile in `pages/flows/node-catalog.ts`
  (`'sun-trigger': 'Sonnenstand'`); der MCP-Server bekommt den Node automatisch über
  `flow_node_types`.
- Doku: Abschnitte in `docs/flows/flow-import-format.md` (`sun-trigger`, erweiterte
  `time-condition`, Entität `sensor.sun`) und `CLAUDE.md`.
- Keine DB-Änderung, keine Security-Zeile (keine neuen Endpunkte).

## Tests

Zeit überall über `Clock.fixed` in Europe/Berlin.

- `SunTimesServiceTest`: bekannte Werte (21.06. und 21.12. an den Tractive-Koordinaten,
  Toleranz ±2 min); Phasen-Grenzen halboffen (`sunrise` ⇒ DAY, `sunset` ⇒ DUSK,
  `dusk` ⇒ NIGHT, `dawn` ⇒ DAWN); ohne Koordinaten leer; Fehler der Bibliothek
  ⇒ leer statt Exception.
- `SunEntityPublisherTest`: Phase → State und Attribute; fehlendes Zuhause ⇒
  `unavailable` mit erhaltenen Attributen; wirft nie.
- `SunTriggerHandlerTest`: nächstes Vorkommen heute / morgen / über Mitternacht; negativer
  Versatz, der heute schon vorbei ist ⇒ morgen; Feuern plant neu; Cleanup verhindert
  Neuplanung; fehlendes Zuhause ⇒ Wiederholung in 60 min statt Fehler; jeder
  Validierungsfehler einzeln.
- `SunTimeExpressionTest`: Grammatik inkl. Fehlerfälle (`sunset -30`, `sunset-300`,
  `noon`, `25:00`).
- `TimeConditionNodeHandlerTest` erweitert: Sonnenausdruck im/außerhalb des Fensters;
  nicht auflösbar ⇒ falsch; gleiche Ausdrücke abgelehnt. Bestehende Fälle bleiben.

## Rollout

1. Branch mergen, Backend auf PROD deployen.
2. Prüfen, dass unter Admin → Hundetracker-Zuhause Koordinaten stehen — sonst bleibt
   `sensor.sun` `unavailable` und jeder `sun-trigger` wartet stündlich.
3. **Erst danach** via flow-mcp Flows anlegen (`flow_create` → `flow_deploy` →
   `flow_set_enabled`). Vorher kennt `flow_deploy` weder `sun-trigger` noch die
   Sonnenausdrücke (dieselbe Falle wie bei `push-send` und `time-condition`).
