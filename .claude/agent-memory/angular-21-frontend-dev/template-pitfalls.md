---
name: template-pitfalls
description: Verified UI traps hit in this repo — @for track keys, label wrapping multiple buttons, plan snippets styled for the wrong tonality, and error banners silently overwritten by a parallel request
metadata:
  type: feedback
---

# Template-Fallen (in diesem Repo real aufgetreten)

## `@for ... track` braucht eindeutige Werte — und NG0955 ist nur eine *Warnung*
Beim Rendern abgeleiteter Werte (z. B. Initialen von Personennamen) ist der Wert selbst
**kein** gueltiger Track-Key: zwei Personen mit demselben Anfangsbuchstaben erzeugen
doppelte Keys. Bei abgeleiteten/nicht garantiert eindeutigen Werten `track $index` nehmen;
`track item.id` nur, wenn es wirklich eine Id gibt.

**Korrektur (nachgemessen, Angular 19.2.18 in diesem Repo):** NG0955 wird als
`console.warn` ausgegeben, **nicht** geworfen — eine fruehere Notiz hier behauptete das
Gegenteil. Ein Test, der nur die Anzeige prueft, laeuft mit doppelten Keys gruen durch;
das Rendern selbst ist beim ersten Durchlauf korrekt. Im Produktionsbuild entfaellt die
Pruefung ganz, uebrig bleibt fehlerhafte DOM-Wiederverwendung beim naechsten
Aktualisieren (Klick auf Chip A oeffnet Vorkommen B). Zusichern laesst sich das nur ueber
`spyOn(console, 'warn')` + `expect(...).not.toContain('NG0955')` — Vorbild:
`calendar.component.spec.ts`.

**Und dieser Nachweis braucht zwei Reconcile-Durchlaeufe.** `@for` prueft die Schluessel
erst gegen eine bereits gefuellte Live-Collection; beim ersten Rendern ist `liveEndIdx =
-1`, die Hauptschleife laeuft null Mal und `recordDuplicateKeys` wird nie erreicht. Dass
so ein Test ueberhaupt greift, liegt allein daran, dass `ComponentFixture.detectChanges()`
intern `checkNoChanges()` nachschiebt. Mit `detectChanges(false)` oder nach einer
Umstellung auf zoneless waere er lautlos wirkungslos — und bliebe gruen. Deshalb ein
zweites `fixture.detectChanges()` explizit hinschreiben und kommentieren.

**Zusammengesetzte Schluessel sind Behauptungen, keine Garantien.** Konkret gefallen:
`eventId + occurrenceDate` fuer Kalender-Vorkommen. Verschiebt der Nutzer ein
Serien-Vorkommen per "Nur diesen Termin" auf einen anderen Tag *derselben* Serie, meldet
die Override-Zeile die Master-Id als `eventId` und den neuen Tag als `occurrenceDate`;
serverseitig unterdrueckt wird nur das urspruengliche Datum
(`CalendarEventService.getOccurrences` fuellt `overriddenDates` aus `recurrenceDate`).
Beide Vorkommen tragen dann denselben Schluessel.

**Why:** Der Schaden ist im Prod-Build still und aeussert sich als "falscher Termin
oeffnet sich" — praktisch nicht als Track-Key-Problem erkennbar.
**How to apply:** Bei jedem neuen `@for` fragen, ob der Track-Ausdruck ueber die ganze
Liste eindeutig sein *muss* und ob er das garantiert ist. Wenn die Eindeutigkeit aus einer
Invariante der Datenquelle folgt, diese Invariante **im Backend nachlesen**, statt sie
hinzuschreiben.

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

## `ngModel` in einem `<form>` ist im Test erst nach `whenStable()` verdrahtet
Verifiziert 2026-07-27 (Angular 19.2.x in `frontend/`, Chrome Headless 150), nachgelesen in
`node_modules/@angular/forms/fesm2022/forms.mjs`: `NgForm.addControl` registriert jedes
`NgModel` erst in einem **Microtask** (`resolvedPromise.then(...)`). Bis der geflossen ist,
haengt der ValueAccessor noch nicht am Modell:

```ts
fixture.detectChanges();
input.value = 'x';
input.dispatchEvent(new Event('input'));   // veraendert das Modell NICHT
```

