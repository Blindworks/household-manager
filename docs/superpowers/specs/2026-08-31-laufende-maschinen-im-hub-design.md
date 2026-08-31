# Laufende Wasch- und Spülmaschine im Intelligence Hub — Design

Datum: 2026-08-31
Status: vom Nutzer freigegebenes Design

## Ziel

Der Intelligence Hub zeigt heute eine Karte, wenn die Wasch- oder Spülmaschine
**fertig** ist (Spec `2026-08-30-fertige-maschinen-im-hub-design.md`). Er soll
zusätzlich zeigen, dass eine Maschine **gerade läuft** — mit der bisher verstrichenen
Laufzeit, z. B. „Waschmaschine läuft — Läuft seit 42 Minuten.".

Wie bei der Fertig-Karte bleibt die Erkennung **allein Sache der Flows** #1
(„Waschmaschine fertig") und #8 („Spülmaschine fertig"). Es entsteht bewusst keine
zweite, im Frontend oder Backend rechnende Auswertung der Leistung, die von der des
Flows abweichen könnte.

## Entscheidungen (mit dem Nutzer geklärt)

- **Quelle:** ein zweiter Helfer je Maschine, gesetzt vom bestehenden Flow — nicht die
  im Dashboard ohnehin vorliegende Wattzahl aus `/v1/power-consumers`. Letzteres wäre
  ohne Rollout-Schritt zu haben, wäre aber eine zweite Definition von „läuft", würde
  bei jeder Pause unter der Schwelle flackern (Einweichen, Trocknen) und könnte keine
  belastbare Startzeit nennen.
