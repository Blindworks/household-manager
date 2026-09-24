# Verbrauch: Jahres- und Monatssummen in der Tablet-Ansicht

**Stand:** 2026-09-24 · **Ziel:** nur Wandtablet (`/tablet/consumption`), Website `/meter-readings` bleibt unverändert

## Anlass

Die Verbrauchsansicht zeigt Balken je Woche oder Monat, im Kachelkopf aber nur den letzten Balken. Es fehlt die Antwort auf „Was habe ich im Jahr / im Monat X insgesamt verbraucht?". Es gibt keine Jahresauflösung, keine Summen, und einen bestimmten Monat liest man nur per Tooltip ab.

## Entscheidung

Variante C, also beides:

1. eine **Jahres-Auflösung** im Diagramm
2. eine **Tabellenansicht** mit Jahressummen, die sich in die Monate aufklappen lassen

## 1. Backend: zwei neue Zeiträume, kein neuer Endpunkt

- `ConsumptionResolution` bekommt `YEAR`. Periodenschlüssel ist `date.getYear()`, `periodStart` der 1. Januar, das Label die Jahreszahl (`"2025"`).
- `ConsumptionRange` bekommt:
  - `YEARS_ALL` (YEAR): Jahresbalken seit der ersten Ablesung
  - `MONTHS_ALL` (MONTH): alle Monate seit der ersten Ablesung, als Datenquelle der Tabelle
- `windowStart` liefert für beide `…_ALL`-Werte `LocalDate.MIN`, also kein Fensterbeginn.
- `aggregateByPeriod`/`mergeGroup` behandeln YEAR mit derselben Operation wie MONTH. Dabei wird der Verbrauch summiert, `estimated` verodert und die Kosten per `sumCosts` gebildet. Damit gelten die bestehenden Regeln automatisch:
  - Fehlt **einer** beitragenden Ablesewoche der Preis, ist `cost` des ganzen Jahres `null`. Eine Teilsumme sähe aus wie ein billiges Jahr.
  - Negative Differenzen (Zählertausch) werden verworfen und geloggt.
  - Eine Ablesewoche über dem Jahreswechsel zählt vollständig zum Jahr ihres **Ablesedatums**. Sie wird nicht tagesgenau aufgeteilt (Muster Monatswechsel).
  - Perioden ohne Ablesung entstehen gar nicht erst, es gibt keine erfundene 0.
- Endpunkt `GET /v1/meter-readings/series?range=…` bleibt unverändert und ist über die generische `GET /v1/**`-Regel KIOSK-lesbar, ohne eigene Zeile in `SecurityConfig`.

## 2. Tablet: Jahres-Auflösung im Diagramm

- Der Auflösungs-Umschalter wird zu „Woche | Monat | Jahr". Bei „Jahr" gibt es genau einen Zeitraumknopf, „Alle Jahre" (`YEARS_ALL`, zugleich Default).
- `RANGE_OPTIONS` und `DEFAULT_RANGE` in `shared/consumption-view.util.ts` bekommen den YEAR-Eintrag. `MONTHS_ALL` steht **nicht** in `RANGE_OPTIONS`, es ist nur Datenquelle der Tabelle.
- Der Kachelkopf zeigt bei „Jahr" den Wert des jüngsten Balkens. Ist das das laufende Kalenderjahr, trägt er den Zusatz „bis heute".
- **Bei „Jahr" entfällt der Vorperiodenvergleich.** `compareToPrevious` liefert für `YEAR` immer `null`. Ein angebrochenes Jahr gegen ein volles Vorjahr wäre eine systematische Falschaussage. Ein Vergleich gegen denselben Zeitraum des Vorjahres ist bewusst nicht Teil dieser Ausbaustufe.
- Kostenmodus je Kachel, blasse Schätzbalken und die Zählung „Perioden ohne hinterlegten Preis" funktionieren unverändert.

## 3. Tablet: Tabellenansicht

