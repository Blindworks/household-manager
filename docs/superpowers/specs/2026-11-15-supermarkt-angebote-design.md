# Supermarkt-Angebote für Lieblingsprodukte – Design

Stand: 2026-11-15. Postleitzahl des Haushalts: 61118 (Bad Vilbel).

## Absicht

Jede Person im Haushalt trägt Lebensmittel ein, die sie gern kauft, so genau wie sie sie benennen würde („Coca-Cola Zero 0,33 l Dose“). Jede Nacht sammelt das System die Wochenangebote aller erreichbaren Quellen, erkennt passende Angebote und meldet **nur echte Schnäppchen**: ein Angebot, das deutlich billiger ist als das, was dieses Produkt in Angeboten üblicherweise kostet. Auf Abruf zeigt die Seite „Angebote“: „Penny hat deine Cola Zero gerade für 1,43 €/l, üblich sind 1,85 €/l.“

Warum: Coca-Cola und Co. sind praktisch jede Woche irgendwo im Angebot – eine Liste aller Angebote wäre Rauschen. Der Wert liegt im Ausreißer.

## Ziele

- Lieblingsprodukte je Person pflegen (optional „für den ganzen Haushalt“), mit harten Regeln.
- Nächtlicher Lauf über **alle** Quellen: Sammler (marktguru, kaufDA) **und** direkte Abrufe bei den Ketten; die Quellen ergänzen und prüfen sich gegenseitig.
- Zuordnung Angebot ↔ Produkt durch Claude, eingehegt von harten Regeln und gelernten „passt nicht“-Rückmeldungen.
- Preisgedächtnis je Produkt; Vorschlag nur bei deutlichem Unterschreiten des üblichen Angebots-Grundpreises.
- Anzeige: Seite „Angebote“, Karte im Intelligence Hub (Dashboard und Wandtablet), Entität je Person.

## Nicht-Ziele (v1)

- **Kein Push.** Kommt später als Flow auf die Entität, wenn die Treffsicherheit nach einigen Wochen klar ist.
- Kein Einkaufszettel, keine Bestellung, keine Warenkorb-Funktion.
- Keine Drogerien/Getränkemärkte als eigene Direktquellen (was die Sammler davon mitbringen, darf mitlaufen).
- Keine Preisprognose („nächste Woche billiger“).

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch läuft)

- Jede Woche „Cola im Angebot“ – also Normalangebote als Schnäppchen melden.
- Ein „passt vielleicht“ als sicheren Treffer darstellen, oder einen geratenen Grundpreis zeigen.
- Vorschläge aus Märkten, in die niemand geht.
- **Umgehen von Bot-Schutz** (Akamai, Cloudflare-Challenges), Logins in Kunden-Apps, aus Apps extrahierte Client-Zertifikate (REWE-mTLS). Was gesperrt ist, kommt über die Sammler.
- Ein ausgefallener Baustein, der den ganzen Lauf kippt oder still bleibt.

## Vorgehensweisen (Approaches)

1. **Sammler als Grundlage, Ketten direkt ergänzen** – *gewählt*. Je Quelle ein Baustein hinter einer gemeinsamen Schnittstelle (Muster `ChargingStationSource`), Zusammenführen doppelter Angebote, Direktquelle gewinnt bei Preis/App-Preis. Bringt App-Preise, Markt-genaue Angebote und Norma; kostet mehr Bausteine, die einzeln brechen können.
2. **Nur Sammler (marktguru + kaufDA)** – schneller, aber ohne Norma, App-Preise und Marktbezug; widerspricht „jede Quelle nutzen“.
3. **Bezahlte MCP-Scraper (Apify)** – verworfen: lesen dieselben Seiten, kosten je Abruf, Abhängigkeit von Dritten; die MCP-Hülle nützt einem Backend-Lauf nichts.

Zuordnung: gewählt ist **Claude plus harte Regeln** (verworfen: reine Stichworte – „versch. Sorten“ ist so nicht bewertbar; reines Claude – keine letzte Kontrolle).
„Üblich“: gewählt ist **mittlerer Angebots-Grundpreis der letzten 12 Wochen** (verworfen: Normalpreis/Streichpreis – jedes Cola-Angebot wäre ein Treffer).

## Quellen (gemessen am 2026-11-15 für 61118)

