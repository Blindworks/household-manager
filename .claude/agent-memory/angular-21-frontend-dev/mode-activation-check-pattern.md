---
name: mode-activation-check-pattern
description: Aktivierungs-Checks beim Einschalten der Modi "Toni allein"/"Abwesend" im Dashboard — warnt, blockiert nie; verwandt mit aber verschieden vom [[switch-confirmation-pattern]]
metadata:
  type: project
---

Task 2 von `2026-08-20-toni-allein-aktivierungs-checks` (Branch `feature/toni-allein-checks`,
Commit `0cf6281`) hat `DashboardComponent.toggleMode` um einen zweiten Dialog-Typ erweitert,
der bewusst ANDERS funktioniert als die Ausschalt-Bestaetigung ([[switch-confirmation-pattern]]):

- Reine Sicherheits-**Warnung** beim EINSCHALTEN von `input_boolean.manual_toni_allein`/
  `input_boolean.manual_abwesend` (`DashboardComponent.CHECKED_MODE_IDS`), nicht beim
  Ausschalten. Der Dialog blockiert nie — "Aktivieren" geht immer, auch wenn beide Checks rot
  sind. Kein Analogon zu `confirmRequired` (das blockt nicht, sondern fragt nur nach — hier
  geht es um Information, nicht um Rueckfrage).
- Zwei parallele, unabhaengige Checks (`modeCheckContacts`/`modeCheckConsumers`), reine
  Auswertung in `shared/mode-activation-check.util.ts` (`buildContactCheck`/
  `buildConsumerCheck`/`loadingCheck`/`failedCheck`) — Component ruft nur `entityStateService
  .getEntities('BINARY_SENSOR','ZIGBEE')` und `powerConsumerService.getConsumers()` auf und
  mappt Erfolg/Fehler auf das Util. **Ein Fehlerfeld pro Check** (nicht ein gemeinsames) —
  faellt der eine Request aus, bleibt der andere unberuehrt (siehe auch
  template-pitfalls.md "Ein Fehlerfeld pro Ursache").
- `confirmModeActivation()` re-resolved den Modus **nur** ueber `this.modes.find(...)` (kein
  Fallback auf die gehaltene Dialog-Referenz wie beim Nachtrag im
  [[switch-confirmation-pattern]]) — bewusst so vom Plan vorgegeben, weil `modes` anders als
  `topSwitches` keine Top-N-Kappung hat und ein Not-Found praktisch nicht vorkommt. Wenn `modes`
  je eine aehnliche Kappung bekommt, diesen Fallback nachziehen.
- Test-Setup-Falle vermieden, die im [[switch-confirmation-pattern]] dokumentiert ist: die
  Tests nutzen `fixture.componentInstance.modes[0]` (aus der component-internen Liste, die
  `getModes()` befuellt hat), nicht eine frisch gebaute Fixture — sonst haette der Re-Resolve-
  Test nichts gefunden.
- Markup/SCSS des Dialogs kommt in einem separaten Task (Task 3) — Task 2 testet nur
  Component-Zustand, kein DOM.

## Task 3 (Markup/Styles/Escape), Commit `a40cfff`

- Dialog-Markup direkt in `dashboard.component.html` (lumina-Kapselung, siehe
  dashboard-style-encapsulation.md) zwischen dem Neustart-Hinweis und dem
  Spaziergaenge-Dialog-Kommentar; SCSS-Block direkt hinter `.lumina__confirm-go`.
  **Im Plan genannte SCSS-Zeilennummer war bereits veraltet** (Plan sagte "Block endet um
  Zeile 1605", tatsaechlich endete er bei Zeile 1551) — die Datei waechst schnell, vor dem
  Einfuegen lieber per Grep die aktuelle Position pruefen statt der im Plan genannten Zeile
  zu vertrauen.
  `.lumina__confirm-go--mode` (gruen statt rot — Aktivieren ist nicht destruktiv) und
  `.lumina__check[data-status]` (ok/warning je Check-Sektion) sind neu, alles andere
  (`.lumina__dialog-head/-close/-body`, `.lumina__confirm-go/-cancel`) wiederverwendet.
- Escape-Anbindung (Nachtrag aus dem Task-2-Review): `onEscape()` bekam
  `this.closeModeCheckDialog();` in der unteren, unbedingten Gruppe (nach den drei
  `if`-Returns) — schliesst nur, schaltet nie (`closeModeCheckDialog` setzt lediglich
  `modeCheckMode = null`).
  Baseline: 80/80 gruen im gefilterten Lauf; `ng build --configuration production` zeigt nur
  die vorbestehende Budget-WARNING (25.51 kB von 16 kB, siehe dashboard-scss-budget.md) —
  kein ERROR, kein Task-4-Handlungsbedarf ausgeloest.
