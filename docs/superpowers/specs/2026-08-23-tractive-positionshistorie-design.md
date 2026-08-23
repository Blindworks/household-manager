# Eigene Positionshistorie für die Tractive-Spaziergänge

Datum: 2026-08-23

## Anlass

Auf der Tablet-Ansicht `/tablet/toni` sind praktisch nur die aktuelle Runde und
allenfalls der laufende Tag sichtbar, egal ob 7, 14 oder 30 Tage gewählt sind.

Die Ursache ist strukturell und liegt an zwei Stellen:

1. **Die Cloud liefert die Historie nicht.** Beim Basic-Abo reicht die
   Positionshistorie von Tractive nur etwa 24 Stunden zurück. Was älter ist,
   lässt sich gar nicht mehr abrufen.
2. **Der vorhandene Cache ist ein Abruf-Cache, kein Speicher.**
   `TractiveWalkService` hält Tage in einer `ConcurrentHashMap` — nur im
   Arbeitsspeicher, verloren bei jedem Neustart, und er enthält ausschließlich
   Tage, die jemand schon einmal erfolgreich abgerufen hat. Einen Tag, den die
   Cloud nie geliefert hat, kann er nie enthalten.

Damit ist klar: **Historie entsteht nur, wenn wir sie selbst mitschreiben.**

## Der tragende Fund

`TractivePollingService` läuft ohnehin **jede Minute** und holt dabei die
aktuelle Position jedes Tiers (`TractivePetSnapshot.position`). Diese Position
wird beim nächsten Poll überschrieben und ist danach weg.

Wir haben also längst einen Positionsstrom im Minutentakt — wir heben ihn nur
nicht auf. Ihn zu speichern kostet **keinen einzigen zusätzlichen
Cloud-Aufruf** und ist vom Abo unabhängig.

## Die kritische Fallstricke-Stelle

**Wenn der Tracker ausgeschaltet ist, liefert die API weiter die letzte
bekannte Position** — mit unverändertem `positionTime`.

Schriebe der Recorder stumpf bei jedem Poll eine Zeile, entstünde ein
künstlich lückenloser Positionsstrom. `TractiveWalkDetector` erkennt
Spaziergänge aber **gerade an den Funkpausen über 30 Minuten**
(`OFF_GAP`) — dieser Haushalt schaltet den Tracker zu Hause aus und nur für
die Runde ein. Die Folge wäre ein einziger, nie endender „Spaziergang".

Gespeichert wird deshalb **nur, wenn `positionTime` neu ist**. Abgesichert
wird das nicht allein durch eine Prüfung im Code, sondern durch einen
**eindeutigen Schlüssel auf (`tracker_id`, `position_time`)** — damit kann
auch ein paralleler Poll oder ein zusätzlich ausgelöstes „Jetzt
aktualisieren" keine Dublette erzeugen.

## Architektur

### Was gespeichert wird: Rohpositionen, nicht fertige Runden

Gespeichert werden die **Positionspunkte**; die Spaziergänge werden bei jedem
Abruf daraus abgeleitet. `TractiveWalkDetector` bleibt dabei unverändert.

**Begründung:** Es gibt genau eine Wahrheit, die nicht mit einer zweiten
auseinanderlaufen kann. Vor allem aber sind die Erkennungsregeln ausdrücklich
als Wette auf die Ein-/Ausschaltgewohnheit dieses Haushalts dokumentiert
(`TractiveWalkDetector`, Entscheidung 2026-07-28): bleibt der Tracker
unterwegs einmal dauerhaft an, muss der Detector zurück auf die
Home-Radius-Logik. Mit gespeicherten Rohpunkten rechnet sich in diesem Fall
die **gesamte** Historie neu, ohne Migration und ohne Datenverlust.

30 Tage sind bei einem Punkt pro Minute und ~2 Stunden Gassi täglich rund
3600 Zeilen. Die Erkennung darüber kostet Millisekunden.

**Verworfen:**

- *Zusätzlich die erkannten Runden materialisieren:* zwei Tabellen, die
  auseinanderlaufen können, und jede Regeländerung braucht einen
  Neuberechnungslauf. Kauft bei Haushaltsgröße nichts.
- *Nur die Runden speichern, Rohpunkte verwerfen:* die Karte einer vergangenen
  Runde wäre für immer verloren, und eine Regeländerung wirkte nur noch nach
  vorn. Widerspricht der Entscheidung, unbegrenzt aufzuheben.

