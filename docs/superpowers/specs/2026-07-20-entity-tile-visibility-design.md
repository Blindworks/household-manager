# Design: Kachel-Sichtbarkeit für Entitäten

**Datum:** 2026-07-20
**Status:** Entwurf (vom Benutzer freigegeben)

## Motivation

Die Schalter-Kachel des Dashboards zeigt heute die vier meistgenutzten Schalter
(rein nutzungsbasiert über `EntityUsageService`). Das passt nicht für Geräte wie
die Waschmaschinen-Steckdose: im Normalfall uninteressant (aus), aber sobald sie
an ist (Waschmaschine fertig), soll sie prominent auf der Kachel erscheinen,
damit man sie ausschalten kann.

Entitäten bekommen deshalb eine benutzergepflegte Sichtbarkeitsregel pro
Dashboard-Kachel. Das Modell ist bewusst generisch (Entität → Kachel), auch wenn
es zunächst nur die Schalter-Kachel nutzt.

## Entscheidungen (Brainstorming)

| Frage | Entscheidung |
| --- | --- |
| Sichtbarkeits-Stufen | Vier: `ALWAYS`, `AUTO` (Standard, heutiges Verhalten), `WHEN_ON`, `NEVER` |
| Geltungsbereich | Generisches Modell Entität→Kachel über `tile_key`; erste Kachel: `switches` |
| Sortierung auf der Kachel | Aktive `WHEN_ON` zuerst, dann `ALWAYS`, dann Rest nach Nutzung |
| Schalter-Dialog | Zeigt weiterhin **alle** Schalter; Regeln gelten nur für die Kachel |
| Datenmodell | Eigene Tabelle `entity_tile_visibility` (Ansatz A) |

## Datenmodell

Neue Tabelle `entity_tile_visibility` (Liquibase-Changeset in
`db/changelog/changes/`):

| Spalte | Typ | Beschreibung |
| --- | --- | --- |
| `id` | BIGINT PK auto | — |
| `entity_id` | VARCHAR(150) NOT NULL | Entity-ID der Spiegel-Schicht |
| `tile_key` | VARCHAR(50) NOT NULL | Stabiler Kachel-Schlüssel, z. B. `switches` |
| `visibility` | VARCHAR(20) NOT NULL | `ALWAYS` / `WHEN_ON` / `NEVER` |
| `updated_at` | DATETIME NOT NULL | Zeitpunkt der letzten Änderung |

Unique-Constraint auf (`entity_id`, `tile_key`).

- Kein Eintrag = `AUTO` (heutiges Verhalten). Setzen von `AUTO` **löscht** die
  Zeile — `AUTO` wird nie persistiert.
- Die Tabelle wird ausschließlich benutzerinitiiert beschrieben (wie
  `customName`), nie vom fehlertoleranten Polling-/Upsert-Pfad der
  Integrationen.

Neue Backend-Bausteine:

- Enum `TileVisibility` (`ALWAYS`, `AUTO`, `WHEN_ON`, `NEVER`) im Paket
  `entitystate`.
- Konstantenklasse `DashboardTiles` mit den bekannten Kachel-Keys (zunächst nur
  `SWITCHES = "switches"`); unbekannte Keys werden von der API abgelehnt.
- JPA-Entity `EntityTileVisibility` in `model/entity`, Repository
  `EntityTileVisibilityRepository` in `com.household.manager.repository`
  (JpaConfig scannt nur dieses Paket).

## Backend-API

- `PUT /v1/entities/{entityId}/tiles/{tileKey}` mit Body
  `{"visibility": "WHEN_ON"}`.
  - `404` bei unbekannter `entityId`.
  - `400` bei unbekanntem `tileKey` oder ungültigem `visibility`-Wert.
  - `visibility: "AUTO"` löscht den Eintrag.
  - Antwort: aktualisierte Entität (`EntityStateResponse`).
- `EntityStateResponse` wird um `tileVisibility` erweitert: Map
  `tile_key → visibility` (nur explizit gesetzte Einträge, `AUTO` erscheint
  nicht). Damit kann die Entitäten-Seite den Stand anzeigen.

## Kachel-Logik (`SwitchQueryService`)

`GET /v1/switches` bekommt einen Parameter `view` (`tile` | `all`, Default
`all`):

- **`view=all` (Dialog):** unverändert — alle schaltbaren Entitäten, rein
  nutzungsbasierte Sortierung, nichts wird ausgeblendet.
- **`view=tile` (Kachel, kombiniert mit `limit=4`):**
  - Gefiltert werden `NEVER` sowie `WHEN_ON`-Entitäten, deren Zustand nicht
    `on` ist.
  - Sortierung in drei Gruppen:
    1. `WHEN_ON` mit Zustand `on` (das Dringende ganz vorne),
    2. `ALWAYS`-gepinnte,
    3. Rest (`AUTO`) nach Nutzung.
  - Innerhalb jeder Gruppe gilt die bestehende Nutzungssortierung
    (Toggle-Anzahl, zuletzt geschaltet, alphabetisch).

Das `limit` wird nach Filterung und Sortierung angewendet.

## Frontend

- **Entitäten-Seite:** pro schaltbarer Entität ein Dropdown
  „Schalter-Kachel: Automatisch / Immer / Nur wenn an / Nie" (analog zur
  bestehenden Kurzname-Bearbeitung). Persistiert über den neuen Endpoint im
  `EntityService`; das Modell `EntityState` (Frontend) bekommt
  `tileVisibility`.
- **Dashboard:** minimale Änderung. Die Kachel-Abfrage ruft
  `view=tile&limit=4`, der Dialog weiterhin die volle Liste. Keine neue
  Darstellungslogik: eine aktive `WHEN_ON`-Entität erscheint automatisch auf
  Platz 1 und verschwindet nach dem Ausschalten mit dem nächsten Refresh
  (30-s-Takt bzw. beim Schließen des Dialogs).

## Fehlerbehandlung

- API-Validierung wie oben (`404`/`400`).
- Die Kachel-Abfrage läuft über den normalen Lesepfad; fällt die
  Sichtbarkeits-Tabelle aus, schlägt die Abfrage wie jede andere DB-Abfrage
  fehl (kein Sonderpfad nötig — das Dashboard behandelt Ladefehler bereits mit
  `catchError`).

## Tests

- **`SwitchQueryService`:** Unit-Tests (Mock-Repositories) für Filterung
  (`NEVER` raus, inaktive `WHEN_ON` raus) und Gruppen-Sortierung inkl.
  Nutzungssortierung innerhalb der Gruppen; `view=all` bleibt unverändert.
- **Controller:** Tests für den neuen Endpoint — Erfolgsfall, `AUTO` löscht,
  `404` unbekannte Entität, `400` unbekannter `tileKey`/Wert.
- **Frontend:** Spec für das Dropdown (Anzeige des aktuellen Werts, Aufruf des
  Service, Fehlerfall).

## Bewusst nicht enthalten (YAGNI)

- Keine frei konfigurierbaren Sichtbarkeits-Bedingungen (dafür gibt es die
  Flow-Engine).
- Keine Kachel-Layout-Konfiguration (Ansatz C wurde verworfen).
- Keine visuellen Sonder-Badges für „dringende" Einträge auf der Kachel.