Rohantworten der Messung sind als Test-Fixtures ins Projekt zu übernehmen (`backend/src/test/resources/grocery/<quelle>/`) – sie sind die aufgezeichnete Wahrheit wie bei den Ladesäulen. Der vollständige Abzug liegt lokal (nicht versioniert, per `.git/info/exclude` ausgeschlossen) unter `.grocery-probes-2026-11-15/` (`probe-aggregators`, `probe-direct-a`, `probe-direct-b`, dazu die Auswerteskripte). Daraus werden verkleinerte Fixtures ins Projekt übernommen; die marktguru-Keys werden nie eingecheckt.

| Quelle | Abruf (Kurzform) | Angebote KW 47 | Besonderheiten |
|---|---|---|---|
| **marktguru** | Keys `apiKey`/`clientKey` aus dem HTML von `www.marktguru.de` (kleingeschriebenes `apiKey`, nicht das Google-`ApiKey`) → Header `x-apikey`/`x-clientkey`. Händlerliste `GET api.marktguru.de/api/v1/offers/publishers?as=mobile&zipCode=61118`, je Händler `GET …/api/v1/publishers/retailer/<uniqueName>/offers?as=web&zipCode=61118&limit=512&offset=n` | REWE 255, Lidl 858, Aldi Süd 520, Penny 298, Edeka 402, Kaufland 513, Netto MD 425 | Strukturierte Felder (`price`, `oldPrice`, `referencePrice`, `unit`, `volume`, `quantity`, `validFrom/To` UTC). Suche durchsucht keine Beschreibung → **immer Komplettlisten laden**. Keys bei 401 neu holen. Aldi Nord/Norma (je 15) vermutlich bundesweit, nicht für 61118 belegt. |
| **kaufDA/Bonial** | `GET www.kaufda.de/api/shelf?lat=50.1781&lng=8.73553&size=60&page=n` → Prospekte; je Prospekt `GET …/api/personalisedOffers?brochureId=<contentId>&size=1000&bonialAccountId=<zufällige, gespeicherte UUID>&userPlatformCategory=desktop`. Aldi Süd fehlt im Shelf, Prospekt-Id über `www.kaufda.de/Geschaefte/Aldi-Sued` (`__NEXT_DATA__`) | Lidl 420, REWE 232, Penny 273, Edeka 279, Kaufland 384, Aldi Süd 211 | Grundpreis nur als Freitext (`"(1 l = 1.33)"`, `"l-Preis 1.35"`), Packung nur in `description`. `closestStore` gibt die Filiale. Netto/Norma fehlen. |
| **REWE direkt** | Marktsuche `GET www.rewe.de/api/wksmarketsearch?searchTerm=61118` (`wwIdent`); Seite `GET www.rewe.de/angebote/<stadt>/<wwIdent>/<slug>/` liefert NANs (`data-offer-nan`) + Woche; Kacheln per `POST www.rewe.de/api/frontend-includes` (Array, ≤80 je Request, `offer-tile-by-nan`) → HTML-Fragmente | 243 (Am Südbahnhof 240767), 154 (Heilsberg 240405) | Angebote **je Markt verschieden**. REWE-Bonus-Guthaben; Streichpreise praktisch nie. Cloudflare davor: nur korrekte URLs verwenden (Slug aus Weiterleitung übernehmen), bei Challenge abbrechen. |
| **Penny direkt** | Märkte `GET www.penny.de/.rest/market` (`sellingRegion`); Woche/Kategorien aus `GET www.penny.de/angebote` (`data-current-week`, `data-category-name`); je Kategorie `GET /.rest/offers/by-category/<woche>/<kategorie>?region=<sellingRegion>` | 305 (Bad Vilbel 230372, Region 15A-05-67) | Sauberes JSON inkl. `benefitPrice` (App), `lowestPrice30Days`, `originalPrice`. Kein Gültigkeitsfeld: Woche bzw. „ab Donnerstag“. |
| **Kaufland direkt** | Filiale `GET filiale.kaufland.de/.klstorebygeo.json?lat=…&lng=…`; Angebote `GET filiale.kaufland.de/angebote/uebersicht.html` mit Cookie `x-aem-variant=<Filiale>`; Daten im eingebetteten `window.SSR[...] = {"component":"OfferTemplate",…}` | 541 (DE2553 Frankfurt), davon 280 Lebensmittel | `loyalty*`-Preise (Kaufland Card), `discount`, `basePrice`, `unit`. Deduplizieren nach `klNr`. |
| **Norma direkt** | `GET www.norma-online.de/de/angebote/` → Tagesseiten `/de/angebote/ab-<tag>,-dd.mm.yy/`; HTML-Blöcke `article.produktBoxContainer` | 196 | Bundesweit, keine Kategorie, Gültig-bis meist fehlend (→ Ende der Woche annehmen). Nur direkt verfügbar. |
| **Lidl direkt** | `GET www.lidl.de/q/api/search?assortment=DE&locale=de_DE&version=2.1.0&q=*&store=1&fetchsize=60&offset=n` (**ohne** `Accept: application/json`) | nur 35 Lebensmittel | Liefert `lidlPlus[]`-App-Preise. Wochensortiment unvollständig → nur Ergänzung zu marktguru. |
| Aldi Süd, Netto MD, Edeka direkt | – | – | Akamai-Sperre. **Nicht umgehen.** Nur über Sammler. Edeka-Startseite hätte 28 bundesweite Angebote – nicht verwenden (sperrt nach Sekunden). |

