---
name: switch-confirmation-pattern
description: Ausschalt-Bestaetigung (confirm_required) — Muster ueber alle drei Oberflaechen, Test-Falle beim Re-Resolve-Guard
metadata:
  type: project
---

Die Ausschalt-Bestaetigung (`confirm_required` auf `entity_states`, siehe CLAUDE.md
"Switch Confirmation") lebt jetzt an drei Stellen: Dashboard-Kachel, Geraeteseite
(`smart-device-list`), Helfer-Seite (`custom-entities`, nur `INPUT_BOOLEAN`). Alle drei
folgen exakt demselben Skelett — bei neuen Oberflaechen mit schaltbaren Entitaeten dieses
Skelett kopieren, nicht neu erfinden:

- Ein `confirmOff*`-Feld (Dashboard: `confirmSwitch`/`confirmSwitchList`, Geraeteseite:
  `confirmOffDevice`, Helfer-Seite: `confirmOffEntity`), null = Dialog zu.
- `toggle()`/`toggleDevice()`/`toggleSwitch()`: bei `confirmRequired && istAn` nur das Feld
  setzen und zurueckkehren, sonst direkt schalten. Einschalten ist **immer** direkt (nie
  geschuetzt) — Grundregel steht in CLAUDE.md.
- `confirmTurnOff()`/`confirmToggle()`: schliesst den Dialog, loest die Entitaet/den
  Schalter **ueber die entityId/id in der aktuellen Liste neu auf** und schaltet nur, wenn
  der frisch aufgeloeste Stand noch "an" ist. Ohne dieses Re-Resolve wuerde ein waehrend
  offenem Dialog eintreffender Hintergrund-Refresh, der das Ding bereits ausgeschaltet hat,
  es ueber den "Ausschalten"-Knopf wieder EINschalten. Dashboard und Helfer-Seite haben das
  urspruenglich nicht gemacht — dieser Bug/Fix ist das Referenzmuster fuer jede neue
  Bestaetigungs-Oberflaeche.
- Dialogmarkup pro Seite **eigenstaendig und gekapselt**, niemals die `lumina-*`-Klassen des
  Dashboards importieren/kopieren (die sind ausschliesslich in `dashboard.component.scss`
  gekapselt, siehe [[dashboard-style-encapsulation]] / template-pitfalls.md). Geraeteseite und
  Helfer-Seite teilen sich denselben `.confirm-backdrop`/`.confirm-dialog`-Klassennamen-Stil
  (schwarzer Backdrop, weisse Karte, rot fuer den Bestaetigen-Knopf) — bewusst redundant statt
  eine gemeinsame Komponente, weil jede Seite ihr eigenes Farbschema hat.
- Barrierefreiheit: `role="dialog"` `aria-modal="true"`, Titel per `<h3 id="...">` +
  `aria-labelledby` (NICHT `aria-label` mit abweichendem Text — genau dieser Fehler stand
  monatelang in der Geraeteseite: `aria-label="Ausschalten bestätigen"` neben einer
  Ueberschrift "Wirklich ausschalten?", die nirgends im Screenreader-Text vorkam).
  `(click)="$event.stopPropagation()"` auf der Dialog-Box, Backdrop-Klick + Escape
  (`@HostListener('document:keydown.escape')`) schliessen. Kein Fokus-Trap — hat in diesem
  Repo noch nichts.
- Interaktion **bewusst uneinheitlich**: Dashboard laesst den Nutzer den echten Schalter im
  Dialog antippen (`app-switch-list` mit `variant="dialog"`); Geraeteseite/Helfer-Seite haben
  stattdessen einen expliziten roten "Ausschalten"-Knopf. In CLAUDE.md festgehalten, damit das
  niemand als Inkonsistenz "repariert".

**Test-Falle beim Re-Resolve-Guard:** Wenn Test-Fixtures den zu bestaetigenden Eintrag per
Factory-Funktion (`entity({...})`/`device({...})`) frisch bauen statt ihn aus der
component-internen Liste (`topSwitches`/`devices`/`entities()`) zu nehmen, ist er ein
*anderes* Objekt als das, was der Guard beim Re-Resolve findet — der Guard sieht dann den
Default-Zustand der Liste (meist `off`), nicht den frisch gebauten `on`-Zustand, und die
Bestaetigung bleibt wirkungslos, obwohl der Code korrekt ist. Fix: die Liste im Test
explizit auf den zu bestaetigenden Eintrag setzen (`fixture.componentInstance.topSwitches =
[guarded]`), bevor `toggleSwitch`/`confirmToggle` aufgerufen wird — spiegelt den echten
UI-Fluss, wo der Klick immer aus der Liste selbst kommt (Referenzidentitaet).

Mutation-Testing-Rezept, das sich bewaehrt hat: Dialogmarkup-Block komplett loeschen →
DOM-Spec muss rot werden; Re-Resolve-Guard auf `if (entity)` zuruecksetzen → Re-Resolve-Spec
muss rot werden. Beide Male tatsaechlich ausfuehren, nicht nur behaupten.

**Nachtrag (vom Reviewer gefunden, 2026-08-19): "ueber die Id neu aufloesen" braucht einen
dritten Fallback auf die gehaltene Referenz, sonst wird der Guard zum stillen No-op.**
`confirmToggle`/`confirmTurnOff` laufen zur *Bestaetigungszeit*, nicht zur Klickzeit — beide
Listen koennen dazwischen leer geworden sein (Dashboard: ein fehlgeschlagener 30s-Refresh
leert `topSwitches` per `catchError(() => of([]))`; `SWITCH_TILE_LIMIT` kann einen weiterhin
an geschuetzten Schalter waehrend offenem Dialog aus den Top 4 verdraengen). Reine
`?? undefined`-Verkettung ohne dritten Fallback findet dann nichts, `undefined?.state ===
'on'` ist `false`, und der Schaltbefehl wird lautlos verschluckt — kein Fehler, keine
Meldung, der Dialog schliesst sich einfach, als waere geschaltet worden. Richtiges Muster:
`liste1.find(...) ?? liste2.find(...) ?? gehaltenerEintrag`, dann `if (aufgeloest.state ===
'on')`. Sicher, weil `toggle()` den Dialog ueberhaupt nur oeffnet, wenn der Eintrag beim
Klick "an" war — "nicht gefunden" ist kein Beleg fuer "ist aus", nur ein Beleg dafuer, dass
die Liste inzwischen anders aussieht. Beim Nachziehen dieses Musters auf eine vierte
Oberflaeche: **immer** einen Not-Found-Fallback-Test schreiben (Liste leeren, bestaetigen,
Toggle muss trotzdem feuern) UND pruefen, dass der bestehende Race-Test noch rot wird, wenn
die `state === 'on'`-Bedingung komplett entfernt wird — beide Richtungen brauchen eigene
Abdeckung, das eine faengt nicht das andere.
