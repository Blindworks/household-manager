# Design: Editierbarer Kurzname für Entitäten

**Datum:** 2026-07-15
**Branch:** feature/garten-aussentemperatur (bzw. eigener Feature-Branch)
**Status:** Genehmigt

## Problem

Entitäten tragen einen `friendlyName`, der bei Geräte-Entitäten (Zigbee, Alexa,
Shelly, …) direkt vom Gerät kommt und für die Anzeige in der GUI oft zu lang ist.
Der Benutzer soll je Entität einen kurzen, frei wählbaren Namen setzen können.

## Zentrale Randbedingung

`friendlyName` wird bei **jedem** Poll-Upsert von der Integration überschrieben
(`EntityStateWriter.upsert`, `entity.setFriendlyName(update.friendlyName())`).
Ein benutzergesetzter Kurzname darf deshalb **nicht** in `friendlyName` liegen,
sondern muss in einem eigenen Feld gespeichert werden, das der Upsert-Pfad nie
anfasst.

## Grundidee

Neues, optionales Feld `customName` (der „Kurzname"), unabhängig vom
integrations-gelieferten `friendlyName` gespeichert. Für die Anzeige liefert das
Backend einen berechneten `displayName = customName ?? friendlyName`, sodass alle
Anzeigestellen denselben Fallback verwenden.

Geltungsbereich: **alle** Entitäten (jede Quelle), nicht nur manuelle Helfer.

## 1. Datenmodell

- Neue Spalte `custom_name VARCHAR(255) NULL` auf Tabelle `entity_states`.
- Liquibase-Changeset `db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml`,
  plus `<include>`-Zeile in `db.changelog-master.xml` (explizite Includes, ans Ende).
- Neues Feld `customName` (nullable) auf der JPA-Entity `EntityState`.
- **Kein** Zugriff im `EntityStateWriter.upsert` → Kurzname überlebt jeden Poll.

## 2. Backend-Schreibpfad

- Neue Methode `EntityStateService.setCustomName(String entityId, String customName)`:
  - Normalisiert: `trim`; leer/blank → `null` (löscht den Kurzname, Fallback greift wieder).
  - Aktualisiert direkt via `EntityStateRepository` in einer normalen
    `@Transactional`-Operation — bewusst **nicht** über den fehlertoleranten
    `reportState`/`upsert`-Pfad, da dies eine benutzerinitiierte CRUD-Aktion mit
    echten Fehlern (404) ist, analog zu `deleteByEntityId`.
  - Rückgabe `Optional<EntityState>` (leer = Entität unbekannt).
- Neuer Endpoint in `EntityStateController` (gilt für alle Quellen):
  - `PUT /v1/entities/{entityId}/custom-name`
  - Body `{ "customName": string | null }`
  - 200 mit aktualisierter `EntityStateResponse`; 404, wenn `entityId` unbekannt.
- Neues DTO `UpdateEntityCustomNameRequest`:
  - Feld `customName` mit `@Size(max = 255)`; blank/null erlaubt (= Löschen).

## 3. API-Response

`EntityStateResponse` (record) und `EntityStateResponseMapper` erweitern:

- `customName` (nullable) — Rohwert, zum Vorbefüllen des Edit-Feldes.
- `displayName` — berechnet `customName != null && !blank ? customName : friendlyName`;
  zentrale Fallback-Logik im Mapper, für alle Anzeigen.
- `friendlyName` bleibt unverändert erhalten.

## 4. Frontend

- **Model** (`entity-state.model.ts`): `EntityState` um `customName?: string | null`
  und `displayName: string` erweitern.
- **Service** (`entity-state.service.ts`): `setCustomName(entityId, customName):
  Observable<EntityState>` → `PUT /api/v1/entities/{entityId}/custom-name`.
- **Entitäten-Seite** (`entities.component`):
  - Name-Spalte zeigt `entity.displayName`.
  - Pro Zeile ein kleiner Bearbeiten-Button (✎); Klick öffnet ein Inline-Eingabefeld
    (vorbefüllt mit `customName`). Speichern ruft `setCustomName`; leeres Feld setzt
    zurück auf `friendlyName` (löscht `customName`). Der Zeilen-Klick (Details
    ausklappen) darf durch die Bearbeiten-Aktion nicht ausgelöst werden
    (`$event.stopPropagation()`).
  - Suche (`filteredEntities`) matcht zusätzlich `displayName`.
- **Flow-Entity-Picker** (`entity-picker.component`): Dropdown-Optionen und
  `displayLabel` von `friendlyName` auf `displayName` umstellen. Damit wirkt der
  Kurzname einheitlich.

## 5. Tests

- **Backend:**
  - `setCustomName`: setzen, löschen (blank → null), 404 bei unbekannter ID.
  - Zentraler Nachweis: nach `setCustomName` überschreibt ein anschließender
    `reportState`-Upsert den `customName` **nicht**.
  - Mapper: `displayName` = `customName` wenn gesetzt, sonst `friendlyName`.
- **Frontend:**
  - Service: `setCustomName` ruft den korrekten Endpoint.
  - Entitäten-Seite: `displayName` wird gerendert; Bearbeiten-Flow ruft Service.

## Namenskonvention

Internes Feld heißt `customName` (technisch: „vom Benutzer gesetzter Name").
`displayName` ist der berechnete Anzeigewert; `friendlyName` bleibt der
integrations-gelieferte Rohname.

## Bewusst nicht enthalten (YAGNI)

- Kein separates Icon/weitere Metadaten in diesem Schritt.
- Keine Historie/Änderungsprotokoll für den Kurznamen.
- Kein Bearbeiten des Kurznamens an anderen Stellen als der Entitäten-Seite
  (Anzeige jedoch überall via `displayName`).