Jede Quelle ist ein eigener Baustein, wirft nie aus dem Lauf heraus und meldet sich an der Job-Tafel (`SystemJobMonitor`: `reportFailure` bei Fehler/0 Angeboten trotz Konfiguration, `reportInactive`, wenn ausgeschaltet). Jede Quelle ist einzeln per Property abschaltbar.

## Märkte

Admin-Seite „Supermärkte“: je Kette, welche Märkte zählen. Vorbelegung über die Marktsuchen um 61118; Auswahl je Kette (REWE: mehrere `wwIdent`; Penny: Markt → `sellingRegion`; Kaufland: Filiale; kaufDA: Prospekte, deren `closestStore` zu den gewählten Märkten passt oder deren Kette bundesweit gilt; Lidl/Aldi/Norma/Netto: bundesweit bzw. Region = „gilt“). marktguru liefert je PLZ und kennt keinen Markt – seine Angebote gelten für die Kette. Eine Kette ohne gewählten Markt wird nicht ausgewertet.

## Angebote zusammenführen

Jeder Baustein liefert ein einheitliches **Rohangebot**: Kette, Markt (oder „Kette“), Quelle, Quell-Id, Titel, Marke, Beschreibung, Packungstext, Preis, Streichpreis, App-Preis, Grundpreis (Betrag + Bezugseinheit), Gültig von/bis, Bild-URL.

- **Grundpreis normalisieren** auf €/l, €/kg oder €/Stück. Reihenfolge: strukturiertes Feld der Quelle → geparster Grundpreistext → aus Packung berechnet („18 x 0,33 l“ → 5,94 l). Pfand zählt nicht. Ist nichts sicher ermittelbar, bleibt der Grundpreis leer – nie raten. Ein Angebot ohne Grundpreis kann nicht als Schnäppchen gelten (wohl aber angezeigt werden, siehe unten).
- **Doppelte Angebote** (dasselbe Angebot über mehrere Quellen): gleiche Kette, überlappende Gültigkeit, gleicher Preis, ähnlicher Titel. Zusammengeführt; Direktquelle gewinnt bei Preis, App-Preis und Markt, Sammler ergänzt fehlende Felder. Widersprechen sich die Preise, gewinnt die Direktquelle und die Abweichung wird geloggt (gegenseitige Prüfung).
- Gespeichert wird je Lauf der Angebotsbestand (aktuell gültige und kommende Woche); Rohangebote sind nach 4 Wochen zu löschen (Retention-Job nachts, nicht zur vollen Stunde).

## Produkte und harte Regeln

Je Produkt: Name (Freitext wie gesprochen), Besitzer (Person oder Haushalt), optional „muss enthalten“-Wörter, „darf nicht enthalten“-Wörter, Bezugseinheit (l/kg/Stück – Vorschlag per Claude beim Anlegen, änderbar), optional ein eigener Normalpreis je Bezugseinheit (Fallback der Anlaufphase), aktiv/inaktiv.

