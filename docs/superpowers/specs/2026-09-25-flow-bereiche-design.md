# Flow-Bereiche: Übersicht in Abschnitten

Datum: 2026-09-25 · Status: vom Nutzer freigegeben

## Anlass

Die Flow-Übersicht `/flows` ist eine flache, alphabetische Liste (Stand: 14 Flows, Tendenz stark steigend).
Zusammengehöriges steht auseinander („Treppenhaus-Taster: Nachtmodus" vs. „Nachtmodus: Geräte aus"),
Sortier-Tricks wie „ZZ Diagnose" zeigen, dass die Struktur fehlt.

## Entscheidung

Jeder Flow bekommt **einen** Bereich (Gliederung **nach Zweck**, Nutzerentscheidung 2026-09-25);
die Übersicht zeigt je Bereich einen Abschnitt. Verworfen:

- **Nur Namensschema** — Gruppe steckt im Text, Tippfehler bleiben unbemerkt, keine echten Abschnitte.
- **Tags (n:m)** — bei Haushaltsgröße Overkill; ein Flow stünde in mehreren Abschnitten. Kann später auf dieses Modell aufsetzen.
- **Nach Raum / nach Auslöser** — raumübergreifende Flows (Nachtmodus) passen in keinen Raum; Auslöser ist Technik, nicht das, wonach man sucht.

## 1. Datenmodell

- Neue Spalte `flows.category VARCHAR(60) NULL` (Liquibase-Changeset, ein einzelnes `addColumn`).
- **Freitext, keine feste Liste und keine Stammdatentabelle.** Konsistenz sichern MCP-Tool-Beschreibung
  (vorhandene Bereiche wiederverwenden) und Autovervollständigung im Editor.
- `NULL` = ohne Bereich, angezeigt als „Sonstiges".

## 2. API

- `category` in `CreateFlowRequest`, `UpdateFlowRequest`, `ImportFlowRequest`, `FlowSummaryResponse`, `FlowDetailResponse`.
- Normalisierung an genau einer Stelle im Service: `trim`, leer ⇒ `NULL`.
- Länge > 60 ⇒ **400**, nicht 500: `@Size(max = FlowFieldLimits.CATEGORY_MAX)` wie bei Name/Beschreibung (de433b1a).
- **`PUT` ist ein Teil-Update** (Muster `FlowService.update`): `category` fehlt/`null` ⇒ unverändert;
  `""` (bzw. nur Leerzeichen) ⇒ Bereich entfernen. Nur so lässt sich ein Bereich überhaupt wieder löschen, ohne dass
  eine reine Beschreibungskorrektur ihn still mitlöscht.
- Keine neuen Endpunkte, keine Security-Änderung (`/v1/flows/**` bleibt ADMIN).

## 3. MCP-Server (`flow-mcp-server/`)

- `flow_create`/`flow_update`: optionaler Parameter `category`.
- `flow_list` gibt `category` mit aus (kommt automatisch aus `FlowSummaryResponse`).
- Tool-Beschreibungen: „Vorhandenen Bereich aus `flow_list` wiederverwenden, nur bei echtem Bedarf einen neuen anlegen."
  Namensregel: „Name = was passiert, wann — ohne Bereich, ohne Nummer."
- `flow_update`: `category` weglassen = unverändert, `""` = Bereich entfernen (steht so in der Tool-Beschreibung).

## 4. Frontend

### Übersicht `/flows` (`flow-list.component`)

- Gruppierung als reine Funktion (`flow-grouping.util.ts`, einzige Definition): Abschnitte alphabetisch
  (`localeCompare` de), „Sonstiges" immer zuletzt; innerhalb nach Name.
- Abschnittskopf: Bereichsname, Anzahl, Hinweis wenn ein Flow darin deaktiviert ist.
- Auf-/Zuklappen je Abschnitt; Zustand in `localStorage` (try/catch, reine Komfortfunktion — ein defekter Storage darf
  die Seite nicht brechen, dann ist alles aufgeklappt).
- Suchfeld über Name und Beschreibung (case-insensitiv). Bei aktiver Suche sind alle Treffer-Abschnitte aufgeklappt,
  Abschnitte ohne Treffer ausgeblendet; der gespeicherte Klappzustand bleibt unangetastet.

### Editor (`flow-editor.component`)

- Neben dem Namensfeld in der Kopfzeile ein Feld „Bereich" mit `<datalist>` aus den vorhandenen Bereichen
  (aus `GET /v1/flows`). Wird mit dem Flow gespeichert.

## 5. Einordnung der Bestandsflows

Per MCP (`flow_update`) **nach** dem PROD-Deploy, nicht im Changeset (Inhalt, kein Schema; PROD- und Dev-Flows unterscheiden sich).

| Bereich | Flows |
|---|---|
| Modi & Szenen | #14 Nachtmodus, #11 Morgenmodus, #7 Toni allein: Kamera |
| Licht & Bewegung | #12 Flur, #13 Treppenhaus |
| Taster | #10 Büro, #9 Treppenhaus |
| Sicherheit & Warnungen | #2 Tür bei Abwesenheit, #4 Feuer-Verdacht, #3 Badfenster bei Regen |
| Haushaltsgeräte | #1 Waschmaschine, #8 Spülmaschine |
| Erinnerungen | #5 Badfenster nach dem Duschen |
| System & Diagnose | #6 Diagnose |

Dabei Namen kürzen: #11 → „Morgenmodus", #6 → „Diagnose (temporär)", #14 → „Nachtmodus". Umbenennen ist für Flows
gefahrlos (keine Referenz auf den Namen).

## 6. Tests

- Backend: Normalisierung (Rand-Leerzeichen, leer ⇒ NULL), 61 Zeichen ⇒ 400, Round-Trip über create/update/import,
  Summary enthält `category`.
- Frontend: Gruppierung inkl. „Sonstiges" zuletzt und deutscher Sortierung; Suche filtert und blendet leere Abschnitte aus;
  Klappzustand übersteht werfenden `localStorage`; Editor sendet `category` mit.

## Bewusst nicht Teil davon

Tags, Unterbereiche, Drag-&-Drop-Reihenfolge, Icons/Farben je Bereich, Umbenennen eines Bereichs für alle Flows auf einmal
(geht per MCP Flow für Flow).
