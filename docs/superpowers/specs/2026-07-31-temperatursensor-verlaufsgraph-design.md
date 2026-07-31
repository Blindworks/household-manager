# Verlaufsgraph im Temperatursensor-Dialog

**Datum:** 2026-07-31
**Status:** Entwurf, abgenommen

## Ziel

Der Detaildialog eines Temperatursensors auf dem Dashboard zeigt heute den aktuellen
Messwert (Temperatur, Luftfeuchte, Status, Zeitstempel). Unter diesen Werten soll ein
Verlaufsgraph mit historischen Daten erscheinen. Der bestehende obere Teil des Dialogs
bleibt unverändert.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Kurven | Temperatur **und** Luftfeuchte, Feuchte auf zweiter Y-Achse rechts |
| Zeitraum | Buttons „24 Stunden / 7 Tage / 30 Tage“, wie im Verbraucher-Verlauf |
| Datenweg | Neuer Endpunkt **pro Sensor** mit serverseitiger Mittelung |

Der dritte Punkt ist der einzige mit echtem Aufwand. Die bestehende API
`GET /v1/temperatures?range=…` liefert **Rohmesswerte aller Sensoren gleichzeitig** —
bei 30 Tagen über alle Zigbee-Sensoren eine Antwort, die auf dem Wandtablet weder
schnell übertragen noch flüssig gezeichnet wird. Für einen Dialog, der genau einen
Sensor zeigt, ist das die falsche Nutzlast.

## Backend

### Endpunkt

```
GET /api/v1/temperatures/series?sensorId=<id>&range=DAY|WEEK|MONTH
```

`range` ist optional mit Default `DAY`. Antwort ist das bereits existierende
`TemperatureSensorSeries` (`sensorId`, `name`, `source`, `temperature[]`, `humidity[]`) —
kein neues DTO, und das Frontend-Model dafür existiert ebenfalls schon.

**`sensorId` ist ein Query-Parameter, keine Pfadvariable.** Die IDs tragen einen
Doppelpunkt (`zigbee:12`, `weather:outdoor`, `alexa:<applianceId>`), und die
Alexa-Appliance-ID kommt unkontrolliert aus der Amazon-API. Enthielte sie ein `/`
oder `=`, zerlegte sie ein Pfadsegment und der Endpunkt wäre für genau diese Sensoren
still kaputt.

**Sicherheit:** Der Endpunkt fällt unter die generische Regel `GET /v1/**` → KIOSK in
`SecurityConfig`, das Wandtablet darf ihn also lesen. Eine eigene Matcher-Zeile ist
weder nötig noch erwünscht.

### Service

`TemperatureSeriesService` bekommt `getSensorSeries(String sensorId, TemperatureRange range)`.
Das Präfix von `sensorId` wählt die Quelle:

- `zigbee:<deviceId>` — `deviceId` als `Long` parsen; ist das nicht möglich, gilt der
  Sensor als unbekannt. Messreihen über die vorhandenen
  `findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc`-Abfragen
  (TEMPERATURE und HUMIDITY). Der `friendlyName` kommt aus `ZigbeeDeviceRepository`,
  das dafür neu injiziert wird — die vorhandene Discovery-Abfrage über alle Geräte zu
  laden und zu filtern wäre genau die Verschwendung, die dieser Endpunkt vermeiden soll.
- `weather:outdoor` — unverändert `findByReadingTimeBetweenOrderByReadingTimeAsc`.
- `alexa:<applianceId>` — braucht eine **neue** Repository-Methode
  `findByApplianceIdAndReadingTimeBetweenOrderByReadingTimeAsc`; die heutige Serien-Methode
  lädt alle Appliances und gruppiert in Java.

Der Anzeigename entsteht über die bestehende private Methode `temperatureName(...)`,
damit ein im Entity-Layer vergebener Kurzname im Dialog genauso greift wie auf der
Temperaturseite.

**Unbekannte oder leere `sensorId` ⇒ 404** mit klarer Meldung. Die
`safe(...)`-Fehlerisolierung der Sammel-Abfrage wird hier bewusst **nicht** verwendet:
sie ist dafür da, dass eine kaputte Quelle die Gesamtantwort nicht kippt. Bei genau
einer angefragten Quelle verwandelte sie einen Fehler in einen stumm leeren Graphen —
und der ist von „dieser Sensor hat in diesem Zeitraum nichts gemeldet“ nicht
unterscheidbar.

Ein Sensor, der existiert, aber im Zeitraum keine Werte hat, liefert **200 mit leeren
Reihen** — nicht 404. Das ist eine Aussage über den Zeitraum, kein Fehler.

### Mittelung

Neue Klasse `TemperatureSeriesDownsampler` mit einer reinen Funktion auf `List<TimeValue>`:

| Range | Bucket |
|---|---|
| DAY | 5 Minuten |
| WEEK | 30 Minuten |
| MONTH | 2 Stunden |

