---
name: presence-header-circles-pattern
description: Anwesenheits-Kachel aus dem Dashboard-Footer in die lumina__clock-Kopfzeile verschoben, als Personenkreise mit tristate Ring-Farbe/Aura-Schein und Status-Icon statt Initiale; Branch feature/presence-header (Commit 51aa565), Restyle auf feature/presence-aura (Commit f0d6fb2), Icon+Breiten-Nachbesserung auf feature/presence-icons
metadata:
  type: project
---

Task 2026-08-26: `PresencePersonStatus`-Anzeige zog vom Footer (`.lumina-card.lumina__presence`)
in die `lumina__clock-meta`-Zeile (Uhr/Datum/Wetter/Aussenfuehler) im Dashboard-Header um, als
Reihe kleiner Kreise mit Initialen statt Zeilen mit Icon+Name+Text.

- **Tristate Ring-Farbe, nicht binaer:** `PresencePersonState` hat vier Werte
  (`on`/`off`/`unavailable`/`unknown`), aber nur drei visuelle Zustaende — `unavailable` UND
  `unknown` bekommen denselben neutralen/grauen Ring, NIE den roten von `off`. Grund: nach jedem
  Backend-Neustart steht jede Person bis zur Karenzzeit auf `unknown` (Messwerte leben nur im
  Speicher, siehe `presence-manual-refresh-frontend.md`) — ein roter Ring haette dem Wandtablet
  nach jedem Deploy "alle weg" vorgegaukelt. Umgesetzt als `presenceRingClass()`: exhaustiver
  `switch` ohne `default`, `case 'unavailable': case 'unknown':` fallen bewusst auf denselben
  Return — das ist weiterhin exhaustiv (jeder Case hat einen Pfad zum Return), `noImplicitReturns`
  greift bei einer kuenftigen fuenften Auspraegung trotzdem. Ersetzt die alte `presenceIcon()`-
  Methode komplett (Icons ergaben in einem 34px-Kreis keinen Sinn mehr) — vor dem Loeschen einer
  Methode wie dieser immer grep nach allen Aufrufstellen (Spec-Datei referenzierte sie an 6
  Stellen).
- **Initialen statt naivem `split(' ')`:** `displayName.trim().split(/\s+/).filter(Boolean)` —
  ein einzelnes Wort ergibt eine Initiale (nicht zwei aus erstem+letztem Zeichen desselben
  Worts), mehrfache/fuehrende/nachgestellte Leerzeichen ergeben keine leeren Teilstuecke. Ein
  Test mit `'  Anna   Maria  '` deckt genau den Fall ab, den `split(' ')` brechen wuerde.
  `presenceAriaLabel()` (`${displayName} • ${presenceLabel(person)}`) liefert denselben Text als
  `title`/`aria-label` — wiederverwendet `presenceLabel()` statt einer zweiten Formulierung.
- **Farbtokens:** kein neues Palette noetig — `var(--secondary)` (gruen, on), `var(--error)`
  (rot/pink, off), `rgba(192, 198, 214, 0.4)` (neutral) ist derselbe Muted-Ton wie
  `.lumina__climate-dot--stale` an anderer Stelle in derselben SCSS-Datei. Immer erst nach
  vorhandenen `--stale`/`--away`-Modifiern greifen statt eine neue Grau-Variable zu erfinden.
- **SCSS-Budget-Falle bestaetigt:** `dashboard.component.scss` war vorher schon 11.23 kB ueber
  dem 16-kB-Warn-Budget (27.23 kB total); nach Footer-Entfernung + neuen Kreis-Styles landete es
  bei 27.78 kB (+0.55 kB) — WARNING bleibt WARNING, kein ERROR. Um den exakten Vorher-Wert zu
  bekommen: `git stash push -- <die 4 Dateien>`, `ng build --configuration production` fuer den
  Baseline-Wert, dann `git stash pop`.