- Neuer Umschalter „Diagramm | Tabelle" im Slot `[shellActions]`. In der Tabellenansicht werden Auflösungs- und Zeitraumknöpfe ausgeblendet, weil die Tabelle immer alles zeigt. Der Modus wird nicht gespeichert, nach dem Neuladen steht die Ansicht auf „Diagramm".
- **Eine** Tabelle für alle Zähler ersetzt das Kachelraster:
  - **Zeilen:** Jahre, das neueste oben. Ein Tipp auf eine Jahreszeile klappt die Monate darunter auf, chronologisch vom Januar an. Beim Öffnen ist das jüngste Jahr aufgeklappt. Der Aufklappzustand überlebt den Refresh.
  - **Spalten:** je Zählertyp aus der Antwort eine Spalte (Strom, Gas, Wasser). Ein Typ ohne Daten fehlt.
  - **Zelle:** Verbrauch (`formatConsumption`) und darunter die Kosten (`formatCost`). Fehlen Kosten, steht dort „–". Hat ein Typ in dieser Periode keinen Wert, ist die Zelle leer („–").
  - Hat eine Periode Schätzanteil, steht ein „≈" vor dem Verbrauch.
  - Laufendes Jahr und laufender Monat tragen den Zusatz „(laufend)".
- **Datenquelle:** zwei parallele Abrufe, `YEARS_ALL` und `MONTHS_ALL` (`forkJoin`). Die Jahressummen kommen **vom Server** und werden nicht im Browser aus den Monaten addiert. Dadurch ist `MeterConsumptionSeriesService` die einzige Stelle der Kostenregel „ein fehlender Preis macht die Summe `null`".
- Die Umformung der Serien in Tabellenzeilen ist eine reine Funktion in `shared/consumption-view.util.ts` (`buildTotalsTable`). Sie gruppiert Monate nach `periodStart`-Jahr unter die Jahreszeile und legt Zellen je Zählertyp an. Monate ohne passende Jahreszeile werden nicht erfunden. Beide Reihen stammen aus denselben Ablesungen, deshalb kommt das nicht vor.
- **Laden und Fehler:** gleiches Muster wie im Diagramm. Selbst-Refresh alle 5 Minuten lädt den **aktiven** Modus. Ein Moduswechsel bestellt einen laufenden Abruf ab (`pendingRequest`), damit eine späte Antwort nicht die falsche Ansicht füllt. Nur der Erstabruf eines Modus meldet einen Fehler, ein fehlgeschlagener Refresh behält die letzten Werte.
- **Layout:** Die Tabelle steckt in der bestehenden Flex-Höhenkette und scrollt **innerhalb** ihres Rahmens (`overflow-y: auto`, `min-height: 0`). Zwei Jahre plus 12 Monate passen nicht auf einen Bildschirm. Die Kopfzeile bleibt dabei stehen (`position: sticky`).

## 4. Tests

**Backend** (`MeterConsumptionSeriesServiceTest`, `ConsumptionRange`):

- YEAR fasst Wochen eines Jahres zu einem Balken zusammen, Label `"2025"`, `periodStart` 1. Januar
- Eine Woche mit Ablesedatum im Januar zählt zum neuen Jahr, auch wenn sie im Dezember beginnt
- Ein fehlender Preis in einer Woche macht die Jahreskosten `null`
- `YEARS_ALL`/`MONTHS_ALL` liefern auch Ablesungen, die weit zurückliegen (kein Fensterbeginn)
- `MeterReadingSeriesControllerTest`: `range=YEARS_ALL` wird akzeptiert

**Frontend:**

- `buildTotalsTable`: Gruppierung, Sortierung (Jahre absteigend, Monate aufsteigend), fehlender Typ, `≈`-Markierung, „(laufend)"
- `compareToPrevious` liefert bei `YEAR` `null`
- Komponente: „Jahr" zeigt nur „Alle Jahre", Umschalten auf Tabelle lädt beide Reihen und blendet die Zeitraumknöpfe aus, Aufklappen per Tipp, jüngstes Jahr initial offen
- Der bestehende Höhenketten-Test bleibt grün. Ein weiterer Test hält fest, dass die Tabelle innerhalb ihres Rahmens scrollt und die Seite nicht über den Bildschirm hinaus wächst.

## Bewusste Grenzen

- keine tagesgenaue Aufteilung am Jahres- oder Monatswechsel
- kein Vergleich „laufendes Jahr bis heute gegen Vorjahr bis zum selben Datum"
- nur Arbeitspreis, keine Grundgebühr (wie bisher)
- keine Website-Variante
- `MONTHS_ALL` lädt die komplette Historie. Bei wöchentlicher Ablesung sind das rund 52 Zeilen je Typ und Jahr, also unkritisch.
