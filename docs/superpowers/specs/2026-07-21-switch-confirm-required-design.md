# Design: Bestätigungspflicht für Schalter

**Datum:** 2026-07-21
**Status:** Entwurf (vom Benutzer freigegeben)

## Motivation

Manche Schalter dürfen nicht versehentlich geschaltet werden (z. B. Geräte, deren
Abschalten Schaden anrichten kann). Für solche Schalter soll ein Klick auf der
Dashboard-Schalter-Kachel oder im Schalter-Dialog nicht sofort schalten, sondern
einen Bestätigungsdialog öffnen, in dem der eigentliche Schalter zu sehen ist —
erst der Klick auf den Schalter im Dialog schaltet wirklich.

## Entscheidungen (Brainstorming)

| Frage | Entscheidung |
| --- | --- |
| Geltungsbereich | Nur UI-Schutz (Dashboard-Kachel + Schalter-Dialog, damit auch Wandtablet). Die API erzwingt nichts — Flows, Tablet-Heartbeat, MCP und künftige Automatiken schalten weiter direkt. |
| Dialog-UX | Dialog zeigt Hinweistext, die vertraute Schalter-Zeile (`app-switch-list` mit genau dieser Entität) und „Abbrechen". Erst der Klick auf den Schalter im Dialog schaltet; danach schließt der Dialog automatisch. Escape/Abbrechen schließt ohne zu schalten. |
| Datenmodell | Boolean-Spalte `confirm_required` an `entity_states` (Ansatz A) — benutzergepflegtes Feld nach dem Muster von `custom_name`. |

## Backend

### Datenmodell

- Liquibase-Changeset: Spalte `confirm_required` `BOOLEAN NOT NULL DEFAULT FALSE`
  an `entity_states`.
- `EntityState`-Entity: Feld `boolean confirmRequired` (`@Column(name =
  "confirm_required", nullable = false)`).
- Der fehlertolerante Polling-Upsert (`EntityStateWriter`) fasst das Feld — wie
  `customName` — nie an; es wird ausschließlich benutzerinitiiert geschrieben.

### Service und API

- `EntityStateService.setConfirmRequired(String entityId, boolean
  confirmRequired)` analog zu `setCustomName`: direkter, benutzerinitiierter
  Schreibpfad; liefert `Optional<EntityState>` (leer bei unbekannter Entity-ID).
- Endpoint `PUT /v1/entities/{entityId}/confirm-required` mit Body
  `{"confirmRequired": true}`; `404` bei unbekannter Entität; Antwort ist die
  aktualisierte `EntityStateResponse`.
- `EntityStateResponse` bekommt das Feld `boolean confirmRequired` (für die
  Entitäten-Seite).
- `SwitchResponse` bekommt das Feld `boolean confirmRequired` (damit
  Kachel/Dialog wissen, wann der Bestätigungsdialog nötig ist); der
  `SwitchResponseMapper` mappt es aus der Entität.

## Frontend

### Pflege (Entitäten-Seite)

- Im aufgeklappten Detail-Block schaltbarer Entitäten (gleiche Bedingung wie das
  Kachel-Dropdown: `isSwitchTileConfigurable`, also SWITCH/INPUT_BOOLEAN ohne
  Haus-Modi) erscheint zusätzlich eine Checkbox „Bestätigung erforderlich".
- Änderung ruft `EntityStateService.setConfirmRequired(entityId, checked)` und
  ersetzt die Entität in der Liste (gleiche Muster wie Kachel-Dropdown).
- Frontend-Modelle: `EntityState.confirmRequired?: boolean`,
  `SwitchEntity.confirmRequired: boolean`.

### Bestätigungsdialog (Dashboard)

- `DashboardComponent.toggleSwitch(entity)` wird zum Guard: Ist
  `entity.confirmRequired` gesetzt, wird nicht geschaltet, sondern der
  Bestätigungsdialog geöffnet (`confirmSwitch = entity`). Der eigentliche
  Schaltpfad wandert in eine interne Methode `executeToggle(entity)` mit dem
  bestehenden optimistischen Update.
- Der Dialog (Markup + Lumina-Styles in der Dashboard-Komponente, wie Energie-
  und Schalter-Dialog) zeigt:
  - Hinweistext („Dieser Schalter erfordert eine Bestätigung."),
  - die Schalter-Zeile via `app-switch-list` (`variant="dialog"`, Liste mit
    genau dieser Entität, `pendingIds` durchgereicht),
  - einen „Abbrechen"-Button.
- `(toggled)` im Dialog ruft `executeToggle(entity)` und schließt den Dialog;
  „Abbrechen" und Escape (bestehender `onEscape`-Handler) schließen ohne zu
  schalten.
- Da Kachel und Schalter-Dialog dasselbe `toggled`-Event über `toggleSwitch`
  behandeln, greift der Schutz an beiden Stellen automatisch — und damit auch
  auf dem Wandtablet (gleiche Web-App im Kiosk-WebView).

## Fehlerbehandlung

- Unverändert: `executeToggle` nutzt den bestehenden optimistischen Pfad inkl.
  Rollback und Fehlermeldung (`switchError`).
- Der Bestätigungsdialog referenziert die Entität aus der aktuellen Liste; nach
  einem zwischenzeitlichen Refresh wird der Zustand über die `entityId`
  aufgelöst (bestehendes `applySwitchState`-Muster).

## Tests

- **Backend:**
  - `EntityStateServiceTest`: `setConfirmRequired` setzt/löscht das Flag,
    unbekannte Entity-ID liefert leeres Optional.
  - `EntityStateControllerTest`: PUT-Erfolgsfall (Antwort enthält
    `confirmRequired`), 404-Fall.
  - `SwitchResponseMapper`-Abdeckung: `confirmRequired` wird gemappt (über
    bestehende `SwitchQueryServiceTest`-Fälle oder einen gezielten Assert).
- **Frontend:**
  - Dashboard-Spec: `confirmRequired`-Entität öffnet den Dialog statt zu
    schalten; Toggle im Dialog ruft den Service und schließt; Abbrechen
    schaltet nicht.
  - Entities-Spec: Checkbox zeigt den aktuellen Wert und ruft
    `setConfirmRequired`.

## Bewusst nicht enthalten (YAGNI)

- Keine serverseitige Erzwingung (Flows/Automatiken schalten weiter direkt).
- Keine PIN-/Passwort-Bestätigung, kein Long-Press — nur der Dialog.
- Keine Bestätigungspflicht für Haus-Modi oder manuelle Entitäten auf der
  Entitäten-Seite (dort ist der Toggle ein bewusster Verwaltungsakt).