- **Testumbau:** `textContent).toContain('Benedikt')`/`toContain('Zu Hause')` funktionierte im
  Footer, weil Name+Label sichtbarer Text waren. Nach dem Umzug stehen sie nur noch in
  `title`/`aria-label` (Kreis zeigt nur Initialen) — Tests muessen auf
  `circle.getAttribute('title')` statt `nativeElement.textContent` pruefen. Wer eine Kachel von
  sichtbarem Volltext auf ein Icon/Kreis-mit-Attribut-Muster umbaut, muss ALLE
  `textContent`-Assertions auf das neue Element+Attribut umstellen, nicht nur die offensichtliche.
- **Deviation von den Stage-Anweisungen:** Auftrag sagte "stage only the three dashboard files"
  (ts/html/scss), aber die Tests mussten mit umgebaut werden (alte `presenceIcon`-Assertions
  waeren sonst ein Compile-Fehler in der Spec-Datei). Alle vier Dashboard-Dateien
  (ts/html/scss/spec.ts) explizit pro Pfad gestaged und committed — Agent-Memory/Backend-Dateien
  bleiben unstaged, wie gefordert.

## Review-Nachbesserung (Commit `14addc8`, gleicher Tag)

Ein Review des ersten Commits (`51aa565`) fand sechs A11y-/Robustheits-Luecken, alle auf dem
Wandtablet-Pfad. Kernlektionen fuer kuenftige Kreis-/Avatar-Muster:

- **`role="img"` ist Pflicht, sobald ein `<div>` per `aria-label` benannt werden soll.** Ein
  rollenloses `<div>` ist implizit `role="generic"`, und ARIA verbietet das Benennen von
  `generic` — der Name faellt fuer Screenreader ersatzlos weg, es bleibt nur der nackte
  Initialen-Buchstabe. Das uebliche Avatar-Muster (Kreis mit Initialen) braucht `role="img"`,
  damit `aria-label` ueberhaupt wirkt.
