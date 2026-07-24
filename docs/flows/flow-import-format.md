# Flow-Import-Format

Extern erzeugte Automatisierungs-Flows werden als **JSON-Datei** über den
„Importieren"-Button in der Flow-Übersicht geladen. Der Import legt einen neuen,
**deaktivierten** Flow als Entwurf (Draft) an; scharf wird er erst durch den
manuellen Deploy im Editor.

## Wrapper-Format

```json
{
  "schemaVersion": 1,
  "name": "Anzeigename des Flows",
  "description": "Optionaler Freitext",
  "definition": {
    "nodes": [ /* siehe unten */ ],
    "wires": [ /* siehe unten */ ]
  }
}
```

| Feld | Pflicht | Bedeutung |
|------|---------|-----------|
| `schemaVersion` | ja | Muss aktuell `1` sein. |
| `name` | ja | Anzeigename (nicht leer). |
| `description` | nein | Freitextbeschreibung. |
| `definition` | ja | Der Graph aus `nodes` und `wires`. |

Beim Import wird nur geprüft, ob die `definition` parsebares JSON ist. Die volle
Prüfung (bekannte Node-Typen, Pflichtfelder, Wire-Ziele) passiert erst beim Deploy —
ein importierter Draft darf also noch unvollständig sein.

## Node

```json
{
  "id": "eindeutige-id",
  "type": "node-typ",
  "name": "optionales Label",
  "position": { "x": 80, "y": 120 },
  "config": { }
}
```

- `id`: innerhalb des Flows eindeutig; von Wires referenziert.
- `type`: einer der Typen unten.
- `position`: Editor-Koordinaten (rein visuell; Standard 0/0).
- `config`: typ-abhängige Felder (siehe unten).

## Wire

```json
{ "from": { "node": "quell-id", "port": 0 }, "to": { "node": "ziel-id" } }
```

- `from.node` / `to.node`: Node-`id`s.
- `from.port`: Ausgangsport der Quell-Node (0-basiert). Die meisten Nodes haben nur
  Port 0; die Bedingung hat Port 0 (wahr) und Port 1 (falsch).

## Node-Typen

### `entity-state-trigger` — Entity-Trigger (Trigger, 1 Ausgang)
Feuert bei Zustandsübergang einer Entität IN den passenden Bereich (flankengetriggert).

| config | Pflicht | Wert |
|--------|---------|------|
| `entityId` | ja | Entity-ID, z. B. `binary_sensor.flur_bewegung` |
| `operator` | ja | einer von `<`, `<=`, `>`, `>=`, `==`, `!=`, `changed` |
| `value` | ja*, außer bei `changed` | Vergleichswert als String, z. B. `"on"`, `"25"` |
| `forSeconds` | nein | Zahl; feuert erst, wenn die Bedingung so viele Sekunden ununterbrochen gilt |

### `schedule-trigger` — Zeitplan (Trigger, 1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `cron` | ja | Spring-Cron mit 6 Feldern: `Sek Min Std Tag Monat Wochentag`, z. B. `0 0 7 * * *` (täglich 07:00) |

### `entity-condition` — Bedingung (2 Ausgänge: 0 = wahr, 1 = falsch)
Prüft den AKTUELLEN Zustand einer beliebigen Entität.

| config | Pflicht | Wert |
|--------|---------|------|
| `entityId` | ja | Entity-ID |
| `operator` | ja | einer von `<`, `<=`, `>`, `>=`, `==`, `!=` |
| `value` | ja | Vergleichswert als String |

### `delay` — Verzögerung (1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `seconds` | ja | Zahl > 0; Sekunden bis zur Weiterleitung |

### `rate-limit` — Drossel (1 Ausgang)
Lässt höchstens eine Message pro Intervall durch.

| config | Pflicht | Wert |
|--------|---------|------|
| `minIntervalSeconds` | ja | Zahl > 0; Mindestabstand in Sekunden |

### `debug` — Debug (0 Ausgänge)
Schreibt jede Message in den Debug-Puffer.

| config | Pflicht | Wert |
|--------|---------|------|
| `label` | nein | Beschriftung im Debug-Panel |

### `alexa-announce` — Alexa-Ansage (1 Ausgang)
Platzhalter im Text: `{entityId}`, `{newState}`, `{oldState}`.

| config | Pflicht | Wert |
|--------|---------|------|
| `text` | ja | Ansagetext (mit optionalen Platzhaltern) |
| `mode` | ja | `SPEAK` (ein Gerät, ohne Gong) oder `ANNOUNCE` (mit Gong) |
| `deviceSerials` | ja | nicht-leeres Array von Alexa-Seriennummern, z. B. `["G0000000000000000"]` |

### `switch-device` — Gerät schalten (1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `deviceId` | ja | numerische SmartDevice-ID (Kasa/Tapo/Meross) |
| `action` | ja | `on` oder `off` |

### `telegram-send` — Telegram-Nachricht (1 Ausgang)
Telegram-Nachricht an die erlaubten Chats oder einen bestimmten Chat senden. Platzhalter im Text: `{entityId}`, `{newState}`, `{oldState}`.

| config | Pflicht | Wert |
|--------|---------|------|
| `message` | ja | Nachrichtentext (mit optionalen Platzhaltern) |
| `chatId` | nein | numerische Chat-ID; leer = an alle erlaubten Chats (`TELEGRAM_ALLOWED_CHAT_IDS`) |

> Referenzen müssen zu deiner Umgebung passen. Ist eine **`entityId`** beim Deploy noch
> unbekannt, meldet der Validator eine **Warnung** (kein Fehler) — der Flow greift, sobald
> die Entität existiert. **`deviceId`** und **`deviceSerials`** werden beim Deploy dagegen
> nicht auf Existenz geprüft; ein falscher Wert fällt erst zur Laufzeit auf, wenn die
> Aktion ausgeführt wird.

## Beispiel 1 — Flurlicht bei Bewegung

```json
{
  "schemaVersion": 1,
  "name": "Flurlicht bei Bewegung",
  "description": "Schaltet Gerät 1 ein, wenn der Bewegungsmelder auslöst",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Bewegung erkannt",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "binary_sensor.flur_bewegung", "operator": "==", "value": "on" }
      },
      {
        "id": "switch",
        "type": "switch-device",
        "name": "Flurlicht an",
        "position": { "x": 360, "y": 120 },
        "config": { "deviceId": 1, "action": "on" }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "switch" } }
    ]
  }
}
```

## Beispiel 2 — Warnung bei hoher Temperatur

```json
{
  "schemaVersion": 1,
  "name": "Warnung bei hoher Temperatur",
  "description": "Sagt eine Warnung an, wenn die Temperatur 25 Grad übersteigt",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Temperatur",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "sensor.wohnzimmer_temperatur", "operator": ">", "value": "25" }
      },
      {
        "id": "limit",
        "type": "rate-limit",
        "name": "Höchstens alle 10 Minuten",
        "position": { "x": 340, "y": 120 },
        "config": { "minIntervalSeconds": 600 }
      },
      {
        "id": "say",
        "type": "alexa-announce",
        "name": "Ansage",
        "position": { "x": 620, "y": 120 },
        "config": {
          "text": "Achtung, {entityId} liegt bei {newState} Grad.",
          "mode": "ANNOUNCE",
          "deviceSerials": ["G0000000000000000"]
        }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "limit" } },
      { "from": { "node": "limit", "port": 0 }, "to": { "node": "say" } }
    ]
  }
}
```