Harte Regeln gelten **vor** Claude (Ausschluss spart Kosten) **und nach** Claude (ein Claude-„passt“ kann eine Regel nicht übergehen). Wortvergleich ohne Groß-/Kleinschreibung, als ganze Wörter. **„Muss enthalten“: alle Wörter müssen vorkommen**; „darf nicht enthalten“: schon eines schließt aus. *(Entscheidung von Claude.)*

## Zuordnung durch Claude

1. **Vorfilter** (billig, ohne Claude): ein Angebot ist Kandidat für ein Produkt, wenn ein Kennwort des Produkts im Titel/Marke/Beschreibung vorkommt. Kennwörter liefert Claude **einmal beim Anlegen/Ändern** des Produkts (z. B. „coca-cola“, „coca cola“, „cola“), sichtbar und editierbar.
2. **Urteil**: je Produkt ein Claude-Aufruf mit den Kandidaten (Modell: Haiku über die vorhandene `ai/`-Anbindung). Antwort je Kandidat: `MATCH` / `MAYBE` / `NO`, ein kurzer Grund („Sorte offen: versch. Sorten“, „1,25-l-Flasche statt Dose“). `MAYBE` = Produkt plausibel enthalten, aber Sorte offen oder andere Packungsform.
3. **Gedächtnis**: Urteile werden je (Produkt, Angebots-Fingerabdruck aus normalisiertem Titel+Beschreibung+Packung) gespeichert und nicht erneut erfragt – dasselbe Angebot steht oft mehrere Nächte und über mehrere Quellen.
4. **„Passt nicht“** vom Nutzer: der Fingerabdruck ist für dieses Produkt dauerhaft `NO`; die letzten zurückgewiesenen Texte gehen als Gegenbeispiele in künftige Aufrufe für das Produkt.
5. **„Passt“** vom Nutzer (nur bei `MAYBE`): der Fingerabdruck ist für dieses Produkt dauerhaft `MATCH` (Nutzer-Urteil, Claude fragt nicht mehr). Ins Preisgedächtnis zählt ein bestätigtes Angebot **nur, wenn Claude als Grund nicht „andere Packung“ genannt hat** – Claude liefert dafür zum Grund eine Art (`SORT_UNCLEAR`, `PACKAGING_DIFFERS`, `OTHER`). Bestätigte Angebote mit `PACKAGING_DIFFERS` werden als Treffer gezeigt, verfälschen aber den üblichen Preis nicht. *(Nutzerentscheidung 2026-11-15.)*
6. **Kosten-Deckel**: höchstens N Claude-Aufrufe je Nacht (Property, Startwert 50) – reicht der Deckel nicht, Rest am nächsten Lauf, Hinweis an der Job-Tafel. Ein Claude-Fehler lässt die Kandidaten unbewertet (nicht `NO`, nicht `MATCH`).

Der Prompt behandelt Angebotstexte als Daten, nicht als Anweisungen.

## Üblicher Preis und Schnäppchen

- **Preisgedächtnis**: jedes `MATCH`-Angebot mit Grundpreis wird als Beobachtung gespeichert (Produkt, Kette, Grundpreis ohne App, Gültigkeitswoche). `MAYBE` zählt nicht ins Gedächtnis (andere Packung verfälscht den Grundpreis). Aufbewahrung 365 Tage.
- **Üblich** = Median der Grundpreise aller Beobachtungen der letzten 12 Wochen, über alle Ketten, eine Beobachtung je Kette und Woche.
- **Schnäppchen** = Grundpreis ≤ üblich × (1 − Schwelle), Schwelle Startwert 10 % (einstellbar auf der Seite, global).
- **Anlaufphase**: solange zu wenige Beobachtungen (Startwert: < 6 Beobachtungen aus < 3 verschiedenen Wochen), gilt ersatzweise der eigene Normalpreis des Produkts, sonst der 30-Tage-Tiefstpreis der Quelle (Penny, Edeka über marktguru/kaufDA, wo vorhanden); der Vorschlag trägt „noch wenig Vergleichsdaten“. **Der Streichpreis ist kein Ersatzmaßstab** – mit ihm wäre jedes Cola-Angebot ein Treffer (Anti-Ziel). Ohne jede Vergleichsgröße kein Vorschlag. *(Builder-Check: Entscheidung von Claude, Streichpreis gestrichen.)*
- Bewertet wird der **Preis ohne App**. Ein App-Preis wird zusätzlich angezeigt („mit Lidl Plus 0,69 €/l“) und darf ein Angebot zusätzlich zum Schnäppchen machen, dann mit Kennzeichen „nur mit App“.
- `MAYBE`-Angebote werden mit demselben Maßstab geprüft und nur als „passt vielleicht“ mit Grund gezeigt.

