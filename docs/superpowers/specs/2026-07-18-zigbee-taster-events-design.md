# Design: Zigbee-Taster als Event-Entitäten

**Datum:** 2026-07-18
**Status:** Entwurf zur Review

## Ziel

Zigbee-Taster (Buttons, z. B. Aqara/IKEA/Tuya) erscheinen bisher nirgends: zigbee2mqtt
meldet Tastendrücke als String-Feld `{"action": "single"}`, der `ZigbeeMessageParser`
kennt aber nur numerische/boolesche Messgrößen — die Nachricht erzeugt keine Entität.

Taster sollen:

1. im **Entities-View** sichtbar sein (letzte Aktion, Batterie, „vor X Min."),
2. als **Flow-Trigger** nutzbar sein — und zwar bei **jedem** Tastendruck, auch wenn
   zweimal hintereinander dieselbe Taste gedrückt wird.

## Gewählter Ansatz: eigener Event-Pfad + eigener Trigger-Node

Taster sind zustandslos: ein Tastendruck ist ein **Ereignis**, kein Dauerzustand. Die
bestehende Zustands-Pipeline passt dafür nicht, aus zwei Gründen:

- `EntityStateWriter.upsert` publiziert nur bei **Wertänderung** ein
  `EntityStateChangedEvent` — „single" → „single" ginge verloren.
- Der `EntityStateTriggerHandler` ist **flankengetriggert** (feuert nur beim Übergang
  in den passenden Bereich) — selbst erzwungene Events würden verschluckt.

Deshalb (Home-Assistant-Vorbild: Event-Entities + Device-Trigger):

- neue Entity-Domain **`EVENT`** — die Entität hält als Zustand die letzte Aktion
  (Sichtbarkeit im Entities-View),
- ein eigenes Spring-Event **`EntityEventFired`**, das bei **jedem** Tastendruck
  publiziert wird (getrennt vom `EntityStateChangedEvent`),
- ein eigener Flow-Trigger-Node **`entity-event-trigger`** mit optionalem
  Aktions-Filter.

**Verworfene Alternative — „idle"-Trick:** jeden Tastendruck als
`EntityStateChangedEvent` mit `oldState=""` publizieren, damit die Flankenprüfung des
bestehenden Trigger-Nodes immer durchgeht. Kein neuer Code-Pfad, aber der vorherige
Zustand wäre gelogen, `changed`-Trigger und State-Logging sähen künstliche Übergänge,
und die Trennung Zustand vs. Ereignis ginge verloren.

## Backend

### Zigbee-Parser (`zigbee/`)

- `ParsedZigbeeMessage` bekommt ein optionales Feld `action` (String, nullable).
- `ZigbeeMessageParser.parse(topic, payload, retained)`:
  - liest das Feld `action`, wenn es ein nicht-leerer String ist. Leere Aktionen
    (`"action": ""` — das Legacy-Reset von zigbee2mqtt) werden ignoriert.
  - **Retained-Nachrichten tragen nie eine Aktion**: bei `retained=true` wird `action`
    verworfen. Sonst würde ein Backend-Neustart bzw. MQTT-Reconnect den letzten,
    vom Broker aufbewahrten Tastendruck „nachfeuern" und Flows geisterhaft auslösen.
  - Eine Nachricht mit Aktion, aber ohne Messwerte/Batterie/Linkquality ist gültig
    (bisheriges Leer-Kriterium um `action != null` erweitert).
- `ZigbeeMqttConfig.handle` reicht `publish.isRetain()` an den Parser durch.
- `ZigbeeReadingService` bleibt unverändert (Aktionen werden nicht als Messwerte
  historisiert; die Entität selbst genügt).

### Entity-Schicht (`entitystate/`)

- `EntityDomain.EVENT` (idPrefix `event`). Kein Liquibase-Changeset nötig: die
  `domain`-Spalte speichert Strings.
- Neues Spring-Event `EntityEventFired(entityId, action, attributes, timestamp)`.
- `EntityStateWriter.upsertEvent(EntityStateUpdate)` (REQUIRES_NEW wie `upsert`):
  Upsert der Entität mit `state = Aktion`; `lastChanged` wird bei **jedem** Ereignis
  gesetzt (auch bei gleicher Aktion), damit „vor X Min." den letzten Tastendruck
  zeigt. Rückgabe **immer** ein `EntityEventFired`.
- `EntityStateService.reportEvent(EntityStateUpdate)`: gleiche Fehlerkapselung wie
  `reportState` (niemals Exceptions zur Integration durchreichen), publiziert das
  `EntityEventFired`. EVENT-Entitäten publizieren **kein** `EntityStateChangedEvent` —
  genau ein Event-Typ pro Domain-Art, keine Doppelzustellung.

### Zigbee-Entity-Mapper (`entitystate/mapper/`)

- Neue Methode `ZigbeeEntityMapper.mapAction(message)` → `Optional<EntityStateUpdate>`:
  bei vorhandener Aktion eine Entität
  `event.zigbee_<slug(friendlyName)>_action` (per `EntityIds.build`), friendlyName
  `"<Name> Taster"`, Attribute: `deviceClass: "button"`, `batteryPercent`,
  `linkQuality` (falls vorhanden).
- `ZigbeeMqttConfig.reportEntityStates` ruft zusätzlich
  `mapAction(...).ifPresent(entityStateService::reportEvent)` — im bestehenden
  try/catch-Hook (Hook-Muster der Spiegel-Schicht).

### Flow-Engine (`flowengine/`)

- `TriggerNodeHandler` bekommt eine Default-Methode
  `onEntityEventFired(EntityEventFired event, NodeConfig config, NodeContext ctx)`
  (no-op) — bestehende Trigger bleiben unberührt.
- `FlowEngineListener` bekommt einen zweiten `@EventListener` für `EntityEventFired`;
  Verteilung identisch zur bestehenden (Registry-Lookup über `watchedEntityId`,
  asynchron über den `flowEngineExecutor`).
- Neuer Node **`entity-event-trigger`** (`EntityEventTriggerHandler`):
  - Felder: `entityId` (ENTITY_REF, Pflicht), `action` (STRING, optional —
    leer = jede Aktion löst aus).
  - Feuert bei jedem `EntityEventFired` der Entität, dessen Aktion dem Filter
    entspricht (exakter String-Vergleich).
  - FlowMessage: `entityId`, `action`, `attributes`, `timestamp`, `triggerNodeId`.
  - Kein `forSeconds`/Timer — Ereignisse haben keine Verweildauer.

## Frontend

- `entity-state.model.ts`: `EntityDomain`-Union um `'EVENT'` erweitern.
- Entities-View: Domain-Filter um `EVENT` ergänzen; Darstellung funktioniert generisch
  (State-Badge zeigt die letzte Aktion als Wert, `relativeTime` den Zeitpunkt).
- `node-catalog.ts`: Label `'entity-event-trigger': 'Taster-Trigger'`. Kategorie kommt
  wie bisher aus dem Trigger-Flag des Backend-Katalogs; das Konfigurationspanel ist
  schema-getrieben und braucht keine Änderung.

## Fehlerbehandlung

- `reportEvent` folgt der bestehenden Fehlertoleranz-Doktrin: Persistenz-/
  Listener-Fehler werden geloggt, der MQTT-Handler bricht nie.
- Unbekannte Aktions-Strings sind kein Fehler — jeder nicht-leere String wird als
  Aktion übernommen (Geräte-Vielfalt: `single`, `double`, `hold`, `on`,
  `brightness_move_up`, …).

## Tests

- **Parser:** Aktion wird geparst; leere Aktion ignoriert; Aktion bei
  `retained=true` verworfen; Nachricht nur mit Aktion ist gültig.
- **Mapper:** `mapAction` erzeugt korrekte Entity-ID, Attribute, friendlyName;
  ohne Aktion leeres Optional.
- **Writer/Service:** `upsertEvent` liefert bei unveränderter Aktion trotzdem ein
  Event und aktualisiert `lastChanged`; `reportEvent` schluckt Exceptions.
- **Trigger-Node:** feuert bei jedem Event; Aktions-Filter greift; ohne Filter
  feuert jede Aktion; `validate` verlangt `entityId`.
- **Frontend:** Node-Label im Katalog; bestehende Specs (Palette/Panel) um den neuen
  Typ ergänzen, soweit sie Kataloge fixieren.