### Schreibpfad

Neue Klasse `TractivePositionRecorder` im Paket
`com.household.manager.tractive`. Sie wird im Poll-Zyklus von
`TractivePollingService` mit den eingesammelten Snapshots aufgerufen und legt
je Tier höchstens eine Zeile ab.

**Der Recorder wirft nie.** Ein Datenbankfehler darf den Poller nicht kippen —
derselbe Poll versorgt auch die Entitäten, die Dashboard-Kachel und den
Zu-Hause-Sensor. Das ist das etablierte Muster von `PowerHistoryRecorder`,
`AuditService` und `PushNotificationService`.

Übersprungen wird eine Position ohne verwertbare Koordinaten
(`TractivePositionDto.hasCoordinates()`) und eine ohne `positionTime` — ein
geratener Zeitstempel würde die Lückenerkennung verfälschen.

### Tabelle `tractive_position`

Liquibase-Changeset `20260823-0048-create-tractive-position-table.xml`,
eingebunden in `db.changelog-master.xml`.

| Spalte | Typ | Anmerkung |
|---|---|---|
| `id` | BIGINT, PK, auto | |
| `tracker_id` | VARCHAR | Hardware-Id des Trackers |
| `position_time` | DATETIME | Zeitpunkt **des Berichts**, nicht des Polls |
| `latitude` | DOUBLE | |
| `longitude` | DOUBLE | |
| `accuracy` | DOUBLE | nullable |
| `sensor_used` | VARCHAR | nullable, z. B. `GPS` / `KNOWN_WIFI` |

Eindeutigkeitsschlüssel auf (`tracker_id`, `position_time`).
Index auf (`tracker_id`, `position_time`) für die Bereichsabfrage — der
Eindeutigkeitsschlüssel deckt das mit ab.

Die Entität liegt in `com.household.manager.model.entity`, das Repository
**zwingend** in `com.household.manager.repository` — `JpaConfig` schränkt das
Scanning auf dieses Paket ein, ein Repository woanders wird nicht gefunden.

`position_time` wird als `Instant` geführt. Tractive liefert Epochensekunden,
die Auflösung ist also sekundengenau; das passt zum Eindeutigkeitsschlüssel.

### Lesepfad

`TractiveWalkService.getWalks(trackerId, days)` liest die Punkte künftig aus
der Datenbank statt aus der Cloud und gibt sie unverändert an
`TractiveWalkDetector`. Die DB-Zeilen werden dafür auf `TractivePositionDto`
abgebildet, damit der Detector nicht angefasst werden muss.

**Damit entfallen ersatzlos:** der Tages-Cache (`dayCache`, `DayKey`,
`CachedDay`, `pruneOldDays`), die Rate-Limit-Behandlung (`rateLimitedUntil`,
`RATE_LIMIT_COOLDOWN`, das Abfangen von `TractiveRateLimitException`), das
Zerlegen in 24-Stunden-Häppchen (`MAX_CHUNK`) und die Fehlerzählung über
`daysWithData`. Der Endpunkt wird zu einer Bereichsabfrage plus dem
unveränderten Detector.

Das ist die eigentliche Vereinfachung: rund 90 Zeilen Cloud-Sonderbehandlung
fallen weg — **und mit ihnen die drei beim letzten Merge dokumentierten
Preise:** Rate-Limit beim ersten 30-Tage-Klick, stille Teilergebnisse ohne
Fehlermeldung, und eine vom Abo abhängige Reichweite.

Erhalten bleibt die Prüfung auf hinterlegte Home-Koordinaten: der Detector
braucht die Home-Zone, um eine Runde von einem Aufenthalt auf der Ladeschale
zu unterscheiden. Ohne Zuhause bleibt es bei der klaren 400-Antwort.

**Die Anmeldeprüfung entfällt.** Der Lesepfad braucht keinen Cloud-Zugang
mehr; gespeicherte Runden bleiben auch dann sichtbar, wenn die
Tractive-Anmeldung abgelaufen ist. Das ist eine Verbesserung, keine
Nachlässigkeit — die Kachel zeigt dann weiter, was war, während die
Live-Position erwartungsgemäß `unavailable` meldet.

### Obergrenze des Zeitraums

