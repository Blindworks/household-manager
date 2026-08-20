# Aktivierungs-Checks für „Toni allein" und „Abwesend"

**Datum:** 2026-08-20
**Status:** Entwurf validiert (Ansatz A, rein Frontend)

## Ziel

Beim Einschalten der Modi „Toni allein" und „Abwesend" über die Modus-Leiste des
Dashboards erscheint ein Dialog, der zwei Checks anzeigt, bevor der Modus
aktiviert wird:

1. **Fenster & Türen:** Sind alle Zigbee-Tür-/Fensterkontakte geschlossen?
2. **Großverbraucher:** Läuft ein Stromverbraucher mit ≥ 50 W (z. B. Waschmaschine)?

Der Dialog **warnt, blockiert aber nicht**: „Aktivieren" ist immer möglich, die
Entscheidung bleibt beim Nutzer. Hintergrund: Der Hund ist allein bzw. niemand
ist zu Hause — offene Fenster und laufende Großverbraucher sollen bewusst
wahrgenommen werden, bevor man geht.

## Entscheidungen (geklärt im Brainstorming)

- **Warnen statt blockieren:** Der OK-Button bleibt immer aktiv, auch bei
  fehlgeschlagenen Checks und auch, während die Checks noch laden.
- **Schwelle 50 W:** Empfindlich genug für Fernseher/PC, bewusst unterhalb der
  Waschmaschinen-Heizphase.
- **Geltung: „Toni allein" UND „Abwesend"** — beide bedeuten „niemand greift ein".
- **Nur Zigbee-Kontakte:** Der Nuki-Türsensor der Haustür wird bewusst NICHT
  geprüft — beim Verlassen des Hauses ist die Haustür naturgemäß gerade offen,
  das gäbe eine Dauer-Warnung ohne Informationswert.
- **Rein Frontend (Ansatz A):** Kein Backend-Code. Die Check-Definition lebt nur
  im Dashboard — konsistent mit dem etablierten UI-Guard-Muster der
  Ausschalt-Bestätigung (`confirm_required`). Telegram, Flows und API schalten
  die Modi unverändert direkt. Ein Backend-Check-Endpunkt (Ansatz B, Muster
  `TractiveHomeResolver`) ist nachrüstbar, falls der Telegram-Bot die Checks je
  ansagen soll. Ein serverseitiges Gate im Toggle-Endpunkt (Ansatz C) wurde
  verworfen, weil Flows dieselbe API nutzen und brechen würden.

## Auslösung

`DashboardComponent.toggleMode` bekommt eine Weiche:

- Bewachte Modi als Frontend-Konstante (Set der Entity-IDs):
  `input_boolean.manual_toni_allein`, `input_boolean.manual_abwesend`
  (stabile IDs aus `HouseModes.entityId`).
- Bewachter Modus **und** Einschalten (`state !== 'on'`) → Check-Dialog öffnen
  statt zu schalten.
- **Ausschalten bleibt immer direkt**, ebenso alle anderen Modi.
- Die bestehende Toggle-Logik (optimistisches Update, Fehler-Rollback,
  `pendingModeIds`) wird in eine private Methode extrahiert, die der direkte
  Pfad und der Dialog-Bestätigungspfad gemeinsam nutzen.

## Dialog und Checks

Dialog-Markup **direkt in `dashboard.component.html`** — die `lumina`-Styles
sind in `dashboard.component.scss` gekapselt und griffen in einer
Kind-Komponente lautlos nicht (bekanntes Muster: Tractive-/Zigbee-/Futter-Kachel,
übrige Dialoge). Beim Öffnen starten zwei unabhängige, parallele Requests:

### Check 1 — Fenster & Türen

`GET /v1/entities?domain=BINARY_SENSOR&source=ZIGBEE`
(`EntityStateService.getEntities('BINARY_SENSOR', 'ZIGBEE')`), gefiltert auf
`attributes.deviceClass === 'door'`. Semantik pro Kontakt (HA-Konvention,
`on` = offen — siehe `ZigbeeEntityMapper`):

| Zustand | Bewertung |
| --- | --- |
| `off` | geschlossen → OK |
| `on` | offen → Warnzeile mit Anzeigename |
| `unavailable` / alles andere | „Zustand unbekannt" → Warnzeile — ein toter Sensor beweist nicht, dass das Fenster zu ist (fail-visible) |

