# Ausschalt-Bestätigung für Geräte-Schalter

**Datum:** 2026-08-19
**Status:** Entwurf, vom Nutzer freigegebener Ansatz A

## Ziel

Manche Schalter (z. B. Kühlschrank, Router, Kamera) dürfen nur mit Bestätigung
**ausgeschaltet** werden — eine Sicherung gegen Fehlklicks. Einschalten ist immer frei.

## Entscheidungen (mit dem Nutzer geklärt)

1. **Reiner Bedienschutz im UI** — Geräteseite und Dashboard zeigen einen
   Bestätigungsdialog. Telegram, Flows und direkte API-Aufrufe schalten weiterhin
   ungefragt. Das ist die bewusste Fortschreibung des bestehenden
   `confirm_required`-Trade-offs (UI-only), keine serverseitige Erzwingung.
2. **Ein Flag, neue Semantik** — das bestehende `confirm_required` auf
   `entity_states` wird umgedeutet: Bestätigung greift nur noch beim
   **Ausschalten**. Kein zweites Flag, kein Modus-Feld, keine Migration.
   Gepflegt wird es unverändert auf der Entities-Seite.
3. **Ansatz A: Backend reichert die Geräteliste an** — `GET /api/devices`
   liefert pro Gerät zusätzlich `confirmRequired` aus der zugehörigen
   Switch-Entität. Kein zweiter Request und keine Mapping-Konvention im Frontend.

## Nicht-Ziele

- Keine serverseitige Ablehnung unbestätigter Ausschalt-Aufrufe.
- Keine Änderung an Flows, Telegram-Tools, Modi (INPUT_BOOLEAN) oder Nuki.
- Keine Pflege des Flags auf der Geräteseite — die bleibt auf der Entities-Seite.

## Umsetzung

### Backend: Anreicherung von `SmartDeviceResponse`

- `SmartDeviceResponse` bekommt ein Feld `confirmRequired` (boolean).
- `SmartDeviceService.toResponse` schlägt die Switch-Entität über
  `EntityIds.build(SWITCH, EntitySource.valueOf(deviceType.name()), externalDeviceId, null)`
  in `EntityStateService.getByEntityId` nach — dieselbe Id-Konstruktion wie
  `SmartDeviceEntityMapper.map`, damit Anreicherung und Spiegelung nie
  auseinanderlaufen. Existiert (noch) keine Entität, ist das Flag `false`.
- Bewusster v1-Kompromiss: ein Lookup pro Gerät (~29 Zeilen, Haushaltsgröße),
  keine Batch-Optimierung. Muster wie bei `getOccurrences` im Kalender.

### Frontend: Geräteseite (`smart-device-list`)

- Modell `SmartDevice` um `confirmRequired?: boolean` erweitern.
- `toggleDevice`: ist das Gerät **an** und `confirmRequired` gesetzt, öffnet
  statt des Schaltbefehls ein Bestätigungsdialog; erst die Bestätigung ruft
  `turnOff`. Einschalten läuft unverändert direkt.
- Eigener schlichter Dialog in der Komponente (Gerätename + Aus-Bestätigung).
  Bewusst **nicht** der Dashboard-Dialog: dessen Markup hängt an den in
  `dashboard.component.scss` gekapselten lumina-Styles und würde hier lautlos
  ungestylt rendern (bekanntes Muster, siehe CLAUDE.md).

### Frontend: Dashboard (Semantik-Änderung)

- `dashboard.component.ts#toggleSwitch`: Dialog nur noch bei
  `entity.confirmRequired && entity.state === 'on'` — Einschalten fragt nicht mehr.
- Dialogtext prüfen und aufs Ausschalten zuspitzen.

### Frontend: Entities-Seite

- Checkbox-Beschriftung von „Bestätigung erforderlich" zu
  „Bestätigung beim Ausschalten" — die Semantik muss an der Pflege-Stelle ablesbar sein.

## Tests

- **Backend:** Anreicherungs-Test — Gerät mit Entität (`confirmRequired=true`)
  liefert das Flag in der Response; Gerät ohne Entität liefert `false`.
- **Frontend `smart-device-list`:** eingeschaltetes Gerät mit Flag → Dialog
  statt HTTP-Call; Bestätigung → `turnOff`; Abbruch → kein Call; Einschalten
  mit Flag → direkter Call.
- **Frontend Dashboard:** bestehende Confirm-Specs auf die neue
  Nur-Aus-Semantik anpassen (Einschalten mit Flag schaltet direkt).

## Risiken / Kanten

- Ein Gerät, dessen Entität noch nie gespiegelt wurde (frisch angelegt, noch
  kein Scan/Refresh), zeigt `confirmRequired=false` — akzeptiert, das Flag
  wird ohnehin erst auf der Entities-Seite gesetzt, wofür die Entität existieren muss.
- Die Umdeutung ändert bestehendes Verhalten: bisher bestätigungspflichtiges
  **Einschalten** auf dem Dashboard entfällt. Vom Nutzer ausdrücklich so gewollt.
