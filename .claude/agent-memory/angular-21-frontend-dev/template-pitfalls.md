---
name: template-pitfalls
description: Verified Angular template/markup traps hit in this repo — @for track keys, label wrapping multiple buttons, and plan snippets styled for the wrong tonality
metadata:
  type: feedback
---

# Template-Fallen (in diesem Repo real aufgetreten)

## `@for ... track` braucht eindeutige Werte
Beim Rendern abgeleiteter Werte (z. B. Initialen von Personennamen) ist der Wert selbst
**kein** gueltiger Track-Key: zwei Personen mit demselben Anfangsbuchstaben erzeugen
doppelte Keys, und Angular wirft dafuer zur Laufzeit (NG0955), nicht beim Build. Bei
abgeleiteten/nicht garantiert eindeutigen Werten `track $index` nehmen; `track item.id`
nur, wenn es wirklich eine Id gibt.

**Why:** Der Fehler kommt erst zur Laufzeit mit echten Daten — Build und Tests mit
Ein-Element-Fixtures laufen gruen durch.
**How to apply:** Bei jedem neuen `@for` fragen, ob der Track-Ausdruck ueber die ganze
Liste eindeutig sein *muss* und ob er das garantiert ist.

## `<label>` darf nicht mehrere Buttons umschliessen
`<button>` ist ein labelable element. Ein `<label>`, das mehrere Toggle-Buttons (oder
Buttons + Hinweistext) umschliesst, leitet einen Klick auf Leerraum oder den Hinweistext
an den **ersten** Button weiter — der Nutzer loest ungewollt die erste Auswahl aus.
Stattdessen `<div class="...__field" role="group" aria-label="...">` verwenden; die
Toggle-Buttons selbst bekommen `aria-pressed`.
Vorbild im Bestand: `calendar.component.html`, Wochentag-Picker (`__weekday-picker`) und
Personenauswahl (`__persons`).

## Vorgegebene SCSS-Schnipsel gegen die Tonalitaet der Zielseite pruefen
Plan-/Design-Schnipsel kommen oft aus dem Dashboard-Kontext und nutzen
`rgba(255, 255, 255, ...)` fuer Raender und aktive Zustaende. Der Kalenderdialog und die
Tageszellen sind aber **weiss** (`var(--color-white)`) — dort waere das weiss auf weiss
und damit unsichtbar. Auf hellen Seiten gilt die Bestandskonvention
`rgba(0, 0, 0, 0.25)` fuer Raender und `var(--color-primary)` fuer den Aktiv-Zustand
(siehe `calendar.component.scss`, `__weekday-btn` / `__btn--primary`).
Touch-Umschalter erben ausserdem `min-height: 2.75rem` — das Wandtablet ist der
Haupt-Einsatzzweck, und die kleineren Default-Groessen wurden dafuer schon einmal
bewusst vergroessert.

Siehe auch [[dashboard-style-encapsulation]] (Kehrseite: `lumina`-Styles greifen nur in
`dashboard.component.*`).