Alle geschlossen → eine grüne Sammelzeile („Alle N Fenster/Türen geschlossen"),
sonst Liste der Problemfälle. Keine Kontakte gefunden (z. B. Zigbee-Ausfall,
leere Liste) → Warnung „Keine Kontakte gefunden", nicht grün.

### Check 2 — Großverbraucher

`GET /v1/power-consumers` (`PowerConsumerService.getConsumers()`), gefiltert
auf `powerWatts != null && powerWatts >= 50`. Die Schwelle ist eine Konstante
in `dashboard.component.ts` (einzige Stelle im UI). Treffer → Warnzeile mit
Name und Wattzahl; keine Treffer → grün „Keine Großverbraucher aktiv".

Verbraucher mit `powerWatts: null` bzw. `unavailable: true` werden **bewusst
ignoriert**: Eine offline Steckdose versorgt ihr Gerät im Normalfall gar nicht,
und die heute einzige Verbraucherquelle (Meross) lässt Offline-Geräte ohnehin
aus der gepollten Liste fallen (siehe CLAUDE.md, Power Consumption History).

### Zustände und Fehlerbehandlung

Jeder Check hat drei Anzeige-Zustände: **lädt** / **OK** (grün) /
**Warnung(en)** (amber). Schlägt ein Request fehl, zeigt der betroffene Check
„Prüfung fehlgeschlagen" als Warnung; der andere Check bleibt davon unberührt.
Ein Fehler blockiert das Aktivieren nie.

## Bestätigung

Footer mit „Abbrechen" und „Aktivieren":

- „Aktivieren" ist **immer aktiv** — auch während die Checks laden. Ein
  langsamer Request darf niemanden aufhalten (warnen, nicht blockieren).
- Beim Bestätigen wird der Modus **aus der aktuellen `modes`-Liste
  re-resolved** (Muster `confirmToggle` der Ausschalt-Bestätigung): Ist er
  inzwischen schon `on` (z. B. per Telegram oder Flow eingeschaltet; die Liste
  wird alle 30 s aufgefrischt), schließt der Dialog **ohne zu schalten** —
  der Toggle würde ihn sonst ausgerechnet wieder ausschalten. Ist er in der
  Liste nicht mehr auffindbar, wird ebenfalls nicht geschaltet (kein Beleg
  über die Richtung).
- „Abbrechen" (und Backdrop/Schließen-Kreuz) schließt ohne jede Wirkung.

## Tests (Dashboard-Spec)

- Bewachter Modus + Einschalten → Dialog öffnet, kein Toggle-Request
- Bewachter Modus + Ausschalten → direkter Toggle, kein Dialog
- Unbewachter Modus → direkter Toggle in beide Richtungen
- Kontakt-Filterung: nur `deviceClass: door`; `on` und `unavailable` als
  Warnung, `off` als OK; leere Kontaktliste als Warnung
- Verbraucher-Schwelle: 49 W kein Treffer, 50 W Treffer, `powerWatts: null`
  ignoriert
- Bestätigen schaltet den Modus (bestehender Toggle-Pfad)
- Modus inzwischen `on` → Dialog schließt ohne Toggle
- Abbrechen → kein Toggle
- Fehlgeschlagener Check-Request → Warnungsanzeige, Aktivieren weiterhin möglich

## Nicht-Ziele / bewusste Grenzen

- **Kein Backend-Code, keine Migration, keine Security-Änderung:** beide GETs
  fallen unter die generische `GET /v1/**`-KIOSK-Regel, der Modi-Toggle ist
  KIOSK-erlaubt — das Feature funktioniert unverändert auf dem Wandtablet.
- **Telegram/Flows/API unverändert:** Die Checks sind ein reiner UI-Schutz.
  Wer den Modus über einen anderen Weg schaltet, bekommt keine Checks.
- **Keine Prüfung des Nuki-Schlosses** (verriegelt?) — nicht angefragt, YAGNI.
- **Die 50-W-Schwelle ist unverifiziert** gegen das reale Verbrauchsprofil der
  Waschmaschine; bei Fehltreffern im Betrieb an genau einer Konstante
  nachziehbar.
