# Wiederkehrende Zahlungen: selbstständige Erkennung

Stand: 2026-11-10

## Anlass und Ziel

Die Seite „Wiederkehrende Zahlungen“ (`/finance-recurring`) erkennt fast nichts, gerade keine Abos. Ein Nachrechnen mit dem echten Sparkasse-Export (10.11.25–10.11.26) ergab heute 7 Kandidaten, die meisten davon Versorger. Gründe:

1. Gruppiert wird nur nach dem normalisierten Empfängernamen (`CounterpartyNameNormalizer`). Alle 92 PayPal-Zahlungen landen in **einer** Gruppe, Spotify, Telekom und LogPay sind darin unsichtbar.
2. Ein Empfänger mit mehreren Verträgen ergibt eine vermischte Reihe. Drillisch zieht 4,99 € (Mobilfunk) und 7,99 € (PremiumSIM) je Monat ein, im Mittel alle 15 Tage, also kein Intervall.
3. `RecurrenceAnalyzer` verlangt, dass **jeder** Abstand höchstens 25 % vom Mittel abweicht. Ein einziger verschobener oder doppelter Termin kippt die Reihe (Host Europe, Claude, Miete).
4. Die Erkennung läuft nur auf Knopfdruck.

**Ziel (Nutzerwunsch):** Das System erkennt wiederkehrende Zahlungen selbstständig, ohne dass Kategorien gepflegt werden müssen, vor allem Abos. Die Gruppierung muss Fälle wie Drillisch und PayPal auseinanderhalten.

**Nicht-Ziele:** keine Erkennung über Kategorien (ausdrücklich verworfen), keine Einnahmen-Reihen (Gehalt), keine Verknüpfung einzelner Buchungen mit der Reihe (`transactions.recurring_id` bleibt ungenutzt), keine Kündigungs- oder Fälligkeitserinnerungen.

**Anti-Ziele:** Supermärkte, Tankstellen oder Lieferdienste als „wiederkehrend“; ein verworfener Kandidat, der bei der nächsten Suche wieder auftaucht; eine Preiserhöhung, die als zwei verschiedene Abos erscheint.

## Verhalten

### Gruppierung

1. **Händlerschlüssel:** Erkennt `PaymentIntermediary` im Empfänger PayPal oder Amazon, ist der Schlüssel `<Token>:<Händler>`. Der Händler kommt aus `MerchantNameExtractor`, in Großbuchstaben, Leerraum zusammengefasst (z. B. `PAYPAL:SPOTIFY AB`); der Schlüssel wird auf 255 Zeichen gekürzt. Liefert der Extraktor nichts (leeres „Ihr Einkauf bei“), fällt die Buchung aus der Erkennung. Sonst ist der Schlüssel der normalisierte Empfängername. Ein leerer Schlüssel fällt ebenfalls aus der Erkennung.
2. **Betragsgruppen innerhalb eines Händlers:** Beträge (Betrag der Abbuchung, positiv gerechnet) aufsteigend sortieren und verketten, solange ein Betrag höchstens **5 %** über dem vorigen liegt. Jede Kette ist eine Gruppe. Eine Gruppe zählt nur, wenn `(max − min) / Median ≤ 10 %`. Das trennt Drillisch in zwei Reihen und wirft schwankende Einkäufe heraus.

### Rhythmus

Je Gruppe:

- Buchungsdaten sortieren; Buchungen innerhalb von **5 Tagen** nach der vorigen zählen als derselbe Termin (Doppel- und Nachbuchungen).
- Mindestens **3** Termine.
- Intervall aus dem **Median** der Abstände: monatlich 30,4 Tage, vierteljährlich 91,3, jährlich 365. Passt, wenn der Median höchstens 20 % daneben liegt.
- Mindestens **75 %** der Abstände liegen innerhalb ±25 % des Intervalls oder seines Doppelten (ein ausgelassener Monat ist kein Bruch).
- Erwarteter Betrag = Betrag des **jüngsten** Termins; nächste Fälligkeit = letzter Termin + Intervall.

### Preisänderungen zusammenführen

Zwei erkannte Reihen desselben Händlers mit demselben Intervall werden zu **einer** zusammengeführt, wenn sie sich zeitlich nicht überlappen (letzter Termin der älteren liegt vor dem ersten der jüngeren) und der Abstand dazwischen höchstens das 1,5-fache Intervall ist. Der Betrag ist dann der aktuelle. Beispiele aus den echten Daten: Giganetz 29,80 € → 84,80 €, Mainova 69 € → 88 €. Drillisch läuft parallel und bleibt getrennt.

Ein **frischer** Preiswechsel hat nach dem Sprung erst 1–2 Termine und wäre allein keine Reihe. Deshalb hängt sich danach eine Betragsgruppe desselben Händlers mit weniger als 3 Terminen an eine erkannte Reihe an, wenn ihr erster Termin nach dem letzten der Reihe liegt und der Abstand höchstens das 1,5-fache Intervall ist (gibt es mehrere Reihen desselben Intervalls, die zeitlich passende mit dem nächstliegenden Betrag). Betrag und letzter Termin kommen dann aus der angehängten Gruppe.

Der Betrag eines Termins ist immer der Einzelbetrag der jüngsten Buchung, nie die Summe zusammengefasster Doppelbuchungen.

### Status „beendet“

Eine Reihe ist **beendet**, wenn heute später ist als letzter Termin + 1,5 × Intervall (Railway, zuletzt April; OpenAI, zuletzt Februar). Sie bleibt in der Liste und wird als „beendet“ angezeigt (Nutzerentscheidung). Taucht später wieder ein passender Termin auf, ist sie wieder aktiv.

### Wann erkannt wird

