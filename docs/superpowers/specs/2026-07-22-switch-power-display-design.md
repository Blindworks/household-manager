# Design: Watt-Anzeige in der Schalter-Kachel

**Datum:** 2026-07-22
**Status:** Entwurf genehmigt

## Ziel

Steckdosen, die ihren Stromverbrauch messen (heute Meross und Shelly), zeigen die
aktuelle Leistung direkt in der Schalter-Zeile des Dashboards an. Zweck: Auf einen
Blick erkennen, dass ein Gerät (z. B. die Waschmaschine) gerade läuft, damit man es
nicht versehentlich ausschaltet. Reine Anzeige — keine Schwellwert- oder
Schutzlogik.

## Kontext

- Power-Sensoren existieren bereits im Entity-State-Layer als eigene Entitäten
  nach der Konvention `sensor.<source>_<slug(sourceRef)>_power` mit
  `deviceClass: power` und `unit: W` (Meross: `MerossElectricityPollingService`,
  Shelly: `ShellyPollingService`).
- Schalter und Power-Sensor teilen sich `source` + `sourceRef`; die Verknüpfung
  ist damit deterministisch über `EntityIds.build(SENSOR, source, sourceRef, "power")`.
- Die Schalter-Kachel refresht alle 30 s, das Meross-Polling läuft alle 60 s —
  die Anzeige ist damit maximal ~90 s alt, ohne neue Polling-Mechanismen.

## Entscheidung

**Backend-Anreicherung** (gewählt aus drei Ansätzen): Die bestehende Schalter-API
`GET /api/v1/switches` liefert die Leistung als neues Feld mit. Verworfen wurden
clientseitiges Matching im Frontend (dupliziert die entityId-Konvention, mehr
Requests) und das Schreiben der Leistung als Attribut an die Switch-Entity
(vermischt Zuständigkeiten, berührt den Hook-/Flow-Pfad).

## Backend

- `SwitchResponse` bekommt ein neues Feld `Double powerWatts`; `null` bedeutet
  „nichts anzeigen".
- `SwitchQueryService.listSwitches(...)` baut nach dem Laden der Schalter für
  jeden die Kandidaten-Sensor-ID über
  `EntityIds.build(EntityDomain.SENSOR, source, sourceRef, "power")` und lädt
  alle Kandidaten in einem einzigen `findByEntityIdIn`-Query
  (`EntityStateRepository`; Methode bei Bedarf ergänzen — Repository liegt
  bereits in `com.household.manager.repository`).
- `SwitchResponseMapper.toResponse(...)` bekommt den Power-Sensor (nullable) als
  zusätzlichen Parameter und setzt `powerWatts` nur, wenn alle Bedingungen
  erfüllt sind:
  1. Schalter-Zustand ist `on`,
  2. Sensor existiert und ist nicht `unavailable`,
  3. Sensor-Zustand ist numerisch parsebar,
  4. `lastUpdated` des Sensors ist jünger als 5 Minuten (Stale-Schutz bei
     Polling-Ausfall).
- In allen anderen Fällen bleibt das Feld `null`; es gibt keinen Fehlerzustand.

## Frontend

- `SwitchEntity`-Model: neues Feld `powerWatts?: number | null`.
- `switch-list.component` rendert bei vorhandenem Wert ein dezentes Element
  neben dem Zustands-Label, z. B. „AN · 1.240 W" (de-DE-Formatierung, auf ganze
  Watt gerundet).
- Kachel und Bestätigungsdialog nutzen dieselbe `switch-list`-Komponente — die
  Anzeige erscheint automatisch auch im Dialog.
- Styling ausschließlich in `switch-list.component.scss` (keine lumina-Klassen
  des Dashboards).

## Tests

- **Backend:** `SwitchQueryService`-/Mapper-Tests: Schalter mit passendem
  Power-Sensor liefert `powerWatts`; ohne Sensor, bei „aus", bei stale oder
  `unavailable` bleibt es `null`.
- **Frontend:** `switch-list.component.spec`: Watt-Anzeige erscheint bei „An"
  mit Wert; nicht bei „Aus", ohne Wert oder bei nicht verfügbarem Schalter.

## Bewusst nicht enthalten (YAGNI)

- Keine Schwellwert-/Schutzlogik beim Ausschalten (bestehendes
  `confirmRequired` bleibt unverändert).
- Keine Verbrauchs-Historie oder Charts in der Kachel.
- Keine Konfiguration pro Schalter (Anzeige erscheint automatisch, sobald ein
  Power-Sensor existiert).
