# Fertige Wasch- und Spülmaschine im Intelligence Hub — Design

Datum: 2026-08-30
Status: vom Nutzer freigegebenes Design

## Ziel

Wenn die Waschmaschine (oder die Spülmaschine) fertig ist, soll das nicht nur als
Alexa-Ansage und Telegram-Nachricht durchlaufen, sondern als Karte im Intelligence Hub
des Dashboards **stehen bleiben** — sichtbar auf dem Wandtablet, bis jemand sie
antippt.

Der bestehende Flow #1 „Waschmaschine fertig" (aktiv, deployt) bleibt die **einzige**
Erkennung: Leistung < 5 W für 600 s → Rate-Limit 3600 s → Alexa + Telegram. Es entsteht
bewusst keine zweite, parallel rechnende Erkennungslogik im Backend, die von der des
Flows abweichen könnte.

## Entscheidungen (mit dem Nutzer geklärt)

- **Quelle:** der vorhandene Flow, nicht eine neue Auswertung der Leistungshistorie.
- **Ende der Karte:** Antippen räumt sie weg — zusätzlich verschwindet sie von selbst,
  sobald die Maschine wieder läuft.
- **Umfang:** Waschmaschine **und** Spülmaschine.
- **Schwelle „läuft wieder":** 50 W.

## Architektur

Der Flow muss dem Dashboard etwas hinterlassen können, das einen Neustart übersteht und
den Zustand „schon quittiert" kennt. Das ist ein Helfer (`INPUT_BOOLEAN`, Quelle
`MANUAL`) je Maschine. Heute kann ein Flow einen solchen Helfer nicht setzen —
`switch-device` schaltet ausschließlich physische Geräte über `SmartDeviceService`.
Diese Lücke schließt ein neuer Node-Typ.

```
Leistung < 5 W für 600 s ──┬─→ rate-limit ──┬─→ alexa-announce
                           │                ├─→ telegram-send
                           │                └─→ helper-set  (on)
Leistung > 50 W ───────────────────────────────→ helper-set  (off)

  Helfer "…fertig" (input_boolean, MANUAL)
        │
        ├─→ GET /v1/entities  →  Dashboard  →  appliance-insight.util  →  Hub-Karte
        └─←  POST /v1/switches/{entityId}/toggle   ← Antippen der Karte
```

### 1. Backend: Node-Typ `helper-set`

Neue Klasse `HelperSetNodeHandler` in `com.household.manager.flowengine.nodes`
(Muster `SwitchDeviceNodeHandler`).

- Felder: `entityId` (`ENTITY_REF`, Pflicht), `action` (`ENUM` `on`/`off`, Pflicht).
- Ausführung ruft `ManualEntityService.setState(entityId, action)`. Damit gilt
  automatisch die dortige Beschränkung auf `EntitySource.MANUAL`: ein Versuch, über
  einen Flow einen echten Geräte- oder Sensorzustand zu fälschen, endet in einer
  Ausnahme statt in einer stillen Lüge in der Entity-Schicht.
- `validate` prüft nur die Konfiguration (Feld gesetzt, `action` ∈ {on, off}) — ob die
  Entität existiert und manuell ist, entscheidet erst die Laufzeit. Ein Deploy soll
  nicht daran scheitern, dass ein Helfer im Moment des Deploys noch fehlt.
- Der Node **wirft** bei einem Fehler, er schluckt ihn nicht (anders als `light-set`):
  hier scheitert kein unerreichbares Funkgerät, sondern ein Schreibzugriff auf die
  eigene Datenbank — das ist ein echter Konfigurationsfehler und soll im Flow-Debug
  sichtbar werden. Die Zweige „Alexa" und „Telegram" hängen am selben Rate-Limit-Node
  und laufen unabhängig weiter.
- Audit-Eintrag `helper.set` mit `entityId -> action` (Muster `switch.device`).

Der Flow-Editor und der flow-mcp-Server brauchen keine Anpassung: beide lesen den
Katalog aus `flow_node_types`, der sich aus `fields()` speist.

### 2. Die beiden Helfer