- **Kartentext:** verstrichene Dauer („Läuft seit 42 Minuten."), nicht die Startuhrzeit.
- **Nicht antippbar:** die Karte ist Statusanzeige, kein Erledigungsvermerk. Wegtippen
  würde behaupten, die Maschine sei aus.
- **Umfang:** Waschmaschine **und** Spülmaschine.

## Architektur

```
Leistung > 50 W ───────────┬─→ helper-set  "…fertig"  (off)   [bestehend]
                           └─→ helper-set  "…läuft"   (on)    [neu]

Leistung < 5 W für 600 s ──┬─→ rate-limit ──┬─→ alexa-announce                [bestehend]
                           │                ├─→ telegram-send                 [bestehend]
                           │                └─→ helper-set "…fertig"  (on)    [bestehend]
                           └─→ helper-set  "…läuft"   (off)                   [neu]

  Helfer "…läuft" (input_boolean, MANUAL)
        └─→ GET /v1/entities → Dashboard → appliance-insight.util → Hub-Karte
```

Beide Flanken setzen immer **beide** Helfer. „läuft" und „fertig" sind damit
strukturell exklusiv; es gibt keinen Zustand, in dem eine Maschine gleichzeitig läuft
und fertig ist.

Der `helper-set`-Node für „läuft → off" hängt **direkt am Trigger, nicht hinter dem
`rate-limit`** — aus demselben Grund, aus dem schon das Abräumen der Fertig-Karte dort
nicht hängt: die Sperre entprellt die Ansage, nicht den Kartenzustand.

Kein Backend-Code, kein neuer Endpunkt, keine Migration. Alle nötigen Bausteine
(`helper-set`, `ManualEntityService`, `GET /v1/entities`) existieren bereits.

### 1. Zwei neue Helfer

Anzulegen unter `/custom-entities` als `INPUT_BOOLEAN`, Kachel-Sichtbarkeit `NEVER`
(sonst stünden sie zusätzlich als gewöhnliche Schalter auf dem Dashboard):

| Helfer-Name (bindend) | Entity-ID |
|---|---|
| `Waschmaschine läuft` | `input_boolean.manual_waschmaschine_laeuft` |
| `Spülmaschine läuft`  | `input_boolean.manual_spuelmaschine_laeuft` |

Die IDs entstehen deterministisch über `EntityIds.build` aus dem Namen (`ä` → `ae`,
`ü` → `ue`). **Bindend ist damit der Name**: ein Tippfehler beim Anlegen lässt die
Karte wortlos ausbleiben. Umbenennen ist dagegen gefahrlos, `ManualEntityService.rename`
lässt die ID stehen.

### 2. Flow-Änderungen (Flow 1 und 8, je identisch)

Je Flow ein zusätzlicher Node und zwei zusätzliche Wires:

- `helper-running-on` (`helper-set`, `action: on`) — verdrahtet von `trigger-power-high`
- `helper-running-off` (`helper-set`, `action: off`) — verdrahtet von `trigger-power-low`

Die Flows sind aktiv und deployt; nach `flow_update` folgt `flow_deploy`.

### 3. Frontend

**`shared/insight-time.util.ts`** bekommt neben `sinceText` eine Funktion
`elapsedText(lastChanged, nowMs, prefix, fallback)`, damit Zeitformatierung an einer
Stelle bleibt:

| Verstrichen | Ausgabe (Präfix „Läuft") |
|---|---|
| < 1 Minute | `Läuft seit weniger als einer Minute.` |
| 1 Minute | `Läuft seit 1 Minute.` |
| 2–59 Minuten | `Läuft seit 42 Minuten.` |
| ab 60 Minuten | `Läuft seit 1 Std. 15 Min.` |
| volle Stunde | `Läuft seit 2 Std.` |
| unlesbarer Zeitstempel | Rückfalltext des Aufrufers |
| Zeitstempel in der Zukunft | Rückfalltext des Aufrufers |

Der Zukunftsfall ist kein Randfall aus Prinzip: Server- und Browseruhr können minimal
auseinanderlaufen, und „Läuft seit -1 Minuten." wäre sichtbarer Unsinn.

**`shared/appliance-insight.util.ts`** bekommt neben `FINISHED_HELPERS` eine zweite
Liste `RUNNING_HELPERS` (gleiche Icons, Titel „Waschmaschine läuft" /
„Spülmaschine läuft", Ton `secondary` statt `primary`). `buildApplianceInsights`
liefert **erst die fertigen, dann die laufenden** Maschinen — das Handlungsbedürftige
vor dem reinen Statusbericht.

Wie bei den Fertig-Karten erzeugt **nur `state === 'on'`** eine Karte; ein fehlender
Helfer oder `unavailable` erzeugt keine — geraten wird nicht.

Die Lauf-Karte trägt **kein `dismissEntityId`** und ist damit nicht antippbar.

**Keine Änderung an `dashboard.component.ts`.** `startApplianceRefresh` lädt bereits
alle 30 s alle `INPUT_BOOLEAN`/`MANUAL`-Entitäten und ruft
`buildApplianceInsights(..., Date.now())` — die neuen Helfer kommen im selben Abruf mit,
und die verstrichene Dauer läuft im 30-s-Takt von selbst mit.

## Fehlerverhalten und bekannte Grenzen

- **Hängende Karte bei Steckdosen-Ausfall:** Fällt die Meross-Steckdose mitten im Lauf
  aus, meldet sie kein `unavailable`, sondern verschwindet aus der gepollten Liste
  (dokumentierte Eigenschaft von `MerossElectricityPollingService`). Der
  `< 5 W`-Trigger feuert dann nie und die Karte bleibt stehen. Ausweg ist die
  Helfer-Seite `/custom-entities`. Bewusst nicht behoben — dieselbe Kehrseite tragen
  schon der Verlaufsgraph und die Modus-Checks.
- **Verpasste Startflanke:** Läuft die Maschine bereits, wenn die Flows neu deployt
  werden, erscheint die Karte erst beim nächsten Lauf. Kein Nachfeuern, wie überall in
  der Engine.
- **Ladefehler im Dashboard:** leert die Maschinen-Karten (Muster Türen) — eine Karte
  ohne bekannten Serverzustand wäre eine Behauptung ohne Deckung.

## Tests

**`insight-time.util.spec.ts`** — `elapsedText` an allen Grenzen der Tabelle oben,
plus unlesbarer und zukünftiger Zeitstempel.

**`appliance-insight.util.spec.ts`** — je Lauf-Helfer eine Karte bei `on`, keine bei
`off`/`unavailable`/fehlend; Reihenfolge fertig-vor-laufend bei gemischtem Zustand;
die Lauf-Karte trägt **kein** `dismissEntityId` (Regressionsschutz: sie darf nicht
versehentlich antippbar werden), die Fertig-Karte weiterhin eines.

## Rollout

1. Deploy des Frontends (die Karten bleiben ohne Helfer wortlos aus — ungefährlich).
2. Helfer `Waschmaschine läuft` und `Spülmaschine läuft` unter `/custom-entities`
   anlegen, Kachel-Sichtbarkeit auf `NEVER`.
3. Flows 1 und 8 via flow-mcp aktualisieren und neu deployen.
4. Nächsten realen Wasch-/Spülgang beobachten: Karte erscheint beim Start und weicht
   nach dem Lauf der Fertig-Karte.