Nach **jedem** erfolgreichen Kontoauszugs-Import für das Konto, in das importiert wurde, zusätzlich weiter per Knopf „Wiederkehrende suchen“. Die Erkennung läuft erst **nach dem Commit** des Imports in eigener Transaktion; ein Fehler darin wird geloggt und lässt den Import nicht scheitern. Betrachtet werden wie bisher die Abbuchungen der letzten 2 Jahre.

### Bestehende Einträge fortschreiben statt duplizieren

Jeder Lauf gleicht seine Ergebnisse mit den gespeicherten Einträgen des Kontos ab, verworfene eingeschlossen. Ein gespeicherter Eintrag gehört zu einer erkannten Reihe, wenn Konto, Händlerschlüssel und Intervall gleich sind **und** sein erwarteter Betrag höchstens 10 % von irgendeinem Betrag der Reihe abweicht (über die ganze Reihe einschließlich zusammengeführter alter Preise; so findet eine Preiserhöhung ihren Eintrag wieder). Passen mehrere Einträge, gewinnt der mit dem geringsten Abstand zum aktuellen Betrag; jeder Eintrag wird höchstens einer Reihe zugeordnet. Eine eigene Spalte für die Betragsgruppe gibt es nicht.

- Gefunden und schon gespeichert ⇒ erwarteter Betrag, nächste Fälligkeit, letzter Termin und Status werden aktualisiert; „bestätigt“ bleibt, wie es ist.
- Neu ⇒ als Kandidat angelegt.
- **„Verwerfen“ löscht nicht mehr, sondern markiert den Eintrag als verworfen** (`DELETE /v1/finance/recurring/{id}` setzt `dismissed`). Verworfene liefert die Liste nie aus, auch nicht mit Filter `confirmed`. Trifft ein Lauf einen verworfenen Eintrag, wird er weiter fortgeschrieben (damit der Abgleich auch nach einer Preiserhöhung greift), bleibt aber verworfen und unsichtbar.
- Ein gespeicherter Eintrag, den der Lauf nicht mehr findet, bleibt unverändert stehen.

Altzeilen aus der bisherigen Erkennung: Die Migration löscht alle **unbestätigten** Einträge. Bestätigte bleiben; für Empfänger ohne Zahlungsdienstleister ist ihr Muster schon der neue Händlerschlüssel, sie werden vom ersten Lauf über die normale Regel gefunden und fortgeschrieben. Bestätigte PayPal-/Amazon-Altzeilen (Muster „PAYPAL EUROPE …“) findet kein Lauf mehr; sie bleiben ohne `last_seen_date` stehen, werden nie „beendet“ und lassen sich verwerfen. Das ist akzeptiert.

### Kategorie

Die Kategorie eines Eintrags ist die der jüngsten Buchung der Reihe (bisher: einer beliebigen).

## Oberfläche

Tabelle wie bisher, mit:

- **Empfänger:** Händler lesbar, bei Zahlungsdienstleistern mit Hinweis, z. B. „Spotify AB (über PayPal)“ statt `PAYPAL:SPOTIFY AB`.
- **Status:** „Kandidat“, „bestätigt“ oder „beendet“ (beendet gewinnt in der Anzeige, die Bestätigung bleibt erhalten).
- **Sortierung:** aktive vor beendeten, innerhalb nach nächster Fälligkeit.
- **Letzte Zahlung** als zusätzliche Spalte.
- „Verwerfen“ wie bisher, wirkt jetzt dauerhaft.
- Die Meldung nach dem Knopf nennt neue und aktualisierte Einträge.

## Daten

Tabelle `recurring_payments`, neue Spalten (je ein Changeset, MariaDB-DDL-Autocommit):

- `last_seen_date DATE NULL` – letzter Termin der Reihe
- `dismissed BOOLEAN NOT NULL DEFAULT FALSE`

„Beendet“ wird nicht gespeichert, sondern beim Lesen aus `last_seen_date`, Intervall und heutigem Datum (`Clock`-Bean, Europe/Berlin) berechnet, damit es ohne neuen Import stimmt. `counterparty_pattern` trägt künftig den Händlerschlüssel. Dazu ein Changeset, das unbestätigte Altkandidaten löscht.

## Erwartetes Ergebnis mit den echten Daten

Ein Prototyp der Regeln oben fand im Export: Miete (Astrid Kaethe Lemon-Schiller), Tagesgeld 600 €, Sparpläne 25 €, Giganetz, Mainova, Vattenfall, Rundfunkbeitrag (vierteljährlich), Drillisch 4,99 € und 7,99 €, Host Europe, Claude (109,78 €), Spotify, Telekom, LogPay, Haarstudio, Railway und OpenAI (beide beendet). Keine Supermärkte. Dieser Datensatz (anonymisiert, nur Datum/Betrag/Empfänger/Zweck der betroffenen Zeilen) wird als Testfixture für den Analyzer übernommen.

## Bekannte, akzeptierte Grenzen

- Claude erscheint zweimal: der Empfänger wechselte von „CLAUDE.AI SUBSCRIPTION“ zu „ANTHROPIC. CLAUDE SUB“. Namenswechsel werden nicht zusammengeführt.
- Mehrere ETF-Sparpläne zu je 25 € an denselben Empfänger am selben Tag ergeben **eine** Reihe.
- Der Friseur wird erkannt, weil er tatsächlich regelmäßig und zum gleichen Preis kommt.
- Ein Abo mit stark schwankendem Betrag (Fremdwährung mit Kursschwankung über 10 %) fällt heraus.

## Dem Builder überlassen

Klassenschnitt (z. B. eigener `RecurringSeriesGrouper` neben `RecurrenceAnalyzer`), genaue Testaufteilung, Formatierung der Händleranzeige im Frontend.
