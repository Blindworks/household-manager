# Tracker-Batterie-Hinweis im Intelligence Hub

**Datum:** 2026-08-12
**Status:** Entwurf umgesetzt

## Ziel

Der Intelligence Hub auf dem Dashboard zeigt einen Hinweis, wenn der Akku eines
Tractive-Trackers (aktuell: Toni) unter 40 % fällt — bevor der Tracker unterwegs
ausgeht und die Positionsdaten wegbrechen.

## Kontext

- Das Dashboard pollt bereits alle 60 s `GET /api/v1/tractive/pets`
  (`startPetRefresh` in `dashboard.component.ts`); die Antwort enthält
  `batteryPercent`, `charging` und `name` pro Tier.
- Der Hub wird aus `HubInsight`-Bausteinen komponiert (`rebuildInsights`);
  Müllabfuhr, Kalender und Lüftung folgen demselben Muster: reine
  Utility-Funktion → `HubInsight | null` → Einsortierung im Dashboard.

**Folge: kein Backend-Change, keine neue API, kein neues Polling.** Das Feature
ist eine reine Frontend-Utility plus zwei Zeilen Verdrahtung.

## Entscheidungen

1. **Alle Tiere, nicht „Toni" hartkodiert.** Der Hinweis entsteht für jedes Tier
   mit `batteryPercent < 40`. Ein Namensfilter würde beim Umbenennen still
   brechen (dieselbe Falle wie bei Flow #6); aktuell gibt es ohnehin nur Toni.
2. **Schwellen:** unter 40 % → Ton `tertiary` (gelb), unter 20 % → `error`
   (rot). Bei mehreren Tieren zählt der niedrigste Stand (Muster Müll-Insight).
3. **Laden unterdrückt den Hinweis** (`charging === true`): Auf der Ladeschale
   ist das Problem bereits gelöst, die Karte wäre Dauer-Rauschen — der Tracker
   lädt zu Hause die meiste Zeit.
4. **Fehlender Akkustand ⇒ kein Hinweis.** `batteryPercent` ist optional
   (Cloud-Ausfall, Tracker still); geraten wird nicht (Muster `atHome`).
5. **Text:** „Tracker-Akku von Toni: 32 %" — bewusst „von <Name>" statt
   Genitiv-s, damit Namen wie „Klaus" keinen kaputten Genitiv erzeugen.
   Mehrere Tiere werden mit „ · " verbunden; Titel „Hundetracker",
   Icon `battery_alert`.

## Umsetzung

- Neu: `frontend/src/app/shared/battery-insight.util.ts` mit
  `buildTrackerBatteryInsight(pets: TractivePet[]): HubInsight | null`
  (+ Spec-Datei, Muster `ventilation-insight.util.spec.ts`).
- `dashboard.component.ts`: im `startPetRefresh`-Subscribe zusätzlich
  `this.trackerBatteryInsight = buildTrackerBatteryInsight(pets)` und
  `rebuildInsights()` aufrufen; in `rebuildInsights` hinter der Lüftung
  einsortieren. Bei Ladefehlern (`null`) bleibt der letzte Stand stehen —
  dasselbe Verhalten wie bei der Tier-Kachel selbst.

## Bewusst nicht Teil davon

- Keine Telegram-/Alexa-Benachrichtigung (dafür wäre ein Flow auf
  `sensor.tractive_<id>_battery` der richtige Weg, kein Frontend-Code).
- Keine konfigurierbare Schwelle — Konstante im Util reicht für einen Haushalt.
