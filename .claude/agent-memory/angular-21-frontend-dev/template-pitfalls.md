---
name: template-pitfalls
description: Verified UI traps hit in this repo — @for track keys, label wrapping multiple buttons, plan snippets styled for the wrong tonality, and error banners silently overwritten by a parallel request
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

## Aus Abwesenheit erschlossene Beschriftungen werden bei Ladefehlern zu Falschaussagen
`"(deaktiviert)"` wurde daraus abgeleitet, dass eine Person **nicht** in der aktiven
Nutzerliste steht. Faellt deren Abruf aus, ist die Liste leer — und jede zugeordnete
Person traegt das Suffix, auch die quicklebendigen. Dieselbe Klasse Fehler wie ein
Hinweis, der aus einer leeren Liste auf "es gibt keine" schliesst, nur in die andere
Richtung. Loesung: das Label an einen **positiven Beleg** haengen (die geladene Liste
enthaelt die Person mit `enabled: false`); ohne Beleg nichts behaupten.

**Why:** Ein Ladefehler darf die Anzeige leer machen, aber nicht falsch.
**How to apply:** Bei jedem abgeleiteten Label/Badge fragen: "Was zeigt das an, wenn die
Quelle leer ist, weil ihr Abruf gescheitert ist?" Dieser Fall braucht einen eigenen Test —
er ist mit den ueblichen Erfolgs-Fixtures nicht abgedeckt.

## Gleiche Beschriftungen in zwei Seitenbereichen brechen textbasierte Test-Sucher
Die Filterleiste des Kalenders traegt dieselben Personennamen wie die Personen-Umschalter
im Termindialog. Der ueblich gewordene Spec-Helfer `findButton(label)` (sucht ueber
`querySelectorAll('button')` nach Text) fand danach den erstbesten — also den falschen —
und ein bestehender Test kippte, obwohl die UI korrekt war.

**Why:** Der Fehlschlag sieht aus wie ein Regressionsfund, ist aber ein mehrdeutiger
Selektor. Kostet Zeit, wenn man ihn falsch deutet.
**How to apply:** Sobald eine neue Ansicht Beschriftungen aus einer bestehenden wiederholt
(Namen, Kategorien, Zustaende), textbasierte Sucher auf ihre Gruppe einschraenken —
`findButtonIn('.calendar__person', label)` statt global. Vorbild:
`calendar.component.spec.ts`.

## Ein Fehlerfeld pro Ursache — sonst loescht ein paralleler Abruf die Meldung
Schreiben zwei Abrufe in dasselbe `loadError`, und einer davon leert es im Erfolgsfall
(`next: () => this.loadError = null`, das uebliche Muster hier), gewinnt der, der zuletzt
antwortet. Beim Seitenaufbau laufen sie parallel — die Meldung des fehlgeschlagenen
Abrufs ist damit praktisch nie zu sehen. Wird der leerende Abruf auch noch bei jeder
Interaktion wiederholt (hier: Monatswechsel), ist sie garantiert weg.

Real passiert in `calendar.component.ts`: Der Kategorien-Ladefehler wurde vom Monatsabruf
ueberschrieben. Uebrig blieb eine normal aussehende Seite, deren Termindialog auf jeden
Klick stumm nicht aufging — schlimmer als der Zustand, den der Guard verhindern sollte.
Loesung: eigenes Feld (`categoryError`) mit eigenem Banner-Block.

**Why:** Ein Guard, der eine Aktion blockiert, ist nur dann eine Verbesserung, wenn der
Nutzer erfaehrt *warum*. Sonst tauscht man einen sichtbaren Fehler gegen einen stummen.
**How to apply:** Beim Hinzufuegen eines Abrufs in eine Komponente pruefen, ob ein
bestehendes Fehlerfeld irgendwo bedingungslos auf `null` gesetzt wird. Und: einen Test,
der ein Fehlerbanner zusichert, immer *nach* dem Beantworten aller parallelen Abrufe
zusichern lassen — sonst prueft er einen Zwischenstand, den der Nutzer nie sieht.