- **Farbe allein ist keine Kodierung, wenn beide Farben aehnlich hell sind.** `--secondary`
  (#53e16f) und `--error` (#ffb4ab) unterscheiden sich in Luminanz um unter 1 % — bei
  Rot-Gruen-Sehschwaeche bricht der Farbton weg und es bleibt kein Fallback. Zweite,
  farbunabhaengige Ebene (hier: `opacity: 0.5` nur auf `--off`) ist Pflicht, sobald Gruen/Rot die
  einzige Unterscheidung ist — genau das hatte die alte Footer-Kachel (`opacity: 0.55` auf
  `--away`) und der erste Refactor-Commit hat es ersatzlos gestrichen, ohne dass ein Test das
  gemerkt haette (keine Kontrast-/A11y-Tests im Suite).
- **`title`/`aria-label` sind auf einem KIOSK-Wandtablet (kein Zeiger, kein Screenreader)
  UNERREICHBAR.** Wer eine Kachel mit sichtbarem Klartext durch ein Icon/Kreis-Muster ersetzt,
  das Info nur noch in Tooltip/ARIA traegt, hat die Info auf genau dem Geraet entfernt, fuer das
  die Kachel urspruenglich gebaut wurde. Der Konflikt tritt typischerweise NICHT durch einen
  fehlenden Test auf, sondern weil "sieht am Desktop gut aus" den Kiosk-Fall verdeckt — bei
  jeder Wandtablet-Komponente aktiv gegenpruefen: "Wie sieht das ohne Maus und ohne
  Screenreader aus?" Fix hier: Name wieder als sichtbarer Text (`<span>` unter dem Kreis),
  Zustandstext bleibt im Tooltip.
- **`flex-wrap` auf der INNEREN Gruppe reicht nicht, wenn der AEUSSERE Flex-Container selbst
  nicht umbricht.** `.lumina__clock` hatte zwar `flex-wrap: wrap` (aber erst ab 768px Media
  Query), die dazwischenliegende `.lumina__clock-meta` (die eigentliche Zeile mit
  Datum/Wetter/Aussenfuehler/Praesenz) hatte gar keinen `flex-wrap` — der Ueberlauf lief in
  `.lumina { overflow: hidden }`, und weil `.lumina__clock` zentriert (`justify-content:
  center`), wurde **an beiden Enden** abgeschnitten (Uhr links, Kreise rechts), ohne jeden
  Hinweis. Bei jedem neuen Element in einer Flex-Zeile IMMER die ganze Container-Kette pruefen,
  nicht nur den direkten Elternteil. `flex-wrap: wrap` + `row-gap` auf BEIDEN Ebenen
  (`.lumina__clock-meta` UND `.lumina__presence-group`) behoben es; Praezedenzfall in derselben
  Datei: `.lumina__viewbar` (in CLAUDE.md dokumentiert, dort umgekehrt geloest mit
  `overflow-x: auto` statt Umbruch — bewusst verschieden, weil dort seitliches Scrollen gewollt
  war und hier Umbruch).
- **Kontrastschwelle 3:1 fuer nicht-textliche UI-Traeger** (WCAG 1.4.11): der neutrale Ring war
  mit `rgba(192, 198, 214, 0.4)` auf `#050505` bei ~2.6:1 — zu niedrig, weil der Ring hier
  alleiniger Informationstraeger ist (keine Beschriftung "unbekannt" direkt am Ring). Alpha auf
  0.55 angehoben (~3.6:1). Bei jedem neuen Farbring/Indikator, der alleinstehend Bedeutung
  traegt (nicht nur Dekoration neben Text), den Kontrast gegen den Hintergrund kurz gegenrechnen.
- **`charAt(0)` ist nicht surrogate-pair-sicher** — `[...string][0]` iteriert nach Codepoints,
  `charAt`/`[0]` nach UTF-16-Codeunits und zerlegt ein Zeichen ausserhalb der BMP (Emoji etc.) in
  eine kaputte Haelfte. Bei jeder "ersten Buchstabe eines Strings"-Logik default zu
  `[...str][0]` statt `str.charAt(0)` oder `str[0]`.
- **Ein `'?'`-Fallback-Zweig ohne Test wirkt wie totes Coverage-Fuellsel und wird als erstes
  wegrefactort.** `trim().split(/\s+/).filter(Boolean)` KANN ein leeres Array liefern (leerer
  oder nur-Leerzeichen-String) — ein Test mit genau diesem Input haelt fest, dass der Zweig
  erreichbar und beabsichtigt ist.
- **`trackBy` bei Live-Daten, die alle N Sekunden neu ankommen, ist auf einem 24/7-Wandtablet
  kein Nice-to-have.** Ohne `trackBy` reisst `*ngFor` bei jedem Poll-Zyklus alle DOM-Knoten ab
  und baut sie neu (hier: alle 30 s, seit Prozessstart bis zum naechsten Deploy) — auf einem
  Geraet, das nie neu laedt, unnoetiger DOM-Churn ohne jeden Nutzen.

## Restyle "Aura" (Branch feature/presence-aura, Commit f0d6fb2, 2026-08-26)

Reiner SCSS-Umbau (`dashboard.component.scss`, keine TS/HTML-Aenderung noetig) auf Wunsch des
Nutzers ("sieht scheisse aus, mach da mal moderner") — vom harten 2px-Ring auf eine getoente
Flaeche mit weichem Aussen-Schein (Glow), Kreis 34 -> 46px, Initialen 12 -> 16px.

- **`--off` bekommt bewusst KEINEN Glow**, waehrend `--on` einen bekommt — das ist die neue,
  staerkere Version der farbunabhaengigen zweiten Ebene aus dem ersten Review (vorher nur
  `opacity`). Gegengeprueft in Graustufen: `--on` hat eine sichtbar hellere Flaeche
  (`rgba(83,225,111,.13)` Hintergrund + Aussen-Schein) als `--off` (nur `rgba(255,180,171,.07)`,
  kein Schein) — der Off-Kreis bleibt in Graustufen deutlich stiller/dunkler als der On-Kreis.
  `--neutral` hat weder Flaechen-Tonung noch Schein (nur ein leiser Ring) und darf dadurch von
  Konstruktion her nie mit `--off` verwechselt werden.
- **Alle Aura-Farbwerte sind exakte RGB-Treffer auf bestehende Tokens** (`--secondary` =
  rgb(83,225,111), `--error` = rgb(255,180,171), `--on-surface-variant` = rgb(192,198,214),
  `--on-surface` = rgb(228,226,228)) — als literale `rgba(...)` mit Alpha geschrieben, NICHT als
  `color-mix()`, weil die Datei nirgends `color-mix` verwendet (z. B.
  `.lumina__climate-dot--stale` nutzt ebenfalls literales `rgba(192, 198, 214, 0.4)` statt eines
  Tokens) — bestehende Konvention der Datei nachgeahmt statt eine neue erfunden. Jede
  Token-abgeleitete rgba-Zeile hat einen Kommentar, der den Quelltoken nennt (fuer eine kuenftige
  Palettenaenderung). Die Text-Tint-Farbe `#c9f7d3` (auf `--on`) ist dagegen KEIN sauberer
  linearer Mix von `--secondary` mit Weiss (durchgerechnet: R/G/B ergeben unterschiedliche
  Mischverhaeltnisse) — vermutlich von Hand gewaehlt fuer Lesbarkeit auf dem Glow, deshalb als
  Literal mit Kommentar belassen statt eine falsche Mix-Formel zu erfinden.
  Der Basis-Hintergrund `rgba(255, 255, 255, 0.05)` ist dagegen kein Token-Ableger, sondern ein
  reiner Weiss-Overlay wie an anderen Stellen der Datei (`.lumina__outdoor:hover`).
- **Name-Dimming bei `--off` ohne HTML-Aenderung:** `<span class="lumina__presence-name">` steht
  im Markup als Nachbar-Element NACH dem Kreis-`<div>` im selben `.lumina__presence-person`. Der
  allgemeine Sibling-Selektor `.lumina__presence-circle--off ~ .lumina__presence-name { opacity:
  .55; }` reicht deshalb aus — kein neues Binding, keine Logikaenderung noetig. Immer erst pruefen,
  ob ein Geschwister-/Nachfolger-Selektor reicht, bevor man fuer eine reine Style-Bedingung eine
  neue `[ngClass]`/Template-Bedingung einfuehrt.
- **`.lumina__presence-person` musste von 44px auf 52px verbreitert werden**, weil der Kreis
  selbst von 34px auf 46px wuchs (46px waere sonst breiter als der Container und rechts
  beschnitten worden). Bei jeder Kreisgroessen-Aenderung in diesem Muster den Container explizit
  nachrechnen, nicht nur die Kreis-Regel selbst anpassen.
- **SCSS-Budget-Delta:** 28.11 kB -> 28.39 kB (+0.28 kB) trotz mehr Kommentaren — WARNING blieb
  WARNING (Budget-Schwelle 16 kB ist eine reine Groessenpolizei, kein Build-Blocker). Baseline vor
  dieser Aenderung per `npx ng build --configuration production` VOR dem Edit gemessen (kein
  `git stash` noetig, da nur eine Datei betroffen war).
- **Tests unveraendert gruen:** `dashboard.component.spec.ts` prueft nur Klassennamen/Text/Attribute
  (`classList).toContain('lumina__presence-circle--on')`, `title`-Attribut), keine Pixelwerte oder
  Border-Eigenschaften — ein reiner CSS-Restyle (Groesse, Border -> Box-Shadow, Farben) brauchte
  deshalb keine einzige Testaenderung. Baseline-Fails bleiben exakt die 3 vorbestehenden
  (AppComponent x2, HeroComponent).

## Nachbesserung "Icon statt Initiale" (Branch feature/presence-icons, 2026-08-26)

Nutzer-Feedback auf die Aura-Variante: Initiale im Kreis raus, stattdessen ein Status-Icon; dazu
war der Name bei laengeren Vornamen ("Benedikt") abgeschnitten (fixe 52px-Item-Breite +
`text-overflow: ellipsis`). Bei Sessionstart lagen TS/HTML des Icon-Wechsels bereits fertig im
Working Tree (unklar aus welcher Vorsession) — nur Spec-Datei, Icon-Groesse und der CLAUDE.md-Satz
fehlten noch. **Lektion: vor Arbeitsbeginn IMMER `git status`/`git diff` pruefen, ob der Auftrag
schon teilweise erledigt ist** — hier ersparte das einen kompletten Doppel-Umbau.

- **`presenceIcon()` kam 1:1 aus der Git-Historie zurueck** (`git log -p -- dashboard.component.ts
  | grep -n presenceIcon`), nicht neu erfunden: exhaustiver `switch` ohne `default`
  (`on`->`home`, `off`->`directions_walk`, `unavailable`->`signal_disconnected`,
  `unknown`->`help`), identischer Name/Wortlaut wie vor dem Initialen-Zwischenschritt. Beim
  "Feature X existierte schon mal, wurde entfernt, soll zurueck" IMMER zuerst in der Git-Historie
  nach dem alten Namen/der alten Implementierung suchen statt eine neue zu erfinden — spart Zeit
  und trifft die vom Team erwartete Benennung.
- **Icon-Groesse per Analogieschluss, nicht Default:** 46px-Kreis bekam 20px Icon, abgeleitet aus
  `.lumina__lock-btn` (44px Kreis, fast gleiche Groesse, 20px Icon) statt aus `.lumina__weather-icon`
  (30px, kein Kreis-Kontext) oder `.lumina__room-icon` (36px, viel groesserer Tile-Kontext). Bei
  "welche Icon-Groesse passt in diesen Kreis" immer nach einem AEHNLICH GROSSEN Kreis-Icon-Paar in
  derselben Datei suchen statt zu schaetzen.
- **Breiten-Fix loest den Ellipsis-Widerspruch analytisch:** Auftrag sagte "entferne die
  Ellipsis-Kuerzung" UND "behalte Ellipsis als Ausweichloesung" — kein Widerspruch, wenn man die
  BREITE des Elements vom Inhalt abhaengig macht (`min-width: 46px` = Kreisbreite, kein festes
  `width`, `max-width: 110px`): normale Namen wachsen das Element auf ihre volle Breite (Ellipsis
  greift nie), nur ein pathologischer Name jenseits 110px wird tatsaechlich gekappt. Verifiziert per
  Browser-JS-Messung (Node-Static-Server im Scratchpad, kein echter Backend-Zugriff noetig): "Benedikt"
  49.9px, "Christopherus" 79.9px (beide unter 110, kein Trunc), ein erfundener 55-Zeichen-Name kappt
  bei genau 110px (`scrollWidth 344 > clientWidth 110`).
- **Wrap-Sicherheit per erzwungenem 220px-Container bewiesen**, nicht nur behauptet: 5 Personen in
  einem kuenstlich verengten `.lumina__presence-group`-Klon wickelten sich zuverlaessig auf 2 Zeilen
  (3+2) statt am Rand beschnitten zu werden — `flex-wrap: wrap` auf `.lumina__presence-group` selbst
  (unabhaengig von der aeusseren `.lumina__clock-meta`-Ebene) traegt die Garantie allein. Weil die
  echte App ohne Backend keine `presencePersons` rendert (Guard `*ngIf="presencePersons.length > 0"`),
  war ein Live-Test in der laufenden Anwendung nicht moeglich — eine isolierte Kopie der echten
  CSS-Regeln in einer statischen HTML-Datei (Python `http.server` im Scratchpad noetig, `file://`
  aus dem Scratchpad-Ordner laesst den Browser-JS-Tool keine Skripte ausfuehren) war der einzige
  praktikable Weg zu einer echten Messung statt einer Vermutung.
- **`presenceInitials`-Tests (5 Assertions inkl. `'?'`-Fallback und Surrogate-Pair-Test) komplett
  entfernt, nicht behalten** — sie waren nach der Methodenumbenennung tote Coverage, kein
  Testverlust. Stattdessen EIN neuer Test, der den eigentlichen Zweck der Aenderung belegt:
  `presenceIcon(unavailable) !== presenceIcon(unknown)` bei gleichzeitig gleichem
  `presenceRingClass` — das ist die Zusicherung, die vorher fehlte und die den Sinn des ganzen
  Umbaus traegt.
- **SCSS-Budget-Delta bei dieser Nachbesserung war NEGATIV** (28.39kB -> 28.36kB, -0.03kB): der
  Wegfall von vier Initialen-Font-Properties (`font-family/font-size/font-weight/letter-spacing`
  auf `.lumina__presence-circle`) wog schwerer als die neue `.lumina__presence-icon`-Regel plus
  laengere Kommentare. Nicht jede Erweiterung treibt das Budget nach oben.