`MAX_DAYS` verliert seinen Sinn als Cloud-Schutz. Die Konstante bleibt als
reine Eingabevalidierung, wird aber auf **365** angehoben — sonst wäre die
Entscheidung „unbegrenzt aufheben" schon an der API abgeschnitten. Die
Tablet-Ansicht bietet unverändert 7 / 14 / 30 Tage an.

### Aufbewahrung

Keine. Es gibt bewusst **keinen** Aufräumjob: bei einem Punkt pro Minute
während der Spaziergänge bleibt die Tabelle auch nach Jahren klein, und
Jahresvergleiche werden dadurch später möglich.

## Genauigkeit — offen ausgesprochen

Ein Punkt pro Minute ist gröber als die Aufzeichnung des Trackers selbst. Die
**Distanz** fällt dadurch systematisch etwas zu niedrig aus, weil Kurven zu
Geraden werden. Dauer und Zeitpunkt bleiben exakt.

Wer die Zahlen mit der Tractive-App vergleicht, wird also kleinere
Kilometerwerte sehen. Das ist der Preis dafür, ohne zusätzliche Cloud-Abrufe
auszukommen, und bewusst akzeptiert.

## Historie beginnt beim Deploy

Was vor dem Deploy war, ist verloren — die Cloud gibt es beim Basic-Abo nicht
mehr her. Die Kachel füllt sich also ab dem Deploy: nach einer Woche stehen
7 Tage, nach einem Monat 30. Ein einmaliges Nachfüllen aus der Cloud wurde
bewusst verworfen; es brächte realistisch einen Tag bei zusätzlichem
Rate-Limit-Risiko.

## Tests

- **`TractivePositionRecorder`**: eine unveränderte `positionTime` erzeugt
  **keine** zweite Zeile; eine Position ohne Koordinaten oder ohne Zeitstempel
  wird übersprungen; ein Repository-Fehler wird geschluckt und bricht den
  Poll-Zyklus nicht ab.
- **`TractiveWalkService`** gegen ein Repository statt gegen den API-Client:
  Runden werden aus gespeicherten Punkten korrekt abgeleitet; es findet
  **kein** Cloud-Aufruf mehr statt (der API-Client-Mock darf nicht berührt
  werden); ohne hinterlegtes Zuhause weiterhin die klare Fehlermeldung.
- **`TractiveWalkDetectorTest`** bleibt unverändert gültig — die
  Erkennungslogik wird nicht angefasst.
- **`TractivePollingServiceTest`**: der Recorder wird je Poll-Zyklus
  aufgerufen; wirft er dennoch, läuft der Poll zu Ende.

## Bewusst nicht Teil davon

- Keine Änderung an `TractiveWalkDetector` und seinen Schwellen.
- Kein Nachfüllen alter Zeiträume aus der Cloud.
- Keine Aufbewahrungsgrenze und kein Aufräumjob.
- Keine Änderung am Frontend: die Tablet-Ansicht und der Dashboard-Dialog
  rufen denselben Endpunkt wie bisher.
- Keine Änderung an der Rate-Limit-Behandlung des Pollers:
  `TractiveRateLimitException` und ihr Handler im `GlobalExceptionHandler`
  bleiben, weil `TractivePollingService` und `TractiveApiClient` sie weiter
  brauchen.

## `getPositionHistory` wird entfernt

Nach dem Umbau ist `TractiveWalkService` der einzige Aufrufer von
`TractiveApiClient.getPositionHistory` — danach ruft die Methode **nur noch
ihr eigener Test** auf. Sie wird deshalb samt ihren beiden Testfällen
gelöscht.

Das ist bewusst und nicht leichtfertig: der Endpunkt war der einzige, dessen
Antwortform nie gegen eine Referenzimplementierung verifiziert werden konnte,
und die daran erarbeiteten Erkenntnisse (Tages-Häppchen wegen Code 7500
HISTORY, Rate-Limit 4006) waren teuer. Sie bleiben aber erhalten — in
`CLAUDE.md`, in dieser Spec und in der Git-Historie. Produktionscode, den nur
sein eigener Test aufruft, vorzuhalten, wäre die schlechtere Wahl: er
suggeriert einen Pfad, den es nicht mehr gibt.

Wer später doch einmal aus der Cloud nachfüllen will, holt sich die Methode
aus der Historie zurück — der Commit ist über diese Spec auffindbar.