Der Bucket-Zeitstempel ist der **Bucket-Anfang**, der Wert das arithmetische Mittel der
enthaltenen Messungen. **Leere Buckets werden weggelassen**, es entstehen keine
künstlichen Nullen.

Anders als beim Leistungsverlauf gibt es **keine `null`-Lückenmarkierung**.
Temperatursensoren melden nur bei Wertänderung; eine Funkpause ist dort der
Normalfall und kein Messausfall. Eine Lücke als Linienabriss zu zeichnen würde einen
ruhigen Sensor wie einen defekten aussehen lassen.

Die Klasse arbeitet quellen-agnostisch auf `TimeValue` und weiß nichts über Zigbee,
Wetter oder Alexa — dadurch ist sie ohne Datenbank testbar.

## Frontend

### Service

`TemperatureService` bekommt `getSensorSeries(sensorId, range)` — dünner Wrapper mit
derselben Fehlerbehandlung wie die bestehenden Methoden.

### Dialog

Im Sensor-Dialog in `dashboard.component.html` (ab Zeile 579) folgt unter dem
Messwerte-Block ein Verlaufsabschnitt mit derselben Struktur wie der
Verbraucher-Verlauf: Zeitraum-Buttons (`lumina__history-range`) und darunter der
ECharts-Container (`lumina__history-chart`). Beide Klassen existieren bereits in
`dashboard.component.scss`; **neues Styling entsteht nicht**.

Das Markup bleibt **direkt in `dashboard.component.html`**. Die `lumina`-Styles sind
dort gekapselt und griffen in einer Kindkomponente lautlos nicht.

### Chart

Zwei Y-Achsen: links °C, rechts % relative Feuchte. Zeitachse `type: 'time'`, Farbgebung
wie im Leistungsverlauf. Hat der Sensor keine Feuchtewerte (leere `humidity`-Reihe),
entfallen Serie **und** rechte Achse — eine leere zweite Achse mitzuzeichnen suggeriert
fehlende Daten, wo es nie welche gab.

`LegendComponent` wird zusätzlich bei ECharts registriert; ohne Legende ist bei zwei
Linien nicht erkennbar, welche welche ist. `LineChart`, `GridComponent`,
`TooltipComponent` und `CanvasRenderer` sind bereits registriert.

### Komponentenzustand

Vier neue Felder in `dashboard.component.ts`, analog zum Verlaufs-Dialog:
`sensorHistoryRange`, `sensorHistoryOptions`, `sensorHistoryEmpty`, `sensorHistoryError`.

- `openSensorDialog` setzt den Zeitraum auf `DAY` zurück und lädt.
- `setSensorHistoryRange` lädt bei Wechsel neu.
- `closeSensorDialog` räumt alle vier Felder ab.

**Verspätete Antworten werden verworfen** — dieselbe Falle wie beim Leistungsverlauf:
eine Antwort wird nur übernommen, wenn `sensorDetail?.sensorId` **und** der aktuelle
Zeitraum noch dem angefragten entsprechen. Ohne diesen Schutz überschreibt eine langsame
30-Tage-Antwort die inzwischen geladene 24-Stunden-Ansicht, und ein alter Fehler legt
sich über einen erfolgreich geladenen Graphen. Der Schutz gilt für Erfolgs- **und**
Fehlerzweig.

### Kein Auto-Refresh des Graphen

Der bestehende Refresh (`refreshSensorDetail`) zieht weiterhin nur die Zahlenwerte oben
nach. Der Graph wird dabei **nicht** neu geladen: ein regelmäßig neu aufgebauter Chart
flackert, und bei 30-Tage-Sicht ist die Last sinnlos — in dieser Auflösung ändert sich
die Kurve nicht sichtbar. Wer aktuellere Daten will, wechselt den Zeitraum oder öffnet
den Dialog neu.

## Tests

**Backend**

- `TemperatureSeriesDownsamplerTest`: Bucket-Grenzen (Punkt exakt auf der Grenze landet
  im folgenden Bucket), Mittelwertbildung, leere Buckets werden ausgelassen, einzelner
  Punkt, leere Eingabe.
- `TemperatureSeriesService`: Präfix-Zuordnung je Quelle; unbekannte, leere und
  nicht-parsbare `sensorId` führen zu 404; existierender Sensor ohne Werte im Zeitraum
  liefert leere Reihen statt 404.

**Frontend**

- Spec für das Verwerfen verspäteter Antworten: Zeitraumwechsel während laufendem
  Request darf die neue Ansicht nicht überschreiben. Das ist der Fall, der ohne Test
  still kaputtgeht.

## Bewusst nicht Teil davon

- Keine Änderung an `GET /v1/temperatures` und keine Umstellung der Temperaturseite auf
  den neuen Endpunkt. Der Sammelabruf hat einen anderen Zweck und funktioniert dort.
- Keine Retention oder Aggregation der Rohtabellen. `zigbee_measurement` wächst
  weiterhin unbegrenzt — ein reales, aber eigenständiges Problem.
