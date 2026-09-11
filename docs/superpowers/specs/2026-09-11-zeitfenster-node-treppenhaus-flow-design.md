# Zeitfenster-Node und Flow „Treppenhaus-Taster: Nachtmodus"

Datum: 2026-09-11

## Anlass

Der Zigbee-Taster im Treppenhaus soll den Nachtmodus steuern: ein einfacher Druck
zwischen 05:00 und 20:00 schaltet ihn **aus**, zu allen anderen Zeiten **ein**.
Trigger (`entity-event-trigger`) und Aktion (`helper-set`) gibt es bereits; was der
Node-Katalog nicht kann, ist eine Uhrzeit prüfen. Zwei Wege standen zur Wahl:

- ein Kunst-Helfer „Tagsüber", den zwei Cron-Trigger um 05:00/20:00 umschalten
  (kein Code, aber ein unsichtbarer Helfer, ein zweiter Flow und ein Backend-Neustart
  genau zur Flanke lässt den Helfer bis zur nächsten falsch stehen), oder
- ein eigener Bedingungs-Node `time-condition` — gewählt, weil ein Zeitfenster in
  praktisch jedem weiteren Flow gebraucht wird.

## Node `time-condition`

`TimeConditionNodeHandler` in `flowengine/nodes/`, Muster `EntityConditionHandler`.

| config | Pflicht | Wert |
|--------|---------|------|
| `from` | ja | Beginn `HH:mm`, gehört zum Fenster |
| `to` | ja | Ende `HH:mm`, gehört **nicht** zum Fenster |

- Zwei Ausgänge: Port 0 „wahr" (jetzt im Fenster), Port 1 „falsch".
- Halboffenes Intervall `[from, to)`. Liegt `to` vor `from`, überspannt das Fenster
  Mitternacht (`22:00`–`06:00`).
- **Dieselbe Regel wie beim Modus-Schnellzugriff.** `ModeQuickAccessResolver.isWithin`
  wird in ein Record `TimeWindow` (`com.household.manager.common`) herausgezogen, das
  beide fragen — die einzige Definition von „Zeitfenster" im Projekt.
- Validierung beim Deploy: fehlende oder unparsbare Uhrzeit und `from == to` sind
  Fehler. `from == to` ist verboten, weil „nie" und „immer" sonst nicht unterscheidbar
  wären (dieselbe Begründung wie das 400 in `mode_quick_access`). Eine Regel „Ende
  muss nach Beginn liegen" wäre falsch — sie verböte genau den Nachtfall.
- Zur Laufzeit wirft der Node nie; die Config ist beim Deploy geprüft.
- Uhr ist der `Clock`-Bean (Europe/Berlin), nicht `systemDefault` — die Cron-Trigger
  hängen noch an der Systemzone, dieser Node soll das nicht wiederholen.
- Feldtyp `STRING` für beide Uhrzeiten; ein neuer Feldtyp `TIME` für ein Feld ist es
  nicht wert, der Editor ist ohnehin nur Viewer.
- Frontend: eine Zeile in `pages/flows/node-catalog.ts` (`'time-condition': 'Zeitfenster'`),
  die Kategorie „Logik" ergibt sich aus dem Default.
- Doku: Abschnitt in `docs/flows/flow-import-format.md`, Eintrag in `CLAUDE.md`.

## Flow „Treppenhaus-Taster: Nachtmodus"

```
entity-event-trigger  event.zigbee_schalter_treppenhaus_action, action = single
  → time-condition    from 05:00, to 20:00
       wahr   → helper-set  input_boolean.manual_nachtmodus = off
       falsch → helper-set  input_boolean.manual_nachtmodus = on
```

- Nur `single`; Doppelklick und Halten bleiben für spätere Flows frei.
- Kein Rate-Limit: ein Druck ist ein Event. `helper-set` ist idempotent.
- Anders als am Dashboard laufen keine Bestätigungs-/Aktivierungs-Checks — der
  Nachtmodus hat auch keine (nur „Abwesend" und „Toni allein").

## Tests

- Handler-Unit-Test mit festem `Clock`: im Fenster, außerhalb, Grenzen (`05:00` wahr,
  `20:00` falsch), Mitternacht überspannend, jeder Validierungsfehler einzeln.
- `TimeWindow`-Test für die Intervallregel; die bestehenden
  `ModeQuickAccessResolver`-Tests sichern die Extraktion ab.

## Rollout

1. Branch mergen, Backend auf PROD deployen.
2. **Erst danach** via flow-mcp: `flow_create` → `flow_deploy` → `flow_set_enabled`.
   Vorher kennt `flow_deploy` den Node-Typ nicht (dieselbe Falle wie bei `push-send`).