Der Test scheitert dann an einer irrefuehrenden Stelle weiter unten ("erwartet 'neu',
bekam 'alt'", oder ein erwarteter Request bleibt aus) — der Absender sieht wie ein
Komponentenfehler aus, ist aber Test-Timing. Abhilfe: `it(..., async () => { ...
await fixture.whenStable(); ... })` nach dem ersten `detectChanges()`. Vorbild:
`admin-calendar-categories.component.spec.ts` (`loadWith`). Ausserhalb eines `<form>`
(z. B. `vision.component.spec.ts`) tritt das nicht auf — dort laeuft `NgModel` standalone
und wird synchron verdrahtet.

**Verwandt, aber Entwarnung:** Ein `[ngModel]` **ohne** `name` in einer Kind-Komponente
(z. B. das Freitextfeld in `IconPickerComponent`) wirft *kein* `NG01352`, auch wenn die
Kind-Komponente in einem `<form>` steht: `NgModel` injiziert seinen `ControlContainer` mit
`@Optional() @Host()`, und `@Host()` stoppt an der Host-Grenze der Komponente. Der
IconPicker darf also gefahrlos in einem `<form>` stehen.

## Ein neuer TABLET_VIEWS-Eintrag kann Schwester-Specs brechen — nie ungeprueft als "vorbestehender Flake" abtun
`shared/tablet-views.ts` ist die einzige Definition der Ansichtsleiste (`app-tablet-shell`,
`<nav class="lumina__viewbar">`, `flex-wrap: wrap`). Ein fuenfter Eintrag (Task 10,
`/tablet/network`) liess die Leiste bei der in Karma gerenderten Breite zweizeilig
umbrechen und stahl dem Kachelraster darunter Hoehe — der Hoehenketten-Test von
`tablet-air-quality.component.spec.ts` fiel dadurch **deterministisch** (3/3 Laeufen)
durch ("Expected 48.86 to be greater than 80"), nicht nur gelegentlich. Ich hatte das
faelschlich als denselben "vorbestehenden Karma-Body-Hoehen-Flake" eingeordnet wie in
[[testing-notes]] dokumentiert, weil der isolierte Lauf der Datei ebenfalls fehlschlug —
aber nur, weil ich testete, ohne die eigene Aenderung an `tablet-views.ts` probeweise
zurueckzunehmen. Setzt man NUR diese Datei zurueck, ist der Test wieder 15/15 gruen.

**Fix:** `.lumina__viewbar` auf `flex-wrap: nowrap` + `overflow-x: auto` (statt `wrap`),
Knopf-Padding/Icon-Groesse kompakter. Existiert **zweimal** (Dashboard-Kopie in
`dashboard.component.scss`, siehe [[dashboard-style-encapsulation]]) — beide Stellen
anfassen.

**Why:** Ein rot laufender Test in einer fremden Datei nach der eigenen Aenderung ist erst
dann ein "vorbestehender Flake", wenn er auch mit der eigenen Aenderung *entfernt*
weiterhin fehlschlaegt — nicht schon, wenn er isoliert (aber mit der Aenderung noch drin)
fehlschlaegt.
**How to apply:** Vor dem Einordnen eines fremden Testfehlschlags als "pre-existing" immer
per `git stash`/gezieltem Zuruecksetzen der eigenen Datei gegenpruefen, ob der Fehlschlag
OHNE die eigene Aenderung verschwindet. Bei jedem neuen Eintrag in einer geteilten
Konstante (`TABLET_VIEWS`, `AIR_QUALITY_METRICS`, etc.) gezielt die Schwester-Specs
mitlaufen lassen, die dieselbe geteilte Datei ueber eine gemeinsame Elternkomponente
rendern.

## `date`/`number`-Pipe mit explizitem `'de'` wirft NG0701 in Karma, ohne `registerLocaleData`
`main.ts` registriert die de-Locale nur fuer die echte App (`registerLocaleData(localeDe)`
beim Bootstrap) — Karma laedt `main.ts` nie. Jedes Template, das `date:'...':undefined:'de'`
oder `number:'...':'de'` mit explizitem Locale-Argument nutzt (z. B. die Vorbild-Snippets
in Taskplaenen fuer Admin-Seiten), wirft beim ersten `detectChanges()` im Test
`NG02100 InvalidPipeArgument: NG0701: Missing locale data for the locale "de"` — alle
Tests der Datei fallen durch, nicht nur einer. Betraf zuletzt `admin-presence.component.ts`
(Task 12, Anwesenheitserkennung) genau nach Vorlage aus dem Plan; `pet-food.component.spec.ts`
hat den Fix schon laenger.

**Fix:** im Spec ganz oben `import { registerLocaleData } from '@angular/common'`,
`import localeDe from '@angular/common/locales/de'`, dann `registerLocaleData(localeDe);`
vor dem `describe`.

**Why:** Ohne das laesst sich der Plan-Code 1:1 uebernehmen, kompiliert, und faellt dann
komplett in Karma durch — leicht mit einem echten Implementierungsfehler zu verwechseln.
**How to apply:** Sobald eine neue Komponente ein Template mit explizitem `'de'`-Locale-
Argument in einer `date`- oder `number`-Pipe bekommt, erst pruefen, ob das Format
ueberhaupt ein sprachabhaengiges Token enthaelt (Monatsname, Wochentag). Reine Ziffernformate
wie `'dd.MM. HH:mm'` brauchen KEIN Locale-Argument — das Argument einfach weglassen ist der
bessere Fix als `registerLocaleData` im Spec nachzuruesten (Review-Fund, Task 12: der Aufruf
war der einzige `'de'`-Pipe-Aufruf der ganzen App und brachte nichts). Nur wenn das Format
wirklich lokalisierte Tokens braucht, den Registrierungs-Import in die Spec-Datei schreiben.

## Ein neu zugewiesenes Formular-Objekt braucht nach `detectChanges()` noch ein `whenStable()`
Bekannt war bereits: die ERSTE Bindung eines `ngModel` in einem `<form>` braucht
`await fixture.whenStable()` nach dem ersten `detectChanges()` (NgForm registriert
ValueAccessors erst im Microtask). Neu (Task 12, Review-Fund): das gilt genauso, wenn
eine BEREITS gebundene Formulargruppe spaeter im Testverlauf komplett ausgetauscht wird
(`this.form = emptyForm();` nach einem erfolgreichen Save/Delete). Ein Test, der nach dem
Reset nur `fixture.detectChanges()` aufruft und dann `input.value` prueft, sieht noch den
ALTEN Wert — `component.form.name` ist zu diesem Zeitpunkt laengst `''`, nur die DOM-Seite
hinkt hinterher. Erst ein zusaetzliches `await fixture.whenStable();` (danach nochmal
`detectChanges()`) synchronisiert den neuen Objekt-Wert ins Input.

**Why:** Der Bug sieht aus wie "clearFormState() laeuft nicht", ist aber ein reines
Test-Timing-Problem — `console.log(component.form)` direkt nach dem ersten `detectChanges()`
zeigt den korrekten (geleerten) Zustand, nur das Input-Element zeigt ihn noch nicht.
**How to apply:** Jeder Test, der nach einer Aktion prueft, ob ein `[(ngModel)]`-gebundenes
Feld zurueckgesetzt wurde, braucht `await fixture.whenStable()` zwischen der ausloesenden
Aktion (bzw. dem letzten `httpMock.flush(...)`) und der DOM-Pruefung — nicht nur beim
allerersten Formularaufbau.

## Ein Fehler-Signal, das zwei unabhaengige Fehlerquellen teilt, loescht sich selbst
Schreiben zwei voneinander unabhaengige Abrufe (z. B. Geraeteliste UND Personenliste) in
dasselbe `errorMessage`-Signal, und Geraete-Aktionen setzen dieses Signal routinemaessig auf
`null` zurueck (Muster: jede Aktion beginnt mit `this.errorMessage.set(null)`), dann loescht
der naechstbeste Klick auf eine ganz andere Aktion (Bearbeiten, Abbrechen, Umschalten) die
Erklaerung fuer den ERSTEN, noch ungeloesten Fehler — ohne dass der Nutzer je erfaehrt,
warum z. B. das Personen-Dropdown leer ist (Task 12, Review-Fund).
**Why:** Sichtbar wird der Bug nur, wenn beide Fehlerquellen gleichzeitig/kurz
hintereinander ausfallen — im Alltag selten genug, um durch Review zu rutschen, aber genau
dann besonders aergerlich, weil er die einzige Erklaerung wegwischt.
**How to apply:** Sobald eine Komponente mehr als eine unabhaengige Fehlerquelle hat, JEDE
ihr eigenes Signal geben und es NUR aus dem eigenen Ladepfad zuruecksetzen — nie aus dem
Reset-Pfad einer anderen Aktion. Siehe [[dashboard-style-encapsulation]] fuer das verwandte
Muster "ein Zustand pro Ursache" bei visueller Kapselung.

## Eine Checkbox als Umschalter kann optisch etwas anderes zeigen als gesendet wird
Eine `<input type="checkbox" [checked]="device.active" (change)="setActive(device, !device.active)">`
kippt beim Klick SOFORT ihren eigenen DOM-Haken um (Browser-Default-Verhalten), aber der
gesendete Wert kommt aus `!device.active` — dem Modell-Wert VOR dem Request. Bis die Antwort
da ist und `device.active` neu geladen wurde, zeigt die Box einen Zustand, der nicht dem
entspricht, was unterwegs ist. Zwei schnelle Klicks senden zweimal denselben Wert, waehrend
die Box sichtbar hin- und herspringt (Task 12, Review-Fund; Referenzimplementierung
`admin-network-devices` nutzt deshalb bewusst einen Knopf statt einer Checkbox).
**Fix:** ein `<button>`, dessen Beschriftung direkt aus dem Modell kommt
(`{{ device.active ? 'Deaktivieren' : 'Aktivieren' }}`) — der kann nie etwas anderes
behaupten als das, was der naechste Klick sendet. Zusaetzlich ein Sperr-Signal
(`togglingId`), das den Knopf waehrend des laufenden Requests deaktiviert, sonst loesen
N Klicks N Requests aus, deren Antworten in beliebiger Reihenfolge eintreffen koennen.
**Why:** Ein Haken, der optisch "an" zeigt, waehrend "aus" unterwegs ist, ist eine
Falschanzeige mit Sicherheitsrelevanz bei Ausschalt-Bestaetigungen (vgl. `confirm_required`
in CLAUDE.md — dieselbe Familie von Bug: "UI zeigt einen Zustand, den das Modell nicht hat").
**How to apply:** Bei jedem Aktiv/Inaktiv-Umschalter in einer Admin-Tabelle grundsaetzlich
den Knopf-statt-Checkbox-Ansatz der Referenzseiten uebernehmen, nicht neu erfinden.

## `routerLink` im Template verlangt `provideRouter([])` in der Spec, auch ohne einen einzigen Router-Test
`RouterLink` injiziert `ActivatedRoute` — fehlt der Router-Provider im `TestBed`, wirft
JEDER Test, dessen `fixture.detectChanges()` den `routerLink` tatsaechlich rendert
(`NullInjectorError: No provider for ActivatedRoute!`), auch wenn der Test selbst nichts
mit Navigation zu tun hat (Task 10, Kamera-Dashboard: der Fehler kam nur in "zeigt den
Login-Hinweis", weil nur dort der Fehlerbanner mit dem `<a routerLink>` sichtbar wird).
Betrifft nur Tests, die den Zweig mit dem Link tatsaechlich durchlaufen — Tests ohne diesen
Zweig bleiben gruen und taeuschen eine funktionierende Spec vor.
**Fix:** `import { provideRouter } from '@angular/router';` und `provideRouter([])` in die
`providers`-Liste des `TestBed.configureTestingModule(...)`. Selbes Muster wie der
`header.component.spec.ts`-Fix in [[testing-notes]].
**Why:** Plan-Vorlagen fuer Seiten mit `routerLink` (hier: Login-Hinweis auf `/vision`)
geben oft nur `imports`+`providers` fuer den fachlichen Service vor und vergessen den
Router-Provider, weil er in einer ersten, gluecklichen Testreihenfolge nicht auffaellt.
**How to apply:** Sobald ein Template `routerLink` (oder `routerLinkActive`) nutzt, im
Spec routinemaessig `provideRouter([])` ergaenzen — unabhaengig davon, ob ein Test
Navigation pruefen soll.