Über die Helfer-Seite (`/custom-entities`) je ein `INPUT_BOOLEAN` anlegen:

| Name                  | Entity-ID                                    | Icon                    |
|-----------------------|----------------------------------------------|-------------------------|
| „Waschmaschine fertig" | `input_boolean.manual_waschmaschine_fertig`  | `local_laundry_service` |
| „Spülmaschine fertig"  | `input_boolean.manual_spuelmaschine_fertig`  | `dishwasher_gen`        |

Die IDs entstehen über `EntityIds.build` aus dem Namen; **Umbenennen ändert die ID
nicht** (`ManualEntityService.rename` lässt sie stehen), Löschen und Neuanlegen dagegen
schon — dann laufen Flow und Hub-Karte still ins Leere.

Beide bekommen für die Kachel `switches` die Sichtbarkeit `NEVER`
(`PUT /v1/entities/{id}/tiles/switches`). Ohne das erschienen sie zusätzlich als
gewöhnliche Schalter auf dem Dashboard und in der Schalterliste — die Karte im Hub ist
die gewollte Darstellung, ein zweiter Schalter daneben wäre Rauschen.

### 3. Flows

**Flow #1 „Waschmaschine fertig" erweitern** (per flow-mcp: `flow_update` → `flow_deploy`):

- dritter Zweig am vorhandenen `rate-limit`-Node: `helper-set` → `input_boolean.manual_waschmaschine_fertig`, `on`.
- zweiter Trigger `entity-state-trigger` auf `sensor.meross_2112156531504590863548e1e9817420_power`,
  Operator `>`, Wert `50`, ohne `forSeconds` → `helper-set` … `off`.

Der Aus-Zweig hängt bewusst **nicht** am Rate-Limit-Node: das Rate-Limit soll die
Ansage entprellen, nicht das Zurücksetzen der Karte verzögern.