## Anzeige

- **Seite „Angebote“** (Website, Navi unter Haushalt): oben meine Schnäppchen (sicher, dann vielleicht), je Eintrag Produkt, Markt, Packung, Preis, Grundpreis, üblich, Ersparnis in %, Gültigkeit, App-Preis, Knopf „passt nicht“ (bei „passt vielleicht“ zusätzlich „passt“). Darunter „Meine Produkte“ mit Anlegen/Bearbeiten/Regeln. **Auswahl je Person** (ich, jede andere Person, Haushalt): jeder darf alle Personen ansehen (Produkte, Schnäppchen, Preisverlauf), bearbeiten und Urteile abgeben nur für eigene und Haushaltsprodukte *(Nutzerentscheidung 2026-11-15, Muster Kalender: im Haushalt sieht jeder alles)*. Optional aufklappbar je Produkt: alle aktuellen passenden Angebote (auch die nicht günstigen) und der Preisverlauf.

```
Angebote für Benedikt                          Stand: heute 04:12
✅ Coca-Cola Zero 0,33 l Dose
   Penny Bad Vilbel · 18×0,33 l · 8,49 € (1,43 €/l) · ab Do 19.11.
   üblich 1,85 €/l · −23 %                              [passt nicht]
❔ … passt vielleicht: Sorte offen
```

- **Hub-Karte**: „2 Schnäppchen: Cola Zero bei Penny 1,43 €/l …“ – für die angemeldete Person plus Haushalt; im Tablet-Modus (KIOSK) die Haushaltsprodukte und alle Personen zusammen, ohne Link (Navigationsregel der Tablet-Ansichten).
- **Entitäten**: `sensor.grocery_deals_<userId>` und `sensor.grocery_deals_household`, State = Anzahl aktueller Schnäppchen, Attribut Kurzliste. Kein `deviceClass`, nie `unavailable` (lokale Daten). Grundlage für den späteren Push-Flow.

## Lauf und Bedienung

- Nächtlich **04:00** (Clock-Zone Europe/Berlin): alle Quellen abrufen → zusammenführen → Vorfilter → Claude → Schnäppchen → Entitäten. Einzelne Quellen isoliert (ein Fehler kippt die anderen nicht).
- „Jetzt suchen“ auf der Admin-Seite (ADMIN, Mindestabstand z. B. 10 min, 429), synchron oder als Hintergrundlauf mit Statusanzeige – Builder-Entscheidung.
- Admin-Seite zeigt je Quelle: letzter Lauf, Anzahl Angebote, Fehlergrund.

## Sicherheit

- Produkte, Vorschläge, „passt“/„passt nicht“: MEMBER (eigene Produkte und Haushaltsprodukte bearbeiten und beurteilen; Produkte anderer Personen nur lesen – serverseitig geprüft, 403 sonst). Lesen der Schnäppchen-Zusammenfassung für die Hub-Karte KIOSK.
- Märkte, Quellen, „Jetzt suchen“: ADMIN, methodenlose Matcher vor der generischen `GET /v1/**`-Regel; `SecurityRulesTest` hält alle Richtungen fest. Audit für Produkt- und Markt-Änderungen.
- Externe Fehler → 502, nie 401 (Auth-Interceptor).

## Rahmenbedingungen

- Backend Spring Boot, Liquibase (je DDL ein Changeset, MariaDB-Autocommit), Repositories im Paket `repository`, `@Scheduled` + Job-Tafel, `Clock`-Bean.
- HTTP-Clients mit normalem User-Agent, Timeouts, HTTP/1.1; keine personenbezogenen Daten in Requests; zufällige kaufDA-Kennung einmal erzeugen und speichern.
- Claude-Kosten getrennt erkennbar (Token-Summe je Lauf in der Laufprotokoll-Zeile).

## Dem Builder überlassen

Tabellen- und Klassennamen, genaue Parser je Quelle, Ähnlichkeitsmaß für das Zusammenführen, Layout der Seiten im Rahmen der Skizze, ob „Jetzt suchen“ synchron läuft, Anzahl Angebote je Claude-Aufruf.