**Neuer Flow „Spülmaschine fertig"** nach demselben Bauplan auf
`sensor.meross_2205060757549251080148e1e991c4f9_power`, mit Alexa-Ansage („Die
Spülmaschine ist fertig."), Telegram-Nachricht und den beiden `helper-set`-Zweigen.
Er wird nach dem Muster erstellt → deployt → aktiviert.

**Bekannte Grenze der Schwellen:** 5 W / 600 s und 50 W stammen aus Flow #1 und sind
Startschätzungen. Eine lange Einweich- oder Trockenpause über 10 Minuten unterhalb von
5 W meldet „fertig" zu früh; das ist heute schon so und wird durch diese Änderung nur
sichtbarer. Nachziehen geht ohne Redeploy über flow-mcp.

### 4. Frontend: die Karte

Neue Datei `frontend/src/app/shared/appliance-insight.util.ts` — reine Funktion über
die bereits geladene Entity-Liste, Muster `door-insight.util.ts`:

```ts
buildApplianceInsights(entities: EntityState[], nowMs: number): HubInsight[]
```

- Feste Liste der überwachten Helfer (Entity-ID → Titel, Icon), wie `DOOR_CONTACTS`.
- Nur `state === 'on'` erzeugt eine Karte. `unavailable` oder eine fehlende Entität
  erzeugen **keine** Karte — geraten wird nicht.
- Ton: `primary` (eine erledigte Maschine ist eine gute Nachricht, keine Warnung).
- Text: „Fertig seit 14:12 Uhr." aus `lastChanged`; liegt der Zeitpunkt vor dem heutigen
  Tag, zusätzlich das Datum („Fertig seit 29.08., 22:40 Uhr.").

Die Zeitformatierung ist identisch mit der der Türkontakte. Sie wandert deshalb aus
`door-insight.util.ts` in eine geteilte Funktion
`sinceText(lastChanged: string, nowMs: number, prefix: string)` in
`shared/insight-time.util.ts`; die Türkarte reicht „Offen" durch, die Maschinenkarte
„Fertig". Der Rückfalltext bei unlesbarem Zeitstempel bleibt je Aufrufer eigen
(„Die Tür ist gerade offen." bzw. „Die Maschine ist fertig.") und ist deshalb ein
weiterer Parameter — er lässt sich nicht aus dem Präfix bilden.

`HubInsight` bekommt ein optionales Feld:

```ts
readonly dismissEntityId?: string;
```

Nur Karten mit diesem Feld sind antippbar. Die übrigen Hub-Karten bleiben unverändert.

### 5. Frontend: Antippen

Im Dashboard (`rebuildInsights` speist die Liste, das Markup steht wegen der
`lumina`-Kapselung direkt in `dashboard.component.html`):

- Karten mit `dismissEntityId` rendern als `role="button"` mit `tabindex`, Klick- und
  Tastatur-Handler (Muster der klickbaren Energie-Karte), plus ein `aria-label`
  („Hinweis erledigt").
- `dismissInsight(entityId)` **löst die Entität vor dem Schalten aus der aktuellen
  Entity-Liste neu auf** und schaltet nur, wenn sie dort noch `on` ist. Das ist die
  Regel aus `confirmToggle`: Der Endpunkt ist ein *Toggle*, und die Liste ist bis zu
  30 Sekunden alt — ohne diese Prüfung würde ein Klick auf eine inzwischen
  abgeräumte Karte den Helfer wieder **ein**schalten.
- Danach `POST /v1/switches/{entityId}/toggle` (bereits in der KIOSK-POST-Whitelist,
  funktioniert also auf dem Wandtablet), im Erfolgsfall die Karte lokal entfernen und
  `rebuildInsights()`. Schlägt der Aufruf fehl, bleibt die Karte stehen — sie ist die
  ehrliche Anzeige des Serverzustands.

## Sicherheit

Keine Änderung an `SecurityConfig`. Lesen läuft über die generische
`GET /v1/**`-Regel (KIOSK), das Wegtippen über den bereits freigegebenen
`POST /v1/switches/*/toggle`. Der neue Node-Typ ist Teil der Flow-Engine und damit
ohnehin ADMIN-only (`/v1/flows/**`).

## Tests

- `HelperSetNodeHandlerTest`: setzt `on`/`off` über `ManualEntityService`; Validierung
  meldet fehlende `entityId` und ungültige `action`; ein Fehler aus dem Service wird
  durchgereicht, nicht geschluckt.
- `appliance-insight.util.spec.ts`: `on` erzeugt eine Karte, `off`/`unavailable`/fehlend
  keine; Datumszusatz nur bei einem früheren Tag; zwei fertige Maschinen ergeben zwei
  Karten in stabiler Reihenfolge.
- `door-insight.util.spec.ts` bleibt unverändert grün — sie ist die Absicherung, dass
  das Herauslösen der Zeitformatierung die Türkarten nicht verändert hat.
- `dashboard.component.spec.ts`: Klick auf eine Karte mit `dismissEntityId` ruft den
  Toggle; steht die Entität in der aktuellen Liste **nicht mehr** auf `on`, wird
  **nicht** geschaltet; eine Karte ohne `dismissEntityId` ist nicht antippbar.

## Rollout (Reihenfolge zwingend)

1. Backend deployen — erst danach kennt `flow_deploy` den Node-Typ `helper-set`.
2. Die beiden Helfer über `/custom-entities` anlegen und ihre Kachel-Sichtbarkeit für
   `switches` auf `NEVER` setzen. **Vor** Schritt 3, sonst läuft der erste
   `helper-set`-Aufruf in eine Ausnahme.
3. Flow #1 erweitern (`flow_update` → `flow_deploy`), Flow „Spülmaschine fertig"
   anlegen → deployen → aktivieren.
4. Frontend deployen.

## Bewusst nicht Teil davon

- Kein Trockner und keine weiteren Geräte — in PROD gibt es nur für Wasch- und
  Spülmaschine einen passenden Leistungssensor.
- Keine Erinnerung, wenn die Wäsche stundenlang liegen bleibt (die Karte bleibt
  stehen, sie mahnt nicht).
- Keine Push-Benachrichtigung — die bestehenden Kanäle Alexa und Telegram bleiben,
  wie sie sind.
